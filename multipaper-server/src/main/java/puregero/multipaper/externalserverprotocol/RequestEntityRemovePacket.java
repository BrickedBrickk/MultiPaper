package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperEntitiesHandler;

import java.util.UUID;

public final class RequestEntityRemovePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(RequestEntityRemovePacket.class);

    private final String world;
    private final UUID uuid;
    private final int chunkX;
    private final int chunkZ;
    private final boolean unloadedWithPlayer;

    public RequestEntityRemovePacket(Entity entity, boolean unloadedWithPlayer) {
        this(((ServerLevel) entity.level()).getWorld().getName(), entity.getUUID(), entity.chunkPosition().x(), entity.chunkPosition().z(), unloadedWithPlayer);
    }

    private RequestEntityRemovePacket(String world, UUID uuid, int chunkX, int chunkZ, boolean unloadedWithPlayer) {
        this.world = world;
        this.uuid = uuid;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.unloadedWithPlayer = unloadedWithPlayer;
    }

    public RequestEntityRemovePacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.uuid = in.readUUID();
        this.chunkX = in.readInt();
        this.chunkZ = in.readInt();
        this.unloadedWithPlayer = in.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(this.world);
        out.writeUUID(this.uuid);
        out.writeInt(this.chunkX);
        out.writeInt(this.chunkZ);
        out.writeBoolean(this.unloadedWithPlayer);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> this.handleOnMainThread(connection));
    }

    private void handleOnMainThread(ExternalServerConnection connection) {
        if (!(Bukkit.getWorld(this.world) instanceof CraftWorld craftWorld)) {
            LOGGER.warn("Cannot apply entity removal request for {} in missing world {}", this.uuid, this.world);
            return;
        }

        ServerLevel level = craftWorld.getHandle();
        var holder = MultiPaper.getChunkHolder(level, this.chunkX, this.chunkZ);
        if (!MultiPaper.isChunkLocal(holder)) {
            ExternalServer owner = holder == null ? null : holder.externalOwner;
            if (owner != null && !owner.isMe() && owner.getConnection() != null) {
                owner.getConnection().send(new RequestEntityRemovePacket(this.world, this.uuid, this.chunkX, this.chunkZ, this.unloadedWithPlayer));
            } else {
                LOGGER.warn("Cannot route entity removal request for {} to its current chunk owner", this.uuid);
            }
            return;
        }

        Entity entity = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup().getRawEntity(this.uuid);
        Entity.RemovalReason reason = entity instanceof Player ? Entity.RemovalReason.KILLED : Entity.RemovalReason.DISCARDED;
        if (this.unloadedWithPlayer) {
            reason = Entity.RemovalReason.UNLOADED_WITH_PLAYER;
        }

        if (entity != null) {
            // The request came from a mirrored entity; explicitly fan out the authoritative
            // result because the normal removal hook is not guaranteed to run for this path.
            MultiPaperEntitiesHandler.removingEntity = true;
            try {
                entity.setRemoved(reason);
            } finally {
                MultiPaperEntitiesHandler.removingEntity = false;
            }
        }
        if (reason != Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            EntityRemovePacket.setEntityRemoved(this.uuid, reason);
        }
        if (entity != null || reason != Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            // Chunk and world subscriber membership can both lag entity transfers.
            MultiPaper.broadcastPacketToExternalServers(new EntityRemovePacket(this.world, this.uuid, this.unloadedWithPlayer));
        }
    }
}
