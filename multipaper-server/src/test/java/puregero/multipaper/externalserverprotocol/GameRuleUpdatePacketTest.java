package puregero.multipaper.externalserverprotocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameRuleUpdatePacketTest {

    @AfterEach
    void clearSuppressionFlag() {
        GameRuleUpdatePacket.updatingGamerules = false;
    }

    @Test
    void restoresGameruleBroadcastingWhenRemoteUpdateThrows() {
        assertFalse(GameRuleUpdatePacket.updatingGamerules);

        assertThrows(IllegalStateException.class, () ->
                GameRuleUpdatePacket.runWithGameruleUpdatesSuppressed(() -> {
                    assertTrue(GameRuleUpdatePacket.updatingGamerules);
                    throw new IllegalStateException("synthetic invalid remote gamerule update");
                }));

        assertFalse(GameRuleUpdatePacket.updatingGamerules,
                "a failed remote update must not leave later local gamerules unsynchronized");
    }

    @Test
    void preservesAnOuterSuppressionScope() {
        GameRuleUpdatePacket.updatingGamerules = true;
        GameRuleUpdatePacket.runWithGameruleUpdatesSuppressed(() -> {
            assertTrue(GameRuleUpdatePacket.updatingGamerules);
            GameRuleUpdatePacket.runWithGameruleUpdatesSuppressed(() ->
                    assertTrue(GameRuleUpdatePacket.updatingGamerules));
            assertTrue(GameRuleUpdatePacket.updatingGamerules);
        });
        assertTrue(GameRuleUpdatePacket.updatingGamerules);
    }
}
