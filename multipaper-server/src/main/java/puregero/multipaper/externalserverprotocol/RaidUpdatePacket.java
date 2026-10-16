package puregero.multipaper.externalserverprotocol;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.io.IOException;

public class RaidUpdatePacket extends ExternalServerPacket {
    private final String world;
    private final byte[] data;

    public RaidUpdatePacket(ServerLevel level, Raid raid) {
        this.world = level.getWorld().getName();

        CompoundTag tag = (CompoundTag) Raid.MAP_CODEC.codec().encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), raid).getOrThrow();
        tag.putInt("MultiPaperId", raid.idOrNegativeOne);

        try {
            this.data = MultiPaper.nbtToBytes(tag);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public RaidUpdatePacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.data = in.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeByteArray(data);
    }

    public static void broadcastUpdate(ServerLevel level, Raid raid) {
        MultiPaper.broadcastPacketToExternalServers(new RaidUpdatePacket(level, raid));
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            try {
                World bukkitWorld = Bukkit.getWorld(world);

                if (bukkitWorld instanceof CraftWorld craftWorld) {
                    ServerLevel level = craftWorld.getHandle();
                    CompoundTag tag = MultiPaper.nbtFromBytes(data);
                    int id = tag.getIntOr("MultiPaperId", -1);
                    Raid raid = Raid.MAP_CODEC.codec()
                        .parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
                        .getOrThrow();
                    raid.idOrNegativeOne = id;
                    level.getRaids().raidMap.put(id, raid);
                    level.getRaids().setDirty();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
