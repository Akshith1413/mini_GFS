package chunkserver;

import common.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class ChunkServer {
    private final int port;
    private final StorageManager storage;
    private final String masterHost;
    private final int masterPort;
    private volatile boolean running = true;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    public ChunkServer(int port, String startDir, String masterHost, int masterPort) {
        this.port = port;
        this.storage = new StorageManager(startDir);
        this.masterHost = masterHost;
        this.masterPort = masterPort;
    }

    public void start() throws IOException {
        log("ChunkServer starting on port " + port + "...");
        log("Master target: " + masterHost + ":" + masterPort);
        log("Storage directory: " + storage.getRootDir());

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            log("Shutting down ChunkServer...");
            threadPool.shutdownNow();
            log("ChunkServer stopped.");
        }));

        // Register with Master (with retry)
        registerWithMaster();

        // Start Heartbeat Thread
        Thread heartbeatThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(3000); // Beat every 3s
                    sendHeartbeat();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logError("Heartbeat failed: " + e.getMessage());
                }
            }
        }, "Heartbeat-" + port);
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            log("ChunkServer is READY and listening on port " + port);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(SocketUtil.READ_TIMEOUT_MS);
                    threadPool.submit(() -> handleClient(clientSocket));
                } catch (SocketTimeoutException e) {
                    // Normal timeout, loop again
                }
            }
        }
    }

    /**
     * Auto-detect the LAN IP address of this machine.
     */
    private String detectLanIp() {
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                java.util.Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            logError("Could not detect LAN IP: " + e.getMessage());
        }
        return "localhost";
    }

    private String getMyAddress() {
        return detectLanIp() + ":" + port;
    }

    /**
     * Register with Master, retrying up to 10 times with exponential backoff.
     */
    private void registerWithMaster() {
        String myAddr = getMyAddress();
        log("Registering as: " + myAddr);

        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                Message msg = new Message(Command.REGISTER);
                msg.put("address", myAddr);
                Message resp = SocketUtil.sendAndReceive(masterHost, masterPort, msg);
                if (resp.command == Command.SUCCESS) {
                    log("Registration successful: " + resp.get("message"));
                    return;
                } else {
                    logError("Registration rejected: " + resp.get("message"));
                }
            } catch (Exception e) {
                logError("Registration attempt " + attempt + "/10 failed: " + e.getMessage());
                if (attempt < 10) {
                    long backoff = (long) Math.pow(2, attempt) * 500;
                    log("Retrying in " + backoff + "ms...");
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        logError("CRITICAL: Could not register with Master after 10 attempts. Continuing anyway (heartbeats may recover).");
    }

    private void sendHeartbeat() {
        try {
            Message msg = new Message(Command.HEARTBEAT);
            msg.put("address", getMyAddress());
            SocketUtil.sendAndReceive(masterHost, masterPort, msg);
        } catch (Exception e) {
            // Suppress repeated heartbeat failures - they'll self-recover
        }
    }

    private void handleClient(Socket socket) {
        try {
            Message request = SocketUtil.readMessage(socket);
            log("Received: " + request.command);

            Message response = new Message(Command.SUCCESS);

            switch (request.command) {
                case WRITE_CHUNK: {
                    String chunkId = (String) request.get("chunkId");
                    byte[] data = request.data;
                    if (chunkId == null || data == null) {
                        response.command = Command.ERROR;
                        response.put("message", "Missing chunkId or data in WRITE_CHUNK");
                        break;
                    }
                    storage.writeChunk(chunkId, data);
                    response.put("status", "Written (" + data.length + " bytes)");
                    break;
                }

                case READ_CHUNK: {
                    String readId = (String) request.get("chunkId");
                    if (readId == null) {
                        response.command = Command.ERROR;
                        response.put("message", "Missing chunkId in READ_CHUNK");
                        break;
                    }
                    if (!storage.hasChunk(readId)) {
                        response.command = Command.ERROR;
                        response.put("message", "Chunk not found: " + readId);
                        break;
                    }
                    byte[] readData = storage.readChunk(readId);
                    response.data = readData;
                    break;
                }

                case DELETE_CHUNK: {
                    String delId = (String) request.get("chunkId");
                    if (delId == null) {
                        response.command = Command.ERROR;
                        response.put("message", "Missing chunkId in DELETE_CHUNK");
                        break;
                    }
                    boolean deleted = storage.deleteChunk(delId);
                    response.put("status", deleted ? "Deleted" : "Not found (already removed)");
                    break;
                }

                case REPLICATE_CHUNK: {
                    String repChunkId = (String) request.get("chunkId");
                    String targetServer = (String) request.get("targetServer");
                    if (repChunkId == null || targetServer == null) {
                        response.command = Command.ERROR;
                        response.put("message", "Missing chunkId or targetServer in REPLICATE_CHUNK");
                        break;
                    }
                    log("Replicating " + repChunkId + " to " + targetServer);

                    if (!storage.hasChunk(repChunkId)) {
                        response.command = Command.ERROR;
                        response.put("message", "Source chunk not found: " + repChunkId);
                        break;
                    }

                    // Read local chunk
                    byte[] chunkData = storage.readChunk(repChunkId);

                    // Send to target
                    String[] parts = SocketUtil.parseAddress(targetServer);
                    Message writeMsg = new Message(Command.WRITE_CHUNK);
                    writeMsg.put("chunkId", repChunkId);
                    writeMsg.data = chunkData;

                    Message targetResp = SocketUtil.sendAndReceive(parts[0], Integer.parseInt(parts[1]), writeMsg);
                    if (targetResp.command == Command.SUCCESS) {
                        log("Replication SUCCESS: " + repChunkId + " -> " + targetServer);
                    } else {
                        logError("Replication FAILED: " + targetResp.get("message"));
                        response.command = Command.ERROR;
                        response.put("message", "Replication failed to target");
                    }
                    break;
                }

                default: {
                    response.command = Command.ERROR;
                    response.put("message", "Unknown command: " + request.command);
                    break;
                }
            }

            SocketUtil.sendMessage(socket, response);
        } catch (Exception e) {
            logError("Error processing request: " + e.getMessage());
            try {
                Message err = new Message(Command.ERROR);
                err.put("message", "ChunkServer error: " + e.getMessage());
                SocketUtil.sendMessage(socket, err);
            } catch (IOException ignored) {
            }
        } finally {
            SocketUtil.closeQuietly(socket);
        }
    }

    private void log(String msg) {
        System.out.println("[" + java.time.LocalTime.now().withNano(0) + "] [CS:" + port + "] " + msg);
    }

    private void logError(String msg) {
        System.err.println("[" + java.time.LocalTime.now().withNano(0) + "] [CS:" + port + "] ERROR: " + msg);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: java chunkserver.ChunkServer <port> <storage_path> [master_host] [master_port]");
            System.out.println("  port         - Port this ChunkServer listens on (e.g. 6001)");
            System.out.println("  storage_path - Local directory to store chunks (e.g. data/s1)");
            System.out.println("  master_host  - Master server IP/hostname (default: localhost)");
            System.out.println("  master_port  - Master server port (default: 6000)");
            return;
        }
        int port = Integer.parseInt(args[0]);
        String path = args[1];
        String masterHost = (args.length > 2) ? args[2] : "localhost";
        int masterPort = 6000;
        if (args.length > 3) {
            try {
                masterPort = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid master port: " + args[3] + ". Using default 6000.");
            }
        }

        new ChunkServer(port, path, masterHost, masterPort).start();
    }
}
