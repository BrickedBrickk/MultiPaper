package puregero.multipaper;

import org.junit.jupiter.api.Test;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerStartedMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerServerStartTrackerTest {
    @Test
    void retainsEarlyAnnouncementAndDispatchesItOnceWhenReady() {
        PeerServerStartTracker tracker = new PeerServerStartTracker();
        ServerStartedMessage announcement = new ServerStartedMessage("127.0.0.1", 25566);
        List<ServerStartedMessage> connections = new ArrayList<>();

        assertTrue(tracker.enqueue(announcement));
        assertFalse(tracker.enqueue(new ServerStartedMessage("127.0.0.1", 25566)), "Duplicate early announcements must coalesce");
        assertFalse(tracker.dispatchIfReady(announcement, false, connections::add));
        assertTrue(tracker.isPending(announcement), "The announcement must remain queued until startup completes");
        assertTrue(tracker.dispatchIfReady(announcement, true, connections::add));
        assertFalse(tracker.dispatchIfReady(announcement, true, connections::add), "A queued announcement must connect only once");
        assertEquals(List.of(announcement), connections);
    }

    @Test
    void canDiscardAnAnnouncementAfterItsStartupDeadline() {
        PeerServerStartTracker tracker = new PeerServerStartTracker();
        ServerStartedMessage announcement = new ServerStartedMessage("127.0.0.1", 25566);
        assertTrue(tracker.enqueue(announcement));
        assertTrue(tracker.discard(announcement));
        assertFalse(tracker.isPending(announcement));
    }
}
