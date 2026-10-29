package puregero.multipaper.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteLockManagerTest {

    @Test
    void chunkReadWaitsUntilEveryOutstandingWriteHasFinished() {
        WriteLockManager manager = new WriteLockManager();
        AtomicInteger reads = new AtomicInteger();

        manager.lockUntilWrite(null, "world", 4, -7);
        manager.lockUntilWrite(null, "world", 4, -7);
        manager.writeStarted(null, "world", 4, -7);
        manager.writeStarted(null, "world", 4, -7);
        manager.waitForLock("world", 4, -7, reads::incrementAndGet);

        assertEquals(0, reads.get());

        manager.writeCompleted(null, "world", 4, -7);
        assertEquals(0, reads.get());

        manager.writeCompleted(null, "world", 4, -7);
        assertEquals(1, reads.get());
    }

    @Test
    void asyncChunkBarrierCompletesOnlyAfterAnnouncedSaveFinishes() {
        WriteLockManager manager = new WriteLockManager();
        manager.lockUntilWrite(null, "async-handoff", 12, -4);
        CompletableFuture<Void> barrier = manager.waitForLockAsync("async-handoff", 12, -4);

        assertFalse(barrier.isDone(), "An announced chunk save must hold the asynchronous ownership barrier");
        manager.writeStarted(null, "async-handoff", 12, -4);
        manager.writeCompleted(null, "async-handoff", 12, -4);

        assertTrue(barrier.isDone(), "The barrier must complete when the announced save finishes");
    }

    @Test
    void disconnectMustNotReleaseBarrierForAlreadyAcceptedAsyncWrite() {
        WriteLockManager manager = new WriteLockManager();
        AtomicInteger reads = new AtomicInteger();
        String world = "disconnect-active-write";
        int chunkX = -8;
        int chunkZ = 19;

        // Model a write announcement followed by Master accepting the packet and
        // starting asynchronous region-file persistence.
        manager.lockUntilWrite(null, world, chunkX, chunkZ);
        manager.writeStarted(null, world, chunkX, chunkZ);
        CompletableFuture<Void> diskWrite = new CompletableFuture<>();
        diskWrite.whenComplete((ignored, failure) -> manager.writeCompleted(null, world, chunkX, chunkZ));
        manager.waitForLock(world, chunkX, chunkZ, reads::incrementAndGet);

        CompletableFuture<Void> writesDrained = manager.releaseAll(null);

        assertEquals(0, reads.get(), "Disconnect may discard an unfulfilled announcement, but not an accepted write still persisting");
        assertFalse(writesDrained.isDone(), "Disconnect cleanup must await the accepted disk write");
        diskWrite.complete(null);
        assertEquals(1, reads.get(), "Reads may resume after the accepted snapshot reaches durable storage");
        assertTrue(writesDrained.isDone(), "Disconnect cleanup may release ownership after persistence completes");
    }

    @Test
    void disconnectStillReleasesAnAnnouncementThatNeverBecameAWrite() {
        WriteLockManager manager = new WriteLockManager();
        AtomicInteger reads = new AtomicInteger();
        manager.lockUntilWrite(null, "disconnect-no-write", 3, 5);
        manager.waitForLock("disconnect-no-write", 3, 5, reads::incrementAndGet);

        CompletableFuture<Void> writesDrained = manager.releaseAll(null);

        assertTrue(writesDrained.isDone(), "An unfulfilled announcement must not block disconnect cleanup forever");
        assertEquals(1, reads.get(), "A read waiting on a write that never arrived must resume after disconnect");
    }

    @Test
    void activeWriteWithoutAnnouncementStillFencesReadAndDisconnect() {
        WriteLockManager manager = new WriteLockManager();
        AtomicInteger reads = new AtomicInteger();
        manager.writeStarted(null, "disconnect-unannounced-write", -3, -5);
        manager.waitForLock("disconnect-unannounced-write", -3, -5, reads::incrementAndGet);

        CompletableFuture<Void> writesDrained = manager.releaseAll(null);

        assertFalse(writesDrained.isDone());
        assertEquals(0, reads.get());
        manager.writeCompleted(null, "disconnect-unannounced-write", -3, -5);
        assertTrue(writesDrained.isDone());
        assertEquals(1, reads.get());
    }
}
