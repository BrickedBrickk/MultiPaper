package puregero.multipaper.server;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataOperationQueueTest {
    @Test
    void runsOperationsForOnePlayerInSubmissionOrder() throws Exception {
        UUID playerId = UUID.randomUUID();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger state = new AtomicInteger();

        var first = PlayerDataOperationQueue.submit(playerId, () -> {
            firstStarted.countDown();
            try {
                if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release first player operation");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            }
            state.set(1);
        });
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

        var second = PlayerDataOperationQueue.submit(playerId, () -> state.set(state.get() + 1));
        assertFalse(second.isDone(), "Disconnect or next-owner work must wait for the pending save");
        releaseFirst.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

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
        assertTrue(editorStarted.await(5, TimeUnit.SECONDS));
        assertTrue(targetStarted.await(5, TimeUnit.SECONDS));

        var transaction = PlayerDataOperationQueue.submitAll(java.util.List.of(editor, target), () -> {
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
        editorBefore.get(5, TimeUnit.SECONDS);
        targetBefore.get(5, TimeUnit.SECONDS);
        transaction.get(5, TimeUnit.SECONDS);
        assertTrue(transactionFinished.await(5, TimeUnit.SECONDS));
        editorAfter.get(5, TimeUnit.SECONDS);
        targetAfter.get(5, TimeUnit.SECONDS);
        assertEquals(2, laterWrites.get());
    }

    private static void await(CountDownLatch release, CountDownLatch started) {
        started.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for release");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }
}
