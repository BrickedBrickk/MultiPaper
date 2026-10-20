package puregero.multipaper;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import puregero.multipaper.externalserverprotocol.AddItemToContainerPacket;
import puregero.multipaper.externalserverprotocol.AddItemToEntityContainerPacket;

public class MultiPaperContainerHandler {
    public enum ExternalTransferResult {
        LOCAL,
        SENT,
        FAILED
    }

    public static ItemStack rollbackFailedExternalTransfer(Container destination, int slot, ItemStack originalDestination, ItemStack originalSource) {
        destination.setItem(slot, originalDestination.copy());
        return originalSource.copy();
    }

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

    public static ExternalTransferResult increaseItemExternal(Container to, int slot, ItemStack stack, int count) {
        if (to instanceof Entity entity) {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(entity);
            if (MultiPaper.isChunkExternal(newChunkHolder)) {
                stack = stack.copy();
                stack.setCount(count);
                ExternalServerConnection connection = newChunkHolder.externalOwner.getConnection();
                return connection != null && connection.send(new AddItemToEntityContainerPacket(entity, slot, stack))
                        ? ExternalTransferResult.SENT : ExternalTransferResult.FAILED;
            } else {
                return ExternalTransferResult.LOCAL;
            }
        }

        if (to instanceof ComposterBlock.InputContainer || to instanceof ComposterBlock.OutputContainer || to instanceof ComposterBlock.EmptyContainer) {
            // It doesn't really matter if we modify composters across servers (which might even be impossible anyways)
            return ExternalTransferResult.LOCAL;
        }

        BlockEntity block;

        if (to instanceof CompoundContainer compoundContainer) {
            block = getPrimaryChest(compoundContainer);
        } else {
            block = (BlockEntity) to;
        }

        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder((ServerLevel) block.getLevel(), block.getBlockPos());
        if (MultiPaper.isChunkExternal(newChunkHolder)) {
            stack = stack.copy();
            stack.setCount(count);
            ExternalServerConnection connection = newChunkHolder.externalOwner.getConnection();
            return connection != null && connection.send(new AddItemToContainerPacket(block, slot, stack))
                    ? ExternalTransferResult.SENT : ExternalTransferResult.FAILED;
        } else {
            return ExternalTransferResult.LOCAL;
        }
    }
}
