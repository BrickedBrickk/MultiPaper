package puregero.multipaper;

import net.minecraft.server.level.ServerPlayer;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

@Normal
class PlayerSyncSessionTest {

    @Test
    void keepsTheSameSessionIdentityAfterThePlayerConnectionIsCleared() {
        ServerPlayer disconnectedPlayer = mock(ServerPlayer.class);

        UUID sessionId = PlayerSyncSession.getSessionId(disconnectedPlayer);

        assertNotNull(sessionId);
        assertEquals(sessionId, PlayerSyncSession.getSessionId(disconnectedPlayer));
    }
}
