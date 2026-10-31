package puregero.multipaper.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerInventoryTransactionJournalTest {
    @TempDir
    Path tempDirectory;

    @Test
    void commitDecisionStoresCompleteAfterImagesAndReplaysIdempotently() throws Exception {
        PlayerInventoryTransactionJournal journal = new PlayerInventoryTransactionJournal(tempDirectory);
        Fixture fixture = new Fixture();

        assertEquals(PlayerInventoryTransactionJournal.RecordResult.RECORDED, journal.recordCommit(fixture.request));
        assertEquals(PlayerInventoryTransactionJournal.RecordResult.ALREADY_RECORDED, journal.recordCommit(fixture.request));
        assertEquals(PlayerInventoryTransactionJournal.RecordResult.ID_CONFLICT,
                journal.recordCommit(fixture.request(fixture.transactionId, new byte[]{99}, fixture.targetAfter)));

        PlayerInventoryTransactionJournal reopened = new PlayerInventoryTransactionJournal(tempDirectory);
        PlayerInventoryTransactionJournal.CommitRecord record = reopened.get(fixture.transactionId);
        assertEquals(fixture.request.transactionId(), record.request().transactionId());
        assertEquals(fixture.editorId, record.request().editor().playerId());
        assertEquals(fixture.editorSession, record.request().editor().sessionId());
        assertEquals(7, record.request().editor().revision());
        assertArrayEquals(fixture.editorBeforeDigest, record.request().editor().snapshotDigest());
        assertArrayEquals(fixture.editorAfter, record.request().editorAfterImage());
        assertArrayEquals(fixture.targetAfter, record.request().targetAfterImage());
        assertEquals(0, record.appliedMask());
    }

    @Test
    void coordinatorRecoversCommittedReservationAndReleasesOnlyAfterDurableDualAcknowledgement() throws Exception {
        Fixture fixture = new Fixture();
        PlayerInventoryTransactionJournal journal = new PlayerInventoryTransactionJournal(tempDirectory);
        PlayerInventoryTransactionCoordinator coordinator = new PlayerInventoryTransactionCoordinator(journal);
        coordinator.begin(fixture.request);
        coordinator.attest(fixture.transactionId, fixture.editorId, fixture.editorSession, 7, fixture.editorBeforeDigest);
        coordinator.attest(fixture.transactionId, fixture.targetId, fixture.targetSession, 9, fixture.targetBeforeDigest);

        assertEquals(PlayerInventoryTransactionCoordinator.DecisionStatus.COMMIT_RECORDED,
                coordinator.recordDurableCommitDecision(fixture.transactionId));
        assertTrue(Files.exists(tempDirectory.resolve(fixture.transactionId + ".itx")));

        PlayerInventoryTransactionCoordinator recovered = new PlayerInventoryTransactionCoordinator(
                new PlayerInventoryTransactionJournal(tempDirectory));
        assertEquals(PlayerInventoryTransactionCoordinator.Status.COMMIT_DECIDED, recovered.status(fixture.transactionId));
        assertEquals(fixture.transactionId, recovered.reservationOwner(fixture.editorId));
        assertEquals(fixture.transactionId, recovered.reservationOwner(fixture.targetId));
        assertEquals(PlayerInventoryTransactionCoordinator.BeginStatus.REPLAY,
                recovered.begin(fixture.request).result());

        assertEquals(PlayerInventoryTransactionCoordinator.ApplyStatus.WAITING_FOR_OTHER_OWNER,
                recovered.acknowledgeApplied(fixture.transactionId, fixture.editorId));
        PlayerInventoryTransactionCoordinator afterOneAck = new PlayerInventoryTransactionCoordinator(
                new PlayerInventoryTransactionJournal(tempDirectory));
        assertEquals(fixture.transactionId, afterOneAck.reservationOwner(fixture.editorId));
        assertEquals(fixture.transactionId, afterOneAck.reservationOwner(fixture.targetId));
        assertTrue(new PlayerInventoryTransactionJournal(tempDirectory).get(fixture.transactionId).isApplied(fixture.editorId));
        assertFalse(new PlayerInventoryTransactionJournal(tempDirectory).get(fixture.transactionId).isApplied(fixture.targetId));

        assertEquals(PlayerInventoryTransactionCoordinator.ApplyStatus.APPLIED,
                afterOneAck.acknowledgeApplied(fixture.transactionId, fixture.targetId));
        PlayerInventoryTransactionCoordinator afterBothAcks = new PlayerInventoryTransactionCoordinator(
                new PlayerInventoryTransactionJournal(tempDirectory));
        assertEquals(PlayerInventoryTransactionCoordinator.Status.APPLIED, afterBothAcks.status(fixture.transactionId));
        assertNull(afterBothAcks.reservationOwner(fixture.editorId));
        assertNull(afterBothAcks.reservationOwner(fixture.targetId));
    }

    @Test
    void randomizedApplyOrderAndMasterRestartsPreserveCommittedAfterImagesUntilBothOwnersAck() throws Exception {
        Random random = new Random(0x4d504954L);

        for (int iteration = 0; iteration < 32; iteration++) {
            Fixture fixture = new Fixture();
            PlayerInventoryTransactionCoordinator coordinator = new PlayerInventoryTransactionCoordinator(
                    new PlayerInventoryTransactionJournal(tempDirectory));
            assertEquals(PlayerInventoryTransactionCoordinator.BeginStatus.STARTED,
                    coordinator.begin(fixture.request).result());
            coordinator.attest(fixture.transactionId, fixture.editorId, fixture.editorSession, 7, fixture.editorBeforeDigest);
            coordinator.attest(fixture.transactionId, fixture.targetId, fixture.targetSession, 9, fixture.targetBeforeDigest);
            assertEquals(PlayerInventoryTransactionCoordinator.DecisionStatus.COMMIT_RECORDED,
                    coordinator.recordDurableCommitDecision(fixture.transactionId));

            // Simulate Master restarts around each durable owner acknowledgement.
            coordinator = new PlayerInventoryTransactionCoordinator(new PlayerInventoryTransactionJournal(tempDirectory));
            UUID firstOwner = random.nextBoolean() ? fixture.editorId : fixture.targetId;
            UUID secondOwner = firstOwner.equals(fixture.editorId) ? fixture.targetId : fixture.editorId;
            assertEquals(PlayerInventoryTransactionCoordinator.ApplyStatus.WAITING_FOR_OTHER_OWNER,
                    coordinator.acknowledgeApplied(fixture.transactionId, firstOwner));

            coordinator = new PlayerInventoryTransactionCoordinator(new PlayerInventoryTransactionJournal(tempDirectory));
            assertEquals(fixture.transactionId, coordinator.reservationOwner(fixture.editorId));
            assertEquals(fixture.transactionId, coordinator.reservationOwner(fixture.targetId));
            PlayerInventoryTransactionJournal.CommitRecord afterFirstRestart =
                    new PlayerInventoryTransactionJournal(tempDirectory).get(fixture.transactionId);
            assertArrayEquals(fixture.editorAfter, afterFirstRestart.request().editorAfterImage());
            assertArrayEquals(fixture.targetAfter, afterFirstRestart.request().targetAfterImage());
            assertEquals(firstOwner, afterFirstRestart.isApplied(fixture.editorId) ? fixture.editorId : fixture.targetId);

            assertEquals(PlayerInventoryTransactionCoordinator.ApplyStatus.APPLIED,
                    coordinator.acknowledgeApplied(fixture.transactionId, secondOwner));
            coordinator = new PlayerInventoryTransactionCoordinator(new PlayerInventoryTransactionJournal(tempDirectory));
            assertEquals(PlayerInventoryTransactionCoordinator.Status.APPLIED, coordinator.status(fixture.transactionId));
            assertNull(coordinator.reservationOwner(fixture.editorId));
            assertNull(coordinator.reservationOwner(fixture.targetId));
            PlayerInventoryTransactionJournal.CommitRecord completed =
                    new PlayerInventoryTransactionJournal(tempDirectory).get(fixture.transactionId);
            assertTrue(completed.allOwnersApplied());
            assertArrayEquals(fixture.editorAfter, completed.request().editorAfterImage());
            assertArrayEquals(fixture.targetAfter, completed.request().targetAfterImage());
        }
    }

    @Test
    void failedOrUnrecordedDecisionDoesNotAllowApplyAcknowledgement() throws Exception {
        PlayerInventoryTransactionJournal journal = new PlayerInventoryTransactionJournal(tempDirectory);
        Fixture fixture = new Fixture();

        assertEquals(PlayerInventoryTransactionJournal.AckResult.NO_COMMIT,
                journal.markApplied(fixture.transactionId, fixture.editorId));
        assertNull(journal.get(fixture.transactionId));

        PlayerInventoryTransactionCoordinator coordinator = new PlayerInventoryTransactionCoordinator(journal);
        coordinator.begin(fixture.request);
        assertEquals(PlayerInventoryTransactionCoordinator.DecisionStatus.NOT_PREPARED,
                coordinator.recordDurableCommitDecision(fixture.transactionId));
        assertNull(journal.get(fixture.transactionId));
    }

    @Test
    void durableAbortTombstoneSurvivesRestartAndPreventsTransactionIdReuse() throws Exception {
        PlayerInventoryTransactionJournal journal = new PlayerInventoryTransactionJournal(tempDirectory);
        Fixture fixture = new Fixture();
        PlayerInventoryTransactionCoordinator coordinator = new PlayerInventoryTransactionCoordinator(journal);
        coordinator.begin(fixture.request);

        assertEquals(PlayerInventoryTransactionCoordinator.AbortStatus.ABORTED,
                coordinator.abortBeforeDecision(fixture.transactionId, true));
        assertEquals(PlayerInventoryTransactionJournal.RecordResult.ABORTED, journal.recordCommit(fixture.request));
        assertEquals(PlayerInventoryTransactionJournal.AbortResult.ALREADY_ABORTED, journal.recordAbort(fixture.request));

        PlayerInventoryTransactionCoordinator recovered = new PlayerInventoryTransactionCoordinator(
                new PlayerInventoryTransactionJournal(tempDirectory));
        assertEquals(PlayerInventoryTransactionCoordinator.Status.ABORTED, recovered.status(fixture.transactionId));
        assertNull(recovered.reservationOwner(fixture.editorId));
        assertNull(recovered.reservationOwner(fixture.targetId));
        assertEquals(PlayerInventoryTransactionCoordinator.BeginStatus.REPLAY,
                recovered.begin(fixture.request).result());
        assertEquals(PlayerInventoryTransactionCoordinator.DecisionStatus.ABORTED,
                recovered.recordDurableCommitDecision(fixture.transactionId));
    }

    @Test
    void journalRejectsChecksumCorruptionInsteadOfApplyingPartialState() throws Exception {
        PlayerInventoryTransactionJournal journal = new PlayerInventoryTransactionJournal(tempDirectory);
        Fixture fixture = new Fixture();
        journal.recordCommit(fixture.request);

        Path record = tempDirectory.resolve(fixture.transactionId + ".itx");
        byte[] bytes = Files.readAllBytes(record);
        bytes[12] ^= 0x40;
        Files.write(record, bytes);

        assertThrows(IOException.class, () -> journal.get(fixture.transactionId));
        assertThrows(IOException.class, journal::loadCommitted);
    }

    @Test
    void startupFailsClosedIfRecoveredCommitReservationsOverlap() throws Exception {
        PlayerInventoryTransactionJournal journal = new PlayerInventoryTransactionJournal(tempDirectory);
        Fixture first = new Fixture();
        Fixture second = new Fixture(first.editorId);
        journal.recordCommit(first.request);
        journal.recordCommit(second.request);

        assertThrows(IOException.class, () -> new PlayerInventoryTransactionCoordinator(
                new PlayerInventoryTransactionJournal(tempDirectory)));
    }

    @Test
    void applyAcknowledgementsAreIdempotentAndRejectNonParticipants() throws Exception {
        PlayerInventoryTransactionJournal journal = new PlayerInventoryTransactionJournal(tempDirectory);
        Fixture fixture = new Fixture();
        journal.recordCommit(fixture.request);

        assertEquals(PlayerInventoryTransactionJournal.AckResult.NOT_A_PARTICIPANT,
                journal.markApplied(fixture.transactionId, UUID.randomUUID()));
        assertEquals(PlayerInventoryTransactionJournal.AckResult.OWNER_APPLIED,
                journal.markApplied(fixture.transactionId, fixture.editorId));
        assertEquals(PlayerInventoryTransactionJournal.AckResult.ALREADY_APPLIED,
                journal.markApplied(fixture.transactionId, fixture.editorId));
        assertEquals(PlayerInventoryTransactionJournal.AckResult.BOTH_APPLIED,
                journal.markApplied(fixture.transactionId, fixture.targetId));
        assertTrue(journal.get(fixture.transactionId).allOwnersApplied());
    }

    @Test
    void concurrentCommitAndAbortCanPersistOnlyOneTerminalDecision() throws Exception {
        Fixture fixture = new Fixture();
        PlayerInventoryTransactionJournal firstJournal = new PlayerInventoryTransactionJournal(tempDirectory);
        PlayerInventoryTransactionJournal secondJournal = new PlayerInventoryTransactionJournal(tempDirectory);
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var commit = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return firstJournal.recordCommit(fixture.request);
            });
            var abort = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return secondJournal.recordAbort(fixture.request);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            PlayerInventoryTransactionJournal.RecordResult commitResult = commit.get(5, TimeUnit.SECONDS);
            PlayerInventoryTransactionJournal.AbortResult abortResult = abort.get(5, TimeUnit.SECONDS);
            PlayerInventoryTransactionJournal.CommitRecord stored = firstJournal.get(fixture.transactionId);
            if (stored.aborted()) {
                assertEquals(PlayerInventoryTransactionJournal.AbortResult.ABORTED, abortResult);
                assertEquals(PlayerInventoryTransactionJournal.RecordResult.ABORTED, commitResult);
            } else {
                assertEquals(PlayerInventoryTransactionJournal.RecordResult.RECORDED, commitResult);
                assertEquals(PlayerInventoryTransactionJournal.AbortResult.COMMIT_ALREADY_RECORDED, abortResult);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static byte[] digest(int fill) {
        byte[] digest = new byte[32];
        java.util.Arrays.fill(digest, (byte) fill);
        return digest;
    }

    private static final class Fixture {
        private final UUID transactionId = UUID.randomUUID();
        private final UUID editorId;
        private final UUID editorSession = UUID.randomUUID();
        private final UUID targetId = UUID.randomUUID();
        private final UUID targetSession = UUID.randomUUID();
        private final byte[] editorBeforeDigest = digest(1);
        private final byte[] targetBeforeDigest = digest(2);
        private final byte[] editorAfter = {11, 12, 13};
        private final byte[] targetAfter = {21, 22, 23};
        private final PlayerInventoryTransactionCoordinator.TransactionRequest request;

        private Fixture() {
            this(UUID.randomUUID());
        }

        private Fixture(UUID editorId) {
            this.editorId = editorId;
            this.request = request(transactionId, editorAfter, targetAfter);
        }

        private PlayerInventoryTransactionCoordinator.TransactionRequest request(UUID id, byte[] editorData, byte[] targetData) {
            var editor = new PlayerInventoryTransactionCoordinator.PlayerSnapshot(editorId, editorSession, 7, editorBeforeDigest);
            var target = new PlayerInventoryTransactionCoordinator.PlayerSnapshot(targetId, targetSession, 9, targetBeforeDigest);
            return new PlayerInventoryTransactionCoordinator.TransactionRequest(
                    id, editor, target, digest(3), editorData, targetData);
        }
    }
}
