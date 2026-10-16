package net.minecraft.server.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerHandshakePacketListenerImplTest {

    @Test
    void acceptsTheAddressOnlyForwardingUsedByTheBuiltInMultiPaperProxy() {
        assertTrue(ServerHandshakePacketListenerImpl.isValidBungeeForwarding(
            new String[] {"play.example", "192.0.2.10"}
        ));
    }

    @Test
    void retainsStandardBungeeForwardingWithUuidAndProperties() {
        assertTrue(ServerHandshakePacketListenerImpl.isValidBungeeForwarding(
            new String[] {"play.example", "192.0.2.10", "0123456789abcdef0123456789abcdef"}
        ));
        assertTrue(ServerHandshakePacketListenerImpl.isValidBungeeForwarding(
            new String[] {"play.example", "192.0.2.10", "0123456789abcdef0123456789abcdef", "[]"}
        ));
    }

    @Test
    void rejectsForwardingWithMissingAddressOrUnsupportedFieldCount() {
        assertFalse(ServerHandshakePacketListenerImpl.isValidBungeeForwarding(new String[] {"play.example"}));
        assertFalse(ServerHandshakePacketListenerImpl.isValidBungeeForwarding(
            new String[] {"play.example", "192.0.2.10", "uuid", "[]", "extra"}
        ));
    }

    @Test
    void rejectsNonAddressForwardedHosts() {
        assertFalse(ServerHandshakePacketListenerImpl.isValidBungeeForwarding(
            new String[] {"play.example", "not-an-ip"}
        ));
    }
}
