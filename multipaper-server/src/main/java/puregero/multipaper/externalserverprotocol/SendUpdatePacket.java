package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperChunkHandler;
import puregero.multipaper.MultiPaperWorldBorderHandler;
import puregero.multipaper.PacketSerialization;

import java.util.UUID;

public class SendUpdatePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(SendUpdatePacket.class.getSimpleName());

    private final UUID world;
    private final Packet<?> packet;

    public SendUpdatePacket(ServerLevel level, Packet<?> packet) {
        this(level.getWorld().getUID(), packet);
    }

    public SendUpdatePacket(UUID world, Packet<?> packet) {
        this.world = world;
        this.packet = packet;
    }

    public SendUpdatePacket(FriendlyByteBuf in) {
        world = in.readUUID();

        packet = PacketSerialization.readClientbound(in.readByteArray());
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(world);

        out.writeByteArray(PacketSerialization.writeClientbound(packet));
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        if (MultiPaper.DEBUG) { // MultiPaper
            String packetDetails = packet instanceof ClientboundBlockUpdatePacket update ? " pos=" + update.getPos() + " state=" + update.getBlockState() : "";
            MultiPaper.debug("recv " + packet.getClass().getSimpleName() + packetDetails + " world=" + world + " from=" + connection.externalServer.getName());
        } // MultiPaper
        if (packet instanceof ClientboundBlockUpdatePacket || packet instanceof ClientboundSectionBlocksUpdatePacket || packet instanceof ClientboundLightUpdatePacket || packet instanceof ClientboundBlockEntityDataPacket) {
            MultiPaper.runSync(() -> MultiPaperChunkHandler.handleBlockUpdate(world, packet, 0));
        } else if (packet instanceof ClientboundSetBorderSizePacket || packet instanceof ClientboundSetBorderLerpSizePacket || packet instanceof ClientboundSetBorderCenterPacket || packet instanceof ClientboundSetBorderWarningDelayPacket || packet instanceof ClientboundSetBorderWarningDistancePacket) {
            MultiPaper.runSync(() -> MultiPaperWorldBorderHandler.handle(world, packet));
        } else {
            LOGGER.warn("Unhandled update packet of type " + packet.getClass().getSimpleName());
        }
    }
}
