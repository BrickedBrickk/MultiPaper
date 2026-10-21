package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperContainerHandler;

import java.io.IOException;

public class AddItemToContainerPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(AddItemToContainerPacket.class.getSimpleName());

    private final String world;
    private final BlockPos pos;
    private final int slot;
    private final ItemStack itemStack;

    public AddItemToContainerPacket(BlockEntity blockEntity, int slot, ItemStack itemStack) {
        this((ServerLevel) blockEntity.getLevel(), blockEntity.getBlockPos(), slot, itemStack);
    }

    public AddItemToContainerPacket(ServerLevel serverLevel, BlockPos blockPos, int slot, ItemStack itemStack) {
        this.world = serverLevel.getWorld().getName();
        this.pos = blockPos;
        this.slot = slot;
        this.itemStack = itemStack;
    }

    public AddItemToContainerPacket(FriendlyByteBuf in) {
        world = in.readUtf();
        pos = BlockPos.of(in.readLong());
        slot = in.readByte();

        try {
            itemStack = MultiPaper.itemStackFromNBT(MultiPaper.nbtFromBytes(in.readByteArray()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeLong(pos.asLong());
        out.writeByte(slot);

        try {
            out.writeByteArray(MultiPaper.nbtToBytes(MultiPaper.itemStackToNBT(itemStack)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerLevel level = ((CraftWorld) Bukkit.getWorld(world)).getHandle();
            Container container = HopperBlockEntity.getContainerAt(level, pos);

            if (container == null) {
                LOGGER.warn("Destination container is missing for external item transfer: " + itemStack + " at " + world + " " + pos + "; dropping the items to preserve them");
            }

            MultiPaperContainerHandler.addItemOrDrop(container, slot, itemStack, remainder -> {
                if (container != null) {
                    container.setChanged();
                    LOGGER.warn("Destination container rejected part of an external item transfer: " + remainder + " at " + world + " " + pos + "; dropping the remainder");
                }
                ItemEntity item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, remainder);
                if (!level.addFreshEntity(item)) {
                    LOGGER.error("Could not spawn recovery item entity for rejected external transfer " + remainder + " at " + world + " " + pos);
                }
            });

            if (container != null) {
                container.setChanged();
            }
        });
    }
}
