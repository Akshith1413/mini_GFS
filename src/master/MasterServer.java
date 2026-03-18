package master;

import common.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MasterServer {
    private final int port;
    private final MetadataManager metadata = new MetadataManager();
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    public MasterServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        log("MasterServer starting on port " + port + "...");

        // Shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            log("Shutting down MasterServer...");
            threadPool.shutdownNow();
            metadata.save();
            log("MasterServer stopped.");
        }));

        // Background thread for failure detection
        Thread failureDetector = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(5000); // Check every 5s
                    List<String> deadServers = metadata.removeDeadServers(15000);

                    for (String deadServer : deadServers) {
                        log("Processing failure for: " + deadServer);
                        List<String> lostChunks = metadata.getChunksOnServer(deadServer);

                        for (String chunkId : lostChunks) {
                            List<String> currentLocs = metadata.getChunkLocations(chunkId);
                            List<String> survivors = new ArrayList<>(currentLocs);
                            survivors.remove(deadServer);

                            if (survivors.isEmpty()) {
                                logError("CRITICAL: Data lost for " + chunkId + " (No survivors)");
                                continue;
                            }

                            String sourceServer = survivors.get(0);
                            Set<String> excluded = new HashSet<>(currentLocs);
                            String newTarget = metadata.getAvailableServer(excluded);

                            if (newTarget != null) {
                                log("Self-Healing: Replicating " + chunkId + " from " + sourceServer + " to "
                                        + newTarget);
                                triggerReplication(sourceServer, chunkId, newTarget);
                                metadata.updateChunkLocation(chunkId, deadServer, newTarget);
                            } else {
                                logError("Self-Healing Failed: No available servers to host " + chunkId);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logError("Failure Detector Error: " + e.getMessage());
                }
            }
        }, "FailureDetector");
        failureDetector.setDaemon(true);
        failureDetector.start();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setSoTimeout(0); // Block indefinitely on accept
            log("MasterServer is READY and listening on port " + port);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(SocketUtil.READ_TIMEOUT_MS);
                    threadPool.submit(() -> handleRequest(clientSocket));
                } catch (SocketTimeoutException e) {
                    // Normal timeout, loop again
                }
            }
        }
    }

    private void handleRequest(Socket socket) {
        try {
            Message request = SocketUtil.readMessage(socket);

            // Log less for heartbeat to reduce noise
            if (request.command != Command.HEARTBEAT) {
                log("Received: " + request.command + " from " + socket.getRemoteSocketAddress());
            }

            Message response = new Message(Command.SUCCESS);

            switch (request.command) {
                case REGISTER: {
                    String serverAddr = (String) request.get("address");
                    if (serverAddr == null || serverAddr.isEmpty()) {
                        response.command = Command.ERROR;
                        response.put("message", "Missing server address in REGISTER");
                        break;
                    }
                    metadata.registerServer(serverAddr);
                    response.put("message", "Registered " + serverAddr);
                    break;
                }

                case ALLOCATE_CHUNK: {
                    String filename = (String) request.get("filename");
                    if (filename == null || filename.isEmpty()) {
                        response.command = Command.ERROR;
                        response.put("message", "Missing filename in ALLOCATE_CHUNK");
                        break;
                    }
                    String chunkId = metadata.allocateChunk(filename);
                    List<String> locs = metadata.getChunkLocations(chunkId);
                    if (locs.isEmpty()) {
                        response.command = Command.ERROR;
                        response.put("message", "No active chunk servers available");
                        break;
                    }
                    response.put("chunkId", chunkId);
                    response.put("locations", new ArrayList<>(locs));
                    break;
                }

                case GET_FILE_LOCATIONS: {
                    String readFile = (String) request.get("filename");
                    if (readFile == null || readFile.isEmpty()) {
                        response.command = Command.ERROR;
                        response.put("message", "Missing filename in GET_FILE_LOCATIONS");
                        break;
                    }
                    List<String> chunks = metadata.getFileChunks(readFile);
                    if (chunks.isEmpty()) {
                        response.command = Command.ERROR;
                        response.put("message", "File not found: " + readFile);
                        break;
                    }
                    response.put("chunks", new ArrayList<>(chunks));

                    // Also send locations for each chunk so client knows where to go
                    Map<String, List<String>> locationsMap = new HashMap<>();
                    for (String cid : chunks) {
                        locationsMap.put(cid, metadata.getChunkLocations(cid));
                    }
                    response.put("chunkLocations", locationsMap);
                    break;
                }

                case LIST_FILES: {
                    Set<String> allFiles = metadata.getAllFiles();
                    response.put("files", new ArrayList<>(allFiles));
                    break;
                }

                case HEARTBEAT: {
                    String hbAddr = (String) request.get("address");
                    if (hbAddr != null) {
                        metadata.updateHeartbeat(hbAddr);
                    }
                    response.put("status", "Ack");
                    break;
                }

                case DELETE_FILE: {
                    String delFile = (String) request.get("filename");
                    if (delFile == null || delFile.isEmpty()) {
                        response.command = Command.ERROR;
                        response.put("message", "Missing filename in DELETE_FILE");
                        break;
                    }
                    // Get chunks before deleting metadata
                    List<String> delChunks = metadata.getFileChunks(delFile);
                    if (delChunks.isEmpty()) {
                        response.command = Command.ERROR;
                        response.put("message", "File not found: " + delFile);
                        break;
                    }

                    // Tell each chunk server to delete its ORPHANED chunks
                    List<String> orphanedChunks = metadata.deleteFile(delFile);
                    for (String chunkId : orphanedChunks) {
                        List<String> chunkLocs = metadata.getChunkLocations(chunkId);
                        for (String loc : chunkLocs) {
                            try {
                                String[] parts = SocketUtil.parseAddress(loc);
                                Message delMsg = new Message(Command.DELETE_CHUNK);
                                delMsg.put("chunkId", chunkId);
                                SocketUtil.sendAndReceive(parts[0], Integer.parseInt(parts[1]), delMsg);
                            } catch (Exception e) {
                                logError("Failed to delete chunk " + chunkId + " from " + loc + ": " + e.getMessage());
                                // Continue - chunk will be orphaned but file metadata will be cleaned
                            }
                        }
                    }

                    response.put("message", "Deleted " + delFile + " (" + orphanedChunks.size() + " chunks removed)");
                    log("Deleted file: " + delFile);
                    break;
                }

                case SNAPSHOT: {
                    String srcFile = (String) request.get("source");
                    String dstFile = (String) request.get("target");

                    if (srcFile == null || dstFile == null || srcFile.isEmpty() || dstFile.isEmpty()) {
                        response.command = Command.ERROR;
                        response.put("message", "Missing source or target in SNAPSHOT");
                        break;
                    }

                    boolean success = metadata.createSnapshot(srcFile, dstFile);
                    if (success) {
                        response.put("message", "Snapshot created: " + dstFile);
                        log("Created snapshot: " + srcFile + " -> " + dstFile);
                    } else {
                        response.command = Command.ERROR;
                        response.put("message", "Snapshot failed (source missing or target exists)");
                    }
                    break;
                }

                case STATUS: {
                    List<String> servers = metadata.getActiveServers();
                    Set<String> files = metadata.getAllFiles();
                    int totalChunks = metadata.getTotalChunkCount();

                    response.put("activeServers", new ArrayList<>(servers));
                    response.put("serverCount", servers.size());
                    response.put("fileCount", files.size());
                    response.put("chunkCount", totalChunks);
                    response.put("masterPort", port);
                    break;
                }

                default: {
                    response.command = Command.ERROR;
                    response.put("message", "Unknown command: " + request.command);
                    logError("Unknown command received: " + request.command);
                    break;
                }
            }

            SocketUtil.sendMessage(socket, response);
        } catch (Exception e) {
            logError("Error handling request from " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
            try {
                Message errorResp = new Message(Command.ERROR);
                errorResp.put("message", "Internal server error: " + e.getMessage());
                SocketUtil.sendMessage(socket, errorResp);
            } catch (IOException ignored) {
            }
        } finally {
            SocketUtil.closeQuietly(socket);
        }
    }

    private void triggerReplication(String source, String chunkId, String target) {
        threadPool.submit(() -> {
            try {
                String[] parts = SocketUtil.parseAddress(source);
                Message msg = new Message(Command.REPLICATE_CHUNK);
                msg.put("chunkId", chunkId);
                msg.put("targetServer", target);
                SocketUtil.sendOnly(parts[0], Integer.parseInt(parts[1]), msg);
                log("Replication triggered: " + chunkId + " → " + target);
            } catch (Exception e) {
                logError("Failed to trigger replication of " + chunkId + ": " + e.getMessage());
            }
        });
    }

    private static void log(String msg) {
        System.out.println("[" + java.time.LocalTime.now().withNano(0) + "] [Master] " + msg);
    }

    private static void logError(String msg) {
        System.err.println("[" + java.time.LocalTime.now().withNano(0) + "] [Master] ERROR: " + msg);
    }

    public static void main(String[] args) throws IOException {
        int port = 6000;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number: " + args[0] + ". Using default 6000.");
            }
        }
        new MasterServer(port).start();
    }
}
