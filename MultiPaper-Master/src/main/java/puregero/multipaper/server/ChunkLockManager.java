package puregero.multipaper.server;

import puregero.multipaper.mastermessagingprotocol.ChunkKey;
import puregero.multipaper.server.util.ChunkLock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkLockManager {

    private static final WriteLockManager locks = new WriteLockManager();
    private static final Map<ChunkKey, ServerConnection> failedWrites = new ConcurrentHashMap<>();
    private static final Map<ChunkKey, Set<ServerConnection>> deferredUnlocks = new ConcurrentHashMap<>();

    static boolean hasFailedWrite(ChunkKey key) {
        return failedWrites.containsKey(key);
    }

    public static void lockUntilWrite(ServerConnection connection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            locks.lockUntilWrite(connection, world, cx, cz);
        }
    }

    public static void writeStarted(ServerConnection connection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            locks.writeStarted(connection, world, cx, cz);
        }
    }

    public static void writeCompleted(ServerConnection connection, String world, int cx, int cz, boolean successful) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        List<ServerConnection> retryingOwners = List.of();
        synchronized (ChunkLock.getChunkLock(key)) {
            if (successful) {
                failedWrites.remove(key);
            } else {
                failedWrites.put(key, connection);
            }
            locks.writeCompleted(connection, world, cx, cz);
            if (successful) {
                Set<ServerConnection> deferred = deferredUnlocks.remove(key);
                if (deferred != null && !deferred.isEmpty()) {
                    retryingOwners = new ArrayList<>(deferred);
                }
            }
        }
        for (ServerConnection retryingOwner : retryingOwners) {
            waitForLock(world, cx, cz, () -> unlockAfterSuccessfulSave(retryingOwner, key));
        }
    }

    public static void cancelPendingWrite(ServerConnection connection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        synchronized (ChunkLock.getChunkLock(key)) {
            locks.cancelPendingWrite(connection, world, cx, cz);
        }
    }

    public static void waitForLock(String world, int cx, int cz, Runnable callback) {
        locks.waitForLock(world, cx, cz, callback);
    }

    public static CompletableFuture<Void> waitForLockAsync(String world, int cx, int cz) {
        return locks.waitForLockAsync(world, cx, cz);
    }

    public static void unlockWhenSaved(ServerConnection connection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        waitForLock(world, cx, cz, () -> unlockAfterSuccessfulSave(connection, key));
    }

    private static void unlockAfterSuccessfulSave(ServerConnection connection, ChunkKey key) {
        synchronized (ChunkLock.getChunkLock(key)) {
            if (locks.hasPendingWrites(key.world, key.x, key.z)) {
                waitForLock(key.world, key.x, key.z, () -> unlockAfterSuccessfulSave(connection, key));
                return;
            }
            if (failedWrites.get(key) == connection) {
                Set<ServerConnection> waiting = deferredUnlocks.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
                if (waiting.add(connection)) {
                    System.err.println("Holding chunk ownership for " + key.world + " " + key.x + "," + key.z
                            + " because its final region save failed; ownership will be released after a successful retry");
                }
                return;
            }
            Set<ServerConnection> waiting = deferredUnlocks.get(key);
            if (waiting != null) {
                waiting.remove(connection);
                if (waiting.isEmpty()) deferredUnlocks.remove(key);
            }
            ChunkSubscriptionManager.unlock(connection, key);
        }
    }

    public static CompletableFuture<Void> releaseAll(ServerConnection connection) {
        return locks.releaseAll(connection).whenComplete((ignored, failure) -> {
            Set<ChunkKey> keys = new HashSet<>(deferredUnlocks.keySet());
            keys.addAll(failedWrites.keySet());
            for (ChunkKey key : keys) {
                synchronized (ChunkLock.getChunkLock(key)) {
                    Set<ServerConnection> waiting = deferredUnlocks.get(key);
                    if (waiting != null) {
                        waiting.remove(connection);
                        if (waiting.isEmpty()) deferredUnlocks.remove(key);
                    }
                    failedWrites.remove(key, connection);
                }
            }
        });
    }

}
