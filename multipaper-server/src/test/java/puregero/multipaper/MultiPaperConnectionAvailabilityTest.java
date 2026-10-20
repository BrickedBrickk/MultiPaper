package puregero.multipaper;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.Channel;
import puregero.multipaper.externalserverprotocol.ExternalServerPacket;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteTickTimeMessage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Normal
class MultiPaperConnectionAvailabilityTest {

    @Test
    void transientTickSampleIsDroppedWhenThereIsNoChannel() {
        assertFalse(MultiPaperConnection.trySendIfActive(null, false, new WriteTickTimeMessage(1, 20.0f)));
    }

    @Test
    void transientTickSampleIsDroppedWhileChannelIsNotReady() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertFalse(MultiPaperConnection.trySendIfActive(channel, false, new WriteTickTimeMessage(1, 20.0f)));
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void transientTickSampleIsDroppedAfterChannelCloses() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.close();
        try {
            assertFalse(MultiPaperConnection.trySendIfActive(channel, true, new WriteTickTimeMessage(1, 20.0f)));
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void transientTickSampleIsSentWhenChannelIsReady() {
        EmbeddedChannel channel = new EmbeddedChannel();
        WriteTickTimeMessage message = new WriteTickTimeMessage(1, 20.0f);
        try {
            assertTrue(MultiPaperConnection.trySendIfActive(channel, true, message));
            assertSame(message, channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void externalPacketSendIsRejectedWhenPeerChannelIsClosed() throws Exception {
        Channel channel = mock(Channel.class);
        when(channel.isOpen()).thenReturn(false);
        ExternalServerConnection connection = new ExternalServerConnection();
        java.lang.reflect.Field channelField = ExternalServerConnection.class.getDeclaredField("channel");
        channelField.setAccessible(true);
        channelField.set(connection, channel);

        assertFalse(connection.send(mock(ExternalServerPacket.class)));
    }
}
