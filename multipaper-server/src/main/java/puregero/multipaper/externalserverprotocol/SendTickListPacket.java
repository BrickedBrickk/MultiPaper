package puregero.multipaper.externalserverprotocol;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.SavedTick;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BinaryOperator;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Transfers scheduled ticks with a stable id and an acknowledgement. */
public class SendTickListPacket extends ExternalServerPacket {
    private static final Logger LOGGER = LogManager.getLogger(SendTickListPacket.class.getSimpleName());
    private static final long TEST_ACK_DELAY_MILLIS = Long.getLong("multipaper.test.tickAckDelayMillis", 0L);
    private static final long TEST_HANDOFF_SEND_DELAY_MILLIS = Long.getLong("multipaper.test.tickHandoffSendDelayMillis", 0L);
    private static final Codec<List<SavedTick<net.minecraft.world.level.block.Block>>> BLOCK_TICKS_CODEC = SavedTick.codec(BuiltInRegistries.BLOCK.byNameCodec()).listOf();
    private static final Codec<List<SavedTick<Fluid>>> FLUID_TICKS_CODEC = SavedTick.codec(BuiltInRegistries.FLUID.byNameCodec()).listOf();
    private static final Map<ChunkLocation, List<DeferredSnapshot>> DEFERRED_TICK_LISTS = new HashMap<>();

    private final String world;
    private final int cx;
    private final int cz;
    private final CompoundTag tag;
    private final String transferId;
    private final boolean durableHandoff;
    private final long sourceGameTime;

    public SendTickListPacket(LevelChunk chunk) {
        this(chunk, false);
    }

    public SendTickListPacket(LevelChunk chunk, boolean durableHandoff) {
        ServerLevel level = (ServerLevel) chunk.getLevel();
        world = level.getWorld().getName();
        cx = chunk.getPos().x();
        cz = chunk.getPos().z();
        this.durableHandoff = durableHandoff;
        sourceGameTime = level.getGameTime();
        tag = new CompoundTag();
        List<SavedTick<net.minecraft.world.level.block.Block>> blockTicks = new ArrayList<>(chunk.blockTicks.pack(sourceGameTime));
        List<SavedTick<Fluid>> fluidTicks = new ArrayList<>(chunk.fluidTicks.pack(sourceGameTime));
        if (durableHandoff) {
            ChunkPos pos = chunk.getPos();
            blockTicks.addAll(level.getBlockTicks().snapshotInFlightTicksInChunk(pos, sourceGameTime));
            fluidTicks.addAll(level.getFluidTicks().snapshotInFlightTicksInChunk(pos, sourceGameTime));
        }
        tag.put("block_ticks", BLOCK_TICKS_CODEC.encodeStart(NbtOps.INSTANCE, blockTicks).getOrThrow());
        tag.put("fluid_ticks", FLUID_TICKS_CODEC.encodeStart(NbtOps.INSTANCE, fluidTicks).getOrThrow());
        transferId = stableId(world, cx, cz, tag, sourceGameTime);
    }

    public SendTickListPacket(FriendlyByteBuf in) {
        world = in.readUtf();
        cx = in.readInt();
        cz = in.readInt();
        transferId = in.readUtf();
        durableHandoff = in.readBoolean();
        sourceGameTime = in.readLong();
        try {
            tag = MultiPaper.nbtFromBytes(in.readByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Could not decode scheduled tick handoff", e);
        }
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeInt(cx);
        out.writeInt(cz);
        out.writeUtf(transferId);
        out.writeBoolean(durableHandoff);
        out.writeLong(sourceGameTime);
        try {
            out.writeByteArray(MultiPaper.nbtToBytes(tag));
        } catch (Exception e) {
            throw new RuntimeException("Could not encode scheduled tick handoff", e);
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            if (!durableHandoff) {
                CraftWorld mirrorWorld = (CraftWorld) Bukkit.getWorld(world);
                ServerLevel mirrorLevel = mirrorWorld == null ? null : mirrorWorld.getHandle();
                ChunkAccess mirrorChunk = MultiPaper.getChunkAccess(world, cx, cz);
                if (mirrorLevel != null && mirrorChunk != null) applyTickLists(mirrorLevel, mirrorChunk, cx, cz, decode(tag), sourceGameTime);
                return;
            }
            try {
                ChunkLocation location = new ChunkLocation(world, cx, cz);
                List<DeferredSnapshot> deferred = loadJournal(location);
                if (!containsTransferId(deferred, transferId, snapshot -> snapshot.transferId)) {
                    deferred.add(new DeferredSnapshot(transferId, tag.copy(), sourceGameTime, false, false));
                }
                persistJournal(location, deferred);
                DEFERRED_TICK_LISTS.put(location, deferred);
                // The sender may discard its copy only after the receiver's recovery journal is on Master storage.
                acknowledge(connection);
                replayIfAvailable(location);
            } catch (Exception e) {
                LOGGER.error("Could not durably stage scheduled ticks for " + world + "," + cx + "," + cz + "; sender will retry", e);
            }
        });
    }

    /** Load a chunk's durable handoff journal when Master assigns ownership to this backend. */
    public static void recoverChunk(String world, int cx, int cz) {
        MultiPaper.runSync(() -> {
            try {
                ChunkLocation location = new ChunkLocation(world, cx, cz);
                List<DeferredSnapshot> deferred = loadJournal(location);
                if (!deferred.isEmpty()) DEFERRED_TICK_LISTS.put(location, deferred);
                replayIfAvailable(location);
            } catch (Exception e) {
                LOGGER.error("Could not recover scheduled tick handoff for " + world + "," + cx + "," + cz, e);
            }
        });
    }

    public static void tickDeferred() {
        Iterator<Map.Entry<ChunkLocation, List<DeferredSnapshot>>> locations = DEFERRED_TICK_LISTS.entrySet().iterator();
        while (locations.hasNext()) {
            Map.Entry<ChunkLocation, List<DeferredSnapshot>> entry = locations.next();
            ChunkLocation location = entry.getKey();
            CraftWorld bukkitWorld = (CraftWorld) Bukkit.getWorld(location.world);
            ServerLevel level = bukkitWorld == null ? null : bukkitWorld.getHandle();
            ChunkAccess chunk = MultiPaper.getChunkAccess(location.world, location.cx, location.cz);
            if (level == null || chunk == null) continue;
            replayIfAvailable(location);
        }
    }

    /**
     * Hold this chunk's scheduled ticks until the receiver has confirmed that
     * the replayed queue is durably stored with the chunk on Master.
     */
    public static boolean isAwaitingDurableApply(String world, int cx, int cz) {
        List<DeferredSnapshot> deferred = DEFERRED_TICK_LISTS.get(new ChunkLocation(world, cx, cz));
        return deferred != null && deferred.stream().anyMatch(snapshot -> !snapshot.applied && !snapshot.complete);
    }

    public static boolean shouldHoldTickExecution(boolean isChunkLocal, boolean awaitingDurableApply) {
        return !isChunkLocal || awaitingDurableApply;
    }

    private static void replayIfAvailable(ChunkLocation location) {
        CraftWorld bukkitWorld = (CraftWorld) Bukkit.getWorld(location.world);
        ServerLevel level = bukkitWorld == null ? null : bukkitWorld.getHandle();
        ChunkAccess chunk = MultiPaper.getChunkAccess(location.world, location.cx, location.cz);
        List<DeferredSnapshot> deferred = DEFERRED_TICK_LISTS.get(location);
        if (level == null || chunk == null || deferred == null
                || !MultiPaper.isChunkLocal(MultiPaper.getChunkHolder(location.world, location.cx, location.cz))) return;
        List<DeferredSnapshot> newlyApplied = new ArrayList<>();
        for (DeferredSnapshot snapshot : deferred) {
            if (snapshot.complete) continue;
            if (!snapshot.applied && applyTickLists(level, chunk, location.cx, location.cz, decode(snapshot.ticks), snapshot.sourceGameTime)) {
                newlyApplied.add(snapshot);
            }
        }
        try {
            if (!newlyApplied.isEmpty()) {
                // Commit the chunk's queue to Master's region storage before recording APPLIED.
                // The server thread cannot run scheduled ticks until this synchronous write returns.
                MultiPaper.writeChunk(chunk.getPos(), level, SerializableChunkData.copyOf(level, chunk).write());
                persistAppliedState(newlyApplied, snapshot -> snapshot.applied = true,
                        snapshot -> snapshot.applied = false, () -> persistJournal(location, deferred));
            }
            List<DeferredSnapshot> newlyCompleted = new ArrayList<>();
            for (DeferredSnapshot snapshot : deferred) {
                if (!snapshot.complete && snapshot.applied && noTicksRemain(level, snapshot.ticks)) {
                    newlyCompleted.add(snapshot);
                }
            }
            if (!newlyCompleted.isEmpty()) {
                // Persist the tick's world effects and queue removal before committing completion.
                // A crash before this chunk write leaves the previously committed tick queued for replay.
                MultiPaper.writeChunk(chunk.getPos(), level, SerializableChunkData.copyOf(level, chunk).write());
                newlyCompleted.forEach(snapshot -> {
                    snapshot.complete = true;
                    snapshot.ticks = emptyTickTag();
                });
                persistJournal(location, deferred);
            }
        } catch (Exception e) {
            LOGGER.error("Could not persist scheduled tick replay state for " + location, e);
        }
    }

    private static boolean noTicksRemain(ServerLevel level, CompoundTag tag) {
        PendingTickLists<SavedTick<net.minecraft.world.level.block.Block>, SavedTick<Fluid>> ticks = decode(tag);
        return ticks.blockTicks.stream().noneMatch(tick -> level.getBlockTicks().hasScheduledTick(tick.pos(), tick.type()))
                && ticks.fluidTicks.stream().noneMatch(tick -> level.getFluidTicks().hasScheduledTick(tick.pos(), tick.type()));
    }

    private static List<DeferredSnapshot> loadJournal(ChunkLocation location) throws IOException {
        byte[] data = MultiPaper.readData(journalPath(location));
        if (data.length == 0) return new ArrayList<>();
        CompoundTag journal = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
        List<DeferredSnapshot> snapshots = new ArrayList<>();
        ListTag transfers = journal.getList("transfers").orElseGet(ListTag::new);
        for (int i = 0; i < transfers.size(); i++) {
            CompoundTag entry = transfers.getCompound(i).orElseGet(CompoundTag::new);
            snapshots.add(new DeferredSnapshot(entry.getString("id").orElse(""), entry.getCompound("ticks").orElseGet(CompoundTag::new), entry.getLong("sourceGameTime").orElse(0L), entry.getBoolean("applied").orElse(false), entry.getBoolean("complete").orElse(false)));
        }
        return snapshots;
    }

    private static void persistJournal(ChunkLocation location, List<DeferredSnapshot> snapshots) throws IOException {
        CompoundTag journal = new CompoundTag();
        ListTag transfers = new ListTag();
        for (DeferredSnapshot snapshot : snapshots) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", snapshot.transferId);
            entry.put("ticks", snapshot.ticks.copy());
            entry.putLong("sourceGameTime", snapshot.sourceGameTime);
            entry.putBoolean("applied", snapshot.applied);
            entry.putBoolean("complete", snapshot.complete);
            transfers.add(entry);
        }
        journal.put("transfers", transfers);
        MultiPaper.writeDataAndAwait(journalPath(location), journal);
    }

    private static CompoundTag emptyTickTag() {
        CompoundTag empty = new CompoundTag();
        empty.put("block_ticks", new ListTag());
        empty.put("fluid_ticks", new ListTag());
        return empty;
    }

    private static String journalPath(ChunkLocation location) {
        org.bukkit.World world = Bukkit.getWorld(location.world);
        if (world == null) throw new IllegalStateException("World is not loaded: " + location.world);
        return new File(new File(world.getWorldFolder(), "data/multipaper/tick-handoffs"), location.cx + "_" + location.cz + ".dat").getPath();
    }

    private void acknowledge(ExternalServerConnection connection) {
        Runnable sendAck = () -> {
            if (connection.isOpen()) connection.send(new TickListAckPacket(world, cx, cz, transferId));
        };
        if (TEST_ACK_DELAY_MILLIS > 0) {
            CompletableFuture.delayedExecutor(TEST_ACK_DELAY_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS).execute(sendAck);
        } else {
            sendAck.run();
        }
    }

    /** Persist the sender's recovery copy before the Master releases chunk ownership. */
    public void persistForOutgoing() throws IOException {
        ChunkLocation location = new ChunkLocation(world, cx, cz);
        List<DeferredSnapshot> deferred = loadJournal(location);
        if (!containsTransferId(deferred, transferId, snapshot -> snapshot.transferId)) {
            deferred.add(new DeferredSnapshot(transferId, tag.copy(), sourceGameTime, false, false));
        }
        persistJournal(location, deferred);
    }

    /** Remove ready ticks from vanilla's in-flight queues after their Master copy is durable. */
    public void removeInFlightFromSource(ServerLevel level) {
        PendingTickLists<SavedTick<net.minecraft.world.level.block.Block>, SavedTick<Fluid>> ticks = decode(tag);
        ChunkPos pos = new ChunkPos(cx, cz);
        level.getBlockTicks().removeInFlightTicksInChunk(pos, ticks.blockTicks, sourceGameTime);
        level.getFluidTicks().removeInFlightTicksInChunk(pos, ticks.fluidTicks, sourceGameTime);
    }

    public String transferId() { return transferId; }
    public String world() { return world; }
    public int cx() { return cx; }
    public int cz() { return cz; }

    /** Send an already durable handoff, with a delay seam for crash-cut tests. */
    public void sendTo(ExternalServerConnection connection) {
        Runnable send = () -> {
            if (connection.isOpen()) connection.send(this);
        };
        if (TEST_HANDOFF_SEND_DELAY_MILLIS > 0) {
            CompletableFuture.delayedExecutor(TEST_HANDOFF_SEND_DELAY_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS).execute(send);
        } else {
            send.run();
        }
    }

    public boolean isEmpty() {
        PendingTickLists<SavedTick<net.minecraft.world.level.block.Block>, SavedTick<Fluid>> ticks = decode(tag);
        return ticks.blockTicks.isEmpty() && ticks.fluidTicks.isEmpty();
    }

    public void removeFrom(LevelChunk chunk) {
        PendingTickLists<SavedTick<net.minecraft.world.level.block.Block>, SavedTick<Fluid>> ticks = decode(tag);
        chunk.blockTicks.removeIf(existing -> ticks.blockTicks.stream().anyMatch(saved -> saved.pos().equals(existing.pos()) && saved.type() == existing.type()
                && saved.priority() == existing.priority() && sourceGameTime + saved.delay() == existing.triggerTick()));
        chunk.fluidTicks.removeIf(existing -> ticks.fluidTicks.stream().anyMatch(saved -> saved.pos().equals(existing.pos()) && saved.type() == existing.type()
                && saved.priority() == existing.priority() && sourceGameTime + saved.delay() == existing.triggerTick()));
    }

    private static void addDeferred(List<DeferredSnapshot> deferred, DeferredSnapshot snapshot) {
        for (int i = 0; i < deferred.size(); i++) {
            if (deferred.get(i).transferId.equals(snapshot.transferId)) {
                // Refresh the connection so a retry after peer reconnect can be acknowledged.
                deferred.set(i, snapshot);
                return;
            }
        }
        deferred.add(snapshot);
    }

    private static PendingTickLists<SavedTick<net.minecraft.world.level.block.Block>, SavedTick<Fluid>> decode(CompoundTag tag) {
        return new PendingTickLists<>(BLOCK_TICKS_CODEC.parse(NbtOps.INSTANCE, tag.get("block_ticks")).getOrThrow(), FLUID_TICKS_CODEC.parse(NbtOps.INSTANCE, tag.get("fluid_ticks")).getOrThrow());
    }

    private static boolean applyTickLists(ServerLevel level, ChunkAccess chunk, int cx, int cz, PendingTickLists<SavedTick<net.minecraft.world.level.block.Block>, SavedTick<Fluid>> ticks, long sourceGameTime) {
        long now = level.getGameTime();
        long subTick = 0L;
        if (level.getChunkIfLoaded(cx, cz) != null) {
            for (SavedTick<net.minecraft.world.level.block.Block> original : ticks.blockTicks) {
                SavedTick<net.minecraft.world.level.block.Block> tick = rebase(original, sourceGameTime, now);
                if (!level.getBlockTicks().hasScheduledTick(tick.pos(), tick.type())) level.getBlockTicks().schedule(tick.unpack(now, subTick));
                subTick++;
            }
            subTick = 0L;
            for (SavedTick<Fluid> original : ticks.fluidTicks) {
                SavedTick<Fluid> tick = rebase(original, sourceGameTime, now);
                if (!level.getFluidTicks().hasScheduledTick(tick.pos(), tick.type())) level.getFluidTicks().schedule(tick.unpack(now, subTick));
                subTick++;
            }
        } else if (chunk instanceof LevelChunk levelChunk) {
            for (SavedTick<net.minecraft.world.level.block.Block> original : ticks.blockTicks) {
                SavedTick<net.minecraft.world.level.block.Block> tick = rebase(original, sourceGameTime, now);
                if (!levelChunk.blockTicks.hasScheduledTick(tick.pos(), tick.type())) levelChunk.blockTicks.schedule(tick.unpack(now, subTick));
                subTick++;
            }
            subTick = 0L;
            for (SavedTick<Fluid> original : ticks.fluidTicks) {
                SavedTick<Fluid> tick = rebase(original, sourceGameTime, now);
                if (!levelChunk.fluidTicks.hasScheduledTick(tick.pos(), tick.type())) levelChunk.fluidTicks.schedule(tick.unpack(now, subTick));
                subTick++;
            }
        } else if (chunk instanceof ProtoChunk protoChunk) {
            for (SavedTick<net.minecraft.world.level.block.Block> original : ticks.blockTicks) {
                SavedTick<net.minecraft.world.level.block.Block> tick = rebase(original, sourceGameTime, now);
                if (!protoChunk.blockTicks.hasScheduledTick(tick.pos(), tick.type())) protoChunk.blockTicks.schedule(tick.unpack(now, subTick));
                subTick++;
            }
            subTick = 0L;
            for (SavedTick<Fluid> original : ticks.fluidTicks) {
                SavedTick<Fluid> tick = rebase(original, sourceGameTime, now);
                if (!protoChunk.fluidTicks.hasScheduledTick(tick.pos(), tick.type())) protoChunk.fluidTicks.schedule(tick.unpack(now, subTick));
                subTick++;
            }
        } else return false;
        return true;
    }

    private static <T> SavedTick<T> rebase(SavedTick<T> tick, long sourceGameTime, long receiverGameTime) {
        long remaining = sourceGameTime + tick.delay() - receiverGameTime;
        int delay = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, remaining));
        return new SavedTick<>(tick.type(), tick.pos(), delay, tick.priority());
    }

    /** Return a collected tick to the chunk queue without losing its ordering data. */
    public static <T> ScheduledTick<T> deferCollectedTick(ScheduledTick<T> tick, long gameTime) {
        long retryTick = Math.max(tick.triggerTick() + 1L, gameTime + 1L);
        return new ScheduledTick<>(tick.type(), tick.pos(), retryTick, tick.priority(), tick.subTickOrder());
    }

    static <K, V, R> void replayAvailable(Map<K, V> deferred, Function<K, R> resolver, BiPredicate<V, R> replay) {
        Iterator<Map.Entry<K, V>> iterator = deferred.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, V> entry = iterator.next();
            R resolved = resolver.apply(entry.getKey());
            if (resolved != null && replay.test(entry.getValue(), resolved)) iterator.remove();
        }
    }

    static <T> boolean containsTransferId(List<T> transfers, String transferId, Function<T, String> identity) {
        return transfers.stream().anyMatch(transfer -> transferId.equals(identity.apply(transfer)));
    }

    static <K, V> void accumulateDeferred(Map<K, V> deferred, K key, V snapshot, BinaryOperator<V> merge) { deferred.merge(key, snapshot, merge); }

    static <T> void persistAppliedState(List<T> snapshots, java.util.function.Consumer<T> markApplied,
                                        java.util.function.Consumer<T> markPending, JournalPersister persist) throws IOException {
        snapshots.forEach(markApplied);
        try {
            persist.run();
        } catch (IOException | RuntimeException failure) {
            // Keep the execution hold active until the APPLIED journal update is durable.
            snapshots.forEach(markPending);
            throw failure;
        }
    }

    @FunctionalInterface
    interface JournalPersister {
        void run() throws IOException;
    }

    static <T> List<T> appendTickSnapshots(List<T> earlier, List<T> later) {
        List<T> combined = new ArrayList<>(earlier.size() + later.size());
        combined.addAll(earlier);
        combined.addAll(later);
        return List.copyOf(combined);
    }

    static <B, F> PendingTickLists<B, F> mergeTickLists(PendingTickLists<B, F> earlier, PendingTickLists<B, F> later) {
        return new PendingTickLists<>(appendTickSnapshots(earlier.blockTicks, later.blockTicks), appendTickSnapshots(earlier.fluidTicks, later.fluidTicks));
    }

    static String stableId(String world, int cx, int cz, CompoundTag tag, long sourceGameTime) {
        StringBuilder identity = new StringBuilder(world).append(':').append(cx).append(':').append(cz);
        appendTickIdentities(identity, "B", BLOCK_TICKS_CODEC.parse(NbtOps.INSTANCE, tag.get("block_ticks")).getOrThrow(), BuiltInRegistries.BLOCK, sourceGameTime);
        appendTickIdentities(identity, "F", FLUID_TICKS_CODEC.parse(NbtOps.INSTANCE, tag.get("fluid_ticks")).getOrThrow(), BuiltInRegistries.FLUID, sourceGameTime);
        return UUID.nameUUIDFromBytes(identity.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static <T> void appendTickIdentities(StringBuilder identity, String kind, List<SavedTick<T>> ticks,
                                                  net.minecraft.core.Registry<T> registry, long sourceGameTime) {
        List<String> canonicalTicks = new ArrayList<>(ticks.size());
        for (SavedTick<T> tick : ticks) {
            canonicalTicks.add(registry.getKey(tick.type()) + ":" + tick.pos().asLong() + ":"
                    + (sourceGameTime + tick.delay()) + ":" + tick.priority().getValue());
        }
        canonicalTicks.sort(String::compareTo);
        for (String tickIdentity : canonicalTicks) identity.append('|').append(kind).append(':').append(tickIdentity);
    }

    private record ChunkLocation(String world, int cx, int cz) { }
    private static final class DeferredSnapshot {
        private final String transferId;
        private CompoundTag ticks;
        private final long sourceGameTime;
        private boolean applied;
        private boolean complete;
        private DeferredSnapshot(String transferId, CompoundTag ticks, long sourceGameTime, boolean applied, boolean complete) { this.transferId = transferId; this.ticks = complete ? emptyTickTag() : ticks.copy(); this.sourceGameTime = sourceGameTime; this.applied = applied; this.complete = complete; }
    }
    record PendingTickLists<B, F>(List<B> blockTicks, List<F> fluidTicks) { }
}
