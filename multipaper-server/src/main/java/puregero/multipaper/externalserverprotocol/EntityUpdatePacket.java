package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.Visibility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperEntitiesHandler;
import puregero.multipaper.PacketSerialization;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EntityUpdatePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(EntityUpdatePacket.class.getSimpleName());

    // If anyone has a better idea for locking only certain threads rather than creating a set of the thread ids that need locking, plz contribute
    public static Set<Long> threadsWritingUpdatePackets = ConcurrentHashMap.newKeySet();

    private final UUID world;
    private final UUID uuid;
    private final Packet<?> packet;

    private final long chunkPos;

    public EntityUpdatePacket(Entity entity, Packet<?> packet) {
        this.world = ((ServerLevel) entity.level()).getWorld().getUID();
        this.uuid = entity.getUUID();
        this.packet = packet;
        this.chunkPos = entity.chunkPosition().pack();
    }

    public EntityUpdatePacket(FriendlyByteBuf in) {
        world = in.readUUID();
        uuid = in.readUUID();

        packet = PacketSerialization.readClientbound(in.readByteArray());
        this.chunkPos = in.readLong();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(world);
        out.writeUUID(uuid);

        byte[] packetData = PacketSerialization.writeClientbound(packet);
        threadsWritingUpdatePackets.add(Thread.currentThread().getId());
        threadsWritingUpdatePackets.remove(Thread.currentThread().getId());
        out.writeByteArray(packetData);
        out.writeLong(this.chunkPos);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> handleLater(connection, 0));
    }

    private void handleLater(ExternalServerConnection connection, int depth) {
        World bukkitWorld = Bukkit.getWorld(world);

        if (bukkitWorld instanceof CraftWorld craftWorld) {
            ServerLevel level = craftWorld.getHandle();
            Entity entity = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup().getRawEntity(uuid);
            if (entity == null) {
                if (EntityRemovePacket.hasRemovalMarker(uuid)) {
                    return;
                }

                // If we can't find the entity, try again later,
                // the spawn entity packet is probably coming later

                // first we check if we really need to request the entity by checking if entity's chunk is loaded
                ChunkPos pendingChunk = ChunkPos.unpack(this.chunkPos);
                if (((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup().getChunk(pendingChunk.x(), pendingChunk.z()) == null) {
                    return;
                }

                if (depth > 5) {
                    if (depth >= 20) {
                        LOGGER.warn("Could not find entity " + uuid + " for " + packet.getClass().getSimpleName() + ", requesting it");
                    }
                    RequestEntityPacket.requestEntity(connection, level, world, uuid, pendingChunk);
                    return;
                }

                RequestEntityPacket.requestEntity(connection, level, world, uuid, pendingChunk);
                return;
            }

            MultiPaperEntitiesHandler.handleEntityUpdate(connection, entity, packet);
        }
    }
}
