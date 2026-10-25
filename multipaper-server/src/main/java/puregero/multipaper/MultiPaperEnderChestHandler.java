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

import java.util.Arrays;

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
        this.onContainerChanged(false);
    }

    private void onContainerChanged(boolean forceBroadcast) {
        Container container = this.player.getEnderChestInventory();
        if (container.getContainerSize() != sentItems.length) {
            sentItems = new ItemStack[container.getContainerSize()];
            Arrays.fill(sentItems, ItemStack.EMPTY);
        }

        if (broadcastChanges && !forceBroadcast && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
            return;
        }

        CompoundTag itemsRoot = new CompoundTag();
        ListTag items = new ListTag();
        for (int i = 0; i < sentItems.length; i++) {
            ItemStack item = container.getItem(i);
            if (!ItemStack.matches(item, sentItems[i])) {
                sentItems[i] = item.copy();

                if (broadcastChanges) {
                    CompoundTag itemToSend = MultiPaper.itemStackToNBT(item);
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

    public void containerChanged() {
        if (MultiPaperInventoryHandler.updatingInventory) {
            return;
        }
        this.onContainerChanged(true);
    }

    public void initialize() {
        Container container = this.player.getEnderChestInventory();
        sentItems = new ItemStack[container.getContainerSize()];
        for (int i = 0; i < sentItems.length; i++) {
            sentItems[i] = container.getItem(i).copy();
        }
    }

    public void tick() {
        if (MultiPaperInventoryHandler.updatingInventory) {
            return;
        }
        this.onContainerChanged(true);
    }

    public static void sendFullEnderChestUpdate(ServerPlayer player, ExternalServerConnection... connections) {
        CompoundTag itemsRoot = new CompoundTag();
        ListTag items = new ListTag();
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            ItemStack item = player.getEnderChestInventory().getItem(i);
            CompoundTag itemToSend = MultiPaper.itemStackToNBT(item);
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
        if (player.enderChestHandler != null) {
            player.enderChestHandler.initialize();
        }
    }
}
