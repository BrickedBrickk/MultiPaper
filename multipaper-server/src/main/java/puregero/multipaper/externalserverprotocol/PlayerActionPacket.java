package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import puregero.multipaper.ExternalPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.PacketSerialization;

import java.util.UUID;

public class PlayerActionPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerActionPacket.class.getSimpleName());

    private final UUID uuid;
    private final Packet<?> action;

    public PlayerActionPacket(ServerPlayer player, Packet<?> action) {
        this.uuid = player.getUUID();
        this.action = action;
    }

    public PlayerActionPacket(FriendlyByteBuf in) {
        uuid = in.readUUID();

        action = PacketSerialization.readServerbound(in.readByteArray());
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);

        out.writeByteArray(PacketSerialization.writeServerbound(action));
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to run an action on a non-existent player with uuid " + uuid);
                return;
            }

            if (action instanceof ServerboundClientInformationPacket clientInformation) {
                player.updateOptions(clientInformation.information());
            } else {
                @SuppressWarnings("unchecked")
                Packet<ServerGamePacketListener> gamePacket = (Packet<ServerGamePacketListener>) action;
                gamePacket.handle(player.connection);
                if (player instanceof ExternalPlayer externalPlayer && action instanceof ServerboundSetCarriedItemPacket) {
                    externalPlayer.syncSelectedSlotEquipmentToLocalViewers();
                }
            }
        });
    }
}
