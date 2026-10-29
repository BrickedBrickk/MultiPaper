package puregero.multipaper;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Tracks Master acknowledgements for the exact live player session and connection generation. */
final class PlayerSessionRegistrationState {
    private final Map<UUID, Registration> currentByPlayer = new HashMap<>();
    private long connectionGeneration;

    synchronized Attempt begin(UUID playerId, UUID sessionId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sessionId, "sessionId");

        Registration current = currentByPlayer.get(playerId);
        if (current != null && current.connectionGeneration == connectionGeneration && current.sessionId.equals(sessionId)) {
            return new Attempt(current, false);
        }

        if (current != null) current.result.complete(false);
        Registration created = new Registration(playerId, sessionId, connectionGeneration);
        currentByPlayer.put(playerId, created);
        return new Attempt(created, true);
    }

    synchronized boolean complete(Attempt attempt, boolean accepted) {
        Objects.requireNonNull(attempt, "attempt");
        Registration registration = attempt.registration;
        Registration current = currentByPlayer.get(registration.playerId);
        if (current != registration || registration.connectionGeneration != connectionGeneration) return false;

        current.result.complete(accepted);
        if (!accepted) currentByPlayer.remove(registration.playerId, current);
        return true;
    }

    synchronized boolean isRegistered(UUID playerId, UUID sessionId) {
        if (playerId == null || sessionId == null) return false;
        Registration current = currentByPlayer.get(playerId);
        return current != null && current.connectionGeneration == connectionGeneration
                && current.sessionId.equals(sessionId) && Boolean.TRUE.equals(current.result.getNow(false));
    }

    synchronized void retire(UUID playerId, UUID sessionId) {
        if (playerId == null || sessionId == null) return;
        Registration current = currentByPlayer.get(playerId);
        if (current != null && current.sessionId.equals(sessionId)) {
            currentByPlayer.remove(playerId, current);
            current.result.complete(false);
        }
    }

    synchronized void invalidate() {
        connectionGeneration++;
        currentByPlayer.values().forEach(registration -> registration.result.complete(false));
        currentByPlayer.clear();
    }

    static final class Attempt {
        private final Registration registration;
        private final boolean shouldSend;

        private Attempt(Registration registration, boolean shouldSend) {
            this.registration = registration;
            this.shouldSend = shouldSend;
        }

        boolean shouldSend() {
            return shouldSend;
        }

        CompletableFuture<Boolean> result() {
            return registration.result;
        }
    }

    private static final class Registration {
        private final UUID playerId;
        private final UUID sessionId;
        private final long connectionGeneration;
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();

        private Registration(UUID playerId, UUID sessionId, long connectionGeneration) {
            this.playerId = playerId;
            this.sessionId = sessionId;
            this.connectionGeneration = connectionGeneration;
        }
    }
}
