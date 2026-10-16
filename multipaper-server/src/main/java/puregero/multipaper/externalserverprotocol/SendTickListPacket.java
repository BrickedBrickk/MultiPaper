package puregero.multipaper.externalserverprotocol;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.io.IOException;

public class SendTickListPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(SendTickListPacket.class.getSimpleName());
    private static final Codec<java.util.List<SavedTick<Block>>> BLOCK_TICKS_CODEC = SavedTick.codec(BuiltInRegistries.BLOCK.byNameCodec()).listOf();
    private static final Codec<java.util.List<SavedTick<Fluid>>> FLUID_TICKS_CODEC = SavedTick.codec(BuiltInRegistries.FLUID.byNameCodec()).listOf();

    private final String world;
    private final int cx;
    private final int cz;
    private final CompoundTag tag;

    public SendTickListPacket(LevelChunk chunk) {
        ServerLevel level = (ServerLevel) chunk.getLevel();
        this.world = level.getServer().storageSource.getLevelId();
        this.cx = chunk.getPos().x();
        this.cz = chunk.getPos().z();

        tag = new CompoundTag();
        tag.put("block_ticks", BLOCK_TICKS_CODEC.encodeStart(NbtOps.INSTANCE, chunk.blockTicks.pack(level.getGameTime())).getOrThrow());
        tag.put("fluid_ticks", FLUID_TICKS_CODEC.encodeStart(NbtOps.INSTANCE, chunk.fluidTicks.pack(level.getGameTime())).getOrThrow());
    }

    public SendTickListPacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.cx = in.readInt();
        this.cz = in.readInt();

        byte[] data = in.readByteArray();
        try {
            tag = MultiPaper.nbtFromBytes(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeInt(cx);
        out.writeInt(cz);

        try {
            byte[] data = MultiPaper.nbtToBytes(tag);

            out.writeByteArray(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            CraftWorld bukkitWorld = ((CraftWorld) Bukkit.getWorld(world));
            ServerLevel level = bukkitWorld != null ? bukkitWorld.getHandle() : null;
            ChunkAccess chunk = MultiPaper.getChunkAccess(world, cx, cz);
            java.util.List<SavedTick<Block>> blockList = BLOCK_TICKS_CODEC.parse(NbtOps.INSTANCE, tag.get("block_ticks")).getOrThrow();
            java.util.List<SavedTick<Fluid>> fluidList = FLUID_TICKS_CODEC.parse(NbtOps.INSTANCE, tag.get("fluid_ticks")).getOrThrow();
            long now = level == null ? 0L : level.getGameTime();
            if (level != null && level.getChunkIfLoaded(cx, cz) != null) {
                long subTick = 0L;
                for (SavedTick<Block> tick : blockList) level.getBlockTicks().schedule(tick.unpack(now, subTick++));
                subTick = 0L;
                for (SavedTick<Fluid> tick : fluidList) level.getFluidTicks().schedule(tick.unpack(now, subTick++));
            } else if (chunk instanceof LevelChunk levelChunk) {
                levelChunk.blockTicks = new LevelChunkTicks<>(blockList);
                levelChunk.fluidTicks = new LevelChunkTicks<>(fluidList);
                levelChunk.unpackTicks(now);
            } else if (chunk instanceof ProtoChunk protoChunk) {
                protoChunk.blockTicks = ProtoChunkTicks.load(blockList);
                protoChunk.fluidTicks = ProtoChunkTicks.load(fluidList);
            } else {
                LOGGER.warn("Received tick lists for an unloaded chunk " + world + "," + cx + "," + cz);
            }
        });
    }
}
