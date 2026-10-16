package puregero.multipaper.externalserverprotocol;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ChunkKey;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.zip.InflaterInputStream;

public class SendChunkPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(SendChunkPacket.class.getSimpleName());

    private final String world;
    private final int cx;
    private final int cz;
    private final byte[] data;

    public SendChunkPacket(String world, int cx, int cz, CompoundTag tag) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;

        try {
            if (tag == null) {
                data = new byte[0];
            } else {
                data = MultiPaper.nbtToBytes(tag);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public SendChunkPacket(FriendlyByteBuf in) {
        world = in.readUtf();
        cx = in.readInt();
        cz = in.readInt();
        data = in.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeInt(cx);
        out.writeInt(cz);
        out.writeByteArray(data);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        try {
            Consumer<DataInputStream> callback = connection.chunkCallbacks.remove(new ChunkKey(world, cx, cz));
            if (callback != null) {
                if (data.length == 0) {
                    LOGGER.warn(connection.externalServer.getName() + " sent us an empty chunk for " + world + "," + cx + "," + cz + ", force loading it from disk");
                    MultiPaper.forceReadChunk(world, "region", cx, cz).thenAccept(data2 -> callback.accept(data2.length == 0 ? null : new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data2)))));
                } else {
                    callback.accept(new DataInputStream(new ByteArrayInputStream(data)));
                }
            } else {
                if (data.length == 0) {
                    return;
                }

                CompoundTag tag = MultiPaper.nbtFromBytes(data);
                CraftWorld craftWorld = ((CraftWorld) Bukkit.getWorld(world));
                ServerLevel level = craftWorld != null ? craftWorld.getHandle() : null;
                NewChunkHolder holder = MultiPaper.getChunkHolder(world, cx, cz);
                if (level == null) {
                    LOGGER.warn("Received chunk data " + world + "," + cx + "," + cz + " but we don't have world loaded.");
                } else if (holder == null) {
                    LOGGER.warn("Received chunk data " + world + "," + cx + "," + cz + " but no chunk is loaded here");
                } else if (holder.getCurrentChunk() instanceof LevelChunk) {
                    LOGGER.warn("Received chunk data " + world + "," + cx + "," + cz + " (" + tag.getString("Status") + "), but it is a level chunk (" + holder.getCurrentGenStatus() + ")");
                } else {
                    LOGGER.warn("Received chunk data " + world + "," + cx + "," + cz + " (" + tag.getString("Status") + "), but we have a " + holder.getCurrentGenStatus() + " chunk, forcing reload from disk.");
                    forceChunkUnsafeUnload(world, cx, cz);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void forceChunkUnsafeUnload(String world, int cx, int cz) {
        MultiPaper.runSync(() -> {
            ChunkPos pos = new ChunkPos(cx, cz);
            CraftWorld craftWorld = ((CraftWorld) Bukkit.getWorld(world));
            if (craftWorld != null) {
                ServerLevel level = craftWorld.getHandle();
                NewChunkHolder holder = MultiPaper.getChunkHolder(level, cx, cz);

                if (holder != null) {
                    ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler().chunkHolderManager.unloadChunkNowNoSave(holder);
                }
            }
        });
    }
}
