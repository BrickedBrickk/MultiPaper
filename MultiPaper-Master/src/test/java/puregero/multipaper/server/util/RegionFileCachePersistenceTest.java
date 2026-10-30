package puregero.multipaper.server.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionFileCachePersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void chunkDataCanBeReadAfterCachedRegionFilesAreClosedAndReopened() throws Exception {
        File regionDirectory = tempDir.resolve("world").resolve("region").toFile();
        byte[] expected = "persisted-chunk-data".getBytes(StandardCharsets.UTF_8);

        RegionFileCache.putChunkDeflatedDataAsync(regionDirectory, -33, 48, expected).join();
        RegionFileCache.closeAll();

        assertArrayEquals(expected, RegionFileCache.getChunkDeflatedDataAsync(regionDirectory, -33, 48).join());
        RegionFileCache.closeAll();
    }

    @Test
    void slowTaskWarningMustNotReleaseLaterRegionFileOperations() throws Exception {
        RegionFile regionFile = new RegionFile(tempDir.resolve("r.0.0.mca").toFile());
        CountDownLatch firstTaskEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);
        CountDownLatch secondTaskEntered = new CountDownLatch(1);
        AtomicBoolean firstTaskFinished = new AtomicBoolean();

        CompletableFuture<String> first = regionFile.submitTask(file -> {
            firstTaskEntered.countDown();
            try {
                if (!releaseFirstTask.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release the first region task");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            } finally {
                firstTaskFinished.set(true);
            }
            return "first";
        }, 25, TimeUnit.MILLISECONDS);

        CompletableFuture<String> second = regionFile.submitTask(file -> {
            secondTaskEntered.countDown();
            if (!firstTaskFinished.get()) {
                throw new AssertionError("A later region task ran before the prior task finished");
            }
            return "second";
        }, 25, TimeUnit.MILLISECONDS);

        try {
            assertTrue(firstTaskEntered.await(2, TimeUnit.SECONDS), "First region task should start");
            Thread.sleep(150);
            assertFalse(first.isDone(), "A slow-task warning must not complete the active task future");
            assertEquals(1, secondTaskEntered.getCount(), "The next operation must remain queued behind active I/O");

            releaseFirstTask.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals("second", second.get(2, TimeUnit.SECONDS));
        } finally {
            releaseFirstTask.countDown();
            regionFile.close();
        }
    }

    @Test
    void closePreventsNewRegionFileTasksFromStartingAfterTheFileIsClosed() throws Exception {
        RegionFile regionFile = new RegionFile(tempDir.resolve("close-race.mca").toFile());
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicBoolean closeFailed = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            try {
                regionFile.close();
            } catch (IOException failure) {
                closeFailed.set(true);
            } finally {
                closeFinished.countDown();
            }
        }, "region-file-close-race-test");
        closer.start();

        boolean rejectedAfterCloseBegan = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!rejectedAfterCloseBegan && System.nanoTime() < deadline) {
            CompletableFuture<Void> task = regionFile.submitTask(file -> null);
            try {
                task.get(200, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                rejectedAfterCloseBegan = true;
            }
            if (!rejectedAfterCloseBegan) {
                Thread.sleep(1);
            }
        }

        assertTrue(closeFinished.await(2, TimeUnit.SECONDS), "close should finish");
        assertFalse(closeFailed.get(), "close should not fail");
        assertTrue(rejectedAfterCloseBegan, "New tasks must be rejected once close starts draining the queue");
        closer.join(2000);
    }

    @Test
    void closeDrainsPreviouslyAcceptedRegionFileTasksBeforeClosing() throws Exception {
        Path path = tempDir.resolve("close-drain.mca");
        RegionFile regionFile = new RegionFile(path.toFile());
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicBoolean closeFailed = new AtomicBoolean();
        byte[] expected = new byte[]{9, 8, 7, 6};
        CompletableFuture<Void> pending = regionFile.submitTask(file -> {
            taskStarted.countDown();
            try {
                if (!releaseTask.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release the region write");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            }
            file.putDeflatedBytes(0, 0, expected);
            return null;
        });
        assertTrue(taskStarted.await(2, TimeUnit.SECONDS), "The accepted region task should start");

        Thread closer = new Thread(() -> {
            try {
                regionFile.close();
            } catch (IOException failure) {
                closeFailed.set(true);
            } finally {
                closeFinished.countDown();
            }
        }, "region-file-close-drain-test");
        closer.start();

        boolean closeStartedDraining = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!closeStartedDraining && System.nanoTime() < deadline) {
            CompletableFuture<Void> probe = regionFile.submitTask(file -> null);
            try {
                probe.get(100, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException expectedRejection) {
                closeStartedDraining = true;
            } catch (java.util.concurrent.TimeoutException stillQueuedBehindWrite) {
                // The close fence has not rejected this probe yet; the accepted write is still held.
            }
            if (!closeStartedDraining) {
                Thread.sleep(1);
            }
        }

        try {
            assertTrue(closeStartedDraining, "close should stop accepting tasks before draining");
            assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS), "close must wait for accepted I/O");
        } finally {
            releaseTask.countDown();
        }

        pending.get(2, TimeUnit.SECONDS);
        assertTrue(closeFinished.await(2, TimeUnit.SECONDS), "close should complete after accepted I/O drains");
        assertFalse(closeFailed.get(), "close should not fail");
        closer.join(2000);

        RegionFile reopened = new RegionFile(path.toFile());
        try {
            assertArrayEquals(expected, reopened.getDeflatedBytes(0, 0),
                    "The accepted write should be readable after close and reopen");
        } finally {
            reopened.close();
        }
    }

    @Test
    void sameSizedReplacementIsWrittenToNewSectorsBeforeTheOldSectorsAreReused() throws Exception {
        File regionDirectory = tempDir.resolve("world").resolve("region").toFile();
        Path regionFile = regionDirectory.toPath().resolve("r.0.0.mca");
        byte[] first = new byte[4096];
        byte[] second = new byte[4096];
        byte[] third = new byte[4096];
        new Random(1).nextBytes(first);
        new Random(2).nextBytes(second);
        new Random(3).nextBytes(third);

        try {
            RegionFileCache.putChunkDeflatedDataAsync(regionDirectory, 0, 0, first).join();
            long firstWriteLength = Files.size(regionFile);
            RegionFileCache.putChunkDeflatedDataAsync(regionDirectory, 0, 0, second).join();
            long secondWriteLength = Files.size(regionFile);

            assertTrue(secondWriteLength > firstWriteLength,
                    "An acknowledged chunk must not be overwritten in place during replacement");
            assertArrayEquals(second, RegionFileCache.getChunkDeflatedDataAsync(regionDirectory, 0, 0).join());

            RegionFileCache.putChunkDeflatedDataAsync(regionDirectory, 0, 0, third).join();
            assertEquals(secondWriteLength, Files.size(regionFile),
                    "A later replacement should reuse the sectors freed after publication");
            RegionFileCache.closeAll();

            assertArrayEquals(third, RegionFileCache.getChunkDeflatedDataAsync(regionDirectory, 0, 0).join());
        } finally {
            RegionFileCache.closeAll();
        }
    }

    @Test
    void chunkReplacementCutPointsKeepTheLastPublishedSnapshotReadable() throws Exception {
        Path beforePublishPath = tempDir.resolve("before-publish.mca");
        byte[] previous = "chest-items-before-update".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "chest-items-after-update".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean interruptBeforePublish = new AtomicBoolean();
        RegionFile beforePublish = new RegionFile(beforePublishPath.toFile()) {
            @Override
            void afterChunkPayloadWrittenBeforeOffsetPublished(int x, int z) {
                if (interruptBeforePublish.get()) throw new SimulatedAbruptWriteInterruption();
            }
        };

        try {
            beforePublish.putDeflatedBytes(0, 0, previous);
            interruptBeforePublish.set(true);
            assertThrows(SimulatedAbruptWriteInterruption.class,
                    () -> beforePublish.putDeflatedBytes(0, 0, replacement),
                    "An interruption before publishing the new offset must not replace the old chunk");
        } finally {
            beforePublish.close();
        }

        RegionFile reopenedBeforePublish = new RegionFile(beforePublishPath.toFile());
        try {
            assertArrayEquals(previous, reopenedBeforePublish.getDeflatedBytes(0, 0),
                    "The prior chest snapshot must remain readable when replacement data is unpublished");
        } finally {
            reopenedBeforePublish.close();
        }

        Path afterPublishPath = tempDir.resolve("after-publish.mca");
        AtomicBoolean interruptAfterPublish = new AtomicBoolean();
        RegionFile afterPublish = new RegionFile(afterPublishPath.toFile()) {
            @Override
            void afterChunkOffsetPublished(int x, int z) {
                if (interruptAfterPublish.get()) throw new SimulatedAbruptWriteInterruption();
            }
        };

        try {
            afterPublish.putDeflatedBytes(0, 0, previous);
            interruptAfterPublish.set(true);
            assertThrows(SimulatedAbruptWriteInterruption.class,
                    () -> afterPublish.putDeflatedBytes(0, 0, replacement),
                    "An interruption after offset publication must leave the replacement authoritative");
        } finally {
            afterPublish.close();
        }

        RegionFile reopenedAfterPublish = new RegionFile(afterPublishPath.toFile());
        try {
            assertArrayEquals(replacement, reopenedAfterPublish.getDeflatedBytes(0, 0),
                    "The published chest snapshot must remain readable even if timestamp update did not run");
        } finally {
            reopenedAfterPublish.close();
        }
    }

    @Test
    void fragmentedRegionKeepsLatestChunkDataAfterRepeatedReplacementAndReopen() throws Exception {
        File regionDirectory = tempDir.resolve("world").resolve("region").toFile();
        Map<Integer, byte[]> expected = new HashMap<>();
        Random random = new Random(0x26_03_17);

        try {
            for (int round = 0; round < 6; round++) {
                for (int chunkX = 0; chunkX < 32; chunkX++) {
                    byte[] payload = new byte[512 + random.nextInt(24_000)];
                    random.nextBytes(payload);
                    RegionFileCache.putChunkDeflatedDataAsync(regionDirectory, chunkX, 0, payload).join();
                    expected.put(chunkX, payload);
                }
            }

            RegionFileCache.closeAll();
            for (Map.Entry<Integer, byte[]> chunk : expected.entrySet()) {
                assertArrayEquals(chunk.getValue(),
                        RegionFileCache.getChunkDeflatedDataAsync(regionDirectory, chunk.getKey(), 0).join(),
                        "Latest payload should survive fragmented allocation for chunk " + chunk.getKey());
            }
        } finally {
            RegionFileCache.closeAll();
        }
    }

    @Test
    void externalChunkSidecarCanBeReadAfterCachedRegionFilesAreClosedAndReopened() throws Exception {
        File regionDirectory = tempDir.resolve("world").resolve("region").toFile();
        int chunkX = 1057;
        int chunkZ = -18;
        byte[] expected = new byte[1_100_000];
        new Random(0x26_03).nextBytes(expected);

        try {
            RegionFileCache.putChunkDeflatedDataAsync(regionDirectory, chunkX, chunkZ, expected).join();
            assertTrue(Files.isRegularFile(regionDirectory.toPath().resolve("c." + chunkX + "." + chunkZ + ".mcc")),
                    "A large chunk should be stored in its external sidecar file");
            RegionFileCache.closeAll();

            assertArrayEquals(expected, RegionFileCache.getChunkDeflatedDataAsync(regionDirectory, chunkX, chunkZ).join());
        } finally {
            RegionFileCache.closeAll();
        }
    }

    private static final class SimulatedAbruptWriteInterruption extends Error {
    }
}
