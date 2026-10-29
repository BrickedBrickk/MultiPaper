package puregero.multipaper.mastermessagingprotocol.messages;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import puregero.multipaper.mastermessagingprotocol.ExtendedByteBuf;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageHandlerTest {

    @Test
    void replyCompletesTheMatchingFuture() throws Exception {
        TestHandler handler = new TestHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestMessage request = new TestMessage();
        var future = handler.setCallbackFuture(request, 5, TimeUnit.SECONDS);
        TestMessage reply = new TestMessage();
        reply.setTransactionId(request.getTransactionId());

        channel.writeInbound(reply);
        assertSame(reply, future.get(1, TimeUnit.SECONDS));
        channel.finishAndReleaseAll();
    }

    @Test
    void channelCloseFailsOutstandingReplyFuture() {
        TestHandler handler = new TestHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestMessage request = new TestMessage();
        var future = handler.setCallbackFuture(request, 5, TimeUnit.SECONDS);

        channel.finishAndReleaseAll();

        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof ClosedChannelException);
    }

    @Test
    void timeoutFailsAndRemovesTheReplyFuture() {
        TestHandler handler = new TestHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestMessage request = new TestMessage();
        var future = handler.setCallbackFuture(request, 10, TimeUnit.MILLISECONDS);

        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TimeoutException);
        assertTrue(!handler.removeCallback(request));
        channel.finishAndReleaseAll();
    }

    @Test
    void explicitRemovalCancelsAndRemovesTheReplyFuture() {
        TestHandler handler = new TestHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        TestMessage request = new TestMessage();
        var future = handler.setCallbackFuture(request, 5, TimeUnit.SECONDS);

        assertTrue(handler.removeCallback(request));
        assertThrows(CancellationException.class, future::join);
        assertTrue(!handler.removeCallback(request));
        channel.finishAndReleaseAll();
    }

    private static final class TestHandler extends MessageHandler<TestMessage> {
    }

    private static final class TestMessage extends Message<TestHandler> {
        @Override
        public void write(ExtendedByteBuf byteBuf) {
        }

        @Override
        public void handle(TestHandler handler) {
        }
    }
}
