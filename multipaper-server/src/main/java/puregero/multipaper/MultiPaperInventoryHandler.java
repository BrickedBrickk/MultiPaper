package puregero.multipaper;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.externalserverprotocol.PlayerActionPacket;
import puregero.multipaper.externalserverprotocol.PlayerInventoryUpdatePacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class MultiPaperInventoryHandler {

    private static final Logger LOGGER = LogManager.getLogger(MultiPaperInventoryHandler.class.getSimpleName());

    public static boolean updatingInventory = false;
    private static final HashMap<UUID, ArrayList<AwaitingTeleport>> awaitingTeleports = new HashMap<>();
    private static final Set<NonNullListFilter<? extends ItemStack>> modifiedInventories = new LinkedHashSet<>();

    public static void runWithInventoryUpdatesSuppressed(Runnable action) {
        boolean previousUpdatingInventory = updatingInventory;
        updatingInventory = true;
        try {
            action.run();
        } finally {
            updatingInventory = previousUpdatingInventory;
        }
    }

    public static boolean handlePacketFromExternalServer(ExternalServer server, ServerPlayer player, Packet<?> packet) {
        if (packet instanceof ClientboundOpenScreenPacket) {
            // An external server has requested to open a window on a player
            player.openContainer = server;
        } else if (packet instanceof ClientboundContainerClosePacket) {
            // An external server has requested to close the open window on a player
            if (player.openContainer == server) {
                player.openContainer = null;
            }
        } else if (packet instanceof ClientboundSetHeldSlotPacket setCarriedItemPacket) {
            // An external server is changing the selected item in the hotbar
            player.getInventory().setSelectedSlot(setCarriedItemPacket.slot());
        } else if (packet instanceof ClientboundSetExperiencePacket setExperiencePacket) {
            // An external server is changing the player's experience level
            player.experienceLevel = setExperiencePacket.getExperienceLevel();
            player.experienceProgress = setExperiencePacket.getExperienceProgress();
            player.totalExperience = setExperiencePacket.getTotalExperience();
        } else if (packet instanceof ClientboundPlayerPositionPacket playerPositionPacket) {
            // An external server is teleporting the player
            player.connection.teleport(playerPositionPacket.change(), playerPositionPacket.relatives());
            server.getConnection().send(new PlayerActionPacket(player, new ServerboundAcceptTeleportationPacket(playerPositionPacket.id(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot())));
            return true;
        }

        return false;
    }

    /**
     * Returns true if the even should be cancelled
     */
    public static boolean handleInteractEvent(ServerPlayer player, ServerboundUseItemOnPacket packet) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder((ServerLevel) player.level(), packet.hitResult().getBlockPos());
        ItemStack item = player.getItemInHand(packet.hand());
        if (MultiPaper.isChunkExternal(newChunkHolder) && !(item.getItem() instanceof BucketItem)) {
            newChunkHolder.externalOwner.getConnection().send(new PlayerActionPacket(player, packet));
            return true;
        }

        return false;
    }

    /**
     * Returns true if the even should be cancelled
     */
    public static boolean handleUseItemEvent(ServerPlayer player, ServerboundUseItemPacket packet) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder((ServerLevel) player.level(), player.blockPosition());
        ItemStack item = player.getItemInHand(packet.hand());
        if (MultiPaper.isChunkExternal(newChunkHolder) && item.getItem() instanceof EnderEyeItem) {
            newChunkHolder.externalOwner.getConnection().send(new PlayerActionPacket(player, packet));
            return true;
        } else if (MultiPaper.isRealPlayer(player) && item.getItem() instanceof FishingRodItem) {
            MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalSubscribers, () -> new PlayerActionPacket(player, packet));
        }

        return false;
    }

    /**
     * Returns true if the even should be cancelled
     */
    public static boolean handleContainerEvent(ServerPlayer player, Packet<ServerGamePacketListener> containerPacket) {
        if (player.openContainer != null) {
            player.openContainer.getConnection().send(new PlayerActionPacket(player, containerPacket));

            if (containerPacket instanceof ServerboundContainerClosePacket) {
                player.openContainer = null;
            }

            return true;
        }

        return false;
    }

    /**
     * TEMPORARY SAFETY GATE: InvSee-style edits to another backend's player
     * mirror are unsafe until the peer transaction/acknowledgement protocol is
     * implemented. Refuse the click before vanilla mutates either the mirror or
     * the editor's cursor/inventory. Replace this gate with that protocol; this
     * is not the RC5-02 transaction fix.
     */
    public static boolean rejectUnsafeRemotePlayerInventoryClick(ServerPlayer editor) {
        AbstractContainerMenu menu = editor.containerMenu;
        boolean containsRemotePlayerInventory = menu.slots.stream().anyMatch(slot ->
                slot.container instanceof Inventory inventory && isRemotePlayerInventoryOwner(inventory.items.player));
        if (!containsRemotePlayerInventory) {
            // InvSee++ 0.31.22's 26.3 main and ender menus use custom Containers
            // rather than player Inventory. Their Bukkit inventories implement the
            // public SpectatorInventory API and expose the spectated UUID. Do not rely
            // on concrete versioned NMS class names: the API marker remains stable.
            org.bukkit.inventory.Inventory topInventory = editor.getBukkitEntity().getOpenInventory().getTopInventory();
            containsRemotePlayerInventory = isRemoteInvSeeSpectatorInventory(topInventory, uuid -> {
                ServerPlayer target = net.minecraft.server.MinecraftServer.getServer().getPlayerList().getPlayer(uuid);
                // An unresolved target is unsafe too: offline InvSee snapshots are not
                // owned by this backend's live player state and must not be edited here.
                return target == null || target instanceof ExternalPlayer;
            });
        }
        if (!containsRemotePlayerInventory) {
            return false;
        }

        editor.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "Editing a player inventory owned by another backend is temporarily disabled for item safety."));
        menu.sendAllDataToRemote();
        return true;
    }

    static boolean isRemotePlayerInventoryOwner(Player inventoryOwner) {
        return inventoryOwner instanceof ExternalPlayer;
    }

    static boolean isRemoteInvSeeSpectatorInventory(Object inventory, java.util.function.Predicate<java.util.UUID> isRemoteOrUnresolvedTarget) {
        Class<?> marker = findInterface(inventory == null ? null : inventory.getClass(), "com.janboerman.invsee.spigot.api.SpectatorInventory");
        if (marker == null) return false;

        try {
            Object targetId = marker.getMethod("getSpectatedPlayerId").invoke(inventory);
            return !(targetId instanceof java.util.UUID uuid) || isRemoteOrUnresolvedTarget.test(uuid);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // If InvSee exposes its marker but its target cannot be resolved, fail closed.
            return true;
        }
    }

    private static Class<?> findInterface(Class<?> type, String name) {
        if (type == null) return null;
        for (Class<?> implemented : type.getInterfaces()) {
            if (implemented.getName().equals(name)) return implemented;
            Class<?> inherited = findInterface(implemented, name);
            if (inherited != null) return inherited;
        }
        return findInterface(type.getSuperclass(), name);
    }

    public static void updateInventory(ServerPlayer player, String name, int slot, ItemStack replacingItem, ItemStack item) {
        runWithInventoryUpdatesSuppressed(() -> updateInventoryWithoutMarkingDirty(player, name, slot, replacingItem, item));
    }

    private static void updateInventoryWithoutMarkingDirty(ServerPlayer player, String name, int slot, ItemStack replacingItem, ItemStack item) {
        NonNullListFilter<ItemStack> component = null;
        switch (name) {
            case "items" -> component = player.getInventory().items;
            case "armor" -> component = player.getInventory().armor;
            case "offhand" -> component = player.getInventory().offhand;
            case "enderchest" -> MultiPaperEnderChestHandler.updateInventory(player, slot, item);
            default -> throw new IllegalArgumentException("Unknown inventory component of " + name);
        }

        item.dirty = false;

        if (component != null) {
            ItemStack lastItem = component.lastItems.get(slot);
            ItemStack currentItem = component.get(slot);

            if (!ItemStack.matches(lastItem, currentItem) && !MultiPaper.isRealPlayer(player)) {
                // Our changes haven't been sent yet, send them
                broadcastComponentChanges(player, component);
            }

            if (MultiPaper.isRealPlayer(player) && replacingItem != null && !ItemStack.matches(replacingItem, currentItem)) {
                // The expected item doesn't match, a merge is required
                item.dirty = true; component.isDirty = true; // Resend the item afterwards to sync the other servers
                if (isSameItemSameTagIgnoringDurability(replacingItem, item)) {
                    if (replacingItem.getCount() != item.getCount()) {
                        int countDiff = item.getCount() - replacingItem.getCount();
                        if (countDiff > 0) {
                            item.setCount(countDiff);
                            addItem(item, player);
                        } else {
                            LOGGER.warn(player.getScoreboardName() + ": An external server tried to remove " + -countDiff + " items from " + replacingItem + ", but that item is now a " + currentItem + ". Reconciling against the player's inventory...");
                            item.setCount(-countDiff);
                            removeFromPlayerOwnedItems(player, item);
                        }
                    } else if (replacingItem.getDamageValue() != item.getDamageValue()) {
                        int damageDiff = item.getDamageValue() - replacingItem.getDamageValue();
                        if (isSameItemSameTagIgnoringDurability(currentItem, item)) {
                            currentItem.setDamageValue(currentItem.getDamageValue() + damageDiff);
                        }
                    } else {
                        LOGGER.warn(player.getScoreboardName() + ": Trying to merge the same item same tags, but neither the count nor the durability is different. " + item + " and " + replacingItem + " and " + currentItem);
                    }
                } else {
                    if (!replacingItem.isEmpty()) {
                        if (!hasPlayerOwnedItems(replacingItem, player.containerMenu.getCarried(), player.getInventory().compartments)) {
                            LOGGER.warn("{}: Ignoring stale inventory replacement {} -> {}; the source item is no longer in player-owned inventory. Re-syncing the authoritative slot.",
                                    player.getScoreboardName(), replacingItem, item);
                            markInventorySlotForSync(component, slot);
                            return;
                        }

                        LOGGER.info(player.getScoreboardName() + ": An external server tried to replace " + replacingItem + " with a " + item + ", but that item is now a " + currentItem + ". Reconciling against the player's inventory...");
                        if (!removeFromPlayerOwnedItems(player, replacingItem.copy())) {
                            // The synchronous preflight above should make this unreachable. Fail closed
                            // if a custom inventory implementation changes during reconciliation.
                            LOGGER.error("{}: Could not atomically remove stale replacement source {}; ignoring replacement {}.",
                                    player.getScoreboardName(), replacingItem, item);
                            markInventorySlotForSync(component, slot);
                            return;
                        }
                    }
                    addItem(item, player);
                }
            } else {
                component.set(slot, item);
                component.lastItems.set(slot, item.copy()); // We don't need to do this if it's our player
            }
        }

    }

    private static void addItem(ItemStack itemStack, ServerPlayer player) {
        if (player.isDeadOrDying() || !player.getInventory().add(itemStack)) {
            player.drop(itemStack, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
    }

    private static boolean removeFromPlayerOwnedItems(ServerPlayer player, ItemStack itemToRemove) {
        // Only compensate in state owned by this player. Scanning every slot in
        // containerMenu can delete legitimate items from a shared chest, and
        // scanning nearby item entities can delete another player's drops.
        if (!hasPlayerOwnedItems(itemToRemove, player.containerMenu.getCarried(), player.getInventory().compartments)) {
            LOGGER.warn("{}: Could not reconcile {} in player-owned inventory; leaving container contents and world item entities untouched.", player.getScoreboardName(), itemToRemove);
            return false;
        }

        removeItems(itemToRemove, player.containerMenu::getCarried, (item, count) -> {
            LOGGER.info("{}: Removing {}x of {} from cursor", player.getScoreboardName(), count, itemToRemove);
            player.containerMenu.setCarried(item);
        });

        if (!itemToRemove.isEmpty()) {
            removeFromInventory(player.getScoreboardName(), itemToRemove, player.getInventory().compartments);
        }

        if (!itemToRemove.isEmpty()) {
            LOGGER.error("{}: Player-owned item preflight passed but {} remained during synchronous removal.", player.getScoreboardName(), itemToRemove);
            return false;
        }
        return true;
    }

    static boolean hasPlayerOwnedItems(ItemStack itemToRemove, ItemStack carried, List<? extends List<ItemStack>> compartments) {
        if (itemToRemove.isEmpty()) return true;
        int available = 0;
        if (ItemStack.isSameItemSameComponents(carried, itemToRemove)) {
            available += carried.getCount();
        }
        for (List<ItemStack> compartment : compartments) {
            for (ItemStack stack : compartment) {
                if (ItemStack.isSameItemSameComponents(stack, itemToRemove)) {
                    available += stack.getCount();
                    if (available >= itemToRemove.getCount()) return true;
                }
            }
        }
        return available >= itemToRemove.getCount();
    }

    private static void markInventorySlotForSync(NonNullListFilter<ItemStack> component, int slot) {
        component.dirty[slot] = true;
        component.isDirty = true;
        modifiedInventories.add(component);
    }

    static void removeFromInventory(String playerName, ItemStack itemToRemove, List<NonNullListFilter<ItemStack>> compartments) {
        for (List<ItemStack> compartment : compartments) {
            for (int slot = 0; slot < compartment.size(); slot++) {
                final int finalSlot = slot;
                removeItems(itemToRemove, () -> compartment.get(finalSlot), (item, count) -> {
                    LOGGER.info("{}: Removing {}x of {} from inventory", playerName, count, itemToRemove);
                    compartment.set(finalSlot, item);
                });
            }
        }
    }

    private static void removeItems(ItemStack itemToRemove, Supplier<ItemStack> getter, BiConsumer<ItemStack, Integer> setter) {
        if (itemToRemove.isEmpty()) return;

        ItemStack item = getter.get();
        if (ItemStack.isSameItemSameComponents(item, itemToRemove)) {
            int itemCount = item.getCount();
            if (item.getCount() < itemToRemove.getCount()) {
                item.setCount(0);
                setter.accept(ItemStack.EMPTY, itemCount);
                itemToRemove.setCount(itemToRemove.getCount() - itemCount);
            } else {
                item.setCount(itemCount - itemToRemove.getCount());
                setter.accept(item, itemToRemove.getCount());
                itemToRemove.setCount(0);
            }
        }
    }

    private static boolean isSameItemSameTagIgnoringDurability(ItemStack left, ItemStack right) {
        boolean hasLeftDurability = false;
        boolean hasRightDurability = false;
        int leftDurability = left.getDamageValue();
        int rightDurability = right.getDamageValue();

        ItemStack leftCopy = left.copy();
        ItemStack rightCopy = right.copy();
        leftCopy.setDamageValue(0);
        rightCopy.setDamageValue(0);
        return ItemStack.isSameItemSameComponents(leftCopy, rightCopy);
    }

    /**
     * Returns true if the changes to the inventory component should be marked as dirty.
     */
    public static <E extends ItemStack> boolean markDirty(NonNullListFilter<E> inventoryComponent) {
        // Standalone inventories (for example, the inventory math tests) are not
        // mirrored between servers and have no player tick to flush their changes.
        if (!(inventoryComponent.player instanceof ServerPlayer)) {
            return false;
        }

        if ( !ca.spottedleaf.moonrise.common.util.TickThread.isTickThread() ) {
            LOGGER.warn("Asynchronous inventory modification. This is unsafe and will eventually cause an issue.", new IllegalStateException("Async access"));
        }

        if (!updatingInventory) {
            modifiedInventories.add(inventoryComponent);
            return true;
        }

        return false;
    }

    /**
     * Runs at the end of a vanilla tick. Ie any changes to the inventory made in the tick will instantly be updated
     * to other servers without a tick delay.
     */
    public static void tick() {
        for (NonNullListFilter<? extends ItemStack> inventoryComponent : modifiedInventories) {
            if (inventoryComponent.player instanceof ServerPlayer player) {
                broadcastComponentChanges(player, inventoryComponent);
            }
        }
        modifiedInventories.clear();
    }

    public static void broadcastComponentChanges(ServerPlayer player, NonNullListFilter<? extends ItemStack> inventoryComponent) {
        if (inventoryComponent.isDirty) {
            inventoryComponent.isDirty = false;
            ListTag items = new ListTag();
            for (int i = 0; i < inventoryComponent.size(); i++) {
                if (inventoryComponent.dirty[i] || inventoryComponent.get(i).dirty) {
                    CompoundTag item = MultiPaper.itemStackToNBT(inventoryComponent.get(i));
                    item.putInt("Slot", i);
                    items.add(item);

                    if (!MultiPaper.isRealPlayer(player)) {
                        CompoundTag replacingItem = MultiPaper.itemStackToNBT(inventoryComponent.lastItems.get(i));
                        item.put("Replacing", replacingItem);
                    }

                    inventoryComponent.dirty[i] = false;
                    inventoryComponent.get(i).dirty = false;
                    inventoryComponent.lastItems.set(i, inventoryComponent.get(i).copy());
                }
            }

            if (!items.isEmpty()) {
                CompoundTag itemsRoot = new CompoundTag();
                itemsRoot.put("items", items);
                MultiPaper.broadcastPacketToExternalServers(player, new PlayerInventoryUpdatePacket(player, inventoryComponent.name, itemsRoot));
                player.detectEquipmentUpdates();
            }
        }
    }

    public static void sendFullInventoryUpdate(ServerPlayer player, ExternalServerConnection... connections) {
        for (NonNullListFilter<? extends ItemStack> inventoryComponent : new NonNullListFilter[] {
                player.getInventory().items,
                player.getInventory().armor,
                player.getInventory().offhand,
        }) {
            ListTag items = new ListTag();
            for (int i = 0; i < inventoryComponent.size(); i++) {
                CompoundTag item = MultiPaper.itemStackToNBT(inventoryComponent.get(i));
                item.putInt("Slot", i);
                items.add(item);
            }

            CompoundTag itemsRoot = new CompoundTag();
            itemsRoot.put("items", items);
            for (ExternalServerConnection connection : connections) {
                connection.send(new PlayerInventoryUpdatePacket((ServerPlayer) inventoryComponent.player, inventoryComponent.name, itemsRoot));
            }
        }
    }

    public static void handleAcceptTeleport(ServerPlayer player, ServerboundAcceptTeleportationPacket packet) {
        ArrayList<AwaitingTeleport> accepts = awaitingTeleports.get(player.getUUID());

        if (accepts != null) {
            accepts.removeIf(accept -> {
               if (accept.id == packet.id()) {
                   accept.externalServer.getConnection().send(new PlayerActionPacket(player, packet));
                   return true;
               } else {
                   return false;
               }
            });

            if (accepts.isEmpty()) {
                awaitingTeleports.remove(player.getUUID());
            }
        }
    }

    private static class AwaitingTeleport {
        private final ExternalServer externalServer;
        private final int id;

        private AwaitingTeleport(ExternalServer externalServer, int id) {
            this.externalServer = externalServer;
            this.id = id;
        }
    }
}
