package puregero.multipaper.externalserverprotocol;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperContainerHandler;

import java.io.IOException;
import java.util.UUID;

public class AddItemToEntityContainerPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(AddItemToEntityContainerPacket.class.getSimpleName());

    private final String world;
    private final UUID uuid;
    private final int slot;
    private final ItemStack itemStack;

    public AddItemToEntityContainerPacket(Entity entity, int slot, ItemStack itemStack) {
        this.world = ((ServerLevel) entity.level()).getWorld().getName();
        this.uuid = entity.getUUID();
        this.slot = slot;
        this.itemStack = itemStack;
    }

    public AddItemToEntityContainerPacket(FriendlyByteBuf in) {
        world = in.readUtf();
        uuid = in.readUUID();
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
        out.writeUUID(uuid);
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
            Entity entity = level.getEntity(uuid);

            if (entity instanceof Container container) {
                if (MultiPaperContainerHandler.addItemToSlot(container, slot, itemStack)) {
                    container.setChanged();
                } else {
                    LOGGER.warn("Could not add " + itemStack + " to slot " + slot + " in entity container " + entity);
                }
            } else {
                LOGGER.warn("Tried to set a " + itemStack + " in slot " + slot + " in a non-existent entity in " + world + " with uuid " + uuid + ": " + entity);
            }
        });
    }
}
