package puregero.multipaper;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class PlayerSessionRegistrationStateTest {
    @Test
    void readinessRequiresAnAcceptedReplyForTheExactPlayerSession() {
        PlayerSessionRegistrationState state = new PlayerSessionRegistrationState();
        UUID player = UUID.randomUUID();
        UUID firstSession = UUID.randomUUID();
        UUID otherSession = UUID.randomUUID();

        PlayerSessionRegistrationState.Attempt attempt = state.begin(player, firstSession);
        assertTrue(attempt.shouldSend());
        assertFalse(state.isRegistered(player, firstSession));
        assertFalse(state.isRegistered(player, otherSession));

        assertTrue(state.complete(attempt, true));
        assertTrue(attempt.result().join());
        assertTrue(state.isRegistered(player, firstSession));
        assertFalse(state.isRegistered(player, otherSession));
    }

    @Test
    void duplicateRegistrationWaitersShareOnePendingRequestAndResult() {
        PlayerSessionRegistrationState state = new PlayerSessionRegistrationState();
        UUID player = UUID.randomUUID();
        UUID session = UUID.randomUUID();

        PlayerSessionRegistrationState.Attempt first = state.begin(player, session);
        PlayerSessionRegistrationState.Attempt duplicate = state.begin(player, session);

        assertTrue(first.shouldSend());
        assertFalse(duplicate.shouldSend());
        assertSame(first.result(), duplicate.result());
        assertTrue(state.complete(first, true));
        assertTrue(duplicate.result().join());
    }

    @Test
    void rejectionLeavesSessionUnreadyAndAllowsRetry() {
        PlayerSessionRegistrationState state = new PlayerSessionRegistrationState();
        UUID player = UUID.randomUUID();
        UUID session = UUID.randomUUID();

        PlayerSessionRegistrationState.Attempt rejected = state.begin(player, session);
        assertTrue(state.complete(rejected, false));
        assertFalse(rejected.result().join());
        assertFalse(state.isRegistered(player, session));

        PlayerSessionRegistrationState.Attempt retry = state.begin(player, session);
        assertTrue(retry.shouldSend());
        assertTrue(state.complete(retry, true));
        assertTrue(state.isRegistered(player, session));
    }

    @Test
    void lateOldSessionReplyCannotMarkReplacementSessionReady() {
        PlayerSessionRegistrationState state = new PlayerSessionRegistrationState();
        UUID player = UUID.randomUUID();
        PlayerSessionRegistrationState.Attempt oldSession = state.begin(player, UUID.randomUUID());
        UUID currentSessionId = UUID.randomUUID();
        PlayerSessionRegistrationState.Attempt currentSession = state.begin(player, currentSessionId);

        assertFalse(oldSession.result().join(), "Replacing a player session must release old waiters as denied");
        assertFalse(state.complete(oldSession, true), "A late old-session reply must be ignored");
        assertFalse(state.isRegistered(player, currentSessionId));
        assertTrue(state.complete(currentSession, true));
        assertTrue(state.isRegistered(player, currentSessionId));
    }

    @Test
    void disconnectInvalidatesPendingAndAcceptedRegistrations() {
        PlayerSessionRegistrationState state = new PlayerSessionRegistrationState();
        UUID player = UUID.randomUUID();
        UUID session = UUID.randomUUID();

        PlayerSessionRegistrationState.Attempt pending = state.begin(player, session);
        state.invalidate();
        assertFalse(pending.result().join());
        assertFalse(state.complete(pending, true));
        assertFalse(state.isRegistered(player, session));

        PlayerSessionRegistrationState.Attempt reconnect = state.begin(player, session);
        assertTrue(reconnect.shouldSend());
        assertTrue(state.complete(reconnect, true));
        assertTrue(state.isRegistered(player, session));

        state.invalidate();
        assertFalse(state.isRegistered(player, session));
    }

    @Test
    void playerDisconnectRetiresOnlyTheMatchingSession() {
        PlayerSessionRegistrationState state = new PlayerSessionRegistrationState();
        UUID player = UUID.randomUUID();
        UUID oldSessionId = UUID.randomUUID();
        UUID newSessionId = UUID.randomUUID();

        PlayerSessionRegistrationState.Attempt oldSession = state.begin(player, oldSessionId);
        assertTrue(state.complete(oldSession, true));
        state.retire(player, oldSessionId);
        assertFalse(state.isRegistered(player, oldSessionId));

        PlayerSessionRegistrationState.Attempt newSession = state.begin(player, newSessionId);
        assertTrue(state.complete(newSession, true));
        state.retire(player, oldSessionId);
        assertTrue(state.isRegistered(player, newSessionId), "A delayed disconnect for an old session must not retire its replacement");
    }
}
