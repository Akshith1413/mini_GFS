package common;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Helper to send/receive Message objects over a socket.
 * Optimized for low-latency distributed communication.
 */
public class SocketUtil {

    /** Connect timeout — fast fail */
    public static final int CONNECT_TIMEOUT_MS = 10000;
    /** Read timeout — fast fail */
    public static final int READ_TIMEOUT_MS = 30000;
    /** Maximum retry attempts */
    public static final int MAX_RETRIES = 2;

    public static void sendMessage(Socket socket, Message msg) throws IOException {
        ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
        out.writeObject(msg);
        out.flush();
    }

    public static Message readMessage(Socket socket) throws IOException, ClassNotFoundException {
        ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
        return (Message) in.readObject();
    }

    public static Socket createSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);       // Disable Nagle's algorithm for instant send
        socket.setKeepAlive(false);       // No keep-alive overhead
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        return socket;
    }

    /**
     * Send a message and wait for a response, with fast retry.
     * Backoff: 200ms, 500ms (much faster than before).
     */
    public static Message sendAndReceive(String host, int port, Message request) throws IOException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (Socket socket = createSocket(host, port)) {
                sendMessage(socket, request);
                return readMessage(socket);
            } catch (ClassNotFoundException e) {
                throw new IOException("Corrupted response from " + host + ":" + port, e);
            } catch (IOException e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    long backoffMs = attempt * 250; // 250ms, 500ms
                    System.err.println("[Retry " + attempt + "/" + MAX_RETRIES + "] "
                            + host + ":" + port + " (" + e.getMessage()
                            + "). Retrying in " + backoffMs + "ms...");
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry", ie);
                    }
                }
            }
        }
        throw new IOException("Failed to reach " + host + ":" + port
                + " after " + MAX_RETRIES + " attempts: " + lastException.getMessage(), lastException);
    }

    public static void sendOnly(String host, int port, Message msg) throws IOException {
        try (Socket socket = createSocket(host, port)) {
            sendMessage(socket, msg);
        }
    }

    public static void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public static String[] parseAddress(String address) {
        int lastColon = address.lastIndexOf(':');
        if (lastColon == -1) {
            throw new IllegalArgumentException("Invalid address: " + address);
        }
        return new String[]{
            address.substring(0, lastColon),
            address.substring(lastColon + 1)
        };
    }
}
