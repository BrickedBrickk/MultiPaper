package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteLevelMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.server.FileLocker;
import puregero.multipaper.server.ServerConnection;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class WriteLevelHandler {
    public static void handle(ServerConnection connection, WriteLevelMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                File worldDir = new File(message.world);
                if (!worldDir.exists()) worldDir.mkdirs();
                FileLocker.writeBytes(new File(worldDir, "level.dat"), message.data);
                connection.sendReply(new BooleanMessageReply(true), message);
            } catch (IOException e) {
                e.printStackTrace();
                connection.sendReply(new BooleanMessageReply(false), message);
            }
        });
    }
}
