package puregero.multipaper.externalserverprotocol;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperInventoryHandler;
import puregero.multipaper.PlayerSyncSession;

import java.io.IOException;
import java.util.UUID;

public class PlayerInventoryUpdatePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerInventoryUpdatePacket.class.getSimpleName());

    private final UUID uuid;
    private final UUID playerSyncSessionId;
    private final String playerOwnerName;
    private final String component;
    private final CompoundTag tag;

    public PlayerInventoryUpdatePacket(ServerPlayer player, String component, CompoundTag tag) {
        this(player.getUUID(), PlayerSyncSession.getSessionId(player), PlayerSyncSession.getOwnerName(player), component, tag);
    }

    PlayerInventoryUpdatePacket(UUID uuid, UUID playerSyncSessionId, String playerOwnerName, String component, CompoundTag tag) {
        this.uuid = uuid;
        this.playerSyncSessionId = playerSyncSessionId;
        this.playerOwnerName = playerOwnerName;
        this.component = component;
        this.tag = tag;
    }

    public PlayerInventoryUpdatePacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        playerSyncSessionId = in.readUUID();
        playerOwnerName = in.readUtf();
        component = in.readUtf();

        try {
            tag = MultiPaper.nbtFromBytes(in.readByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeUUID(playerSyncSessionId);
        out.writeUtf(playerOwnerName);
        out.writeUtf(component);

        try {
            out.writeByteArray(MultiPaper.nbtToBytes(tag));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to update the inventory of a non-existent player uuid " + uuid);
                return;
            }

            if (!PlayerSyncSession.matches(PlayerSyncSession.getSessionId(player), playerSyncSessionId,
                    PlayerSyncSession.getOwnerName(player), playerOwnerName)) {
                LOGGER.debug("Ignoring stale inventory update for player {} from another player session or owner", uuid);
                return;
            }

            if (!isUpdateFromPlayerOwner(component, connection.externalServer == null ? null : connection.externalServer.getName(), playerOwnerName)) {
                LOGGER.warn("Ignoring {} inventory update for player {} from non-owner backend {} (owner {})",
                        component, uuid, connection.externalServer == null ? "unknown" : connection.externalServer.getName(), playerOwnerName);
                return;
            }

            ListTag items = tag.getListOrEmpty("items");
            if ("enderchest".equals(component)) {
                if (items.isEmpty()) return;
                // Validate the complete delta before advancing its revision so a
                // malformed slot or stack cannot consume the sequence and leave
                // this mirror permanently behind the owner.
                int size = player.getEnderChestInventory().getContainerSize();
                for (Tag entry : items) {
                    if (!(entry instanceof CompoundTag item)) return;
                    int slot = item.getIntOr("Slot", 0);
                    if (slot < 0 || slot >= size) return;
                    MultiPaper.itemStackFromNBT(item);
                    item.getCompound("Replacing").ifPresent(MultiPaper::itemStackFromNBT);
                }
                if (!(player instanceof puregero.multipaper.ExternalPlayer externalPlayer)) {
                    LOGGER.warn("Ignoring remote Ender Chest update for non-mirror player {}", uuid);
                    return;
                }
                long revision = tag.getLongOr("revision", -1L);
                if (!externalPlayer.acceptEnderChestSyncRevision(revision)) {
                    LOGGER.debug("Ignoring stale or unversioned Ender Chest update revision {} for player {}",
                            revision, uuid);
                    return;
                }
            }

            items.forEach(i -> {
                CompoundTag item = (CompoundTag) i;
                ItemStack itemStack = MultiPaper.itemStackFromNBT(item);

                ItemStack replacingItem = item.getCompound("Replacing").map(MultiPaper::itemStackFromNBT).orElse(null);

                MultiPaperInventoryHandler.updateInventory(player, component, item.getIntOr("Slot", 0), replacingItem, itemStack);
            });

            player.detectEquipmentUpdates();
        });
    }

    UUID getPlayerSyncSessionId() {
        return playerSyncSessionId;
    }

    String getPlayerOwnerName() {
        return playerOwnerName;
    }

    static boolean isUpdateFromPlayerOwner(String component, String sourceBackendName, String ownerBackendName) {
        return !"enderchest".equals(component)
                || sourceBackendName != null && sourceBackendName.equals(ownerBackendName);
    }
}
