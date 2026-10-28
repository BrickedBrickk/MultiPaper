package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PlayerDisconnectMessage;
import puregero.multipaper.server.PlayerDataOperationQueue;
import puregero.multipaper.server.ServerConnection;

import java.util.List;

public class PlayerDisconnectHandler {
    public static void handle(ServerConnection connection, PlayerDisconnectMessage message) {
        connection.markPlayerDisconnecting(message.uuid);
        PlayerDataOperationQueue.submit(message.uuid, () -> {
            List<ServerConnection> connections = ServerConnection.getConnections();
            synchronized (connections) {
                connection.removePlayer(message.uuid);
            }
        });
    }
}
