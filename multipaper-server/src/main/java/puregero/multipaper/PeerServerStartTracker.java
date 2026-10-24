package puregero.multipaper;

import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerStartedMessage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Retains peer announcements received before the Minecraft server is ready. */
final class PeerServerStartTracker {
    private final Map<String, ServerStartedMessage> pending = new ConcurrentHashMap<>();

    boolean enqueue(ServerStartedMessage message) {
        return pending.putIfAbsent(key(message), message) == null;
    }

    boolean isPending(ServerStartedMessage message) {
        return pending.get(key(message)) == message;
    }

    boolean dispatchIfReady(ServerStartedMessage message, boolean ready, Consumer<ServerStartedMessage> connector) {
        if (!ready) {
            return false;
        }
        return pending.remove(key(message), message) && dispatch(message, connector);
    }

    boolean discard(ServerStartedMessage message) {
        return pending.remove(key(message), message);
    }

    private static boolean dispatch(ServerStartedMessage message, Consumer<ServerStartedMessage> connector) {
        connector.accept(message);
        return true;
    }

    private static String key(ServerStartedMessage message) {
        return message.host + ":" + message.port;
    }
}
