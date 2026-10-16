package puregero.multipaper;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.GameType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import puregero.multipaper.config.MultiPaperConfiguration;
import puregero.multipaper.externalserverprotocol.EntityUpdatePacket;
import puregero.multipaper.externalserverprotocol.HurtEntityPacket;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class ExternalPlayer extends ServerPlayer {

    public static final java.util.Map<UUID, Integer> persistentEntityIds = new java.util.concurrent.ConcurrentHashMap<>(); // MultiPaper - persistent entity ids for players across servers
    private static final Logger LOGGER = LogManager.getLogger(ExternalPlayer.class.getSimpleName());
    public static HashMap<UUID, String> loadedAdvancements = new HashMap<>();
    public static HashMap<UUID, String> loadedStats = new HashMap<>();
    public ExternalServerConnection externalServerConnection;
    private final InetSocketAddress address;
    private boolean sendPackets = true;
    public boolean updatingData = false;

    public static ExternalPlayer create(ExternalServerConnection externalServerConnection, GameProfile gameProfile, String world, double x, double y, double z, float yaw, float pitch, GameType gamemode, InetSocketAddress address, CompoundTag saveData, String advancements, String stats, int entityId) {
        loadedAdvancements.put(gameProfile.id(), advancements);
        loadedStats.put(gameProfile.id(), stats);
        return new ExternalPlayer(externalServerConnection, gameProfile, world, x, y, z, yaw, pitch, gamemode, address, saveData, entityId);
    }

    public ExternalPlayer(ExternalServerConnection externalServerConnection, GameProfile gameProfile, String world, double x, double y, double z, float yaw, float pitch, GameType gamemode, InetSocketAddress address, CompoundTag saveData, int entityId) {
        super(((CraftServer) Bukkit.getServer()).getServer(), ((CraftWorld) Bukkit.getWorld(world)).getHandle(), gameProfile, net.minecraft.server.level.ClientInformation.createDefault());
        MultiPaperInventoryHandler.updatingInventory = true;
        this.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, this.level().registryAccess(), saveData));
        MultiPaperInventoryHandler.updatingInventory = false;

        if (MultiPaperConfiguration.get().syncSettings.syncEntityIds) {
            setId(entityId);
            if (MultiPaperConfiguration.get().syncSettings.persistentPlayerEntityIds) persistentEntityIds.put(gameProfile.id(), getId());// MultiPaper - persistent entity ids for players across servers
            // Update cache hast sets with our new entity id

        }

        this.externalServerConnection = externalServerConnection;
        this.address = address;
        this.isRealPlayer = true;
        this.valid = true;
        this.onGround = true;
        connection = new ServerGamePacketListenerImpl(this.level().getServer(), new ExternalPlayerConnection(PacketFlow.CLIENTBOUND), this, net.minecraft.server.network.CommonListenerCookie.createInitial(gameProfile, false));
        setPos(x, y, z);
        setYRot(yaw);
        setXRot(pitch);

        for (ServerPlayer entityplayer1 : this.level().getServer().getPlayerList().getPlayers()) {

            if (!entityplayer1.getBukkitEntity().canSee(getBukkitEntity())) {
                continue;
            }

            entityplayer1.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(this)));
        }

        this.level().getServer().getPlayerList().addPlayer(this);
        ((ServerLevel) level()).addNewPlayer(this);
        sendPackets = false;
        containerMenu.transferTo(containerMenu, getBukkitEntity());
        initInventoryMenu();
        sendPackets = true;
        gameMode.changeGameModeForPlayer(gamemode);
        detectEquipmentUpdates();
    }

    @Override
    public void tick() {
        // Don't tick
    }

    private class ExternalPlayerConnection extends net.minecraft.network.Connection {
        public ExternalPlayerConnection(PacketFlow side) {
            super(side);
            this.address = ExternalPlayer.this.address;
        }

        @Override
        public void setReadOnly() {
            // Do nothing
        }
 
        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void send(Packet<?> packet, @Nullable ChannelFutureListener packetsendlistener) {
            if (sendPackets && !(packet instanceof ClientboundPlayerAbilitiesPacket)
                    && !(packet instanceof ClientboundSetPlayerTeamPacket)
                    && !(packet instanceof ClientboundCommandsPacket)
                    && !(packet instanceof ClientboundSetScorePacket)
                    && !(packet instanceof ClientboundSetObjectivePacket)
                    && !(packet instanceof ClientboundSetDisplayObjectivePacket)
                    && !(packet instanceof ClientboundSetChunkCacheCenterPacket)
                    && !(packet instanceof ClientboundSetChunkCacheRadiusPacket)) {
//                LOGGER.info("Forwarding packet " + packet);
                externalServerConnection.sendPacket(ExternalPlayer.this, packet);
            } else {
//                LOGGER.info("Not sending packet " + packet.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        if (!updatingData) {
            MultiPaper.broadcastPacketToExternalServers(new EntityUpdatePacket(this,
                    new ClientboundSetEntityDataPacket(getId(), Collections.singletonList(getEntityData().getItem(data).value()))));
        }
    }

}
