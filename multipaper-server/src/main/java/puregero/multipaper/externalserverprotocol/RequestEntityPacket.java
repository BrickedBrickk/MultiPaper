package puregero.multipaper.externalserverprotocol;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class RequestEntityPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(RequestEntityPacket.class.getSimpleName());

    private static final ExpiringRequestCounter<Pair<UUID, ExternalServerConnection>> playerRequestCounts = new ExpiringRequestCounter<>();
    private static final Set<Triple<String, UUID, UUID>> requestedEntitiesThisTick = new HashSet<>();

    private final UUID world;
    private final UUID uuid;

    public static void tick() {
        requestedEntitiesThisTick.clear();
    }

    public static void requestEntity(ExternalServerConnection connection, UUID world, UUID entity) {
        if (!hasAlreadyRequestedThisTick(connection, world, entity)) {
            connection.send(new RequestEntityPacket(world, entity));
        }
    }

    private static boolean hasAlreadyRequestedThisTick(ExternalServerConnection connection, UUID world, UUID entity) {
        if (!Bukkit.isPrimaryThread()) {
            LOGGER.warn(new Exception("RequestEntityPacket.requestEntity called off main thread, sending packet async without checks"));
            return false; // We can't check this async, so just send the packet anyway
        } else {
            return !requestedEntitiesThisTick.add(Triple.of(connection.externalServer.getName(), world, entity));
        }
    }

    private RequestEntityPacket(UUID world, UUID uuid) {
        this.world = world;
        this.uuid = uuid;
    }

    public RequestEntityPacket(FriendlyByteBuf in) {
        this.world = in.readUUID();
        this.uuid = in.readUUID();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(world);
        out.writeUUID(uuid);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            CraftWorld craftWorld = ((CraftWorld) Bukkit.getWorld(world));
            if (craftWorld == null) return;
            ServerLevel level = craftWorld.getHandle();
            Entity entity = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup().getRawEntity(uuid);
            if (entity != null) {
                if (entity.isFake()) return;
                if (entity instanceof ServerPlayer serverPlayer) {
                    triedToRequestPlayer(connection, serverPlayer);
                    return;
                }

                // Send the vehicle the entity is in
                entity = entity.getRootVehicle();

                // Check that the server is subscribed to the entity's chunk
                NewChunkHolder chunkHolder = MultiPaper.getChunkHolder(level, entity.chunkPosition().x(), entity.chunkPosition().z());

                if (chunkHolder == null || !chunkHolder.externalEntitiesSubscribers.contains(connection.externalServer)) {
                    LOGGER.warn(connection.externalServer.getName() + " requested entity " + uuid + ", but that entity is not in a chunk that server is subscribed to (" + craftWorld.getName() + "," + entity.chunkPosition().x() + "," + entity.chunkPosition().z() + ")");
                    return;
                }

                EntityUpdateWithDependenciesPacket.sendVehicleAndPassengersPacketsRecursivelyToServers(entity, List.of(connection.externalServer));
            } else {
                LOGGER.warn(connection.externalServer.getName() + " requested entity " + uuid + ", but that entity doesn't exist");
            }
        });
    }

    private void triedToRequestPlayer(ExternalServerConnection connection, ServerPlayer serverPlayer) {
        if (!MinecraftServer.getServer().getPlayerList().getPlayers().contains(serverPlayer)) return;
        Pair<UUID, ExternalServerConnection> key = Pair.of(serverPlayer.getUUID(), connection);
        ExpiringRequestCounter.State state = playerRequestCounts.increment(key);
        int count = state.count();
        if (count > 11) {
            LOGGER.error(connection.externalServer.getName() + " tried to request player " + serverPlayer.getScoreboardName() + " more than 10 times! This means they didn't sync correctly. Kicking them.");
            serverPlayer.getBukkitEntity().kickPlayer("Your player failed to sync. Please reconnect.");
            playerRequestCounts.expire(key, state);
        } else {
            LOGGER.warn(connection.externalServer.getName() + " tried to request entity " + uuid + ", which is the player " + serverPlayer.getScoreboardName() + "! This means that server is missing that player.");
            CompletableFuture.runAsync(() -> playerRequestCounts.expire(key, state), CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS));
        }
    }
}
