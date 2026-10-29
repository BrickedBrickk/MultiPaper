package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RegisterPlayerSessionMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.server.PlayerDataOperationQueue;
import puregero.multipaper.server.ServerConnection;

import java.util.List;

public final class RegisterPlayerSessionHandler {
    private RegisterPlayerSessionHandler() { }

    public static void handle(ServerConnection connection, RegisterPlayerSessionMessage message) {
        PlayerDataOperationQueue.submit(message.playerId, () -> {
            boolean accepted = false;
            List<ServerConnection> connections = ServerConnection.getConnections();
            synchronized (connections) {
                if (connections.contains(connection)) {
                    accepted = connection.registerPlayerSession(message.playerId, message.sessionId);
                }
            }
            connection.sendReply(new BooleanMessageReply(accepted), message);
        });
    }
}
