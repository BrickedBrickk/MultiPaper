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
    private static final Set<ChunkKey> successfulWritesAwaitingBarrier = ConcurrentHashMap.newKeySet();

    static boolean hasFailedWrite(ChunkKey key) {
        if (failedWrites.containsKey(key)) return true;
        try {
            return FailedChunkWriteStore.isFailed(key);
        } catch (java.io.IOException failure) {
            System.err.println("Could not read failed region-write marker for " + key + "; treating chunk storage as quarantined");
            failure.printStackTrace();
            return true;
        }
    }

    static boolean hasPendingWrites(String world, int cx, int cz) {
        return locks.hasPendingWrites(world, cx, cz);
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
                if (locks.isLastPendingWrite(world, cx, cz)) {
                    successful = clearSuccessfulWriteQuarantine(key, connection);
                } else {
                    // Keep an existing durable quarantine until every already
                    // announced or active write for this chunk has resolved. A
                    // crash during a later write must not expose the earlier disk
                    // image after this successful completion clears the marker.
                    successfulWritesAwaitingBarrier.add(key);
                }
            } else {
                successfulWritesAwaitingBarrier.remove(key);
                failedWrites.put(key, connection);
                try {
                    FailedChunkWriteStore.markFailed(key);
                } catch (java.io.IOException markerFailure) {
                    System.err.println("Could not durably quarantine failed region save for " + key
                            + "; the chunk remains quarantined in Master memory only");
                    markerFailure.printStackTrace();
                }
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
            if (!locks.hasPendingWrites(world, cx, cz) && successfulWritesAwaitingBarrier.remove(key)) {
                clearSuccessfulWriteQuarantine(key, connection);
            }
        }
    }

    private static boolean clearSuccessfulWriteQuarantine(ChunkKey key, ServerConnection connection) {
        try {
            FailedChunkWriteStore.clearFailed(key);
            failedWrites.remove(key);
            successfulWritesAwaitingBarrier.remove(key);
            return true;
        } catch (java.io.IOException failure) {
            // The region write itself completed, but keep reads and ownership
            // fail-closed if an earlier quarantine could not be durably removed.
            if (connection != null) failedWrites.put(key, connection);
            System.err.println("Could not clear failed region-write marker after successful save for " + key);
            failure.printStackTrace();
            return false;
        }
    }

    public static void waitForLock(String world, int cx, int cz, Runnable callback) {
        locks.waitForLock(world, cx, cz, callback);
    }

    /**
     * Schedules an authoritative region read only after all announced writes drain.
     * The empty-barrier check and read enqueue share the chunk lock with write
     * announcements, so a late save cannot overtake the read. If a previous owner
     * save failed, the caller must fail closed instead of serving an older snapshot.
     */
    public static void readWhenSafe(String world, int cx, int cz, Runnable read, Runnable failedWrite) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        locks.waitForLock(world, cx, cz, () -> {
            boolean retryAfterLateWrite;
            boolean storageIsStale;
            synchronized (ChunkLock.getChunkLock(key)) {
                retryAfterLateWrite = locks.hasPendingWrites(world, cx, cz);
                storageIsStale = !retryAfterLateWrite && hasFailedWrite(key);
                if (!retryAfterLateWrite && !storageIsStale) {
                    // Enqueue the region read before releasing the lock shared with
                    // write announcements and owner-authorized write submission.
                    read.run();
                }
            }
            if (retryAfterLateWrite) {
                readWhenSafe(world, cx, cz, read, failedWrite);
            } else if (storageIsStale) {
                failedWrite.run();
            }
        });
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
            keys.addAll(successfulWritesAwaitingBarrier);
            for (ChunkKey key : keys) {
                synchronized (ChunkLock.getChunkLock(key)) {
                    if (!locks.hasPendingWrites(key.world, key.x, key.z)
                            && successfulWritesAwaitingBarrier.contains(key)) {
                        clearSuccessfulWriteQuarantine(key, connection);
                    }
                    Set<ServerConnection> waiting = deferredUnlocks.get(key);
                    if (waiting != null) {
                        waiting.remove(connection);
                        if (waiting.isEmpty()) deferredUnlocks.remove(key);
                    }
                    // Keep the chunk quarantined after its writer disconnects. The
                    // persisted region file may still contain the previous chest
                    // image, so forgetting the failed save here would let a later
                    // owner read stale contents as authoritative. A successful
                    // write for this chunk clears the marker in writeCompleted.
                }
            }
        });
    }

}
