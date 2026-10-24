package puregero.multipaper.externalserverprotocol;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class HelloPacketConnectionPreferenceTest {

    @Test
    void bothServersPreferTheSameConnectionDirection() {
        assertTrue(HelloPacket.isPreferredConnection("backend-a", "backend-b", true));
        assertTrue(HelloPacket.isPreferredConnection("backend-b", "backend-a", false));
        assertFalse(HelloPacket.isPreferredConnection("backend-a", "backend-b", false));
        assertFalse(HelloPacket.isPreferredConnection("backend-b", "backend-a", true));
    }

    @Test
    void preferredDirectionDoesNotDependOnClockValues() {
        assertTrue(HelloPacket.isPreferredConnection("alpha", "zeta", true));
        assertFalse(HelloPacket.isPreferredConnection("zeta", "alpha", true));
        assertTrue(HelloPacket.isPreferredConnection("zeta", "alpha", false));
    }

    @Test
    void acceptsFirstOrNewerPreferredConnection() {
        assertEquals(HelloPacket.ConnectionDecision.ACCEPT,
                HelloPacket.decideConnection(true, false, false, 0, 10));
        assertEquals(HelloPacket.ConnectionDecision.ACCEPT,
                HelloPacket.decideConnection(true, false, true, 10, 11));
    }

    @Test
    void ignoresNegotiatedNonceEchoOnTheCurrentConnection() {
        assertEquals(HelloPacket.ConnectionDecision.IGNORE_DUPLICATE,
                HelloPacket.decideConnection(true, true, true, 10, 10));
        assertEquals(HelloPacket.ConnectionDecision.IGNORE_DUPLICATE,
                HelloPacket.decideConnection(true, true, true, 10, 1));
    }

    @Test
    void rejectsStaleOrNonPreferredConnections() {
        assertEquals(HelloPacket.ConnectionDecision.REJECT,
                HelloPacket.decideConnection(true, false, true, 11, 10));
        assertEquals(HelloPacket.ConnectionDecision.REJECT,
                HelloPacket.decideConnection(false, false, false, 0, 10));
    }
}
