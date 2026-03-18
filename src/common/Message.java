package common;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * A generic container for all network messages.
 * Uses Java Serialization for simplicity.
 */
public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    public Command command;
    public Map<String, Object> payload = new HashMap<>();
    public byte[] data; // For raw file content

    public Message(Command command) {
        this.command = command;
    }

    public void put(String key, Object value) {
        payload.put(key, value);
    }

    public Object get(String key) {
        return payload.get(key);
    }

    @Override
    public String toString() {
        return "Message{command=" + command + ", payload=" + payload + "}";
    }
}
