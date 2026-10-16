package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.Visibility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperEntitiesHandler;
import puregero.multipaper.PacketSerialization;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public class EntityUpdateWithDependenciesPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(EntityUpdateWithDependenciesPacket.class.getSimpleName());

    private final UUID world;
    private final UUID uuid;
    private final UUID[] uuids;
    private final Packet<?> packet;
    private final long chunkPos;

    public EntityUpdateWithDependenciesPacket(Entity entity, Collection<Entity> dependents, Packet<?> packet) {
        this.world = ((ServerLevel) entity.level()).getWorld().getUID();
        this.uuid = entity.getUUID();
        this.uuids = dependents.stream().filter(Objects::nonNull).map(Entity::getUUID).toArray(UUID[]::new);
        this.packet = packet;
        this.chunkPos = entity.chunkPosition().pack();
    }

    public EntityUpdateWithDependenciesPacket(FriendlyByteBuf in) {
        world = in.readUUID();
        uuid = in.readUUID();

        uuids = new UUID[in.readVarInt()];

        for (int i = 0; i < uuids.length; i++) {
            uuids[i] = in.readUUID();
        }

        packet = PacketSerialization.readClientbound(in.readByteArray());
        this.chunkPos = in.readLong();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(world);
        out.writeUUID(uuid);

        out.writeVarInt(uuids.length);
        for (UUID uuid : uuids) {
            out.writeUUID(uuid);
        }

        out.writeByteArray(PacketSerialization.writeClientbound(packet));
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
            Entity entity = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup().get(uuid);
            if (entity == null) {
                if (EntityRemovePacket.removedEntities.containsKey(uuid)) {
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
                    LOGGER.warn("Could not find entity " + uuid + " for " + packet.getClass().getSimpleName() + ", requesting it");
                    RequestEntityPacket.requestEntity(connection, world, uuid);
                    return;
                }

                RequestEntityPacket.requestEntity(connection, world, uuid);
                return;
            }

            Entity[] entities = new Entity[uuids.length];

            for (int i = 0; i < uuids.length; i++) {
                entities[i] = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup().get(uuids[i]);

                if (entities[i] == null) {
                    if (EntityRemovePacket.removedEntities.containsKey(uuids[i])) {
                        return;
                    }

                    if (depth > 5) {
                        LOGGER.warn("Could not find dependent entity " + uuids[i] + " for " + packet.getClass().getSimpleName() + ", requesting it");
                        RequestEntityPacket.requestEntity(connection, world, uuids[i]);
                        return;
                    }

                    RequestEntityPacket.requestEntity(connection, world, uuids[i]);
                    return;
                }
            }

            MultiPaperEntitiesHandler.handleEntityWithDependenicesUpdate(entity, entities, packet);
        }
    }

    public static void sendVehicleAndPassengersPacketsRecursivelyToServers(Entity entity, Collection<ExternalServer> servers) {
        if (!servers.isEmpty()) {
            compileVehicleAndPassengersPacketsRecursively(entity).forEach(packet -> MultiPaper.broadcastPacketToExternalServers(servers, packet));
        }
    }

    /**
     * Get a list of packets that contain the entity, and it's vehicles and passengers (recursively)'s NBT data, along with
     * any other packets that are required to link the passengers to their vehicles.
     * Order of packets must be maintained.
     */
    public static List<ExternalServerPacket> compileVehicleAndPassengersPacketsRecursively(Entity entity) {
        List<ExternalServerPacket> packets = new ArrayList<>();

        compilePacketsForEntityAndPassengers(entity.getRootVehicle(), packets);

        return packets;
    }

    private static void compilePacketsForEntityAndPassengers(Entity entity, List<ExternalServerPacket> packets) {
        if (!(entity instanceof ServerPlayer) && (entity.getVehicle() == null || entity.getVehicle() instanceof ServerPlayer)) {
            // This entity is the vehicle and will save the nbt for itself and all its passengers
            // Note that Players don't get saved, so any entity riding a player will also need to be saved
            packets.add(new EntityUpdateNBTPacket(entity));
        }

        for (Entity passenger : entity.getPassengers()) {
            compilePacketsForEntityAndPassengers(passenger, packets);
        }

        // Link the passengers to their vehicle (especially important if a vehicle or passenger is a player)
        // (And also unlink old passengers from the vehicle)
        packets.add(new EntityUpdateWithDependenciesPacket(entity, entity.getPassengers(), new ClientboundSetPassengersPacket(entity)));
    }
}
