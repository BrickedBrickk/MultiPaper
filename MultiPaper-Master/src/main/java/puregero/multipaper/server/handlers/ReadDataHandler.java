package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadDataMessage;
import puregero.multipaper.mastermessagingprotocol.SharedDataPath;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataMessageReply;
import puregero.multipaper.server.FileLocker;
import puregero.multipaper.server.ServerConnection;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ReadDataHandler {
    public static void handle(ServerConnection connection, ReadDataMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                byte[] b = FileLocker.readBytes(SharedDataPath.resolveSharedPath(message.path).toFile());
                connection.sendReply(new DataMessageReply(b), message);
            } catch (IOException e) {
                System.err.println("Failed to read shared saved-data path " + message.path);
                e.printStackTrace();
                connection.sendReply(new DataMessageReply(new byte[0]), message);
            }
        });
    }
}
