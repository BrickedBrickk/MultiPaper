package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.ChunkKey;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RequestChunkOwnershipMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.server.ChunkSubscriptionManager;
import puregero.multipaper.server.ChunkLockManager;
import puregero.multipaper.server.ServerConnection;

import java.util.concurrent.CompletableFuture;

public class RequestChunkOwnershipHandler {
    public static void handle(ServerConnection connection, RequestChunkOwnershipMessage message) {
        boolean hasAtLeastOneChunkLocked = false;
        for (ChunkKey key : message.chunks) {
            if (ChunkSubscriptionManager.getOwner(key.world, key.x, key.z) == connection) {
                hasAtLeastOneChunkLocked = true;
            }
        }

        if (hasAtLeastOneChunkLocked) {
            // A forced claim cannot take a chunk away from another active owner.
            // Reject that request before waiting on the other owner's save barrier;
            // the final atomic check below still closes races with ownership changes.
            boolean conflictsWithActiveOwner = java.util.Arrays.stream(message.chunks)
                    .anyMatch(key -> {
                        ServerConnection owner = ChunkSubscriptionManager.getOwner(key.world, key.x, key.z);
                        return owner != null && owner != connection;
                    });
            if (conflictsWithActiveOwner) {
                connection.sendReply(new BooleanMessageReply(false), message);
                return;
            }

            CompletableFuture<?>[] pendingSaves = java.util.Arrays.stream(message.chunks)
                    .map(key -> ChunkLockManager.waitForLockAsync(key.world, key.x, key.z))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(pendingSaves).thenRun(() -> {
                boolean stillOwnsRequestedChunk = java.util.Arrays.stream(message.chunks)
                        .anyMatch(key -> ChunkSubscriptionManager.getOwner(key.world, key.x, key.z) == connection);
                if (!stillOwnsRequestedChunk) {
                    connection.sendReply(new BooleanMessageReply(false), message);
                    return;
                }
                boolean claimed = ChunkSubscriptionManager.forceLockIfNoOtherOwner(connection, message.chunks);
                CompletableFuture.runAsync(() -> connection.sendReply(new BooleanMessageReply(claimed), message));
            });
        } else {
            connection.sendReply(new BooleanMessageReply(false), message);
        }
    }
}
