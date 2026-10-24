package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.StartMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerStartedMessage;
import puregero.multipaper.server.ServerConnection;

public class StartHandler {
    public static void handle(ServerConnection connection, StartMessage message) {
        if (!message.host.isEmpty() && !message.host.equals("0.0.0.0")) {
            System.out.print("Setting " + connection.getBungeeCordName() + "'s host to " + message.host + " with port " + message.port);
            if (!message.host.equals(connection.getHost())) {
                System.out.print(" (Host address was " + connection.getHost() + ")");
            }
            System.out.println();
            connection.setHost(message.host);
        }

        connection.setPort(message.port);
        connection.setStarted(true);
        System.out.println(connection.getBungeeCordName() + " (" + connection.getHost() + ":" + message.port + ") has started, announcing connected servers...");
        for (ServerConnection existing : ServerConnection.getConnections()) {
            // SetPortMessage can arrive before StartMessage. Do not announce that
            // server here until its StartMessage is handled: its own StartHandler
            // will broadcast it, otherwise every peer can receive duplicate starts
            // and create competing external-server connections.
            if (existing != connection && existing.hasStarted() && existing.getPort() > 0 && existing.getChannel().isActive()) {
                if (connection.markServerAnnounced(existing)) {
                    connection.send(new ServerStartedMessage(existing.getHost(), existing.getPort()));
                }
            }
        }
        for (ServerConnection existing : ServerConnection.getConnections()) {
            if (existing != connection && existing.getChannel().isActive() && existing.markServerAnnounced(connection)) {
                existing.send(new ServerStartedMessage(connection.getHost(), message.port));
            }
        }
    }
}
