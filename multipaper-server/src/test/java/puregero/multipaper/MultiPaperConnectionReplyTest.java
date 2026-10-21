package puregero.multipaper;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.NullableStringMessageReply;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Normal
class MultiPaperConnectionReplyTest {

    @Test
    void completesWithExpectedReplyType() throws Exception {
        CompletableFuture<BooleanMessageReply> future = new CompletableFuture<>();

        MultiPaperConnection.completeReply(future, BooleanMessageReply.class, new BooleanMessageReply(true));

        assertEquals(true, future.get().result);
    }

    @Test
    void completesExceptionallyWhenReplyTypeDoesNotMatch() {
        CompletableFuture<BooleanMessageReply> future = new CompletableFuture<>();

        MultiPaperConnection.completeReply(future, BooleanMessageReply.class, new NullableStringMessageReply("unexpected"));

        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        assertEquals(IllegalStateException.class, exception.getCause().getClass());
        assertEquals("Expected " + BooleanMessageReply.class.getName() + " reply but received " + NullableStringMessageReply.class.getName(), exception.getCause().getMessage());
    }
}
