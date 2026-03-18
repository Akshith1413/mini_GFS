package client;

import java.util.List;
import java.util.Map;

/**
 * Command-line client for MiniGFS.
 * Supports: upload, download, list, delete, status
 */
public class DemoClient {
    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                printUsage();
                return;
            }

            String masterHost = System.getProperty("minigfs.master.host", "localhost");
            int masterPort = Integer.parseInt(System.getProperty("minigfs.master.port", "6000"));

            GFSClient client = new GFSClient(masterHost, masterPort);
            String mode = args[0].toLowerCase();

            switch (mode) {
                case "upload": {
                    if (args.length < 3) {
                        System.err.println("Error: upload requires <localFile> <gfsName>");
                        System.err.println("  Example: upload myfile.txt /remote/myfile.txt");
                        return;
                    }
                    String localPath = args[1];
                    String gfsName = args[2];

                    java.io.File file = new java.io.File(localPath);
                    if (!file.exists()) {
                        System.err.println("Error: Local file not found: " + localPath);
                        return;
                    }

                    System.out.println("========================================");
                    System.out.println("  UPLOAD: " + localPath + " -> " + gfsName);
                    System.out.println("  Master: " + masterHost + ":" + masterPort);
                    System.out.println("========================================");

                    byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(localPath));
                    client.writeFile(gfsName, data);

                    System.out.println("========================================");
                    System.out.println("  SUCCESS! File uploaded to cluster.");
                    System.out.println("========================================");
                    break;
                }

                case "download": {
                    if (args.length < 3) {
                        System.err.println("Error: download requires <gfsName> <localDest>");
                        System.err.println("  Example: download /remote/myfile.txt restored.txt");
                        return;
                    }
                    String gfsName = args[1];
                    String localDest = args[2];

                    System.out.println("========================================");
                    System.out.println("  DOWNLOAD: " + gfsName + " -> " + localDest);
                    System.out.println("  Master: " + masterHost + ":" + masterPort);
                    System.out.println("========================================");

                    byte[] data = client.readFile(gfsName);
                    java.nio.file.Files.write(java.nio.file.Paths.get(localDest), data);

                    System.out.println("========================================");
                    System.out.println("  SUCCESS! Saved to " + localDest + " (" + data.length + " bytes)");
                    System.out.println("========================================");
                    break;
                }

                case "list": {
                    System.out.println("========================================");
                    System.out.println("  FILES IN CLUSTER");
                    System.out.println("  Master: " + masterHost + ":" + masterPort);
                    System.out.println("========================================");

                    List<String> files = client.listFiles();
                    if (files.isEmpty()) {
                        System.out.println("  (No files stored)");
                    } else {
                        for (int i = 0; i < files.size(); i++) {
                            System.out.println("  " + (i + 1) + ". " + files.get(i));
                        }
                    }
                    System.out.println("  Total: " + files.size() + " file(s)");
                    System.out.println("========================================");
                    break;
                }

                case "delete": {
                    if (args.length < 2) {
                        System.err.println("Error: delete requires <gfsName>");
                        System.err.println("  Example: delete /remote/myfile.txt");
                        return;
                    }
                    String gfsName = args[1];

                    System.out.println("========================================");
                    System.out.println("  DELETE: " + gfsName);
                    System.out.println("  Master: " + masterHost + ":" + masterPort);
                    System.out.println("========================================");

                    String result = client.deleteFile(gfsName);
                    System.out.println("  " + result);
                    System.out.println("========================================");
                    break;
                }

                case "snapshot": {
                    if (args.length < 3) {
                        System.err.println("Error: snapshot requires <srcGfsName> <dstGfsName>");
                        System.err.println("  Example: snapshot /remote/myfile.txt /remote/backup.txt");
                        return;
                    }
                    String srcGfsName = args[1];
                    String dstGfsName = args[2];

                    System.out.println("========================================");
                    System.out.println("  SNAPSHOT: " + srcGfsName + " -> " + dstGfsName);
                    System.out.println("  Master: " + masterHost + ":" + masterPort);
                    System.out.println("========================================");

                    String result = client.snapshotFile(srcGfsName, dstGfsName);
                    System.out.println("  " + result);
                    System.out.println("========================================");
                    break;
                }

                case "status": {
                    System.out.println("========================================");
                    System.out.println("  CLUSTER STATUS");
                    System.out.println("  Master: " + masterHost + ":" + masterPort);
                    System.out.println("========================================");

                    Map<String, Object> status = client.getStatus();
                    System.out.println("  Master Port   : " + status.get("masterPort"));
                    System.out.println("  Active Servers : " + status.get("serverCount"));
                    System.out.println("  Files Stored   : " + status.get("fileCount"));
                    System.out.println("  Total Chunks   : " + status.get("chunkCount"));
                    System.out.println("  Server List    : " + status.get("activeServers"));
                    System.out.println("========================================");
                    break;
                }

                default:
                    System.err.println("Error: Unknown command '" + mode + "'");
                    printUsage();
                    break;
            }
        } catch (Exception e) {
            System.err.println("\n  ERROR: " + e.getMessage());
            System.err.println("  Tip: Make sure the Master server is running and reachable.\n");
        }
    }

    private static void printUsage() {
        System.out.println("========================================");
        System.out.println("  MiniGFS Client");
        System.out.println("========================================");
        System.out.println("Usage:");
        System.out.println("  java -Dminigfs.master.host=<IP> -cp out client.DemoClient <command> [args]");
        System.out.println("");
        System.out.println("Commands:");
        System.out.println("  upload   <localFile> <gfsName>    Upload a file");
        System.out.println("  download <gfsName>  <localDest>   Download a file");
        System.out.println("  list                              List all files");
        System.out.println("  delete   <gfsName>                Delete a file");
        System.out.println("  snapshot <srcGfsName> <dstGfs>    Create a fast snapshot");
        System.out.println("  status                            Show cluster health");
        System.out.println("");
        System.out.println("Options (system properties):");
        System.out.println("  -Dminigfs.master.host=<host>   Master hostname/IP (default: localhost)");
        System.out.println("  -Dminigfs.master.port=<port>   Master port (default: 6000)");
        System.out.println("");
        System.out.println("Examples:");
        System.out.println("  java -cp out client.DemoClient upload report.pdf /docs/report.pdf");
        System.out.println("  java -Dminigfs.master.host=192.168.1.50 -cp out client.DemoClient list");
        System.out.println("========================================");
    }
}
