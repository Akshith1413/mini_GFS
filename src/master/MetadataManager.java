package master;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory metadata storage for the Master Server.
 * Supports persistence to disk so metadata survives restarts.
 */
public class MetadataManager {
    // Persistence File
    private static final String META_FILE = "metadata.ser";

    // Filename -> List of Chunk IDs
    private final Map<String, List<String>> fileToChunks = new ConcurrentHashMap<>();

    // Chunk ID -> List of ChunkServer (host:port)
    private final Map<String, List<String>> chunkLocations = new ConcurrentHashMap<>();

    // Chunk ID -> Reference Count (for snapshots)
    private final Map<String, Integer> chunkRefCounts = new ConcurrentHashMap<>();

    // Active ChunkServers
    private final Set<String> activeServers = ConcurrentHashMap.newKeySet();
    // Last heartbeat timestamp
    private final Map<String, Long> lastHeartbeat = new ConcurrentHashMap<>();

    public MetadataManager() {
        load();
    }

    public synchronized void save() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(META_FILE))) {
            oos.writeObject(new HashMap<>(fileToChunks));
            oos.writeObject(new HashMap<>(chunkLocations));
            oos.writeObject(new HashMap<>(chunkRefCounts));
            System.out
                    .println("[Meta] Saved: " + fileToChunks.size() + " files, " + chunkLocations.size() + " chunks.");
        } catch (IOException e) {
            System.err.println("[Meta] Failed to save: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void load() {
        File f = new File(META_FILE);
        if (f.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
                Map<String, List<String>> files = (Map<String, List<String>>) ois.readObject();
                fileToChunks.clear();
                fileToChunks.putAll(files);

                try {
                    Map<String, List<String>> locs = (Map<String, List<String>>) ois.readObject();
                    chunkLocations.clear();
                    chunkLocations.putAll(locs);

                    try {
                        Map<String, Integer> refs = (Map<String, Integer>) ois.readObject();
                        chunkRefCounts.clear();
                        chunkRefCounts.putAll(refs);
                    } catch (EOFException e) {
                        System.out.println("[Meta] Legacy metadata (no chunkRefCounts). Initializing defaults to 1.");
                        chunkRefCounts.clear();
                        for (String chunkId : chunkLocations.keySet()) {
                            chunkRefCounts.put(chunkId, 1);
                        }
                    }
                } catch (EOFException e) {
                    System.out.println("[Meta] Legacy metadata (no chunk locations). They will be re-populated.");
                }

                System.out.println(
                        "[Meta] Loaded: " + fileToChunks.size() + " files, " + chunkLocations.size() + " chunks.");
            } catch (Exception e) {
                System.err.println("[Meta] Failed to load: " + e.getMessage());
            }
        }
    }

    public void registerServer(String serverAddress) {
        activeServers.add(serverAddress);
        lastHeartbeat.put(serverAddress, System.currentTimeMillis());
        System.out.println("[Meta] Registered ChunkServer: " + serverAddress);
    }

    public void updateHeartbeat(String serverAddress) {
        lastHeartbeat.put(serverAddress, System.currentTimeMillis());
        // If it was dead, re-add (simple recovery)
        activeServers.add(serverAddress);
    }

    /**
     * Detect and remove dead servers. Uses a snapshot to avoid
     * ConcurrentModificationException.
     */
    public List<String> removeDeadServers(long timeoutMs) {
        List<String> dead = new ArrayList<>();
        long now = System.currentTimeMillis();
        // Take a snapshot of active servers to avoid ConcurrentModificationException
        List<String> snapshot = new ArrayList<>(activeServers);
        for (String server : snapshot) {
            long last = lastHeartbeat.getOrDefault(server, 0L);
            if (now - last > timeoutMs) {
                System.out.println(
                        "[Meta] ALERT: Server " + server + " is DEAD! (No heartbeat for " + (now - last) + "ms)");
                activeServers.remove(server);
                dead.add(server);
            }
        }
        return dead;
    }

    public List<String> getActiveServers() {
        return new ArrayList<>(activeServers);
    }

    /**
     * Sanitize chunk ID so it is safe as a filename on all operating systems.
     * Replaces path separators and special characters.
     */
    private String sanitizeChunkId(String filename, int index) {
        // Replace / and \ with _ to make valid filenames, also remove leading
        // separators
        String safe = filename.replaceAll("[/\\\\]", "_");
        // Remove leading underscores
        while (safe.startsWith("_")) {
            safe = safe.substring(1);
        }
        return safe + "_chunk_" + index;
    }

    public String allocateChunk(String filename) {
        int nextIndex = fileToChunks.getOrDefault(filename, Collections.emptyList()).size();
        String chunkId = sanitizeChunkId(filename, nextIndex);

        fileToChunks.computeIfAbsent(filename, k -> new ArrayList<>()).add(chunkId);

        // Replication Strategy: Default 2 replicas
        List<String> servers = new ArrayList<>(activeServers);
        Collections.shuffle(servers); // Randomize placement
        if (servers.size() > 2) {
            servers = servers.subList(0, 2);
        }

        if (servers.isEmpty()) {
            System.out.println("[Meta] WARNING: No active servers to allocate chunk!");
        } else {
            System.out.println("[Meta] Allocated " + chunkId + " -> " + servers);
        }
        chunkLocations.put(chunkId, new ArrayList<>(servers));
        chunkRefCounts.put(chunkId, 1); // newly allocated chunk has refCount = 1
        save();
        return chunkId;
    }

    public List<String> getChunkLocations(String chunkId) {
        return chunkLocations.getOrDefault(chunkId, Collections.emptyList());
    }

    public List<String> getFileChunks(String filename) {
        return fileToChunks.getOrDefault(filename, Collections.emptyList());
    }

    public Set<String> getAllFiles() {
        return fileToChunks.keySet();
    }

    public int getTotalChunkCount() {
        return chunkLocations.size();
    }

    // --- Snapshot ---

    /**
     * Creates a fast metadata-only snapshot of a file.
     * 
     * @return true if successful, false if source file not found or target already
     *         exists.
     */
    public synchronized boolean createSnapshot(String sourceFile, String targetFile) {
        if (!fileToChunks.containsKey(sourceFile)) {
            return false;
        }
        if (fileToChunks.containsKey(targetFile)) {
            return false;
        }

        List<String> chunks = fileToChunks.get(sourceFile);
        List<String> clonedChunks = new ArrayList<>(chunks);
        fileToChunks.put(targetFile, clonedChunks);

        // Increment reference counts
        for (String chunkId : clonedChunks) {
            chunkRefCounts.put(chunkId, chunkRefCounts.getOrDefault(chunkId, 0) + 1);
        }

        save();
        System.out.println("[Meta] Created snapshot: " + sourceFile + " -> " + targetFile);
        return true;
    }

    // --- Delete ---

    /**
     * Remove a file and decrement chunk reference counts.
     * 
     * @return List of chunk IDs that reached 0 references (orphaned chunks that
     *         need physical deletion).
     */
    public synchronized List<String> deleteFile(String filename) {
        List<String> orphanedChunks = new ArrayList<>();
        List<String> chunks = fileToChunks.remove(filename);

        if (chunks != null) {
            for (String chunkId : chunks) {
                int refCount = chunkRefCounts.getOrDefault(chunkId, 1) - 1;

                if (refCount <= 0) {
                    chunkRefCounts.remove(chunkId);
                    chunkLocations.remove(chunkId);
                    orphanedChunks.add(chunkId);
                } else {
                    chunkRefCounts.put(chunkId, refCount);
                }
            }
        }
        save();
        return orphanedChunks;
    }

    // --- Self-Healing Methods ---

    public List<String> getChunksOnServer(String server) {
        List<String> affected = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : chunkLocations.entrySet()) {
            if (entry.getValue().contains(server)) {
                affected.add(entry.getKey());
            }
        }
        return affected;
    }

    public void updateChunkLocation(String chunkId, String deadServer, String newServer) {
        List<String> locs = chunkLocations.get(chunkId);
        if (locs != null) {
            locs.remove(deadServer);
            if (!locs.contains(newServer)) {
                locs.add(newServer);
            }
            save();
        }
    }

    public String getAvailableServer(Set<String> excluded) {
        List<String> candidates = new ArrayList<>(activeServers);
        candidates.removeAll(excluded);
        if (candidates.isEmpty())
            return null;
        Collections.shuffle(candidates);
        return candidates.get(0);
    }
}
