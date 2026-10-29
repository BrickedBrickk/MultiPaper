package puregero.multipaper.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataOperationQueueTest {
    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void runsOperationsForOnePlayerInSubmissionOrder() throws Exception {
        UUID playerId = UUID.randomUUID();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger state = new AtomicInteger();

        var first = PlayerDataOperationQueue.submit(playerId, () -> {
            firstStarted.countDown();
            await(releaseFirst);
            state.set(1);
        });
        assertTrue(firstStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        var second = PlayerDataOperationQueue.submit(playerId, () -> state.set(state.get() + 1));
        assertFalse(second.isDone(), "Disconnect or next-owner work must wait for the pending save");
        releaseFirst.countDown();
        first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(2, state.get());
    }

    @Test
    void multiPlayerOperationWaitsForBothOwnersAndFencesLaterWrites() throws Exception {
        UUID editor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        CountDownLatch editorStarted = new CountDownLatch(1);
        CountDownLatch targetStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch transactionFinished = new CountDownLatch(1);
        AtomicInteger phase = new AtomicInteger();
        AtomicInteger laterWrites = new AtomicInteger();

        var editorBefore = PlayerDataOperationQueue.submit(editor, () -> await(release, editorStarted));
        var targetBefore = PlayerDataOperationQueue.submit(target, () -> await(release, targetStarted));
        assertTrue(editorStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(targetStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        var transaction = PlayerDataOperationQueue.submitAll(List.of(editor, target), () -> {
            assertEquals(0, phase.get(), "both participants' earlier operations must finish first");
            phase.set(1);
            transactionFinished.countDown();
        });
        var editorAfter = PlayerDataOperationQueue.submit(editor, () -> {
            assertEquals(1, phase.get(), "later editor writes must wait for the joint operation");
            laterWrites.incrementAndGet();
        });
        var targetAfter = PlayerDataOperationQueue.submit(target, () -> {
            assertEquals(1, phase.get(), "later target writes must wait for the joint operation");
            laterWrites.incrementAndGet();
        });

        assertFalse(transaction.isDone());
        assertFalse(editorAfter.isDone());
        assertFalse(targetAfter.isDone());
        release.countDown();
        editorBefore.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        targetBefore.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        transaction.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(transactionFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        editorAfter.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        targetAfter.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, laterWrites.get());
    }

    @Test
    void asynchronousMultiPlayerOperationKeepsAllParticipantBarriersUntilAcknowledged() throws Exception {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID unrelatedPlayer = UUID.randomUUID();
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        CompletableFuture<Void> started = new CompletableFuture<>();
        AtomicBoolean participantFollowupRan = new AtomicBoolean();

        CompletableFuture<Void> transaction = PlayerDataOperationQueue.submitAllAsync(
                List.of(firstPlayer, secondPlayer), () -> {
                    started.complete(null);
                    return acknowledgement;
                });
        started.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<Void> participantFollowup = PlayerDataOperationQueue.submit(secondPlayer,
                () -> participantFollowupRan.set(true));
        CompletableFuture<Void> unrelated = PlayerDataOperationQueue.submit(unrelatedPlayer, () -> { });

        unrelated.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(participantFollowup.isDone(),
                "work for a reserved participant must wait until owner acknowledgements complete");
        assertFalse(participantFollowupRan.get());

        acknowledgement.complete(null);
        transaction.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        participantFollowup.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(participantFollowupRan.get());
    }

    @Test
    void overlappingTransactionsWithReverseParticipantOrderDoNotDeadlock() throws Exception {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        CompletableFuture<Void> firstAcknowledgement = new CompletableFuture<>();
        CompletableFuture<Void> secondStarted = new CompletableFuture<>();

        CompletableFuture<Void> first = PlayerDataOperationQueue.submitAllAsync(
                List.of(firstPlayer, secondPlayer), () -> firstAcknowledgement);
        CompletableFuture<Void> second = PlayerDataOperationQueue.submitAllAsync(
                List.of(secondPlayer, firstPlayer), () -> {
                    secondStarted.complete(null);
                    return CompletableFuture.completedFuture(null);
                });

        assertFalse(secondStarted.isDone(), "the second transaction must queue behind both participants");
        firstAcknowledgement.complete(null);
        first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        secondStarted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void failedAcknowledgementReleasesTheParticipantsForLaterWork() throws Exception {
        UUID player = UUID.randomUUID();
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<Void> transaction = PlayerDataOperationQueue.submitAllAsync(
                List.of(player), () -> {
                    started.complete(null);
                    return acknowledgement;
                });
        started.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<Void> later = PlayerDataOperationQueue.submit(player, () -> { });
        acknowledgement.completeExceptionally(new IllegalStateException("simulated owner rejection"));

        assertThrows(ExecutionException.class, () -> transaction.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        later.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void cancellingCallerHandleDoesNotReleaseAnOutstandingReservation() throws Exception {
        UUID player = UUID.randomUUID();
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        CompletableFuture<Void> started = new CompletableFuture<>();
        AtomicBoolean laterOperationRan = new AtomicBoolean();

        CompletableFuture<Void> callerHandle = PlayerDataOperationQueue.submitAllAsync(
                List.of(player), () -> {
                    started.complete(null);
                    return acknowledgement;
                });
        started.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<Void> later = PlayerDataOperationQueue.submit(player, () -> laterOperationRan.set(true));

        assertTrue(callerHandle.cancel(false), "the caller should be able to stop waiting");
        assertFalse(later.isDone(), "cancelling a waiter must not release the live participant reservation");
        assertFalse(laterOperationRan.get());

        acknowledgement.complete(null);
        later.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(laterOperationRan.get());
    }

    @Test
    void cancellingSinglePlayerWaiterDoesNotLetLaterWorkOvertakeEarlierReservation() throws Exception {
        UUID player = UUID.randomUUID();
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<Void> transaction = PlayerDataOperationQueue.submitAllAsync(
                List.of(player), () -> {
                    started.complete(null);
                    return acknowledgement;
                });
        started.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<Void> callerHandle = PlayerDataOperationQueue.submit(player, () -> { });
        CompletableFuture<Void> later = PlayerDataOperationQueue.submit(player, () -> { });
        assertTrue(callerHandle.cancel(false));
        assertFalse(later.isDone(), "cancelling a waiter must not bypass its earlier queued operation");

        acknowledgement.complete(null);
        transaction.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        later.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void cancellingMultiPlayerWaiterDoesNotLetLaterWorkOvertakeEarlierReservation() throws Exception {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<Void> transaction = PlayerDataOperationQueue.submitAllAsync(
                List.of(firstPlayer), () -> {
                    started.complete(null);
                    return acknowledgement;
                });
        started.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<Void> callerHandle = PlayerDataOperationQueue.submitAll(
                List.of(firstPlayer, secondPlayer), () -> { });
        CompletableFuture<Void> later = PlayerDataOperationQueue.submit(secondPlayer, () -> { });
        assertTrue(callerHandle.cancel(false));
        assertFalse(later.isDone(), "cancelling a waiter must not bypass its earlier queued operation");

        acknowledgement.complete(null);
        transaction.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        later.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch release) {
        await(release, null);
    }

    private static void await(CountDownLatch release, CountDownLatch started) {
        if (started != null) started.countDown();
        try {
            if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release queued player operation");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }
}
