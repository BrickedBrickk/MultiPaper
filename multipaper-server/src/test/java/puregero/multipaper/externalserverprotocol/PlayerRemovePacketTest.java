package puregero.multipaper.externalserverprotocol;

import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;
import puregero.multipaper.ExternalPlayer;
import puregero.multipaper.ExternalServerConnection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PlayerRemovePacketTest {

    @Test
    void removesAnExternalPlayerOwnedByThePacketSource() {
        ExternalServerConnection source = new ExternalServerConnection();
        ExternalPlayer player = mock(ExternalPlayer.class);
        player.externalServerConnection = source;

        assertTrue(PlayerRemovePacket.shouldRemovePlayer(player, source));
    }

    @Test
    void doesNotLetAStaleRemovalDisconnectALocallyOwnedPlayer() {
        ServerPlayer localPlayer = mock(ServerPlayer.class);

        assertFalse(PlayerRemovePacket.shouldRemovePlayer(localPlayer, new ExternalServerConnection()));
    }

    @Test
    void doesNotRemoveAnExternalPlayerOwnedByAnotherConnection() {
        ExternalPlayer player = mock(ExternalPlayer.class);
        player.externalServerConnection = new ExternalServerConnection();

        assertFalse(PlayerRemovePacket.shouldRemovePlayer(player, new ExternalServerConnection()));
    }

    @Test
    void ignoresMissingPlayers() {
        assertFalse(PlayerRemovePacket.shouldRemovePlayer(null, new ExternalServerConnection()));
    }
}
