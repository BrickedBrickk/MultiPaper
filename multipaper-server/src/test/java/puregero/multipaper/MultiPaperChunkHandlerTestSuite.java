package puregero.multipaper;

import ca.spottedleaf.concurrentutil.completable.Completable;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MultiPaperChunkHandlerTestSuite {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void skipsPlacementCallbacksWhenMirroringAnUpdateIntoAnUnloadedLevelChunk() {
        LevelChunk chunk = mock(LevelChunk.class);
        BlockPos pos = new BlockPos(47, 37, 18);
        BlockState state = mock(BlockState.class);

        MultiPaperChunkHandler.setBlockInUnloadedChunk(chunk, pos, state);

        verify(chunk).setBlockState(pos, state, Block.UPDATE_NONE | Block.UPDATE_SKIP_ON_PLACE);
    }

    @Test
    void keepsProtoChunkMirroringFreeOfBlockPlacementCallbacks() {
        ChunkAccess chunk = mock(ChunkAccess.class);
        BlockPos pos = new BlockPos(47, 37, 18);
        BlockState state = mock(BlockState.class);

        MultiPaperChunkHandler.setBlockInUnloadedChunk(chunk, pos, state);

        verify(chunk).setBlockState(pos, state, Block.UPDATE_NONE);
    }

    @Test
    void waitsForDurableChunkWriteBeforeSchedulingOwnershipRelease() {
        Completable<CompoundTag> write = new Completable<>();
        AtomicReference<Runnable> queuedOnTickThread = new AtomicReference<>();
        AtomicBoolean ownershipReleased = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        MultiPaperChunkHandler.afterChunkWrite(write, queuedOnTickThread::set, () -> ownershipReleased.set(true), failure::set);

        assertNull(queuedOnTickThread.get(), "Ownership release must wait while the chunk write is pending");
        assertTrue(write.complete(new CompoundTag()));
        assertNotNull(queuedOnTickThread.get(), "A successful durable write should schedule ownership release");
        queuedOnTickThread.get().run();
        assertTrue(ownershipReleased.get());
        assertNull(failure.get());
    }

    @Test
    void keepsOwnershipWhenChunkWriteFails() {
        Completable<CompoundTag> write = new Completable<>();
        AtomicReference<Runnable> queuedOnTickThread = new AtomicReference<>();
        AtomicBoolean ownershipReleased = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        IOException writeFailure = new IOException("synthetic persistence failure");

        MultiPaperChunkHandler.afterChunkWrite(write, queuedOnTickThread::set, () -> ownershipReleased.set(true), failure::set);

        assertTrue(write.completeExceptionally(writeFailure));
        assertNull(queuedOnTickThread.get(), "A failed chunk write must not release ownership");
        assertFalse(ownershipReleased.get());
        assertSame(writeFailure, failure.get());
    }

    @Test
    void failedTickPersistenceKeepsSubscriptionUntilOwnershipCanBeReleased() {
        AtomicInteger unsubscribeCalls = new AtomicInteger();

        boolean completed = MultiPaperChunkHandler.releaseOwnershipThenUnsubscribe(true, () -> false, unsubscribeCalls::incrementAndGet);

        assertFalse(completed, "A failed durable tick handoff must prevent unsubscribe");
        assertEquals(0, unsubscribeCalls.get(), "The backend must stay subscribed while Master still considers it owner");
    }

    @Test
    void successfulOwnershipReleaseSubsequentlyUnsubscribes() {
        AtomicInteger unsubscribeCalls = new AtomicInteger();

        boolean completed = MultiPaperChunkHandler.releaseOwnershipThenUnsubscribe(true, () -> true, unsubscribeCalls::incrementAndGet);

        assertTrue(completed);
        assertEquals(1, unsubscribeCalls.get());
    }

    @Test
    void nonOwnerChunkUnloadUnsubscribesWithoutAttemptingOwnershipRelease() {
        AtomicInteger releaseCalls = new AtomicInteger();
        AtomicInteger unsubscribeCalls = new AtomicInteger();

        boolean completed = MultiPaperChunkHandler.releaseOwnershipThenUnsubscribe(false, () -> {
            releaseCalls.incrementAndGet();
            return false;
        }, unsubscribeCalls::incrementAndGet);

        assertTrue(completed);
        assertEquals(0, releaseCalls.get());
        assertEquals(1, unsubscribeCalls.get());
    }
}
