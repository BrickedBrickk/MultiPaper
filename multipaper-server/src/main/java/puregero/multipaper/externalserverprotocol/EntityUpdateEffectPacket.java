package puregero.multipaper.externalserverprotocol;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperEffectsHandler;

import java.io.IOException;
import java.util.UUID;

public class EntityUpdateEffectPacket extends ExternalServerPacket {

    private final String world;
    private final UUID uuid;
    private final boolean remove;
    private final byte[] data;

    public EntityUpdateEffectPacket(Entity entity, MobEffectInstance effect, boolean remove) {
        this.world = ((ServerLevel) entity.level()).getWorld().getName();
        this.uuid = entity.getUUID();
        this.remove = remove;

        CompoundTag tag = (CompoundTag) MobEffectInstance.CODEC.encodeStart(
            org.bukkit.craftbukkit.CraftRegistry.getMinecraftRegistry().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), effect
        ).getOrThrow();

        try {
            this.data = MultiPaper.nbtToBytes(tag);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public EntityUpdateEffectPacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.uuid = in.readUUID();
        this.remove = in.readBoolean();
        this.data = in.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeUUID(uuid);
        out.writeBoolean(remove);
        out.writeByteArray(data);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            try {
                ServerLevel level = ((CraftWorld) Bukkit.getWorld(world)).getHandle();
                Entity entity = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup().getRawEntity(uuid);
                CompoundTag tag = MultiPaper.nbtFromBytes(data);
                MobEffectInstance effect = MobEffectInstance.CODEC.parse(
                    org.bukkit.craftbukkit.CraftRegistry.getMinecraftRegistry().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), tag
                ).getOrThrow();
                if (entity != null) {
                    MultiPaperEffectsHandler.handle(entity, effect, remove);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
