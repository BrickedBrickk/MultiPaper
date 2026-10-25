package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerChangeDimensionPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerChangeDimensionPacket.class.getSimpleName());

    private final UUID uuid;
    private final ResourceKey<Level> dimension;
    private final double x;
    private final double y;
    private final double z;
    private final boolean reset;

    public PlayerChangeDimensionPacket(ServerPlayer player, boolean reset) {
        this.uuid = player.getUUID();
        this.dimension = player.level().dimension();
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
        this.reset = reset;
    }

    public PlayerChangeDimensionPacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        dimension = readDimension(in);
        x = in.readDouble();
        y = in.readDouble();
        z = in.readDouble();
        reset = in.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeUtf(dimension.identifier().toString());
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        out.writeBoolean(reset);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            MinecraftServer server = MinecraftServer.getServer();
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                LOGGER.error("Could not change remote player {} to unloaded dimension {}", uuid, dimension.identifier());
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                LOGGER.warn("Could not change dimension for missing remote player {} to {}", uuid, dimension.identifier());
                return;
            }

            // Remove from old world
            ((ServerLevel) player.level()).removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);

            player.setPosRaw(x, y, z);

            if (reset) {
                player.keepLevel = false;
                player.newLevel = 0;
                player.newTotalExp = 0;
                player.expToDrop = 0;
                player.newExp = 0;

                player.reset();
            }

            // Add to new world
            player.setServerLevel(level);
            player.unsetRemoved();
            level.addRespawnedPlayer(player);
        });
    }

    static ResourceKey<Level> readDimension(FriendlyByteBuf in) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(in.readUtf()));
    }

}
