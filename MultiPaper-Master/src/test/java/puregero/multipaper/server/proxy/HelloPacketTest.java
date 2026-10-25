package puregero.multipaper.server.proxy;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
