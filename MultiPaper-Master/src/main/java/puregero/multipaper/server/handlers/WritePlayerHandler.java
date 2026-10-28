package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WritePlayerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.server.PlayerDataOperationQueue;
import puregero.multipaper.server.PlayerDataTransactionManager;
import puregero.multipaper.server.ServerConnection;

import java.io.IOException;
import java.util.List;

public class WritePlayerHandler {
    public static void handle(ServerConnection connection, WritePlayerMessage message) {
        java.util.UUID playerId = java.util.UUID.fromString(message.uuid);
        List<ServerConnection> connections = ServerConnection.getConnections();
        final boolean authorizedAtReceipt;
        synchronized (connections) {
            // Capture authorization while handling the ordered channel message. A valid final
            // save may still be queued when channelInactive removes this connection.
            authorizedAtReceipt = connections.contains(connection)
                    && connection.hasPlayer(playerId)
                    && !connection.isPlayerDisconnecting(playerId);
        }
        PlayerDataOperationQueue.submit(playerId, () -> {
            try {
                if (!authorizedAtReceipt) {
                    connection.sendReply(new BooleanMessageReply(false), message);
                    return;
                }
                // Per-player queue order places this write before a later disconnect/reconnect.
                PlayerDataTransactionManager.writePlayer(message.world, playerId, message.data);
                connection.sendReply(new BooleanMessageReply(true), message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
