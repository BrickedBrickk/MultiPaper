package puregero.multipaper.server;

import puregero.multipaper.mastermessagingprotocol.ChunkKey;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks pending asynchronous writes for one Master storage path.
 * Every mutation is serialized by ConcurrentHashMap.compute for its chunk key.
 */
final class WriteLockManager {

    private final ConcurrentHashMap<ChunkKey, LockState> locks = new ConcurrentHashMap<>();

    void lockUntilWrite(ServerConnection connection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        locks.compute(key, (ignored, state) -> {
            if (state == null) {
                state = new LockState();
            }
            state.pendingWrites++;
            state.pendingWritesByConnection.merge(connection, 1, Integer::sum);
            return state;
        });
    }

    void writeCompleted(ServerConnection connection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        AtomicReference<CompletableFuture<Void>> completed = new AtomicReference<>();

        locks.computeIfPresent(key, (ignored, state) -> {
            Integer pendingForConnection = state.pendingWritesByConnection.get(connection);
            if (pendingForConnection == null) {
                return state;
            }

            if (pendingForConnection == 1) {
                state.pendingWritesByConnection.remove(connection);
            } else {
                state.pendingWritesByConnection.put(connection, pendingForConnection - 1);
            }

            if (--state.pendingWrites == 0) {
                completed.set(state.completed);
                return null;
            }
            return state;
        });

        complete(completed.get());
    }

    void waitForLock(String world, int cx, int cz, Runnable callback) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        AtomicReference<CompletableFuture<Void>> pending = new AtomicReference<>();

        locks.compute(key, (ignored, state) -> {
            if (state != null) {
                pending.set(state.completed);
            }
            return state;
        });

        CompletableFuture<Void> lock = pending.get();
        if (lock == null) {
            callback.run();
        } else {
            lock.thenRun(() -> waitForLock(world, cx, cz, callback));
        }
    }

    void releaseAll(ServerConnection connection) {
        for (ChunkKey key : locks.keySet()) {
            AtomicReference<CompletableFuture<Void>> completed = new AtomicReference<>();
            locks.computeIfPresent(key, (ignored, state) -> {
                Integer pendingForConnection = state.pendingWritesByConnection.remove(connection);
                if (pendingForConnection == null) {
                    return state;
                }

                state.pendingWrites -= pendingForConnection;
                if (state.pendingWrites == 0) {
                    completed.set(state.completed);
                    return null;
                }
                return state;
            });
            complete(completed.get());
        }
    }

    private static void complete(CompletableFuture<Void> future) {
        if (future != null) {
            future.complete(null);
        }
    }

    private static final class LockState {
        private final Map<ServerConnection, Integer> pendingWritesByConnection = new HashMap<>();
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private int pendingWrites;
    }
}
