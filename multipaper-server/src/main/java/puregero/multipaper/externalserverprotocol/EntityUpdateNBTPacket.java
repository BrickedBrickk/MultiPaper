package puregero.multipaper.externalserverprotocol;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.Visibility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.io.IOException;
import java.util.UUID;

public class EntityUpdateNBTPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(EntityUpdateNBTPacket.class.getSimpleName());

    private final String world;
    private final UUID uuid;
    private final byte[] data;

    public EntityUpdateNBTPacket(Entity entity) {
        this.world = ((ServerLevel) entity.level()).getWorld().getName();
        this.uuid = entity.getUUID();

        net.minecraft.world.level.storage.TagValueOutput output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
            net.minecraft.util.ProblemReporter.DISCARDING, entity.registryAccess()
        );
        entity.save(output);
        CompoundTag tag = output.buildResult();

        try {
            this.data = MultiPaper.nbtToBytes(tag);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public EntityUpdateNBTPacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.uuid = in.readUUID();
        this.data = in.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeUUID(uuid);
        out.writeByteArray(data);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            try {
                World bukkitWorld = Bukkit.getWorld(world);

                if (bukkitWorld instanceof CraftWorld craftWorld) {
                    ServerLevel level = craftWorld.getHandle();
                    CompoundTag tag = MultiPaper.nbtFromBytes(data);
                    loadEntity(level, tag, uuid);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public static Entity loadEntity(ServerLevel level, CompoundTag tag, UUID uuid) {
        Entity.RemovalReason removalReason = EntityRemovePacket.getRemovalReason(uuid);
        if (removalReason != null && removalReason.shouldDestroy()) {
            // We've already removed this entity. This is likely a race condition, so don't recreate the entity.
            return null;
        }

        Entity entity = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup().getRawEntity(uuid);

        if (entity == null) {
            entity = EntityType.loadEntityRecursive(tag, level, new net.minecraft.world.entity.EntitySpawnRequest(net.minecraft.world.entity.EntitySpawnReason.LOAD, false), entity2 -> {
                if (((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup().getChunk(entity2.chunkPosition().x(), entity2.chunkPosition().z()) != null) {
                    ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup().addNewEntity(entity2);
                    return entity2;
                } else {
                    EntityRemovePacket.setEntityRemoved(uuid, Entity.RemovalReason.UNLOADED_TO_CHUNK, 20);
                    LOGGER.warn("Tried to create an entity from nbt, but the entities for that chunk aren't loaded: " + entity2); // Warnings are there for a reason
                    return null;
                }
            });
        } else if (entity instanceof ServerPlayer player) {
            new Exception("Tried to update the nbt of player " + player.getScoreboardName() + " to " + tag).printStackTrace();
        } else {
            entity.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag));
        }

        return entity;
    }
}
