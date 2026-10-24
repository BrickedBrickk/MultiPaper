package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaperExternalBlocksHandler;

/** Acknowledges durable staging of one scheduled-tick handoff. */
public final class TickListAckPacket extends ExternalServerPacket {
    private final String world;
    private final int cx;
    private final int cz;
    private final String transferId;

    public TickListAckPacket(String world, int cx, int cz, String transferId) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.transferId = transferId;
    }

    public TickListAckPacket(FriendlyByteBuf in) {
        world = in.readUtf();
        cx = in.readInt();
        cz = in.readInt();
        transferId = in.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeInt(cx);
        out.writeInt(cz);
        out.writeUtf(transferId);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaperExternalBlocksHandler.acknowledgeTickList(world, cx, cz, transferId);
    }
}
