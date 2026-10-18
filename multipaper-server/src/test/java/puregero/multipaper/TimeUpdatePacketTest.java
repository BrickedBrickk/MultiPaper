package puregero.multipaper;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import puregero.multipaper.externalserverprotocol.TimeUpdatePacket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
public class TimeUpdatePacketTest {

    @Test
    void catchesUpClockWhenPeerIsMoreThanOneSecondAhead() {
        assertTrue(TimeUpdatePacket.shouldUpdateTime(100, 121, false));
    }

    @Test
    void doesNotMoveClockBackOrApplySmallDifferences() {
        assertFalse(TimeUpdatePacket.shouldUpdateTime(100, 120, false));
        assertFalse(TimeUpdatePacket.shouldUpdateTime(121, 100, false));
    }

    @Test
    void forcedUpdateCanMoveClockBack() {
        assertTrue(TimeUpdatePacket.shouldUpdateTime(200, 100, true));
    }
}
