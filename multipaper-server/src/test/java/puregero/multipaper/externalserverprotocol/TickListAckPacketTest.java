package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@Normal
class TickListAckPacketTest {
    @Test
    void acknowledgementWireFormatRoundTripsStableTransferIdentity() {
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        new TickListAckPacket("world", -12, 34, "transfer-123").write(encoded);
        byte[] bytes = new byte[encoded.readableBytes()];
        encoded.getBytes(encoded.readerIndex(), bytes);

        FriendlyByteBuf decodedInput = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        FriendlyByteBuf reencoded = new FriendlyByteBuf(Unpooled.buffer());
        new TickListAckPacket(decodedInput).write(reencoded);
        byte[] roundTrip = new byte[reencoded.readableBytes()];
        reencoded.getBytes(reencoded.readerIndex(), roundTrip);

        assertArrayEquals(bytes, roundTrip);
    }
}
