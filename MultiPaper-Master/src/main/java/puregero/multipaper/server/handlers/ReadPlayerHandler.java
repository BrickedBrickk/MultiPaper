package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadPlayerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataMessageReply;
import puregero.multipaper.server.PlayerDataOperationQueue;
import puregero.multipaper.server.PlayerDataTransactionManager;
import puregero.multipaper.server.ServerConnection;

import java.io.IOException;
import java.util.UUID;

public class ReadPlayerHandler {
    public static void handle(ServerConnection connection, ReadPlayerMessage message) {
        UUID playerId = UUID.fromString(message.uuid);
        PlayerDataOperationQueue.submit(playerId, () -> {
            try {
                byte[] b = PlayerDataTransactionManager.readPlayer(message.world, playerId);
                connection.sendReply(new DataMessageReply(b), message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
