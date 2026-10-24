package puregero.multipaper.externalserverprotocol;

import net.minecraft.world.entity.Entity;
import puregero.multipaper.ExternalServer;
import puregero.multipaper.ExternalServerConnection;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Normal
class RequestEntityPacketTest {

    @Test
    void answersRequestsForRecentlyKilledOrDiscardedEntitiesWithRemoval() {
        assertTrue(RequestEntityPacket.shouldAnswerMissingRequestWithRemoval(Entity.RemovalReason.KILLED));
        assertTrue(RequestEntityPacket.shouldAnswerMissingRequestWithRemoval(Entity.RemovalReason.DISCARDED));
        assertTrue(RequestEntityPacket.shouldAnswerMissingRequestWithRemoval(Entity.RemovalReason.UNLOADED_TO_CHUNK));
        assertTrue(RequestEntityPacket.shouldAnswerMissingRequestWithRemoval(Entity.RemovalReason.CHANGED_DIMENSION));
    }

    @Test
    void doesNotTreatPlayerTransferOrUnknownEntityAsTerminalRemoval() {
        assertFalse(RequestEntityPacket.shouldAnswerMissingRequestWithRemoval(Entity.RemovalReason.UNLOADED_WITH_PLAYER));
        assertFalse(RequestEntityPacket.shouldAnswerMissingRequestWithRemoval(null));
    }

    @Test
    void routesRepairToCurrentOwnerInsteadOfThePeerThatSentAStaleUpdate() {
        ExternalServerConnection source = new ExternalServerConnection();
        ExternalServerConnection currentOwnerConnection = new ExternalServerConnection();
        ExternalServer currentOwner = new ExternalServer("current-owner", false);
        currentOwner.setConnection(currentOwnerConnection);

        assertSame(currentOwnerConnection, RequestEntityPacket.resolveRequestConnection(source, currentOwner));
    }

    @Test
    void doesNotRequestAStalePeerUpdateWhenThisBackendOwnsTheChunk() {
        ExternalServerConnection source = new ExternalServerConnection();
        ExternalServer localOwner = new ExternalServer("local-owner", true);

        assertNull(RequestEntityPacket.resolveRequestConnection(source, localOwner));
    }

    @Test
    void fallsBackToSourceWhenCurrentOwnerIsUnavailable() {
        ExternalServerConnection source = new ExternalServerConnection();
        ExternalServer unavailableOwner = new ExternalServer("unavailable-owner", false);

        assertSame(source, RequestEntityPacket.resolveRequestConnection(source, unavailableOwner));
        assertSame(source, RequestEntityPacket.resolveRequestConnection(source, null));
    }
}
