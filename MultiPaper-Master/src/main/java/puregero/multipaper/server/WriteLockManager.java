package puregero.multipaper.server;

import puregero.multipaper.mastermessagingprotocol.ChunkKey;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
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

    void writeStarted(ServerConnection connection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        locks.compute(key, (ignored, state) -> {
            if (state == null) {
                state = new LockState();
            }
            Integer announced = state.pendingWritesByConnection.get(connection);
            if (announced != null) {
                if (announced == 1) {
                    state.pendingWritesByConnection.remove(connection);
                } else {
                    state.pendingWritesByConnection.put(connection, announced - 1);
                }
            } else {
                // Also fence reads against writes that were accepted without a prior
                // WillSaveChunkLater announcement.
                state.pendingWrites++;
            }
            state.activeWritesByConnection.merge(connection, 1, Integer::sum);
            state.activeWriteDrains.computeIfAbsent(connection, ignoredConnection -> new CompletableFuture<>());
            return state;
        });
    }

    void writeCompleted(ServerConnection connection, String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        AtomicReference<CompletableFuture<Void>> completed = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> activeWriteCompleted = new AtomicReference<>();

        locks.computeIfPresent(key, (ignored, state) -> {
            Integer activeForConnection = state.activeWritesByConnection.get(connection);
            if (activeForConnection == null) {
                return state;
            }

            if (activeForConnection == 1) {
                state.activeWritesByConnection.remove(connection);
                activeWriteCompleted.set(state.activeWriteDrains.remove(connection));
            } else {
                state.activeWritesByConnection.put(connection, activeForConnection - 1);
            }

            if (--state.pendingWrites == 0) {
                completed.set(state.completed);
                return null;
            }
            return state;
        });

        complete(completed.get());
        complete(activeWriteCompleted.get());
    }

    void cancelPendingWrite(ServerConnection connection, String world, int cx, int cz) {
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
        waitForLockAsync(world, cx, cz).thenRun(callback);
    }

    CompletableFuture<Void> waitForLockAsync(String world, int cx, int cz) {
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
            return CompletableFuture.completedFuture(null);
        }
        return lock.thenCompose(ignored -> waitForLockAsync(world, cx, cz));
    }

    boolean hasPendingWrites(String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        AtomicReference<Boolean> pending = new AtomicReference<>(false);
        locks.compute(key, (ignored, state) -> {
            pending.set(state != null && state.pendingWrites > 0);
            return state;
        });
        return pending.get();
    }

    boolean isLastPendingWrite(String world, int cx, int cz) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        AtomicReference<Boolean> last = new AtomicReference<>(false);
        locks.computeIfPresent(key, (ignored, state) -> {
            last.set(state.pendingWrites == 1);
            return state;
        });
        return last.get();
    }

    CompletableFuture<Void> releaseAll(ServerConnection connection) {
        List<CompletableFuture<Void>> activeWrites = new ArrayList<>();
        for (ChunkKey key : locks.keySet()) {
            AtomicReference<CompletableFuture<Void>> completed = new AtomicReference<>();
            locks.computeIfPresent(key, (ignored, state) -> {
                Integer pendingForConnection = state.pendingWritesByConnection.remove(connection);
                if (pendingForConnection != null) {
                    state.pendingWrites -= pendingForConnection;
                }

                CompletableFuture<Void> activeWrite = state.activeWriteDrains.get(connection);
                if (activeWrite != null) {
                    activeWrites.add(activeWrite);
                }
                if (state.pendingWrites == 0) {
                    completed.set(state.completed);
                    return null;
                }
                return state;
            });
            complete(completed.get());
        }
        return activeWrites.isEmpty() ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(activeWrites.toArray(CompletableFuture[]::new));
    }

    private static void complete(CompletableFuture<Void> future) {
        if (future != null) {
            future.complete(null);
        }
    }

    private static final class LockState {
        private final Map<ServerConnection, Integer> pendingWritesByConnection = new HashMap<>();
        private final Map<ServerConnection, Integer> activeWritesByConnection = new HashMap<>();
        private final Map<ServerConnection, CompletableFuture<Void>> activeWriteDrains = new HashMap<>();
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private int pendingWrites;
    }
}
