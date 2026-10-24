package puregero.multipaper;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Normal
class MultiPaperEntitiesHandlerRemovalRoutingTest {

    @Test
    void sendsToConnectedRemoteOwnerWithoutBroadcastingFallback() {
        ExternalServer owner = connectedRemoteOwner();
        AtomicBoolean fallbackCalled = new AtomicBoolean();

        boolean sentToOwner = MultiPaperEntitiesHandler.routeRemovalToOwnerOrFallback(owner, () -> true,
                () -> fallbackCalled.set(true));

        assertTrue(sentToOwner);
        assertFalse(fallbackCalled.get());
    }

    @Test
    void broadcastsWhenOwnerSendFails() {
        ExternalServer owner = connectedRemoteOwner();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger fallbacks = new AtomicInteger();

        boolean sentToOwner = MultiPaperEntitiesHandler.routeRemovalToOwnerOrFallback(owner, () -> {
            attempts.incrementAndGet();
            return false;
        }, fallbacks::incrementAndGet);

        assertFalse(sentToOwner);
        assertEquals(1, attempts.get());
        assertEquals(1, fallbacks.get());
    }

    @Test
    void broadcastsWhenOwnerIsMissingOrNotRemote() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger fallbacks = new AtomicInteger();

        assertFalse(MultiPaperEntitiesHandler.routeRemovalToOwnerOrFallback(null,
                () -> { attempts.incrementAndGet(); return true; }, fallbacks::incrementAndGet));

        ExternalServer self = new ExternalServer("backend-a", true);
        self.setConnection(mock(ExternalServerConnection.class));
        assertFalse(MultiPaperEntitiesHandler.routeRemovalToOwnerOrFallback(self,
                () -> { attempts.incrementAndGet(); return true; }, fallbacks::incrementAndGet));

        ExternalServer disconnected = new ExternalServer("backend-b", false);
        assertFalse(MultiPaperEntitiesHandler.routeRemovalToOwnerOrFallback(disconnected,
                () -> { attempts.incrementAndGet(); return true; }, fallbacks::incrementAndGet));

        assertEquals(0, attempts.get(), "an unavailable or local owner must not receive a request");
        assertEquals(3, fallbacks.get());
    }

    private static ExternalServer connectedRemoteOwner() {
        ExternalServer owner = new ExternalServer("backend-b", false);
        owner.setConnection(mock(ExternalServerConnection.class));
        return owner;
    }
}
