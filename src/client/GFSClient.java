package client;

import common.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

public class GFSClient {
    private final String masterHost;
    private final int masterPort;

    private static final int CHUNK_SIZE = 2 * 1024 * 1024; // 2MB Chunk Size

    private final java.util.concurrent.ExecutorService uploadPool = java.util.concurrent.Executors
            .newFixedThreadPool(5);

    public GFSClient(String masterHost, int masterPort) {
        this.masterHost = masterHost;
        this.masterPort = masterPort;
    }

    /**
     * Upload a file to the distributed file system.
     */
    @SuppressWarnings("unchecked")
    public void writeFile(String filename, byte[] data) throws Exception {
        int totalBytes = data.length;
        int numChunks = (int) Math.ceil((double) totalBytes / CHUNK_SIZE);

        System.out.println("Uploading " + filename + " (" + totalBytes + " bytes) in " + numChunks + " chunk(s)...");

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < numChunks; i++) {
            final int chunkIndex = i;

            // Extract chunk data
            int start = i * CHUNK_SIZE;
            int length = Math.min(CHUNK_SIZE, totalBytes - start);
            byte[] chunkData = new byte[length];
            System.arraycopy(data, start, chunkData, 0, length);

            // Talk to Master: Allocate Chunk
            Message request = new Message(Command.ALLOCATE_CHUNK);
            request.put("filename", filename);

            Message response = SocketUtil.sendAndReceive(masterHost, masterPort, request);

            if (response.command != Command.SUCCESS) {
                String errMsg = (String) response.get("message");
                throw new RuntimeException("Master failed to allocate chunk " + i
                        + (errMsg != null ? ": " + errMsg : ""));
            }

            String chunkId = (String) response.get("chunkId");
            List<String> locations = (List<String>) response.get("locations");

            if (locations == null || locations.isEmpty()) {
                throw new RuntimeException("No chunk servers available for chunk " + chunkId);
            }

            // Write to ChunkServers (Parallel)
            futures.add(uploadPool.submit(() -> {
                System.out.println("  Chunk " + (chunkIndex + 1) + "/" + numChunks + ": " + chunkId
                        + " -> " + locations);

                int successCount = 0;
                for (String location : locations) {
                    try {
                        String[] parts = SocketUtil.parseAddress(location);
                        String host = parts[0];
                        int port = Integer.parseInt(parts[1]);

                        Message writeReq = new Message(Command.WRITE_CHUNK);
                        writeReq.put("chunkId", chunkId);
                        writeReq.data = chunkData;

                        Message writeResp = SocketUtil.sendAndReceive(host, port, writeReq);

                        if (writeResp.command == Command.SUCCESS) {
                            successCount++;
                        } else {
                            System.err.println("  WARN: Failed to write " + chunkId + " to " + location
                                    + ": " + writeResp.get("message"));
                        }
                    } catch (Exception e) {
                        System.err.println("  WARN: Error writing " + chunkId + " to " + location
                                + ": " + e.getMessage());
                    }
                }

                if (successCount == 0) {
                    throw new RuntimeException("FATAL: All replicas failed for chunk " + chunkId);
                }
                System.out.println("  Chunk " + chunkId + ": " + successCount + "/" + locations.size()
                        + " replicas written");
            }));
        }

        // Wait for all uploads to finish
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Upload Failed: " + e.getCause().getMessage(), e.getCause());
            }
        }

        System.out.println("Upload complete: " + filename);
    }

    /**
     * Download a file from the distributed file system.
     */
    @SuppressWarnings("unchecked")
    public byte[] readFile(String filename) throws Exception {
        // 1. Get Chunk Locations from Master
        Message request = new Message(Command.GET_FILE_LOCATIONS);
        request.put("filename", filename);

        Message response = SocketUtil.sendAndReceive(masterHost, masterPort, request);

        if (response.command != Command.SUCCESS) {
            String errMsg = (String) response.get("message");
            throw new RuntimeException("Failed to get file info for '" + filename + "'"
                    + (errMsg != null ? ": " + errMsg : ""));
        }

        List<String> chunks = (List<String>) response.get("chunks");
        Map<String, List<String>> locationsMap = (Map<String, List<String>>) response.get("chunkLocations");

        if (chunks == null || chunks.isEmpty()) {
            throw new RuntimeException("File '" + filename + "' has no chunks (empty file or not found)");
        }

        ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();

        // 2. Read each chunk (try replicas until one works)
        for (String chunkId : chunks) {
            List<String> replicas = locationsMap.get(chunkId);
            if (replicas == null || replicas.isEmpty()) {
                throw new RuntimeException("Chunk " + chunkId + " has no known locations!");
            }

            boolean chunkRead = false;
            for (String replica : replicas) {
                try {
                    String[] parts = SocketUtil.parseAddress(replica);

                    Message readReq = new Message(Command.READ_CHUNK);
                    readReq.put("chunkId", chunkId);

                    Message readResp = SocketUtil.sendAndReceive(parts[0], Integer.parseInt(parts[1]), readReq);

                    if (readResp.command == Command.SUCCESS && readResp.data != null) {
                        fileBuffer.write(readResp.data);
                        chunkRead = true;
                        System.out.println("  Read " + chunkId + " from " + replica
                                + " (" + readResp.data.length + " bytes)");
                        break; // Success, move to next chunk
                    } else {
                        System.err.println("  WARN: Bad response from " + replica
                                + " for " + chunkId + ": " + readResp.get("message"));
                    }
                } catch (Exception e) {
                    System.err.println("  WARN: Failed to read " + chunkId + " from " + replica
                            + ": " + e.getMessage());
                }
            }
            if (!chunkRead) {
                throw new RuntimeException("Failed to read chunk " + chunkId
                        + " from any replica: " + replicas);
            }
        }

        System.out.println("Download complete: " + filename + " (" + fileBuffer.size() + " bytes)");
        return fileBuffer.toByteArray();
    }

    /**
     * List all files in the distributed file system.
     */
    @SuppressWarnings("unchecked")
    public List<String> listFiles() throws Exception {
        Message request = new Message(Command.LIST_FILES);
        Message response = SocketUtil.sendAndReceive(masterHost, masterPort, request);

        if (response.command == Command.SUCCESS) {
            List<String> files = (List<String>) response.get("files");
            return files != null ? files : new ArrayList<>();
        }
        throw new RuntimeException("Failed to list files: " + response.get("message"));
    }

    /**
     * Delete a file from the distributed file system.
     */
    public String deleteFile(String filename) throws Exception {
        Message request = new Message(Command.DELETE_FILE);
        request.put("filename", filename);

        Message response = SocketUtil.sendAndReceive(masterHost, masterPort, request);

        if (response.command == Command.SUCCESS) {
            return (String) response.get("message");
        }
        throw new RuntimeException("Failed to delete '" + filename + "': " + response.get("message"));
    }

    /**
     * Create a fast snapshot of a file.
     */
    public String snapshotFile(String source, String target) throws Exception {
        Message request = new Message(Command.SNAPSHOT);
        request.put("source", source);
        request.put("target", target);

        Message response = SocketUtil.sendAndReceive(masterHost, masterPort, request);

        if (response.command == Command.SUCCESS) {
            return (String) response.get("message");
        }
        throw new RuntimeException(
                "Failed to create snapshot from '" + source + "' to '" + target + "': " + response.get("message"));
    }

    /**
     * Get cluster status from the Master.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getStatus() throws Exception {
        Message request = new Message(Command.STATUS);
        Message response = SocketUtil.sendAndReceive(masterHost, masterPort, request);

        if (response.command == Command.SUCCESS) {
            return response.payload;
        }
        throw new RuntimeException("Failed to get status: " + response.get("message"));
    }
}
