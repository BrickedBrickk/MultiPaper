package puregero.multipaper.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import puregero.multipaper.mastermessagingprotocol.ChunkKey;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLockManagerTest {
    @TempDir
    Path tempDirectory;

    @Test
    @Timeout(10)
    void forcedReadEnqueueIsAtomicWithLateSaveAnnouncement() throws Exception {
        String world = "read-enqueue-race-" + System.nanoTime();
        int chunkX = 71;
        int chunkZ = -73;
        CountDownLatch readEnqueueStarted = new CountDownLatch(1);
        CountDownLatch allowReadEnqueueToFinish = new CountDownLatch(1);
        CountDownLatch announcementAttemptStarted = new CountDownLatch(1);
        AtomicReference<Throwable> readFailure = new AtomicReference<>();

        Thread readThread = new Thread(() -> {
            try {
                ChunkLockManager.readWhenSafe(world, chunkX, chunkZ, () -> {
                    readEnqueueStarted.countDown();
                    try {
                        if (!allowReadEnqueueToFinish.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("test did not release the simulated read enqueue");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }, () -> readFailure.set(new AssertionError("an unrelated read was marked as failed")));
            } catch (Throwable failure) {
                readFailure.set(failure);
            }
        }, "chunk-read-enqueue-race-test");
        readThread.setDaemon(true);

        CompletableFuture<Void> lateAnnouncement = null;
        try {
            readThread.start();
            assertTrue(readEnqueueStarted.await(5, TimeUnit.SECONDS), "read should begin with an empty write barrier");

            lateAnnouncement = CompletableFuture.runAsync(() -> {
                announcementAttemptStarted.countDown();
                ChunkLockManager.lockUntilWrite(null, world, chunkX, chunkZ);
            });
            assertTrue(announcementAttemptStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertFalse(lateAnnouncement.isDone(),
                    "A save announcement must not overtake the read after the barrier check but before its enqueue");
        } finally {
            allowReadEnqueueToFinish.countDown();
        }

        readThread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(readThread.isAlive(), "read enqueue thread should finish");
        assertNull(readFailure.get(), "read enqueue should not fail");
        lateAnnouncement.get(5, TimeUnit.SECONDS);
        assertTrue(ChunkLockManager.hasPendingWrites(world, chunkX, chunkZ));
        ChunkLockManager.cancelPendingWrite(null, world, chunkX, chunkZ);
        assertFalse(ChunkLockManager.hasPendingWrites(world, chunkX, chunkZ));
    }

    @Test
    @Timeout(10)
    void orphanedFailedWriteMarkerStageKeepsChunkQuarantined() throws Exception {
        String world = tempDirectory.resolve("orphaned-marker-world").toString();
        int chunkX = -81;
        int chunkZ = 83;
        Path marker = Path.of(world, "data", "multipaper",
                "failed-region-chunk-" + chunkX + "-" + chunkZ + ".dat");
        Files.createDirectories(marker.getParent());
        Files.write(marker.resolveSibling(marker.getFileName() + "_new"), new byte[]{'M', 'P', 'F', 'C', 1});

        CountDownLatch readRejected = new CountDownLatch(1);
        AtomicBoolean storageReadStarted = new AtomicBoolean();
        ChunkLockManager.readWhenSafe(world, chunkX, chunkZ,
                () -> storageReadStarted.set(true), readRejected::countDown);

        assertTrue(readRejected.await(5, TimeUnit.SECONDS), "A crash-left marker stage must fail closed");
        assertFalse(storageReadStarted.get(), "Master must not return a chunk while its failed-write marker is ambiguous");
        assertTrue(FailedChunkWriteStore.isFailed(new ChunkKey(world, chunkX, chunkZ)));
    }
}
