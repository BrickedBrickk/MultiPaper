package puregero.multipaper;

import org.junit.jupiter.api.Test;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.StartMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ServerStartMessageTrackerTest {
    @Test
    void registrationIsSentOnInitialConnectAndEveryReconnect() {
        ServerStartMessageTracker tracker = new ServerStartMessageTracker();
        StartMessage registration = new StartMessage("192.0.2.10", 25566);
        List<StartMessage> sent = new ArrayList<>();

        tracker.set(registration, sent::add);
        assertEquals(0, sent.size(), "A disconnected Master cannot receive the registration yet");

        tracker.onConnect(sent::add);
        assertEquals(1, sent.size(), "The initial Master connection must register the backend");
        assertSame(registration, sent.get(0));

        tracker.onDisconnect();
        tracker.onConnect(sent::add);
        assertEquals(2, sent.size(), "A replacement Master connection must receive the registration again");
        assertSame(registration, sent.get(1));
    }

    @Test
    void registrationUpdateIsSentOnceAndTheLatestValueSurvivesReconnect() {
        ServerStartMessageTracker tracker = new ServerStartMessageTracker();
        StartMessage initial = new StartMessage("192.0.2.10", 25566);
        StartMessage updated = new StartMessage("192.0.2.11", 25567);
        List<StartMessage> sent = new ArrayList<>();

        tracker.set(initial, sent::add);
        tracker.onConnect(sent::add);
        tracker.set(updated, sent::add);
        assertEquals(2, sent.size(), "Updating an active backend registration should send one update");
        assertSame(updated, sent.get(1));

        tracker.onDisconnect();
        tracker.onConnect(sent::add);
        assertEquals(3, sent.size());
        assertSame(updated, sent.get(2), "Reconnect must use the latest registration");
    }
}
