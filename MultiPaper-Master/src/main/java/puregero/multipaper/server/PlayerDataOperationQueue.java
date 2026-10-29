package puregero.multipaper.server;

import java.util.HashMap;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Serializes player ownership changes and playerdata IO in Master message order. */
public final class PlayerDataOperationQueue {
    private static final Map<UUID, CompletableFuture<Void>> PENDING = new HashMap<>();

    private PlayerDataOperationQueue() { }

    public static CompletableFuture<Void> submit(UUID playerId, Runnable operation) {
        synchronized (PENDING) {
            CompletableFuture<Void> previous = PENDING.getOrDefault(playerId, CompletableFuture.completedFuture(null));
            CompletableFuture<Void> next = previous.handle((ignored, failure) -> null).thenRunAsync(operation);
            PENDING.put(playerId, next);
            next.whenComplete((ignored, failure) -> {
                synchronized (PENDING) {
                    if (PENDING.get(playerId) == next) {
                        PENDING.remove(playerId);
                    }
                }
            });
            // Keep caller cancellation from completing the internal participant
            // barrier early and allowing later work to overtake this operation.
            return next.copy();
        }
    }

    /**
     * Queues one operation after all current work for the given players, then
     * makes later work for every participant wait for that same operation.
     * This provides a deterministic barrier for a future atomic multi-player
     * playerdata transaction without acquiring per-player locks in nested order.
     */
    public static CompletableFuture<Void> submitAll(Collection<UUID> playerIds, Runnable operation) {
        LinkedHashSet<UUID> participants = new LinkedHashSet<>(playerIds);
        if (participants.isEmpty() || participants.contains(null)) {
            throw new IllegalArgumentException("A multi-player operation requires non-null participants");
        }

        synchronized (PENDING) {
            CompletableFuture<?>[] previous = participants.stream()
                    .map(playerId -> PENDING.getOrDefault(playerId, CompletableFuture.completedFuture(null)))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture<Void> next = CompletableFuture.allOf(previous)
                    .handle((ignored, failure) -> null)
                    .thenRunAsync(operation);
            for (UUID playerId : participants) {
                PENDING.put(playerId, next);
            }
            next.whenComplete((ignored, failure) -> {
                synchronized (PENDING) {
                    for (UUID playerId : participants) {
                        if (PENDING.get(playerId) == next) {
                            PENDING.remove(playerId);
                        }
                    }
                }
            });
            // A cancelled waiter must not release any participant reservation.
            return next.copy();
        }
    }

    /**
     * Queues an operation after all current work for the given players and keeps
     * every participant reserved until the asynchronous operation completes.
     * Use this for transactions that await owner/backend acknowledgements; a
     * synchronous Runnable that merely starts network requests would release its
     * participant barrier before those acknowledgements arrive.
     */
    public static CompletableFuture<Void> submitAllAsync(
            Collection<UUID> playerIds,
            Supplier<? extends CompletionStage<Void>> operation) {
        Objects.requireNonNull(operation, "operation");
        LinkedHashSet<UUID> participants = new LinkedHashSet<>(playerIds);
        if (participants.isEmpty() || participants.contains(null)) {
            throw new IllegalArgumentException("A multi-player operation requires non-null participants");
        }

        synchronized (PENDING) {
            CompletableFuture<?>[] previous = participants.stream()
                    .map(playerId -> PENDING.getOrDefault(playerId, CompletableFuture.completedFuture(null)))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture<Void> next = CompletableFuture.allOf(previous)
                    .handle((ignored, failure) -> null)
                    .thenComposeAsync(ignored -> operation.get())
                    .toCompletableFuture();
            for (UUID playerId : participants) {
                PENDING.put(playerId, next);
            }
            next.whenComplete((ignored, failure) -> {
                synchronized (PENDING) {
                    for (UUID playerId : participants) {
                        if (PENDING.get(playerId) == next) {
                            PENDING.remove(playerId);
                        }
                    }
                }
            });
            // Do not expose the queue's internal reservation future: a caller
            // cancelling its returned handle must not release participants while
            // the asynchronous owner operation is still running.
            return next.copy();
        }
    }
}
