package puregero.multipaper.mastermessagingprotocol.messages;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import puregero.multipaper.mastermessagingprotocol.datastream.DataStreamManager;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public abstract class MessageHandler<T extends Message> extends SimpleChannelInboundHandler<T> {

    private final Map<Integer, Consumer<T>> callbacks = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<T>> replyFutures = new ConcurrentHashMap<>();
    private final AtomicInteger transactionId = new AtomicInteger(1);
    private final DataStreamManager<T> dataStreamManager = new DataStreamManager<>(this);

    public DataStreamManager<T> getDataStreamManager() {
        return dataStreamManager;
    }

    public <X extends Message<?>> X createDataStreamMessage(int streamId, byte[] data, int offset, int length) {
        throw new UnsupportedOperationException();
    }

    public <X extends Message<?>> X setCallback(X message, Consumer<T> callback) {
        message.setTransactionId(transactionId.incrementAndGet());
        callbacks.put(message.getTransactionId(), callback);
        return message;
    }

    /**
     * Registers a reply future for an outbound request. The future is failed if
     * the channel closes or the bounded reply timeout expires, and its entry is
     * removed on every completion path.
     */
    public <X extends Message<?>> CompletableFuture<T> setCallbackFuture(X message, long timeout, TimeUnit unit) {
        if (timeout <= 0) {
            throw new IllegalArgumentException("Reply timeout must be positive");
        }
        java.util.Objects.requireNonNull(unit, "unit");
        message.setTransactionId(transactionId.incrementAndGet());
        int id = message.getTransactionId();
        CompletableFuture<T> future = new CompletableFuture<>();
        replyFutures.put(id, future);
        future.whenComplete((reply, failure) -> replyFutures.remove(id, future));
        CompletableFuture.delayedExecutor(timeout, unit).execute(() ->
                future.completeExceptionally(new TimeoutException("Timed out waiting for reply to message transaction " + id)));
        return future;
    }

    public boolean removeCallback(Message<?> message) {
        Consumer<T> callback = callbacks.remove(message.getTransactionId());
        CompletableFuture<T> future = replyFutures.remove(message.getTransactionId());
        if (future != null) {
            future.completeExceptionally(new CancellationException("Reply callback removed for message transaction " + message.getTransactionId()));
        }
        return callback != null || future != null;
    }

    public boolean completeCallback(Message<?> request, T response) {
        Consumer<T> callback = callbacks.remove(request.getTransactionId());
        if (callback == null) {
            return false;
        }
        callback.accept(response);
        return true;
    }

    /**
     * Called when a message is received.
     * @param message The message that has been received
     * @return true if we have handled it, or false if the normal handler
     *         should handle it
     */
    public boolean onMessage(T message) {
        return false;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, T message) {
        Consumer<T> callback = callbacks.remove(message.getTransactionId());
        if (callback != null) {
            callback.accept(message);
            return;
        }
        CompletableFuture<T> future = replyFutures.remove(message.getTransactionId());
        if (future != null) {
            future.complete(message);
            return;
        }

        if (!onMessage(message)) {
            try {
                message.handle(this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        callbacks.clear();
        java.nio.channels.ClosedChannelException failure = new java.nio.channels.ClosedChannelException();
        replyFutures.forEach((id, future) -> {
            if (replyFutures.remove(id, future)) {
                future.completeExceptionally(failure);
            }
        });
        super.channelInactive(ctx);
    }

}
