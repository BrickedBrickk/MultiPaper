package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerSetRespawnPosition extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerSetRespawnPosition.class.getSimpleName());
    private static boolean settingRespawnPosition = false;

    private final UUID uuid;
    private final ResourceKey<Level> dimension;
    private final BlockPos pos;
    private final float angle;
    private final boolean forced;

    public PlayerSetRespawnPosition(ServerPlayer player) {
        this.uuid = player.getUUID();
        ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        this.dimension = config == null ? Level.OVERWORLD : config.respawnData().dimension();
        this.pos = config == null ? null : config.respawnData().pos();
        this.angle = config == null ? 0.0F : config.respawnData().yaw();
        this.forced = config != null && config.forced();
    }

    public PlayerSetRespawnPosition(FriendlyByteBuf in) {
        uuid = in.readUUID();
        dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(in.readUtf()));
        pos = in.readBoolean() ? BlockPos.of(in.readLong()) : null;
        angle = in.readFloat();
        forced = in.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeUtf(dimension.identifier().toString());

        out.writeBoolean(pos != null);
        if (pos != null) {
            out.writeLong(pos.asLong());
        }

        out.writeFloat(angle);
        out.writeBoolean(forced);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to set a respawn position on a non-existent player with uuid " + uuid);
                return;
            }

            settingRespawnPosition = true;
            ServerPlayer.RespawnConfig config = pos == null ? null : new ServerPlayer.RespawnConfig(
                new net.minecraft.world.level.storage.LevelData.RespawnData(net.minecraft.core.GlobalPos.of(dimension, pos), angle, 0.0F), forced
            );
            player.setRespawnPosition(config, false);
            settingRespawnPosition = false;
        });
    }

    public static void broadcastRespawnPosition(ServerPlayer player) {
        if (!settingRespawnPosition) {
            MultiPaper.broadcastPacketToExternalServers(player, new PlayerSetRespawnPosition(player));
        }
    }
}
