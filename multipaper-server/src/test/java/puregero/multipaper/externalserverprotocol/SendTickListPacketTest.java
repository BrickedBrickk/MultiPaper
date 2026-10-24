package puregero.multipaper.externalserverprotocol;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class SendTickListPacketTest {

    @Test
    void failedAppliedJournalWriteKeepsReplayTicksHeld() {
        MutableJournalEntry entry = new MutableJournalEntry();

        assertThrows(IOException.class, () -> SendTickListPacket.persistAppliedState(List.of(entry),
                snapshot -> snapshot.applied = true,
                snapshot -> snapshot.applied = false,
                () -> { throw new IOException("Master write not acknowledged"); }));

        assertFalse(entry.applied, "a failed APPLIED commit must roll back to the held state");
        assertTrue(SendTickListPacket.shouldHoldTickExecution(true, !entry.applied),
                "LevelTicks must continue holding the queued replay until a later durable retry succeeds");
    }

    @Test
    void successfulAppliedJournalWriteReleasesReplayTicks() throws IOException {
        MutableJournalEntry entry = new MutableJournalEntry();

        SendTickListPacket.persistAppliedState(List.of(entry),
                snapshot -> snapshot.applied = true,
                snapshot -> snapshot.applied = false,
                () -> { });

        assertTrue(entry.applied);
        assertFalse(SendTickListPacket.shouldHoldTickExecution(true, !entry.applied));
    }

    private static final class MutableJournalEntry {
        private boolean applied;
    }

    @Test
    void holdsRecoveredTicksUntilTheirAppliedStateIsDurable() {
        org.junit.jupiter.api.Assertions.assertTrue(SendTickListPacket.shouldHoldTickExecution(true, true),
                "a local owner must hold replayed ticks until the APPLIED journal write is confirmed");
        org.junit.jupiter.api.Assertions.assertTrue(SendTickListPacket.shouldHoldTickExecution(false, false),
                "a non-owner must never execute ticks");
        org.junit.jupiter.api.Assertions.assertFalse(SendTickListPacket.shouldHoldTickExecution(true, false),
                "a local owner can execute after durable application is confirmed");
    }

    @Test
    void transferIdentitySurvivesSenderRestartWhenAbsoluteTickTimeIsUnchanged() {
        assertEquals(stableTickId(5, 100), stableTickId(3, 102),
                "relative saved delays may change after restart, but the same scheduled tick keeps its identity");
        org.junit.jupiter.api.Assertions.assertNotEquals(stableTickId(5, 100), stableTickId(6, 100),
                "a later legitimate schedule at the same position must have a distinct identity");
    }

    @Test
    void transferIdentityDoesNotDependOnTickListIterationOrder() {
        CompoundTag first = tick("minecraft:stone", 65, 3);
        CompoundTag second = tick("minecraft:dirt", 66, 5);
        assertEquals(stableTickId(List.of(first, second), 100), stableTickId(List.of(second, first), 100),
                "snapshot iteration order must not create a second durable transfer identity");
    }

    @Test
    void collectedTickIsReturnedToTheQueueWithItsIdentityAndOrdering() {
        ScheduledTick<String> original = new ScheduledTick<>("minecraft:stone", new BlockPos(65, 80, 66), 40L, TickPriority.HIGH, 7L);

        ScheduledTick<String> deferred = SendTickListPacket.deferCollectedTick(original, 50L);

        assertEquals(original.type(), deferred.type());
        assertEquals(original.pos(), deferred.pos());
        assertEquals(51L, deferred.triggerTick(), "the queued tick must be eligible on the next server tick");
        assertEquals(original.priority(), deferred.priority());
        assertEquals(original.subTickOrder(), deferred.subTickOrder());
    }

    @Test
    void duplicateTransferIdDoesNotCreateAnotherReplayRecord() {
        List<JournalEntry> journal = new java.util.ArrayList<>();
        JournalEntry staged = new JournalEntry("transfer-1", "pending");
        journal.add(staged);

        if (!SendTickListPacket.containsTransferId(journal, "transfer-1", JournalEntry::id)) {
            journal.add(new JournalEntry("transfer-1", "pending"));
        }

        assertEquals(1, journal.size(), "retries with the same stable id must reuse the durable journal entry");
        assertEquals(staged, journal.getFirst(), "a retry must not reset applied/completed recovery state");
    }

    private record JournalEntry(String id, String state) { }

    private static String stableTickId(int delay, long sourceTime) {
        return stableTickId(List.of(tick("minecraft:stone", 65, delay)), sourceTime);
    }

    private static CompoundTag tick(String block, int x, int delay) {
        CompoundTag tick = new CompoundTag();
        tick.putString("i", block);
        tick.putInt("x", x);
        tick.putInt("y", 80);
        tick.putInt("z", 66);
        tick.putInt("t", delay);
        tick.putInt("p", 0);
        return tick;
    }

    private static String stableTickId(List<CompoundTag> ticks, long sourceTime) {
        ListTag blocks = new ListTag();
        ticks.forEach(blocks::add);
        CompoundTag snapshot = new CompoundTag();
        snapshot.put("block_ticks", blocks);
        snapshot.put("fluid_ticks", new ListTag());
        return SendTickListPacket.stableId("world", 4, 4, snapshot, sourceTime);
    }

    @Test
    void keepsTickSnapshotUntilChunkCanAcceptItThenReplaysOnce() {
        Map<String, String> deferred = new HashMap<>();
        deferred.put("world:4,9", "block-and-fluid-ticks");
        AtomicInteger replayCount = new AtomicInteger();
        AtomicReference<String> applied = new AtomicReference<>();

        SendTickListPacket.replayAvailable(deferred, ignored -> null, (ticks, chunk) -> {
            throw new AssertionError("must not replay before the chunk is available");
        });

        assertEquals(1, deferred.size(), "the snapshot must survive an unavailable chunk");

        SendTickListPacket.replayAvailable(deferred, key -> key, (ticks, chunk) -> {
            assertEquals("world:4,9", chunk);
            applied.set(ticks);
            replayCount.incrementAndGet();
            return true;
        });

        assertEquals("block-and-fluid-ticks", applied.get());
        assertEquals(1, replayCount.get());
        assertEquals(0, deferred.size(), "successfully replayed snapshots should be removed");

        SendTickListPacket.replayAvailable(deferred, key -> key, (ticks, chunk) -> {
            replayCount.incrementAndGet();
            return true;
        });
        assertEquals(1, replayCount.get(), "a replayed snapshot must not be applied a second time");
    }

    @Test
    void retainsSnapshotWhenReplayFailsSoItCanBeRetried() {
        Map<String, String> deferred = new HashMap<>();
        deferred.put("world:2,3", "ticks");
        AtomicReference<String> retried = new AtomicReference<>();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                SendTickListPacket.replayAvailable(deferred, key -> key, (ticks, chunk) -> {
                    throw new IllegalStateException("chunk tick apply failed");
                }));

        assertEquals(1, deferred.size());
        SendTickListPacket.replayAvailable(deferred, key -> key, (ticks, chunk) -> {
            retried.set(ticks);
            return true;
        });

        assertEquals("ticks", retried.get());
        assertEquals(0, deferred.size());
    }

    @Test
    void retainsSnapshotWhenAvailableChunkCannotAcceptItYet() {
        Map<String, String> deferred = new HashMap<>();
        deferred.put("world:7,8", "ticks");
        AtomicInteger replayCount = new AtomicInteger();

        SendTickListPacket.replayAvailable(deferred, key -> key, (ticks, chunk) -> {
            replayCount.incrementAndGet();
            return false;
        });

        assertEquals(1, replayCount.get());
        assertEquals(1, deferred.size(), "the snapshot must remain queued when the chunk cannot accept it");

        SendTickListPacket.replayAvailable(deferred, key -> key, (ticks, chunk) -> true);
        assertEquals(0, deferred.size());
    }

    @Test
    void accumulatesConsecutiveSnapshotsForTheSameUnavailableChunk() {
        Map<String, SendTickListPacket.PendingTickLists<String, String>> deferred = new HashMap<>();
        SendTickListPacket.accumulateDeferred(deferred, "world:4,9",
                new SendTickListPacket.PendingTickLists<>(List.of("block-1"), List.of("fluid-1")), SendTickListPacket::mergeTickLists);
        SendTickListPacket.accumulateDeferred(deferred, "world:4,9",
                new SendTickListPacket.PendingTickLists<>(List.of("block-2"), List.of("fluid-2")), SendTickListPacket::mergeTickLists);
        SendTickListPacket.accumulateDeferred(deferred, "world:4,9",
                new SendTickListPacket.PendingTickLists<>(List.of("block-3"), List.of("fluid-3")), SendTickListPacket::mergeTickLists);

        SendTickListPacket.PendingTickLists<String, String> queued = deferred.get("world:4,9");
        assertEquals(List.of("block-1", "block-2", "block-3"), queued.blockTicks());
        assertEquals(List.of("fluid-1", "fluid-2", "fluid-3"), queued.fluidTicks());

        AtomicReference<SendTickListPacket.PendingTickLists<String, String>> applied = new AtomicReference<>();
        SendTickListPacket.replayAvailable(deferred, key -> key, (ticks, chunk) -> {
            applied.set(ticks);
            return true;
        });

        assertEquals(queued, applied.get());
        assertEquals(0, deferred.size(), "the accumulated snapshots should be removed after successful replay");
    }
}
