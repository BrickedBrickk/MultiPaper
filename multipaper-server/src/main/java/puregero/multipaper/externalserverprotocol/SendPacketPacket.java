package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperInventoryHandler;
import puregero.multipaper.PacketSerialization;

import java.util.List;
import java.util.UUID;

public class SendPacketPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(SendPacketPacket.class.getSimpleName());

    private final UUID[] uuids;
    private final Packet<?> packet;

    public SendPacketPacket(List<? extends ServerPlayer> players, Packet<?> packet) {
        this.uuids = new UUID[players.size()];
        for (int i = 0; i < players.size(); i++) {
            this.uuids[i] = players.get(i).getUUID();
        }
        this.packet = packet;
    }

    public SendPacketPacket(ServerPlayer player, Packet<?> packet) {
        this.uuids = new UUID[1];
        this.uuids[0] = player.getUUID();
        this.packet = packet;
    }

    public SendPacketPacket(FriendlyByteBuf in) {
        uuids = new UUID[in.readInt()];
        for (int i = 0; i < uuids.length; i++) {
            this.uuids[i] = in.readUUID();
        }

        packet = PacketSerialization.readClientbound(in.readByteArray());
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeInt(uuids.length);
        for (UUID uuid : uuids) {
            out.writeUUID(uuid);
        }

        out.writeByteArray(PacketSerialization.writeClientbound(packet));
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        if (packet instanceof ClientboundPlayerPositionPacket) {
            MultiPaper.runSync(() -> doHandle(connection));
        } else {
            doHandle(connection);
        }
    }

    public void doHandle(ExternalServerConnection connection) {
        for (UUID uuid : uuids) {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to send a packet to a non-existent player uuid " + uuid);
                continue;
            }

            if (MultiPaperInventoryHandler.handlePacketFromExternalServer(connection.externalServer, player, packet)) {
                continue;
            }

            player.connection.send(packet);
        }
    }
}
