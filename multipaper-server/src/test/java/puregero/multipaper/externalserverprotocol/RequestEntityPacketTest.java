package puregero.multipaper.externalserverprotocol;

import net.minecraft.world.entity.Entity;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
