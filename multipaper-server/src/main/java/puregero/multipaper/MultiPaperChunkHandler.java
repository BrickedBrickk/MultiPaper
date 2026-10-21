package puregero.multipaper;

import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import io.papermc.paper.util.MCUtil;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.externalserverprotocol.SendUpdatePacket;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.SubscribeChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.UnsubscribeChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;

import javax.annotation.Nullable;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class MultiPaperChunkHandler {

    private static final Logger LOGGER = LogManager.getLogger(MultiPaperChunkHandler.class.getSimpleName());

    public static boolean shouldTick(Level level, BlockPos pos) {
        LevelChunk chunk = level.getChunkIfLoaded(pos);
        return MultiPaper.isChunkLocal(chunk);
    }

    public static void onChunkLoad(LevelChunk chunk) {

    }

    public static void onChunkUnload(NewChunkHolder newChunkHolder, @Nullable ChunkAccess chunk, @Nullable ChunkEntitySlices chunkEntitySlices) {
        if (newChunkHolder.hasExternalLockRequest) {
            MultiPaper.unlockChunk(newChunkHolder, chunk, chunkEntitySlices);
        }
        MultiPaper.getConnection().sendAndAwaitReply(new UnsubscribeChunkMessage(newChunkHolder.world.getWorld().getName(), newChunkHolder.chunkX, newChunkHolder.chunkZ), BooleanMessageReply.class).thenRun(() ->
            onChunkUnsubscribed(newChunkHolder.world.getWorld().getName(), newChunkHolder.chunkX, newChunkHolder.chunkZ)
        );
    }

    public static void onChunkUnsubscribed(String world, int cx, int cz) {
        NewChunkHolder holder = MultiPaper.getChunkHolder(world, cx, cz);

        if (holder != null && holder.getCurrentChunk() != null) {
            LOGGER.warn("Chunk " + world + "," + cx + "," + cz + " was unsubscribed from but has a chunk loaded! Resubscribing...");
            MultiPaper.getConnection().send(new SubscribeChunkMessage(world, cx, cz));
        }
    }

    private static final HashSet<BlockEntity> blockEntitiesToBroadcast = new HashSet<>();
    public static void broadcastBlockEntityChange(BlockEntity entity) {
        if (blockUpdateChunk != null) return; // Don't broadcast the update to other servers if we're handling an update
        blockEntitiesToBroadcast.add(entity);
        // Wait a bit as the block entity may get changed multiple times in 1 tick
        MultiPaper.runSync(() -> {
            for (BlockEntity blockEntity : blockEntitiesToBroadcast) {
                if (blockEntity != null && blockEntity.getLevel() != null) {
                    NewChunkHolder holder = MultiPaper.getChunkHolder(blockEntity.getLevel().getWorld().getName(), blockEntity.getBlockPos());
                    onBlockUpdate(holder, new ClientboundBlockUpdatePacket(blockEntity.getBlockPos(), blockEntity.getBlockState()));
                    onBlockUpdate(holder, ClientboundBlockEntityDataPacket.create(blockEntity, BlockEntity::saveWithFullMetadata, false));
                }
            }
            blockEntitiesToBroadcast.clear();
        });
    }

    public static void broadcastBlockEntityChange(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || blockUpdateChunk != null) return;
        BlockEntity entity = serverLevel.getBlockEntity(pos);
        if (entity != null) {
            broadcastBlockEntityChange(entity);
        }
    }

    public static void onBlockUpdate(NewChunkHolder newChunkHolder, Packet<?> packet) {
        if (newChunkHolder == null) {
            // Chunk is still loading
            return;
        }

        ChunkAccess chunk = newChunkHolder.getCurrentChunk();

        if (chunk instanceof ImposterProtoChunk imposterProtoChunk) {
            chunk = imposterProtoChunk.getWrapped();
        }

        if (chunk == null) {
            LOGGER.warn("A " + packet.getClass().getSimpleName() + " occurred on an unloaded chunk " + newChunkHolder);
            return;
        }
        if (blockUpdateChunk == null) { // Don't broadcast the update to other servers if we're handling an update
            MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalSubscribers, () -> new SendUpdatePacket(newChunkHolder.world.uuid, packet));
        }
    }

    public static void broadcastChangesToOtherServers(ServerLevel level, BlockPos pos) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(level, pos);
        if (newChunkHolder == null || newChunkHolder.externalSubscribers.isEmpty()) {
            return;
        }

        ChunkAccess chunk = newChunkHolder.getCurrentChunk();
        if (chunk instanceof ImposterProtoChunk imposterProtoChunk) {
            chunk = imposterProtoChunk.getWrapped();
        }
        if (chunk instanceof LevelChunk levelChunk) {
            newChunkHolder.vanillaChunkHolder.broadcastChangesToOtherServers(levelChunk);
        }
    }

    public static ChunkAccess blockUpdateChunk = null;
    private static NewChunkHolder holder = null;
    public static void handleBlockUpdate(UUID world, Packet<?> packet, int depth) {
        holder = null;
        blockUpdateChunk = null;
        ChunkAccess tempChunk = null;
        CraftWorld bukkitWorld = ((CraftWorld) Bukkit.getWorld(world));
        ServerLevel level = bukkitWorld != null ? bukkitWorld.getHandle() : null;
        if (level == null) {
            return;
        } else if (packet instanceof ClientboundBlockUpdatePacket update) {
            holder = MultiPaper.getChunkHolder(world, update.getPos());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
            update.runUpdates((pos, state) -> {
                if (holder == null) holder = MultiPaper.getChunkHolder(world, pos);
            });
        } else if (packet instanceof ClientboundBlockEntityDataPacket update) {
            holder = MultiPaper.getChunkHolder(world, update.getPos());
        } else if (packet instanceof ClientboundLightUpdatePacket update) {
            holder = MultiPaper.getChunkHolder(world, update.x(), update.z());
        }

        if (holder != null && holder.hasGenerationTask()) {
            holder.onCurrentChunkLoaded().thenRun(() -> {
                if (!Bukkit.isPrimaryThread()) {
                    new Exception("Loaded chunk " + holder.chunkX + "," + holder.chunkZ + ", outside of the main thread! (currentThread=" + Thread.currentThread() + ")").printStackTrace();
                }
                handleBlockUpdate(world, packet, depth);
            });
            return;
        }

        if (holder != null) {
            tempChunk = holder.getCurrentChunk();

            if (tempChunk instanceof ImposterProtoChunk imposterProtoChunk) {
                tempChunk = imposterProtoChunk.getWrapped();
            }
        }

        if (holder != null) {
            // Only send changes we make below to players, not other servers
            holder.vanillaChunkHolder.addChangesToPlayersOnly = true;
        }

        // Set blockUpdateChunk here so that we can broadcast changes beforehand
        blockUpdateChunk = tempChunk;

        if (holder != null && level.getChunkIfLoaded(holder.chunkX, holder.chunkZ) != null) {
            // Chunk is loaded
            if (packet instanceof ClientboundBlockUpdatePacket update) {
                setBlock(((LevelChunk) blockUpdateChunk), update.getPos(), update.getBlockState());
            } else if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
                update.runUpdates((pos, state) -> {
                    setBlock(((LevelChunk) blockUpdateChunk), pos, state);
                });
            } else if (packet instanceof ClientboundBlockEntityDataPacket update) {
                BlockEntity existingBlockEntity = blockUpdateChunk.getBlockEntity(update.getPos());
                if (existingBlockEntity != null && existingBlockEntity.minecraftKey.toString().equals(update.getTag().getString("id"))) {
                    existingBlockEntity.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), update.getTag()));
                    markBlockChanged(holder, level, update.getPos());
                } else if (!blockUpdateChunk.getBlockState(update.getPos()).hasBlockEntity() && depth < 1) {
                    CompletableFuture.delayedExecutor(50, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> handleBlockUpdate(world, packet, depth + 1));
                } else {
                    blockUpdateChunk.removeBlockEntity(update.getPos());
                    blockUpdateChunk.setBlockEntityNbt(update.getTag());
                    blockUpdateChunk.getBlockEntity(update.getPos());
                    markBlockChanged(holder, level, update.getPos());
                }
            }
        } else if (blockUpdateChunk != null) {
            // Chunk is not loaded
            if (packet instanceof ClientboundBlockUpdatePacket update) {
                setBlockInUnloadedChunk(blockUpdateChunk, update.getPos(), update.getBlockState());
            } else if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
                update.runUpdates((pos, state) -> {
                    setBlockInUnloadedChunk(blockUpdateChunk, pos, state);
                });
            } else if (packet instanceof ClientboundBlockEntityDataPacket update) {
                blockUpdateChunk.removeBlockEntity(update.getPos());
                if (blockUpdateChunk instanceof LevelChunk levelChunk && levelChunk.blockEntitiesToLoad != null) {
                    levelChunk.blockEntitiesToLoad.add(update.getTag());
                } else {
                    blockUpdateChunk.setBlockEntityNbt(update.getTag());
                    blockUpdateChunk.getBlockEntity(update.getPos());
                }
            } else if (packet instanceof ClientboundLightUpdatePacket update) {
                handleLightUpdatePacket(level, blockUpdateChunk, update);
            }
        }

        if (holder != null) {
            holder.vanillaChunkHolder.addChangesToPlayersOnly = false;
        }

        blockUpdateChunk = null;
    }

    private static void setBlockInUnloadedChunk(ChunkAccess chunk, BlockPos pos, BlockState blockState) {
        if (chunk instanceof LevelChunk levelChunk) {
            levelChunk.setBlockState(pos, blockState, net.minecraft.world.level.block.Block.UPDATE_NONE);
        } else {
            chunk.setBlockState(pos, blockState, net.minecraft.world.level.block.Block.UPDATE_NONE);
        }
    }

    private static void setBlock(LevelChunk chunk, BlockPos pos, BlockState blockState) {
        BlockState oldState = chunk.setBlockState(pos, blockState, net.minecraft.world.level.block.Block.UPDATE_NONE);
        markBlockChanged(holder, (ServerLevel) chunk.getLevel(), pos);

        if (oldState != null && blockState != oldState) {
            ((ServerLevel) chunk.getLevel()).getChunkSource().getLightEngine().checkBlock(pos);
        }
    }

    private static void markBlockChanged(NewChunkHolder chunkHolder, ServerLevel level, BlockPos pos) {
        if (chunkHolder.vanillaChunkHolder.blockChanged(pos)) {
            // Changes applied from another backend bypass ServerChunkCache.blockChanged,
            // so explicitly queue the holder for vanilla's changed-chunk broadcast.
            level.getChunkSource().onChunkReadyToSend(chunkHolder.vanillaChunkHolder);
        }
    }

    // From the client
    private static void handleLightUpdatePacket(ServerLevel level, ChunkAccess chunk, ClientboundLightUpdatePacket packet) {
        int i = packet.x();
        int j = packet.z();
        LevelLightEngine levellightengine = level.getChunkSource().getLightEngine();
        var lightData = packet.lightData();
        BitSet bitset = lightData.skyYMask();
        BitSet bitset1 = lightData.emptySkyYMask();
        Iterator<byte[]> iterator = lightData.skyUpdates().iterator();
        readSectionList(chunk, i, j, levellightengine, LightLayer.SKY, bitset, bitset1, iterator);
        BitSet bitset2 = lightData.blockYMask();
        BitSet bitset3 = lightData.emptyBlockYMask();
        Iterator<byte[]> iterator1 = lightData.blockUpdates().iterator();
        readSectionList(chunk, i, j, levellightengine, LightLayer.BLOCK, bitset2, bitset3, iterator1);
    }

    // From the client
    private static void readSectionList(ChunkAccess chunk, int i, int j, LevelLightEngine levelLightEngine, LightLayer lightLayer, BitSet bitset2, BitSet bitset3, Iterator<byte[]> iterator1) {
        for(int k = 0; k < levelLightEngine.getLightSectionCount(); ++k) {
            int l = levelLightEngine.getMinLightSection() + k;
            boolean flag = bitset2.get(k);
            boolean flag1 = bitset3.get(k);
            if (flag || flag1) {
                if (lightLayer == LightLayer.BLOCK) {
                    ((ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk) chunk).starlight$getBlockNibbles()[k] = flag ? new SWMRNibbleArray(iterator1.next().clone()) : new SWMRNibbleArray();
                } else if (lightLayer == LightLayer.SKY) {
                    ((ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk) chunk).starlight$getSkyNibbles()[k] = flag ? new SWMRNibbleArray(iterator1.next().clone()) : new SWMRNibbleArray();
                }
            }
        }
    }
}
