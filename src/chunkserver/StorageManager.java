package chunkserver;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Manages raw file (chunk) storage on the local disk.
 */
public class StorageManager {
    private final String rootDir;

    public StorageManager(String rootDir) {
        this.rootDir = rootDir;
        File dir = new File(rootDir);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) {
                System.out.println("[Storage] Created directory: " + dir.getAbsolutePath());
            } else {
                System.err.println("[Storage] WARNING: Could not create directory: " + dir.getAbsolutePath());
            }
        }
    }

    public String getRootDir() {
        return new File(rootDir).getAbsolutePath();
    }

    public void writeChunk(String chunkId, byte[] data) throws IOException {
        Path path = Paths.get(rootDir, chunkId);
        Files.write(path, data);
        System.out.println("[Storage] Stored " + chunkId + " (" + data.length + " bytes)");
    }

    public byte[] readChunk(String chunkId) throws IOException {
        Path path = Paths.get(rootDir, chunkId);
        if (!Files.exists(path)) {
            throw new FileNotFoundException("Chunk not found: " + chunkId);
        }
        byte[] data = Files.readAllBytes(path);
        System.out.println("[Storage] Read " + chunkId + " (" + data.length + " bytes)");
        return data;
    }

    public boolean hasChunk(String chunkId) {
        return Files.exists(Paths.get(rootDir, chunkId));
    }

    /**
     * Delete a chunk from local storage.
     * @return true if the chunk was deleted, false if it didn't exist.
     */
    public boolean deleteChunk(String chunkId) {
        try {
            Path path = Paths.get(rootDir, chunkId);
            boolean deleted = Files.deleteIfExists(path);
            if (deleted) {
                System.out.println("[Storage] Deleted " + chunkId);
            }
            return deleted;
        } catch (IOException e) {
            System.err.println("[Storage] Failed to delete " + chunkId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * List all chunks currently stored on this server.
     */
    public List<String> listChunks() {
        List<String> chunks = new ArrayList<>();
        File dir = new File(rootDir);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    chunks.add(f.getName());
                }
            }
        }
        return chunks;
    }
}
