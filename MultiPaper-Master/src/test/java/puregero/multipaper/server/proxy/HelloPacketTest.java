package puregero.multipaper.server.proxy;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelloPacketTest {

    @Test
    void preservesFloodgateStyleNulDelimitedHandshakeData() {
        String host = "geyser.example\0floodgate-v2\0opaque-encrypted-payload";
        ByteBuffer buffer = ByteBuffer.allocate(512);
        new HelloPacket(777, host, (short) 25565, (byte) 2).write(buffer);
        buffer.flip();

        HelloPacket parsed = HelloPacket.read(buffer);

        assertNotNull(parsed);
        assertEquals(host, parsed.host);
        assertEquals(host, HelloPacket.appendForwardingAddressIfMissing(parsed.host, "192.0.2.10"));
    }

    @Test
    void addsAddressToOrdinaryHandshakeWithoutExistingForwardingData() {
        assertEquals("geyser.example" + '\0' + "192.0.2.10",
            HelloPacket.appendForwardingAddressIfMissing("geyser.example", "192.0.2.10"));
    }

    @Test
    void waitsUntilTheCompleteHandshakeFrameArrives() {
        ByteBuffer encoded = ByteBuffer.allocate(512);
        new HelloPacket(777, "play.example", (short) 25565, (byte) 2).write(encoded);
        byte[] packet = Arrays.copyOf(encoded.array(), encoded.position());

        for (int available = 0; available < packet.length; available++) {
            ByteBuffer partial = ByteBuffer.wrap(Arrays.copyOf(packet, available));
            assertFalse(HelloPacket.isComplete(partial), "available bytes: " + available);
        }

        assertTrue(HelloPacket.isComplete(ByteBuffer.wrap(packet)));
    }
}
