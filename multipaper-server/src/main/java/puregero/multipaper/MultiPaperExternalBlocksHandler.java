package puregero.multipaper;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.externalserverprotocol.SendTickListPacket;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.mastermessagingprotocol.ChunkKey;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RequestChunkOwnershipMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class MultiPaperExternalBlocksHandler {

    private static final Logger LOGGER = LogManager.getLogger(MultiPaperExternalBlocksHandler.class.getSimpleName());
    private static final HashSet<NewChunkHolder> scheduledChunks = new HashSet<>();
    private static final List<CompletableFutureWithKey<HashSet<NewChunkHolder>, Boolean>> takingControlOf = new ArrayList<>();
    private static final Map<String, PendingOutgoing> pendingOutgoing = new ConcurrentHashMap<>();
    private static final Map<ChunkLocation, SendTickListPacket> preparedOutgoing = new ConcurrentHashMap<>();
    private static long tickCounter;

    public static void onBlockScheduled(ServerLevel level, BlockPos pos) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(level, pos);

        if (MultiPaper.isChunkExternal(newChunkHolder)) {
            scheduledChunks.add(newChunkHolder);
        }
    }

    public static void tick() {
        tickCounter++;
        if (tickCounter % 20 == 0) retryPendingTickLists();
        takingControlOf.clear();

        while (!scheduledChunks.isEmpty()) {
            Iterator<NewChunkHolder> iterator = scheduledChunks.iterator();
            NewChunkHolder newChunkHolder = iterator.next();
            iterator.remove();

            if (!newChunkHolder.hasExternalLockRequest) {
                // We aren't ticking this chunk
                sendTickListTo(newChunkHolder);
                continue;
            }

            HashSet<NewChunkHolder> neighbours = new HashSet<>();

            fillTickingNeighbours(newChunkHolder, neighbours);

            boolean hasALocalChunk = false;

            for (NewChunkHolder neighbour : neighbours) {
                if (MultiPaper.isChunkLocal(neighbour)) {
                    hasALocalChunk = true;
                    break;
                }
            }

            if (hasALocalChunk) {
                takingControlOf.add(requestChunkOwnership(neighbours));
            } else {
                for (NewChunkHolder neighbour : neighbours) {
                    sendTickListTo(neighbour);
                }
            }
        }

        if (!takingControlOf.isEmpty()) {
            CompletableFuture<Void> allFuture = CompletableFuture.allOf(takingControlOf.toArray(CompletableFuture[]::new));

            // Wait for the control process to complete before continuing the tick so that this doesn't mess up the next tick
            MinecraftServer.getServer().managedBlock(allFuture::isDone);

            for (CompletableFutureWithKey<HashSet<NewChunkHolder>, Boolean> completableFuture : takingControlOf) {
                if (!completableFuture.join()) {
                    // Failed to take control of the chunks, send the tick lists to their owners
                    for (NewChunkHolder newChunkHolder : completableFuture.getKey()) {
                        sendTickListTo(newChunkHolder);
                    }
                }
            }
        }
    }

    private static void sendTickListTo(NewChunkHolder newChunkHolder) {
        if (MultiPaper.isChunkExternal(newChunkHolder)) {
            if (newChunkHolder.getCurrentChunk() instanceof LevelChunk levelChunk) {
                SendTickListPacket packet = new SendTickListPacket(levelChunk, true);
                if (packet.isEmpty()) return;
                queueHandoff(packet, newChunkHolder.externalOwner.getConnection(), (ServerLevel) levelChunk.getLevel());
            } else {
                LOGGER.warn("Tried sending a tick list to a chunk that isn't a level chunk: " + newChunkHolder);
            }
        }
    }

    public static void handoffTicks(LevelChunk chunk, ExternalServer owner) {
        ChunkLocation location = new ChunkLocation(((ServerLevel) chunk.getLevel()).getWorld().getName(), chunk.getPos().x(), chunk.getPos().z());
        SendTickListPacket packet = preparedOutgoing.remove(location);
        if (packet == null) packet = new SendTickListPacket(chunk, true);
        if (!packet.isEmpty()) queueHandoff(packet, owner.getConnection(), (ServerLevel) chunk.getLevel());
    }

    /** Stage a sender recovery record before Master can release this owner's lock. */
    public static boolean persistTicksBeforeUnlock(LevelChunk chunk) {
        ServerLevel level = (ServerLevel) chunk.getLevel();
        ChunkLocation location = new ChunkLocation(level.getWorld().getName(), chunk.getPos().x(), chunk.getPos().z());
        SendTickListPacket packet = new SendTickListPacket(chunk, true);
        if (packet.isEmpty()) return true;
        try {
            packet.persistForOutgoing();
            packet.removeInFlightFromSource(level);
            pendingOutgoing.putIfAbsent(packet.transferId(), new PendingOutgoing(packet));
            preparedOutgoing.put(location, packet);
            return true;
        } catch (Exception e) {
            LOGGER.error("Could not persist scheduled ticks before releasing chunk " + location + "; retaining ownership", e);
            return false;
        }
    }

    private static void queueHandoff(SendTickListPacket packet, ExternalServerConnection connection, ServerLevel level) {
        try {
            packet.persistForOutgoing();
            packet.removeInFlightFromSource(level);
            pendingOutgoing.putIfAbsent(packet.transferId(), new PendingOutgoing(packet));
            packet.sendTo(connection);
        } catch (Exception e) {
            LOGGER.error("Could not durably stage outgoing scheduled ticks for " + packet.world() + "," + packet.cx() + "," + packet.cz(), e);
        }
    }

    private static void retryPendingTickLists() {
        for (PendingOutgoing pending : new ArrayList<>(pendingOutgoing.values())) {
            SendTickListPacket packet = pending.packet;
            NewChunkHolder holder = MultiPaper.getChunkHolder(packet.world(), packet.cx(), packet.cz());
            if (holder != null && MultiPaper.isChunkExternal(holder) && holder.externalOwner != null && holder.externalOwner.getConnection() != null) {
                packet.sendTo(holder.externalOwner.getConnection());
            }
        }
    }

    public static void acknowledgeTickList(String world, int cx, int cz, String transferId) {
        MultiPaper.runSync(() -> {
            PendingOutgoing pending = pendingOutgoing.remove(transferId);
            if (pending == null) return;
            preparedOutgoing.remove(new ChunkLocation(world, cx, cz), pending.packet);
            NewChunkHolder holder = MultiPaper.getChunkHolder(world, cx, cz);
            if (holder != null && holder.getCurrentChunk() instanceof LevelChunk levelChunk) pending.packet.removeFrom(levelChunk);
        });
    }

    private static void fillTickingNeighbours(NewChunkHolder newChunkHolder, HashSet<NewChunkHolder> neighbours) {
        for (int x = -1; x <= 1; x ++) {
            for (int z = -1; z <= 1; z ++) {
                NewChunkHolder neighbour = MultiPaper.getChunkHolder(newChunkHolder.world, newChunkHolder.chunkX + x, newChunkHolder.chunkZ + z);

                if (neighbour != null && !neighbours.contains(neighbour) && neighbour.hasExternalLockRequest &&
                        neighbour.getCurrentChunk() instanceof LevelChunk levelChunk &&
                        (levelChunk.getBlockTicks().count() > 0 || levelChunk.getFluidTicks().count() > 0)) {
                    scheduledChunks.remove(neighbour);
                    neighbours.add(neighbour);
                    fillTickingNeighbours(neighbour, neighbours);
                }
            }
        }
    }

    private static CompletableFutureWithKey<HashSet<NewChunkHolder>, Boolean> requestChunkOwnership(HashSet<NewChunkHolder> neighbours) {
        CompletableFutureWithKey<HashSet<NewChunkHolder>, Boolean> future = new CompletableFutureWithKey<>(neighbours);

        String world = null;
        ChunkKey[] chunkKeys = new ChunkKey[neighbours.size()];
        int i = 0;

        for (NewChunkHolder newChunkHolder : neighbours) {
            if (world == null) {
                world = newChunkHolder.world.getWorld().getName();
            }

            chunkKeys[i++] = new ChunkKey(newChunkHolder.world.getWorld().getName(), newChunkHolder.chunkX, newChunkHolder.chunkZ);
        }

        MultiPaper.getConnection().sendAndAwaitReply(new RequestChunkOwnershipMessage(world, chunkKeys), BooleanMessageReply.class).thenAccept(message -> future.complete(message.result));

        return future;
    }

    private static class CompletableFutureWithKey<K, V> extends CompletableFuture<V> {
        private final K key;

        private CompletableFutureWithKey(K key) {
            this.key = key;
        }

        public K getKey() {
            return key;
        }
    }

    private record PendingOutgoing(SendTickListPacket packet) { }
    private record ChunkLocation(String world, int cx, int cz) { }

}
