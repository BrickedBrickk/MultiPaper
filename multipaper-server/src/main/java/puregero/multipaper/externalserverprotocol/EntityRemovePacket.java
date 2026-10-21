package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.Visibility;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.scheduler.CraftScheduler;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperEntitiesHandler;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class EntityRemovePacket extends ExternalServerPacket {
    private static final ExpiringEntityRemovals removedEntities = new ExpiringEntityRemovals();

    private final String world;
    private final UUID uuid;
    private final boolean unloadedWithPlayer;

    public EntityRemovePacket(Entity entity, boolean unloadedWithPlayer) {
        this(((ServerLevel) entity.level()).getWorld().getName(), entity.getUUID(), unloadedWithPlayer);
    }

    public EntityRemovePacket(String world, UUID uuid, boolean unloadedWithPlayer) {
        this.world = world;
        this.uuid = uuid;
        this.unloadedWithPlayer = unloadedWithPlayer;
    }

    public EntityRemovePacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.uuid = in.readUUID();
        this.unloadedWithPlayer = in.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeUUID(uuid);
        out.writeBoolean(this.unloadedWithPlayer);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(this::removeEntity);
    }

    private void removeEntity() {
        ServerLevel level = ((CraftWorld) Bukkit.getWorld(world)).getHandle();

        Entity entity = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup().getRawEntity(uuid);
        Entity.RemovalReason reason = entity instanceof Player ? Entity.RemovalReason.KILLED : Entity.RemovalReason.DISCARDED;
        if (this.unloadedWithPlayer) reason = Entity.RemovalReason.UNLOADED_WITH_PLAYER;

        // Reject any queued entity updates that arrive after this removal packet.
        if (reason != Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            setEntityRemoved(uuid, reason);
        }

        if (entity != null) {
            MultiPaperEntitiesHandler.removingEntity = true;
            try {
                entity.setRemoved(reason);
            } finally {
                MultiPaperEntitiesHandler.removingEntity = false;
            }
        }
    }

    public static void setEntityRemoved(UUID uuid, Entity.RemovalReason reason) {
        setEntityRemoved(uuid, reason, 300);
    }

    public static void setEntityRemoved(UUID uuid, Entity.RemovalReason reason, int durationInTicks) {
        if (reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) return;
        ExpiringEntityRemovals.Marker marker = removedEntities.markRemoved(uuid, reason);
        java.util.concurrent.CompletableFuture.delayedExecutor(durationInTicks * 50L, TimeUnit.MILLISECONDS)
            .execute(() -> removedEntities.expire(uuid, marker));
    }

    public static Entity.RemovalReason getRemovalReason(UUID uuid) {
        return removedEntities.getReason(uuid);
    }

    public static boolean hasRemovalMarker(UUID uuid) {
        return removedEntities.contains(uuid);
    }
}
