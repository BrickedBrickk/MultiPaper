package puregero.multipaper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnderChestSyncRevisionTest {
    @Test
    void rejectsReplayedAndOutOfOrderOwnerUpdates() {
        EnderChestSyncRevision revisions = new EnderChestSyncRevision();

        assertTrue(revisions.accept(4));
        assertTrue(revisions.accept(7));
        assertFalse(revisions.accept(7), "a replay must not apply twice");
        assertFalse(revisions.accept(5), "a delayed old slot image must not overwrite newer state");
        assertFalse(revisions.accept(-1), "unversioned legacy updates fail closed");
        assertEquals(7, revisions.lastAccepted());
        assertTrue(revisions.accept(8));
        assertEquals(8, revisions.lastAccepted());
    }
}
