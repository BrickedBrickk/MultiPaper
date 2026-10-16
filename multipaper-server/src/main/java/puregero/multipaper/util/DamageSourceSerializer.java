package puregero.multipaper.util;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.UUID;

public class DamageSourceSerializer {

    private static final Logger LOGGER = LogManager.getLogger(DamageSourceSerializer.class.getSimpleName());

    public static void serialize(DamageSource source, FriendlyByteBuf friendlyByteBuf) throws IOException {
        friendlyByteBuf.writeUtf(source.typeHolder().unwrapKey().orElseThrow().identifier().getPath());

        friendlyByteBuf.writeNullable(source.getEntity(), (byteBuf, causingEntity) -> {
            byteBuf.writeLong(causingEntity.getUUID().getMostSignificantBits());
            byteBuf.writeLong(causingEntity.getUUID().getLeastSignificantBits());
        });

        friendlyByteBuf.writeNullable(source.getDirectEntity(), (byteBuf, directEntity) -> {
            byteBuf.writeLong(directEntity.getUUID().getMostSignificantBits());
            byteBuf.writeLong(directEntity.getUUID().getLeastSignificantBits());
        });

        friendlyByteBuf.writeNullable(source.getSourcePosition(), (byteBuf, vec3) -> {
            byteBuf.writeDouble(vec3.x);
            byteBuf.writeDouble(vec3.y);
            byteBuf.writeDouble(vec3.z);
        });

        friendlyByteBuf.writeBoolean(source.isCritical());
    }

    public static DamageSource deserialize(Entity entity, ServerLevel level, FriendlyByteBuf friendlyByteBuf) throws IOException {
        String msgId = friendlyByteBuf.readUtf();

        Entity causingEntity = friendlyByteBuf.readNullable(byteBuf -> {
            UUID uuid = new UUID(byteBuf.readLong(), byteBuf.readLong());
            Entity levelEntity = level.getEntity(uuid);
            if (levelEntity == null) {
                LOGGER.warn("Unknown entity for damage source " + msgId + " uuid=" + uuid);
            }
            return levelEntity;
        });

        Entity directEntity = friendlyByteBuf.readNullable(byteBuf -> {
            UUID uuid = new UUID(byteBuf.readLong(), byteBuf.readLong());
            Entity levelEntity = level.getEntity(uuid);
            if (levelEntity == null) {
                LOGGER.warn("Unknown direct entity for damage source " + msgId + " uuid=" + uuid);
            }
            return levelEntity;
        });

        Vec3 sourcePosition = friendlyByteBuf.readNullable(byteBuf -> new Vec3(byteBuf.readDouble(), byteBuf.readDouble(), byteBuf.readDouble()));

        boolean critical = friendlyByteBuf.readBoolean();

        ResourceKey<DamageType> resourceKey = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(msgId));

        DamageSource damageSource = new DamageSource(entity.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(resourceKey), directEntity, causingEntity, sourcePosition);
        if (critical) damageSource.critical();
        return damageSource;
    }

}
