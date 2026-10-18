package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteDataMessage;
import puregero.multipaper.mastermessagingprotocol.SharedDataPath;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataUpdateMessage;
import puregero.multipaper.server.FileLocker;
import puregero.multipaper.server.ServerConnection;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class WriteDataHandler {
    public static void handle(ServerConnection connection, WriteDataMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                final String sharedPath = SharedDataPath.normalizeSharedPath(message.path);
                FileLocker.writeBytes(new File(sharedPath), message.data);
                connection.sendReply(new BooleanMessageReply(true), message);

                if (sharedPath.contains("scoreboard")) {
                    // Scoreboards are synced with other methods
                    return;
                }

                connection.broadcastOthers(new DataUpdateMessage(sharedPath, message.data));
            } catch (IOException e) {
                e.printStackTrace();
                connection.sendReply(new BooleanMessageReply(false), message);
            }
        });
    }
}
