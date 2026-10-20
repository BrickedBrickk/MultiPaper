package puregero.multipaper;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import puregero.multipaper.externalserverprotocol.PlayerInventoryUpdatePacket;

public class MultiPaperEnderChestHandler implements ContainerListener {

    private static boolean broadcastChanges = true;

    private final ServerPlayer player;
    private ItemStack[] sentItems = new ItemStack[0];

    public MultiPaperEnderChestHandler(ServerPlayer player) {
        this.player = player;
    }

    @Override
    public void slotChanged(AbstractContainerMenu container, int slotIndex, ItemStack itemStack) {
        if (slotIndex < this.player.getEnderChestInventory().getContainerSize()) {
            this.onContainerChanged();
        }
    }

    @Override
    public void dataChanged(AbstractContainerMenu container, int id, int value) {
        // not used
    }

    private void onContainerChanged() {
        Container container = this.player.getEnderChestInventory();
        if (container.getContainerSize() != sentItems.length) {
            sentItems = new ItemStack[container.getContainerSize()];
        }

        if (broadcastChanges && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
            return;
        }

        CompoundTag itemsRoot = new CompoundTag();
        ListTag items = new ListTag();
        for (int i = 0; i < sentItems.length; i++) {
            ItemStack item = container.getItem(i);
            if (!item.equals(sentItems[i])) {
                sentItems[i] = item.copy();

                if (broadcastChanges) {
                    CompoundTag itemToSend = (CompoundTag) net.minecraft.world.item.ItemStack.CODEC.encodeStart(
                        org.bukkit.craftbukkit.CraftRegistry.getMinecraftRegistry().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), item
                    ).getOrThrow();
                    itemToSend.putInt("Slot", i);
                    items.add(itemToSend);
                }
            }
        }

        if (!items.isEmpty()) {
            itemsRoot.put("items", items);
            MultiPaper.broadcastPacketToExternalServers(new PlayerInventoryUpdatePacket(player, "enderchest", itemsRoot));
        }
    }

    public static void sendFullEnderChestUpdate(ServerPlayer player, ExternalServerConnection... connections) {
        CompoundTag itemsRoot = new CompoundTag();
        ListTag items = new ListTag();
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            ItemStack item = player.getEnderChestInventory().getItem(i);
            CompoundTag itemToSend = (CompoundTag) net.minecraft.world.item.ItemStack.CODEC.encodeStart(
                org.bukkit.craftbukkit.CraftRegistry.getMinecraftRegistry().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), item
            ).getOrThrow();
            itemToSend.putInt("Slot", i);
            items.add(itemToSend);
        }

        itemsRoot.put("items", items);
        for (ExternalServerConnection connection : connections) {
            connection.send(new PlayerInventoryUpdatePacket(player, "enderchest", itemsRoot));
        }
    }

    public static void updateInventory(ServerPlayer player, int slot, ItemStack item) {
        boolean previousBroadcastChanges = broadcastChanges;
        broadcastChanges = false;
        try {
            player.getEnderChestInventory().setItem(slot, item);
        } finally {
            broadcastChanges = previousBroadcastChanges;
        }
    }
}
