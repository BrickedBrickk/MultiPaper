package puregero.multipaper;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.StartMessage;

import java.util.function.Consumer;

/** Keeps the backend's proxy registration available for every Master connection. */
final class ServerStartMessageTracker {
    private StartMessage startMessage;
    private boolean connected;

    synchronized void set(StartMessage message, Consumer<StartMessage> sender) {
        startMessage = message;
        if (connected) {
            sender.accept(message);
        }
    }

    synchronized void onConnect(Consumer<StartMessage> sender) {
        connected = true;
        if (startMessage != null) {
            sender.accept(startMessage);
        }
    }

    synchronized void onDisconnect() {
        connected = false;
    }
}
