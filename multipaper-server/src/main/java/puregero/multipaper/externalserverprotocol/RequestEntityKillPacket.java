package puregero.multipaper.externalserverprotocol;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

/** Executes an explicit kill on the backend that owns the entity's chunk. */
public final class RequestEntityKillPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(RequestEntityKillPacket.class);

    private final String world;
    private final UUID uuid;
    private final int chunkX;
    private final int chunkZ;

    public RequestEntityKillPacket(Entity entity) {
        this(((ServerLevel) entity.level()).getWorld().getName(), entity.getUUID(), entity.chunkPosition().x(), entity.chunkPosition().z());
    }

    private RequestEntityKillPacket(String world, UUID uuid, int chunkX, int chunkZ) {
        this.world = world;
        this.uuid = uuid;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public RequestEntityKillPacket(FriendlyByteBuf in) {
        this(in.readUtf(), in.readUUID(), in.readInt(), in.readInt());
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(this.world);
        out.writeUUID(this.uuid);
        out.writeInt(this.chunkX);
        out.writeInt(this.chunkZ);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> this.handleOnMainThread());
    }

    private void handleOnMainThread() {
        if (!(Bukkit.getWorld(this.world) instanceof CraftWorld craftWorld)) {
            LOGGER.warn("Cannot apply entity kill request for {} in missing world {}", this.uuid, this.world);
            return;
        }

        ServerLevel level = craftWorld.getHandle();
        NewChunkHolder holder = MultiPaper.getChunkHolder(level, this.chunkX, this.chunkZ);
        if (!MultiPaper.isChunkLocal(holder)) {
            ExternalServer owner = holder == null ? null : holder.externalOwner;
            if (owner != null && !owner.isMe() && owner.getConnection() != null) {
                owner.getConnection().send(new RequestEntityKillPacket(this.world, this.uuid, this.chunkX, this.chunkZ));
            } else {
                LOGGER.warn("Cannot route entity kill request for {} to its current chunk owner", this.uuid);
            }
            return;
        }

        Entity entity = ((ChunkSystemServerLevel) level).moonrise$getEntityLookup().getRawEntity(this.uuid);
        if (entity != null) {
            // Calling the vanilla method here preserves living-entity death events,
            // drops, and other subclass-specific kill behavior on the authority.
            entity.kill(level);
            return;
        }

        // The entity may have been removed while the request was in flight. Tell all
        // mirrors to converge on a terminal state instead of leaving an unkillable ghost.
        EntityRemovePacket.setEntityRemoved(this.uuid, Entity.RemovalReason.KILLED);
        MultiPaper.broadcastPacketToExternalServers(new EntityRemovePacket(this.world, this.uuid, false));
    }
}
