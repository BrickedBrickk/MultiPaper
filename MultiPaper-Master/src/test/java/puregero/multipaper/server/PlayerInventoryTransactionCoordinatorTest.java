package puregero.multipaper.server;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerInventoryTransactionCoordinatorTest {
    private static final byte[] EDITOR_DIGEST = digest(1);
    private static final byte[] TARGET_DIGEST = digest(2);
    private static final byte[] REQUEST_DIGEST = digest(3);

    @Test
    void beginIsIdempotentAndRejectsTransactionIdReuseWithDifferentRequest() {
        PlayerInventoryTransactionCoordinator coordinator = new PlayerInventoryTransactionCoordinator();
        Fixture fixture = new Fixture();

        assertEquals(PlayerInventoryTransactionCoordinator.BeginStatus.STARTED,
                coordinator.begin(fixture.request).result());
        assertEquals(PlayerInventoryTransactionCoordinator.BeginStatus.REPLAY,
                coordinator.begin(fixture.request).result());

        PlayerInventoryTransactionCoordinator.TransactionRequest conflicting = new PlayerInventoryTransactionCoordinator.TransactionRequest(
                fixture.transactionId, fixture.editorSnapshot, fixture.targetSnapshot, digest(4), new byte[]{1}, new byte[]{2});
        assertEquals(PlayerInventoryTransactionCoordinator.BeginStatus.ID_CONFLICT,
                coordinator.begin(conflicting).result());
    }

    @Test
    void overlappingTransactionsReserveBothPlayersAtomically() throws Exception {
        PlayerInventoryTransactionCoordinator coordinator = new PlayerInventoryTransactionCoordinator();
        UUID shared = UUID.randomUUID();
        UUID otherA = UUID.randomUUID();
        UUID otherB = UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            Callable<PlayerInventoryTransactionCoordinator.BeginStatus> first = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return coordinator.begin(request(UUID.randomUUID(), shared, otherA, digest(5))).result();
            };
            Callable<PlayerInventoryTransactionCoordinator.BeginStatus> second = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return coordinator.begin(request(UUID.randomUUID(), shared, otherB, digest(6))).result();
            };
            var one = pool.submit(first);
            var two = pool.submit(second);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<PlayerInventoryTransactionCoordinator.BeginStatus> results = List.of(
                    one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(status -> status == PlayerInventoryTransactionCoordinator.BeginStatus.STARTED).count());
            assertEquals(1, results.stream().filter(status -> status == PlayerInventoryTransactionCoordinator.BeginStatus.BUSY).count());
            assertNotNull(coordinator.reservationOwner(shared));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void staleAttestationDoesNotPrepareOrReleaseEitherReservation() {
        PlayerInventoryTransactionCoordinator coordinator = new PlayerInventoryTransactionCoordinator();
        Fixture fixture = new Fixture();
        coordinator.begin(fixture.request);

        assertEquals(PlayerInventoryTransactionCoordinator.AttestationStatus.STALE,
                coordinator.attest(fixture.transactionId, fixture.editorId, fixture.editorSession, 8, EDITOR_DIGEST));
        assertEquals(PlayerInventoryTransactionCoordinator.Status.PREPARING, coordinator.status(fixture.transactionId));
        assertEquals(fixture.transactionId, coordinator.reservationOwner(fixture.editorId));
        assertEquals(fixture.transactionId, coordinator.reservationOwner(fixture.targetId));
    }

    @Test
    void bothExactLiveAttestationsAreRequiredBeforeCommitDecision() throws Exception {
        PlayerInventoryTransactionCoordinator coordinator = new PlayerInventoryTransactionCoordinator();
        Fixture fixture = new Fixture();
        coordinator.begin(fixture.request);

        assertEquals(PlayerInventoryTransactionCoordinator.AttestationStatus.WAITING_FOR_OTHER_PARTICIPANT,
                fixture.attestEditor(coordinator));
        assertEquals(PlayerInventoryTransactionCoordinator.DecisionStatus.NOT_PREPARED,
                coordinator.recordDurableCommitDecision(fixture.transactionId));
        assertEquals(PlayerInventoryTransactionCoordinator.AttestationStatus.PREPARED,
                fixture.attestTarget(coordinator));
        assertEquals(PlayerInventoryTransactionCoordinator.DecisionStatus.COMMIT_RECORDED,
                coordinator.recordDurableCommitDecision(fixture.transactionId));
        assertEquals(PlayerInventoryTransactionCoordinator.DecisionStatus.ALREADY_COMMITTED,
                coordinator.recordDurableCommitDecision(fixture.transactionId));
    }

    @Test
    void committedReservationsRemainUntilBothOwnerAcknowledgements() throws Exception {
        PreparedRun run = preparedCoordinator(new Fixture());
        PlayerInventoryTransactionCoordinator coordinator = run.coordinator();
        Fixture fixture = run.fixture();
        assertEquals(PlayerInventoryTransactionCoordinator.ApplyStatus.WAITING_FOR_OTHER_OWNER,
                coordinator.acknowledgeApplied(fixture.transactionId, fixture.editorId));
        assertEquals(fixture.transactionId, coordinator.reservationOwner(fixture.editorId));
        assertEquals(fixture.transactionId, coordinator.reservationOwner(fixture.targetId));

        assertEquals(PlayerInventoryTransactionCoordinator.ApplyStatus.APPLIED,
                coordinator.acknowledgeApplied(fixture.transactionId, fixture.targetId));
        assertEquals(PlayerInventoryTransactionCoordinator.Status.APPLIED, coordinator.status(fixture.transactionId));
        assertNull(coordinator.reservationOwner(fixture.editorId));
        assertNull(coordinator.reservationOwner(fixture.targetId));
        assertEquals(PlayerInventoryTransactionCoordinator.ApplyStatus.ALREADY_APPLIED,
                coordinator.acknowledgeApplied(fixture.transactionId, fixture.editorId));
    }

    @Test
    void callerTimeoutCannotReleaseReservationsWithoutDurableNoCommitConfirmation() throws Exception {
        PlayerInventoryTransactionCoordinator coordinator = new PlayerInventoryTransactionCoordinator();
        Fixture fixture = new Fixture();
        coordinator.begin(fixture.request);
        fixture.attestEditor(coordinator);
        fixture.attestTarget(coordinator);

        assertEquals(PlayerInventoryTransactionCoordinator.AbortStatus.NO_DURABLE_NO_COMMIT_CONFIRMATION,
                coordinator.abortBeforeDecision(fixture.transactionId, false));
        assertEquals(fixture.transactionId, coordinator.reservationOwner(fixture.editorId));
        assertEquals(PlayerInventoryTransactionCoordinator.Status.PREPARED, coordinator.status(fixture.transactionId));
    }

    @Test
    void onlyConfirmedPreCommitAbortReleasesReservations() throws Exception {
        PlayerInventoryTransactionCoordinator coordinator = new PlayerInventoryTransactionCoordinator();
        Fixture fixture = new Fixture();
        coordinator.begin(fixture.request);
        fixture.attestEditor(coordinator);
        fixture.attestTarget(coordinator);

        assertEquals(PlayerInventoryTransactionCoordinator.AbortStatus.ABORTED,
                coordinator.abortBeforeDecision(fixture.transactionId, true));
        assertEquals(PlayerInventoryTransactionCoordinator.AbortStatus.ALREADY_ABORTED,
                coordinator.abortBeforeDecision(fixture.transactionId, true));
        assertEquals(PlayerInventoryTransactionCoordinator.Status.ABORTED, coordinator.status(fixture.transactionId));
        assertEquals(PlayerInventoryTransactionCoordinator.AttestationStatus.TERMINAL,
                fixture.attestEditor(coordinator));
        assertEquals(PlayerInventoryTransactionCoordinator.BeginStatus.REPLAY,
                coordinator.begin(fixture.request).result());
        assertNull(coordinator.reservationOwner(fixture.editorId));
        assertNull(coordinator.reservationOwner(fixture.targetId));
    }

    @Test
    void durableCommitCannotBeAbortedAndNonOwnersCannotAcknowledge() throws Exception {
        PreparedRun run = preparedCoordinator(new Fixture());
        PlayerInventoryTransactionCoordinator coordinator = run.coordinator();
        Fixture fixture = run.fixture();
        assertEquals(PlayerInventoryTransactionCoordinator.AbortStatus.COMMIT_ALREADY_RECORDED,
                coordinator.abortBeforeDecision(fixture.transactionId, true));
        assertEquals(PlayerInventoryTransactionCoordinator.ApplyStatus.NOT_A_PARTICIPANT,
                coordinator.acknowledgeApplied(fixture.transactionId, UUID.randomUUID()));
        assertEquals(PlayerInventoryTransactionCoordinator.Status.COMMIT_DECIDED, coordinator.status(fixture.transactionId));
    }

    @Test
    void snapshotsDefensivelyCopyDigestsAndValidateTransactionInputs() {
        byte[] digest = digest(7);
        PlayerInventoryTransactionCoordinator.PlayerSnapshot snapshot = new PlayerInventoryTransactionCoordinator.PlayerSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), 0, digest);
        digest[0] = 99;
        assertArrayEquals(PlayerInventoryTransactionCoordinatorTest.digest(7), snapshot.snapshotDigest());
        byte[] returned = snapshot.snapshotDigest();
        returned[0] = 0;
        assertArrayEquals(PlayerInventoryTransactionCoordinatorTest.digest(7), snapshot.snapshotDigest());

        assertThrows(IllegalArgumentException.class, () -> new PlayerInventoryTransactionCoordinator.PlayerSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), -1, digest(1)));
        assertThrows(IllegalArgumentException.class, () -> new PlayerInventoryTransactionCoordinator.PlayerSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), 0, new byte[31]));
        UUID player = UUID.randomUUID();
        var samePlayer = new PlayerInventoryTransactionCoordinator.PlayerSnapshot(player, UUID.randomUUID(), 0, digest(1));
        assertThrows(IllegalArgumentException.class, () -> new PlayerInventoryTransactionCoordinator.TransactionRequest(
                UUID.randomUUID(), samePlayer, samePlayer, digest(2), new byte[]{1}, new byte[]{2}));
    }

    private static PreparedRun preparedCoordinator(Fixture fixture) throws Exception {
        PlayerInventoryTransactionCoordinator coordinator = new PlayerInventoryTransactionCoordinator();
        coordinator.begin(fixture.request);
        fixture.attestEditor(coordinator);
        fixture.attestTarget(coordinator);
        assertEquals(PlayerInventoryTransactionCoordinator.Status.PREPARED, coordinator.status(fixture.transactionId));
        assertEquals(PlayerInventoryTransactionCoordinator.DecisionStatus.COMMIT_RECORDED,
                coordinator.recordDurableCommitDecision(fixture.transactionId));
        return new PreparedRun(coordinator, fixture);
    }

    private static PlayerInventoryTransactionCoordinator.TransactionRequest request(
            UUID transactionId, UUID editorId, UUID targetId, byte[] requestDigest) {
        var editor = new PlayerInventoryTransactionCoordinator.PlayerSnapshot(editorId, UUID.randomUUID(), 10, EDITOR_DIGEST);
        var target = new PlayerInventoryTransactionCoordinator.PlayerSnapshot(targetId, UUID.randomUUID(), 20, TARGET_DIGEST);
        return new PlayerInventoryTransactionCoordinator.TransactionRequest(
                transactionId, editor, target, requestDigest, new byte[]{1}, new byte[]{2});
    }

    private static byte[] digest(int value) {
        byte[] digest = new byte[32];
        Arrays.fill(digest, (byte) value);
        return digest;
    }

    private static final class Fixture {
        private final UUID transactionId = UUID.randomUUID();
        private final UUID editorId = UUID.randomUUID();
        private final UUID editorSession = UUID.randomUUID();
        private final UUID targetId = UUID.randomUUID();
        private final UUID targetSession = UUID.randomUUID();
        private final PlayerInventoryTransactionCoordinator.PlayerSnapshot editorSnapshot =
                new PlayerInventoryTransactionCoordinator.PlayerSnapshot(editorId, editorSession, 10, EDITOR_DIGEST);
        private final PlayerInventoryTransactionCoordinator.PlayerSnapshot targetSnapshot =
                new PlayerInventoryTransactionCoordinator.PlayerSnapshot(targetId, targetSession, 20, TARGET_DIGEST);
        private final PlayerInventoryTransactionCoordinator.TransactionRequest request =
                new PlayerInventoryTransactionCoordinator.TransactionRequest(
                        transactionId, editorSnapshot, targetSnapshot, REQUEST_DIGEST, new byte[]{11}, new byte[]{22});

        private PlayerInventoryTransactionCoordinator.AttestationStatus attestEditor(
                PlayerInventoryTransactionCoordinator coordinator) {
            return coordinator.attest(transactionId, editorId, editorSession, 10, EDITOR_DIGEST);
        }

        private PlayerInventoryTransactionCoordinator.AttestationStatus attestTarget(
                PlayerInventoryTransactionCoordinator coordinator) {
            return coordinator.attest(transactionId, targetId, targetSession, 20, TARGET_DIGEST);
        }
    }

    private record PreparedRun(PlayerInventoryTransactionCoordinator coordinator, Fixture fixture) { }
}
