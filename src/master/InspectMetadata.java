package master;

import java.io.*;
import java.util.*;

public class InspectMetadata {
    public static void main(String[] args) throws Exception {
        File f = new File("metadata.ser");
        System.out.println("Inspecting: " + f.getAbsolutePath());

        if (!f.exists()) {
            System.out.println("No metadata file found.");
            return;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            // Read 1: File -> Chunk List
            Map<String, List<String>> fileToChunks = (Map<String, List<String>>) ois.readObject();

            // Read 2: Chunk -> Server Locations
            Map<String, List<String>> chunkLocations = (Map<String, List<String>>) ois.readObject();

            System.out.println("\n=== 📂 REQUIREMENT: File Names & Lists of Chunks ===");
            for (String file : fileToChunks.keySet()) {
                System.out.println("File: " + file);
                List<String> chunks = fileToChunks.get(file);
                System.out.println("  └── Chunks (" + chunks.size() + "): " + chunks);
            }

            System.out.println("\n=== 🗺️ REQUIREMENT: Mapping of Chunks to Servers ===");
            for (String chunk : chunkLocations.keySet()) {
                System.out.println("Chunk: " + chunk);
                System.out.println("  └── Servers: " + chunkLocations.get(chunk));
            }

        } catch (Exception e) {
            System.err.println("Error reading metadata: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
