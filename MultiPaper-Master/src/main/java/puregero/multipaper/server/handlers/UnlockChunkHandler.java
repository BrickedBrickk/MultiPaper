package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.UnlockChunkMessage;
import puregero.multipaper.server.ChunkLockManager;
import puregero.multipaper.server.ChunkSubscriptionManager;
import puregero.multipaper.server.ServerConnection;

public class UnlockChunkHandler {
    public static void handle(ServerConnection connection, UnlockChunkMessage message) {
        // A backend announces an asynchronous chunk save before it starts writing
        // the snapshot to Master. Do not hand ownership to another backend until
        // that snapshot is durably stored, or the next owner can load stale chest NBT.
        ChunkLockManager.waitForLock(message.world, message.cx, message.cz,
                () -> ChunkSubscriptionManager.unlock(connection, message.world, message.cx, message.cz));
    }
}
