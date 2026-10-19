package puregero.multipaper.externalserverprotocol;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.ConcurrentModificationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class RequestChunkPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(RequestChunkPacket.class.getSimpleName());
    private final String world;
    private final int cx;
    private final int cz;

    public RequestChunkPacket(String world, int cx, int cz) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
    }

    public RequestChunkPacket(FriendlyByteBuf in) {
        world = in.readUtf();
        cx = in.readInt();
        cz = in.readInt();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeInt(cx);
        out.writeInt(cz);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        puregero.multipaper.MultiPaper.debug("peer-req " + connection.externalServer.getName() + " requested " + world + "," + cx + "," + cz); // MultiPaper
        World bukkitWorld = Bukkit.getWorld(world);

        if (!(bukkitWorld instanceof CraftWorld craftWorld)) {
            LOGGER.warn(connection.externalServer.getName() + " is requesting chunk " + world + "," + cx + "," + cz + " but we don't have the world " + world + " loaded.");
            connection.send(new SendChunkPacket(world, cx, cz, null));
            return;
        }

        ServerLevel level = craftWorld.getHandle();
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(world, cx, cz);

        if (newChunkHolder == null) {
            LOGGER.warn(connection.externalServer.getName() + " is requesting chunk " + world + "," + cx + "," + cz + " but we aren't trying to load it.");
            connection.send(new SendChunkPacket(world, cx, cz, null));
            return;
        }

        CompletableFuture<ChunkAccess> future = newChunkHolder.onCurrentChunkLoaded();

        CompletableFuture<ChunkAccess> futureToWaitOn = future;
        if (connection.isWaitingForChunkData()) {
            ChunkAccess access = newChunkHolder.getCurrentChunk();
            if (access != null) {
                futureToWaitOn = CompletableFuture.completedFuture(newChunkHolder.getCurrentChunk());
            } else {
                connection.send(new SendChunkPacket(world, cx, cz, null));
                return;
            }
        }

        futureToWaitOn.thenAccept(chunk -> {
            if (future != newChunkHolder.onCurrentChunkLoaded()) {
                // The future has been updated, try again
                handle(connection);
                return;
            }

            if (chunk == null) {
                LOGGER.warn(connection.externalServer.getName() + " is requesting chunk " + world + "," + cx + "," + cz + " but we don't have it loaded.");
                connection.send(new SendChunkPacket(world, cx, cz, null));
                return;
            }

            CompletableFuture<Void> lightFuture = ((ca.spottedleaf.moonrise.patches.starlight.light.StarLightLightingProvider) level.getLightEngine()).starlight$getLightEngine().getChunkFuture(newChunkHolder.vanillaChunkHolder.getPos());

            if (!lightFuture.isDone()) {
                awaitLighting(lightFuture, 15_000, () -> CompletableFuture.runAsync(() -> handle(connection)), throwable -> {
                    Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                    if (cause instanceof TimeoutException) {
                        LOGGER.warn("Timed out waiting for lighting while sending chunk " + world + "," + cx + "," + cz);
                    } else {
                        LOGGER.warn("Error while waiting for lighting while sending chunk " + world + "," + cx + "," + cz, cause);
                    }
                    connection.send(new SendChunkPacket(world, cx, cz, null));
                });
                return;
            }

            try {
                ListTag entitiesToLoad = null;
                ListTag blockEntitiesToLoad = null;

                ChunkAccess fullChunk = chunkForSerialization(chunk);

                if (fullChunk instanceof LevelChunk levelChunk) {
                    // Cache these tags in case they get deleted while we serialize the chunk (multi-threaded fun!)
                    entitiesToLoad = levelChunk.entitiesToLoad;
                    blockEntitiesToLoad = levelChunk.blockEntitiesToLoad;
                }

                CompoundTag tag = serializeChunk(level, chunk);

                if (entitiesToLoad != null) {
                    tag.put("entities", entitiesToLoad);
                }
                if (blockEntitiesToLoad != null) {
                    tag.put("block_entities", blockEntitiesToLoad);
                }

                connection.send(new SendChunkPacket(world, cx, cz, tag));
            } catch (ConcurrentModificationException e) {
                LOGGER.warn("Got ConcurrentModificationException while sending chunk, sending it in main thread instead");
                MultiPaper.runSync(() -> handle(connection));
            }
        })
        // Timeout instantly if this server is blocking our chunk loading, as this is probably also blocking their chunk loading
        .orTimeout(15, TimeUnit.SECONDS).exceptionally(throwable -> {
            if (throwable instanceof TimeoutException) {
                LOGGER.warn("Timed out while sending chunk " + world + "," + cx + "," + cz);
            } else {
                LOGGER.warn("Error while sending chunk " + world + "," + cx + "," + cz, throwable);
            }

            connection.send(new SendChunkPacket(world, cx, cz, null));
            return null;
        });
    }

    static ChunkAccess chunkForSerialization(ChunkAccess chunk) {
        return chunk instanceof ImposterProtoChunk imposterProtoChunk ? imposterProtoChunk.getWrapped() : chunk;
    }

    static void awaitLighting(CompletableFuture<Void> lightFuture, long timeoutMillis, Runnable onReady, Consumer<Throwable> onFailure) {
        lightFuture.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                onReady.run();
            } else {
                onFailure.accept(throwable);
            }
        });
    }

    static CompoundTag serializeChunk(ServerLevel level, ChunkAccess chunk) {
        return SerializableChunkData.copyOf(level, chunkForSerialization(chunk)).write();
    }
}
