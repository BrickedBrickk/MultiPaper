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
    private long syncRevision;

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
        long revision = -1;
        for (int i = 0; i < sentItems.length; i++) {
            ItemStack item = container.getItem(i);
            if (!ItemStack.matches(item, sentItems[i])) {
                ItemStack replacingItem = sentItems[i].copy();
                sentItems[i] = item.copy();

                if (broadcastChanges) {
                    if (revision < 0) revision = nextSyncRevision();
                    CompoundTag itemToSend = MultiPaper.itemStackToNBT(item);
                    itemToSend.putInt("Slot", i);
                    itemToSend.put("Replacing", MultiPaper.itemStackToNBT(replacingItem));
                    items.add(itemToSend);
                }
            }
        }

        if (!items.isEmpty()) {
            itemsRoot.put("items", items);
            itemsRoot.putLong("revision", revision);
            MultiPaper.broadcastPacketToExternalServers(new PlayerInventoryUpdatePacket(player, "enderchest", itemsRoot));
        }
    }

    private synchronized long nextSyncRevision() {
        if (syncRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("Ender Chest sync revision exhausted for " + player.getUUID());
        }
        return ++syncRevision;
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
        long revision = player.enderChestHandler == null ? 0 : player.enderChestHandler.nextSyncRevision();
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            ItemStack item = player.getEnderChestInventory().getItem(i);
            CompoundTag itemToSend = MultiPaper.itemStackToNBT(item);
            itemToSend.putInt("Slot", i);
            items.add(itemToSend);
        }

        itemsRoot.put("items", items);
        itemsRoot.putLong("revision", revision);
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
