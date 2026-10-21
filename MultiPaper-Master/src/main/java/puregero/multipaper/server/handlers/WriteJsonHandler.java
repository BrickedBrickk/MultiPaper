package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteJsonMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.server.FileLocker;
import puregero.multipaper.server.ServerConnection;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

public class WriteJsonHandler {
    public static final HashMap<String, byte[]> writesInProgress = new HashMap<>();
    private static final Object writingLock = new Object();

    public static void handle(ServerConnection connection, WriteJsonMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                synchronized (writingLock) {
                    synchronized (writesInProgress) {
                        writesInProgress.put(message.file, message.data);
                    }

                    try {
                        FileLocker.writeBytes(new File(message.file), message.data);
                    } finally {
                        synchronized (writesInProgress) {
                            writesInProgress.remove(message.file);
                        }
                    }
                }

                connection.sendReply(new BooleanMessageReply(true), message);
            } catch (IOException e) {
                System.err.println("Failed to persist JSON file " + message.file);
                e.printStackTrace();
                connection.sendReply(new BooleanMessageReply(false), message);
            }
        });
    }
}
