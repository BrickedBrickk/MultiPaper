package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerActionOnEntityPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerActionOnEntityPacket.class.getSimpleName());

    private final UUID uuid;
    private final UUID entityUuid;
    private final Packet<ServerGamePacketListener> action;

    public PlayerActionOnEntityPacket(ServerPlayer player, Entity entity, Packet<ServerGamePacketListener> action) {
        this.uuid = player.getUUID();
        this.entityUuid = entity.getUUID();
        this.action = action;
    }

    public PlayerActionOnEntityPacket(FriendlyByteBuf in) {
        this.uuid = in.readUUID();
        this.entityUuid = in.readUUID();

        boolean attack = in.readBoolean();
        action = attack ? ServerboundAttackPacket.STREAM_CODEC.decode(in) : ServerboundInteractPacket.STREAM_CODEC.decode(in);
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeUUID(entityUuid);

        boolean attack = action instanceof ServerboundAttackPacket;
        out.writeBoolean(attack);
        if (attack) {
            ServerboundAttackPacket.STREAM_CODEC.encode(out, (ServerboundAttackPacket) action);
        } else if (action instanceof ServerboundInteractPacket interact) {
            ServerboundInteractPacket.STREAM_CODEC.encode(out, interact);
        } else {
            throw new IllegalArgumentException("Unsupported entity action packet " + action.getClass().getName());
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to run an action on a non-existent player with uuid " + uuid);
                return;
            }

            Entity entity = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) player.level()).moonrise$getEntityLookup().get(entityUuid); // MultiPaper

            if (entity == null) {
                LOGGER.warn(player.getScoreboardName() + " tried to run an action on a non-existent entity with uuid " + entityUuid);
                return;
            }

            Packet<ServerGamePacketListener> newPacket;

            // Refactor the entity id
            if (action instanceof ServerboundInteractPacket serverboundInteractPacket) {
                newPacket = new ServerboundInteractPacket(entity.getId(), serverboundInteractPacket.hand(), serverboundInteractPacket.location(), serverboundInteractPacket.usingSecondaryAction());
            } else if (action instanceof ServerboundAttackPacket) {
                newPacket = new ServerboundAttackPacket(entity.getId());
            } else {
                LOGGER.error("Unhandled action on entity " + action);
                return;
            }

            newPacket.handle(player.connection);
        });
    }
}
