package puregero.multipaper;

import com.mojang.authlib.GameProfile;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class ExternalPlayerSyncedDataTest {

    @Test
    void doesNotBroadcastSuperclassConstructionUpdatesWithoutAProfile() {
        assertFalse(ExternalPlayer.shouldBroadcastSyncedData(null, false));
    }

    @Test
    void broadcastsUpdatesAfterThePlayerProfileIsInitialized() {
        assertTrue(ExternalPlayer.shouldBroadcastSyncedData(new GameProfile(UUID.randomUUID(), "Player"), false));
    }

    @Test
    void doesNotBroadcastUpdatesWhileApplyingSynchronizedData() {
        assertFalse(ExternalPlayer.shouldBroadcastSyncedData(new GameProfile(UUID.randomUUID(), "Player"), true));
    }
}
