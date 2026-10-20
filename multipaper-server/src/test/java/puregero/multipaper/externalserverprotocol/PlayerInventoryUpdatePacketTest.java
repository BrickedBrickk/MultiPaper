package puregero.multipaper.externalserverprotocol;

import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;
import puregero.multipaper.ExternalPlayer;
import puregero.multipaper.ExternalServerConnection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PlayerInventoryUpdatePacketTest {

    @Test
    void acceptsUpdatesFromTheBackendCurrentlyOwningTheMirroredPlayer() {
        ExternalServerConnection owner = new ExternalServerConnection();
        ExternalPlayer player = mock(ExternalPlayer.class);
        player.externalServerConnection = owner;

        assertTrue(PlayerInventoryUpdatePacket.isCurrentOwner(player, owner));
    }

    @Test
    void rejectsUpdatesFromTheBackendThatPreviouslyOwnedThePlayer() {
        ExternalPlayer player = mock(ExternalPlayer.class);
        player.externalServerConnection = new ExternalServerConnection();

        assertFalse(PlayerInventoryUpdatePacket.isCurrentOwner(player, new ExternalServerConnection()));
    }

    @Test
    void doesNotApplyPeerInventoryUpdatesToALocallyOwnedPlayer() {
        assertFalse(PlayerInventoryUpdatePacket.isCurrentOwner(mock(ServerPlayer.class), new ExternalServerConnection()));
    }

    @Test
    void rejectsAnUpdateWhenThePlayerIsMissing() {
        assertFalse(PlayerInventoryUpdatePacket.isCurrentOwner(null, new ExternalServerConnection()));
    }
}
