package puregero.multipaper.externalserverprotocol;

import net.minecraft.world.entity.Entity;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class ExpiringEntityRemovalsTest {

    @Test
    void anOldExpiryCannotClearARefreshedRemovalMarker() {
        ExpiringEntityRemovals removals = new ExpiringEntityRemovals();
        UUID uuid = UUID.randomUUID();

        ExpiringEntityRemovals.Marker first = removals.markRemoved(uuid, Entity.RemovalReason.KILLED);
        ExpiringEntityRemovals.Marker refreshed = removals.markRemoved(uuid, Entity.RemovalReason.KILLED);

        assertNotSame(first, refreshed);
        assertFalse(removals.expire(uuid, first));
        assertTrue(removals.contains(uuid));
        assertEquals(Entity.RemovalReason.KILLED, removals.getReason(uuid));
        assertTrue(removals.expire(uuid, refreshed));
        assertFalse(removals.contains(uuid));
        assertNull(removals.getReason(uuid));
    }

    @Test
    void anOlderMarkerCannotExpireAReplacementWithAnotherRemovalReason() {
        ExpiringEntityRemovals removals = new ExpiringEntityRemovals();
        UUID uuid = UUID.randomUUID();

        ExpiringEntityRemovals.Marker first = removals.markRemoved(uuid, Entity.RemovalReason.KILLED);
        ExpiringEntityRemovals.Marker replacement = removals.markRemoved(uuid, Entity.RemovalReason.DISCARDED);

        assertFalse(removals.expire(uuid, first));
        assertEquals(Entity.RemovalReason.DISCARDED, removals.getReason(uuid));
        assertTrue(removals.expire(uuid, replacement));
    }
}
