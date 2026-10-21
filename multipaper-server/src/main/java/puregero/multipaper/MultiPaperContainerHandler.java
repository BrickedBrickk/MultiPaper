package puregero.multipaper;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.mastermessagingprotocol.ChunkKey;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RequestChunkOwnershipMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PersistContainerTransactionMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PersistContainerTransactionMessage.Snapshot;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MultiPaperContainerHandler {
    private static final Logger LOGGER = LogManager.getLogger(MultiPaperContainerHandler.class);
    private static final Set<ChunkKey> ownershipRequests = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<ChunkKey, Long> ownershipRequestRetryAfter = new ConcurrentHashMap<>();

    public enum ExternalTransferResult {
        LOCAL,
        FAILED
    }

    public static ItemStack rollbackFailedExternalTransfer(Container destination, int slot, ItemStack originalDestination, ItemStack originalSource) {
        destination.setItem(slot, originalDestination.copy());
        return originalSource.copy();
    }

    public static final class TransferSnapshot {
        private final Map<Object, InventoryState> inventories;

        private TransferSnapshot(Map<Object, InventoryState> inventories) {
            this.inventories = inventories;
        }
    }

    public static TransferSnapshot snapshotTransfer(Container from, Container to) {
        Map<Object, InventoryState> inventories = new LinkedHashMap<>();
        collectInventories(from, inventories);
        collectInventories(to, inventories);
        return new TransferSnapshot(inventories);
    }

    public static boolean persistCrossChunkTransfer(TransferSnapshot before) {
        if (before.inventories.size() < 2) return true;

        Set<StorageChunkKey> chunks = new HashSet<>();
        String world = null;
        for (Object owner : before.inventories.keySet()) {
            ServerLevel level = owner instanceof BlockEntity block ? (ServerLevel) block.getLevel()
                    : owner instanceof Entity entity && entity.level() instanceof ServerLevel serverLevel ? serverLevel : null;
            if (level == null) return false;
            if (world == null) world = level.getWorld().getName();
            if (!world.equals(level.getWorld().getName())) return false;
            InventoryState inventory = before.inventories.get(owner);
            chunks.add(new StorageChunkKey(inventory.path, inventory.chunkX, inventory.chunkZ));
        }
        if (chunks.size() < 2) return true;
        for (StorageChunkKey chunk : chunks) {
            if (!MultiPaper.isChunkLocal(MultiPaper.getChunkHolder(world, chunk.chunkX, chunk.chunkZ))) return false;
        }

        List<Snapshot> snapshots = new ArrayList<>();
        try {
            for (Object owner : before.inventories.keySet()) {
                InventoryState inventory = before.inventories.get(owner);
                CompoundTag afterTag = owner instanceof BlockEntity block
                        ? block.saveWithFullMetadata(block.getLevel().registryAccess())
                        : saveEntity((Entity) owner);
                snapshots.add(new Snapshot(inventory.path, inventory.chunkX, inventory.chunkZ, inventory.identity,
                        serializeBlockEntity(inventory.beforeTag), serializeBlockEntity(afterTag)));
            }

            PersistContainerTransactionMessage message = new PersistContainerTransactionMessage(world, UUID.randomUUID().toString(), snapshots.toArray(Snapshot[]::new));
            BooleanMessageReply reply = MultiPaper.getConnection().sendAndAwaitReply(message, BooleanMessageReply.class).join();
            if (!reply.result) {
                restore(before);
                LOGGER.error("Master rejected durable cross-chunk hopper transaction in world {}", world);
                return false;
            }
            return true;
        } catch (RuntimeException | IOException failure) {
            restore(before);
            LOGGER.error("Could not commit durable cross-chunk hopper transaction in world " + world, failure);
            return false;
        }
    }

    private static void collectInventories(Container container, Map<Object, InventoryState> inventories) {
        if (container instanceof CompoundContainer compoundContainer) {
            collectInventories(compoundContainer.container1, inventories);
            collectInventories(compoundContainer.container2, inventories);
            return;
        }
        if (container instanceof BlockEntity blockEntity) {
            inventories.putIfAbsent(blockEntity, captureInventory(blockEntity, container, "region", blockEntity.getBlockPos().getX() >> 4,
                    blockEntity.getBlockPos().getZ() >> 4, ""));
        } else if (container instanceof Entity entity) {
            var pos = entity.chunkPosition();
            inventories.putIfAbsent(entity, captureInventory(entity, container, "entities", pos.x(), pos.z(), entity.getUUID().toString()));
        }
    }

    private static InventoryState captureInventory(Object owner, Container container, String path, int chunkX, int chunkZ, String identity) {
        List<ItemStack> stacks = new ArrayList<>(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) stacks.add(container.getItem(slot).copy());
        CompoundTag tag = owner instanceof BlockEntity blockEntity
                ? blockEntity.saveWithFullMetadata(blockEntity.getLevel().registryAccess()) : saveEntity((Entity) owner);
        return new InventoryState(container, stacks, tag, path, chunkX, chunkZ, identity);
    }

    private static CompoundTag saveEntity(Entity entity) {
        net.minecraft.world.level.storage.TagValueOutput output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, entity.registryAccess());
        entity.save(output);
        return output.buildResult();
    }

    private static byte[] serializeBlockEntity(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            tag.write(output);
        }
        return bytes.toByteArray();
    }

    public static void restore(TransferSnapshot snapshot) {
        for (Map.Entry<Object, InventoryState> inventory : snapshot.inventories.entrySet()) {
            Object owner = inventory.getKey();
            Container container = inventory.getValue().container;
            List<ItemStack> stacks = inventory.getValue().stacks;
            for (int slot = 0; slot < Math.min(container.getContainerSize(), stacks.size()); slot++) {
                container.setItem(slot, stacks.get(slot).copy());
            }
            if (owner instanceof BlockEntity block) block.setChanged();
        }
    }

    private record InventoryState(Container container, List<ItemStack> stacks, CompoundTag beforeTag, String path,
                                  int chunkX, int chunkZ, String identity) { }
    private record StorageChunkKey(String path, int chunkX, int chunkZ) { }

    public static boolean addItemToSlot(Container destination, int slot, ItemStack addition) {
        if (slot < 0 || slot >= destination.getContainerSize() || addition.isEmpty() || !destination.canPlaceItem(slot, addition)) {
            return false;
        }

        ItemStack current = destination.getItem(slot);
        if (current.isEmpty()) {
            if (addition.getCount() > Math.min(addition.getMaxStackSize(), destination.getMaxStackSize())) {
                return false;
            }
            destination.setItem(slot, addition.copy());
            return true;
        }

        if (!ItemStack.isSameItemSameComponents(current, addition)) {
            return false;
        }

        int maxStackSize = Math.min(current.getMaxStackSize(), destination.getMaxStackSize());
        if (addition.getCount() > maxStackSize - current.getCount()) {
            return false;
        }

        ItemStack updated = current.copy();
        updated.grow(addition.getCount());
        destination.setItem(slot, updated);
        return true;
    }

    public static void addItemOrDrop(Container destination, int slot, ItemStack addition, Consumer<ItemStack> dropRemainder) {
        if (destination == null) {
            dropRemainder.accept(addition.copy());
            return;
        }

        if (slot == -1) {
            ItemStack leftover = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(
                    null, destination, addition.copy(), net.minecraft.core.Direction.NORTH
            );
            if (!leftover.isEmpty()) {
                dropRemainder.accept(leftover);
            }
        } else if (!addItemToSlot(destination, slot, addition)) {
            dropRemainder.accept(addition.copy());
        }
    }

    public static BlockEntity getPrimaryChest(CompoundContainer compoundContainer) {
        BlockEntity chest1 = (BlockEntity) compoundContainer.container1;
        BlockEntity chest2 = (BlockEntity) compoundContainer.container2;

        if (chest1.getBlockPos().getX() > chest2.getBlockPos().getX()) {
            return chest1;
        } else if (chest2.getBlockPos().getX() > chest1.getBlockPos().getX()) {
            return chest2;
        } else if (chest1.getBlockPos().getZ() > chest2.getBlockPos().getZ()) {
            return chest1;
        } else {
            return chest2;
        }
    }

    public static ExternalTransferResult increaseItemExternal(Container from, Container to) {
        ChunkKey localWitness = getLocalContainerChunkKey(from);
        if (to instanceof Entity entity) {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(entity);
            if (MultiPaper.isChunkExternal(newChunkHolder)) {
                if (localWitness != null) {
                    requestOwnership(List.of(new ChunkKey(newChunkHolder.world.getWorld().getName(), newChunkHolder.chunkX, newChunkHolder.chunkZ)), localWitness);
                }
                return ExternalTransferResult.FAILED;
            } else {
                return ExternalTransferResult.LOCAL;
            }
        }

        if (to instanceof ComposterBlock.InputContainer || to instanceof ComposterBlock.OutputContainer || to instanceof ComposterBlock.EmptyContainer) {
            // It doesn't really matter if we modify composters across servers (which might even be impossible anyways)
            return ExternalTransferResult.LOCAL;
        }

        List<BlockEntity> blocks = new ArrayList<>();
        if (to instanceof CompoundContainer compoundContainer) {
            blocks.add((BlockEntity) compoundContainer.container1);
            blocks.add((BlockEntity) compoundContainer.container2);
        } else {
            blocks.add((BlockEntity) to);
        }

        List<ChunkKey> externalChunks = new ArrayList<>();
        for (BlockEntity block : blocks) {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder((ServerLevel) block.getLevel(), block.getBlockPos());
            if (MultiPaper.isChunkExternal(newChunkHolder)) {
                externalChunks.add(new ChunkKey(newChunkHolder.world.getWorld().getName(), newChunkHolder.chunkX, newChunkHolder.chunkZ));
            }
        }

        if (externalChunks.isEmpty()) {
            return ExternalTransferResult.LOCAL;
        }

        if (localWitness != null) {
            requestOwnership(externalChunks, localWitness);
        }
        return ExternalTransferResult.FAILED;
    }

    private static ChunkKey getLocalContainerChunkKey(Container container) {
        if (container instanceof Entity entity) {
            NewChunkHolder holder = MultiPaper.getChunkHolder(entity);
            return MultiPaper.isChunkLocal(holder) ? new ChunkKey(holder.world.getWorld().getName(), holder.chunkX, holder.chunkZ) : null;
        }
        if (container instanceof CompoundContainer compoundContainer) {
            BlockEntity blockEntity = getPrimaryChest(compoundContainer);
            NewChunkHolder holder = MultiPaper.getChunkHolder((ServerLevel) blockEntity.getLevel(), blockEntity.getBlockPos());
            return MultiPaper.isChunkLocal(holder) ? new ChunkKey(holder.world.getWorld().getName(), holder.chunkX, holder.chunkZ) : null;
        }
        if (container instanceof BlockEntity blockEntity) {
            NewChunkHolder holder = MultiPaper.getChunkHolder((ServerLevel) blockEntity.getLevel(), blockEntity.getBlockPos());
            return MultiPaper.isChunkLocal(holder) ? new ChunkKey(holder.world.getWorld().getName(), holder.chunkX, holder.chunkZ) : null;
        }
        return null;
    }

    private static void requestOwnership(List<ChunkKey> chunks, ChunkKey localWitness) {
        long now = System.currentTimeMillis();
        List<ChunkKey> request = new ArrayList<>();
        for (ChunkKey chunk : new HashSet<>(chunks)) {
            if (now < ownershipRequestRetryAfter.getOrDefault(chunk, 0L)) {
                continue;
            }
            if (ownershipRequests.add(chunk)) {
                request.add(chunk);
            }
        }
        if (request.isEmpty()) {
            return;
        }

        List<ChunkKey> requestWithWitness = new ArrayList<>(request);
        if (!requestWithWitness.contains(localWitness)) {
            requestWithWitness.add(localWitness);
        }
        String world = localWitness.world;
        MultiPaper.getConnection().trySendAndAwaitReply(
                new RequestChunkOwnershipMessage(world, requestWithWitness.toArray(ChunkKey[]::new)), BooleanMessageReply.class
        ).whenComplete((reply, error) -> {
            if (error != null || !reply.result) {
                for (ChunkKey chunk : request) {
                    ownershipRequests.remove(chunk);
                    ownershipRequestRetryAfter.put(chunk, System.currentTimeMillis() + 1000);
                }
                if (error != null) {
                    LOGGER.warn("Could not request local ownership of container chunks " + request, error);
                } else {
                    LOGGER.warn("Master denied local ownership of container chunks " + request);
                }
                return;
            }

            for (ChunkKey chunk : request) {
                ownershipRequestRetryAfter.remove(chunk);
                CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> ownershipRequests.remove(chunk));
            }
        });
    }

    public static void onChunkOwnershipChanged(String world, int chunkX, int chunkZ, boolean isLocalOwner) {
        ChunkKey key = new ChunkKey(world, chunkX, chunkZ);
        if (isLocalOwner) {
            ownershipRequests.remove(key);
            ownershipRequestRetryAfter.remove(key);
        }
    }
}
