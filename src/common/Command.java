package common;

/**
 * Defines the types of messages that can be exchanged between Client, Master,
 * and ChunkServers.
 */
public enum Command {
    // Master <-> ChunkServer
    HEARTBEAT, // ChunkServer -> Master (I'm alive)
    REGISTER, // ChunkServer -> Master (First time join)

    // Client -> Master
    GET_FILE_LOCATIONS, // Client -> Master (Where is file.txt?)
    LIST_FILES, // Client -> Master (Show me all files)
    ALLOCATE_CHUNK, // Client -> Master (I want to write a new chunk)
    DELETE_FILE, // Client -> Master (Delete a file and its chunks)
    SNAPSHOT, // Client -> Master (Create a fast snapshot of a file)
    STATUS, // Client -> Master (Cluster health check)

    // Client <-> ChunkServer
    READ_CHUNK, // Client -> ChunkServer
    WRITE_CHUNK, // Client -> ChunkServer
    DELETE_CHUNK, // Master/Client -> ChunkServer (Delete a specific chunk)

    // Master -> ChunkServer (Replication)
    REPLICATE_CHUNK, // Master -> SourceServer (Copy this chunk to TargetServer)

    // Responses
    SUCCESS,
    ERROR
}
