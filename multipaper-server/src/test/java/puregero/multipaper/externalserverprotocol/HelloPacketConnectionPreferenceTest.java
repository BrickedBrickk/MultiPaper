package puregero.multipaper.externalserverprotocol;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

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
}
