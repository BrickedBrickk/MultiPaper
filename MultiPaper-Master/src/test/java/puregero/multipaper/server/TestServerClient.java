package puregero.multipaper.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import puregero.multipaper.mastermessagingprotocol.MessageBootstrap;
import puregero.multipaper.mastermessagingprotocol.messages.Message;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.MasterBoundMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.MasterBoundProtocol;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.AddChunkSubscriberMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.AddEntitySubscriberMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ChunkSubscribersSyncMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataUpdateMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.EntitySubscribersSyncMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.FileContentMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.RemoveChunkSubscriberMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.RemoveEntitySubscriberMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerBoundMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerBoundMessageHandler;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerBoundProtocol;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerChangedChunkStatusMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerInfoUpdateMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerStartedMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.SetChunkOwnerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.SetSecretMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ShutdownMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.HelloMessage;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A fake MultiPaper server used to drive the master in integration tests.
 */
public class TestServerClient extends ServerBoundMessageHandler {

    public final String name;
    private final UUID serverUuid;
    private volatile boolean acknowledgePlayerDataTransactionCapability;
    private volatile Channel channel;

    private final CompletableFuture<String> secretFuture = new CompletableFuture<>();
    private final CompletableFuture<Boolean> capabilityFuture = new CompletableFuture<>();
    private final CompletableFuture<Boolean> sessionCapabilityFuture = new CompletableFuture<>();
    public final BlockingQueue<SetChunkOwnerMessage> chunkOwnerUpdates = new LinkedBlockingQueue<>();
    public final BlockingQueue<ServerStartedMessage> serverStartedMessages = new LinkedBlockingQueue<>();
    public final BlockingQueue<AddChunkSubscriberMessage> chunkSubscriberAdds = new LinkedBlockingQueue<>();
    public final BlockingQueue<RemoveChunkSubscriberMessage> chunkSubscriberRemoves = new LinkedBlockingQueue<>();
    public final BlockingQueue<AddEntitySubscriberMessage> entitySubscriberAdds = new LinkedBlockingQueue<>();
    public final BlockingQueue<RemoveEntitySubscriberMessage> entitySubscriberRemoves = new LinkedBlockingQueue<>();
    public final BlockingQueue<ChunkSubscribersSyncMessage> chunkSubscribersSyncs = new LinkedBlockingQueue<>();
    public final BlockingQueue<EntitySubscribersSyncMessage> entitySubscribersSyncs = new LinkedBlockingQueue<>();
    public final List<ServerInfoUpdateMessage> serverInfoUpdates = new ArrayList<>();
    public final List<ServerChangedChunkStatusMessage> chunkStatusChanges = new ArrayList<>();
    public volatile boolean shutdownReceived = false;

    private TestServerClient(String name, UUID serverUuid, boolean acknowledgePlayerDataTransactionCapability) {
        this.name = name;
        this.serverUuid = serverUuid;
        this.acknowledgePlayerDataTransactionCapability = acknowledgePlayerDataTransactionCapability;
    }

    public static TestServerClient connect(int port, String name) throws Exception {
        return connect(port, name, true);
    }

    public static TestServerClient connectLegacy(int port, String name) throws Exception {
        return connect(port, name, false);
    }

    private static TestServerClient connect(int port, String name, boolean acknowledgeCapability) throws Exception {
        UUID serverUuid = UUID.nameUUIDFromBytes(name.getBytes());
        TestServerClient client = new TestServerClient(name, serverUuid, acknowledgeCapability);
        MessageBootstrap<ServerBoundMessage, MasterBoundMessage> bootstrap =
                new MessageBootstrap<>(new ServerBoundProtocol(), new MasterBoundProtocol(), channel -> {
                    client.channel = channel;
                    channel.pipeline().addLast(client);
                });

        ChannelFuture future = bootstrap.connectTo("127.0.0.1", port).sync();
        assertTrue(future.isSuccess(), "Failed to connect to master");

        client.send(new HelloMessage(name, serverUuid));
        String secret = client.secretFuture.get(10, TimeUnit.SECONDS);
        assertTrue(!secret.isEmpty(), "Master did not send a secret");
        if (acknowledgeCapability) {
            assertTrue(client.capabilityFuture.get(10, TimeUnit.SECONDS), "Master did not negotiate playerdata transaction support");
            assertTrue(client.sessionCapabilityFuture.get(10, TimeUnit.SECONDS), "Master did not negotiate player session support");
        }
        return client;
    }

    public String getSecret() {
        return secretFuture.getNow(null);
    }

    public boolean awaitSecret(long timeoutMillis) throws Exception {
        return secretFuture.get(timeoutMillis, TimeUnit.MILLISECONDS) != null;
    }

    public boolean supportsPlayerDataTransactionProtocol() {
        return capabilityFuture.getNow(false);
    }

    public boolean supportsPlayerSessionProtocol() {
        return sessionCapabilityFuture.getNow(false);
    }

    public void send(MasterBoundMessage message) {
        channel.writeAndFlush(message);
    }

    public void sendWithoutFlush(MasterBoundMessage message) {
        channel.write(message);
    }

    public void flush() {
        channel.flush();
    }

    public void sendHelloWithoutCapabilityAcknowledgement() {
        acknowledgePlayerDataTransactionCapability = false;
        send(new HelloMessage(name, serverUuid));
    }

    public ServerBoundMessage request(MasterBoundMessage message) throws Exception {
        CompletableFuture<ServerBoundMessage> reply = new CompletableFuture<>();
        channel.writeAndFlush(setCallback(message, reply::complete));
        ServerBoundMessage result = reply.get(10, TimeUnit.SECONDS);
        if (result == null) {
            fail("No reply for " + message.getClass().getSimpleName());
        }
        return result;
    }

    public void close() throws InterruptedException {
        channel.close().sync();
    }

    public void abort() throws InterruptedException {
        channel.config().setOption(ChannelOption.SO_LINGER, 0);
        channel.close().sync();
    }

    public String getAddress() {
        return ((InetSocketAddress) channel.localAddress()).getAddress().getHostAddress();
    }

    @Override
    public void handle(SetSecretMessage message) {
        secretFuture.complete(message.secret);
        if (message.getTransactionId() == MasterBoundProtocol.PLAYER_DATA_TRANSACTION_CAPABILITY_ID) {
            if (acknowledgePlayerDataTransactionCapability) {
                HelloMessage acknowledgement = new HelloMessage(name, serverUuid);
                acknowledgement.setTransactionId(MasterBoundProtocol.PLAYER_DATA_TRANSACTION_CAPABILITY_ID);
                send(acknowledgement);
                capabilityFuture.complete(true);
            } else {
                capabilityFuture.complete(false);
            }
        } else if (message.getTransactionId() == MasterBoundProtocol.PLAYER_SESSION_CAPABILITY_ID) {
            if (acknowledgePlayerDataTransactionCapability) {
                HelloMessage acknowledgement = new HelloMessage(name, serverUuid);
                acknowledgement.setTransactionId(MasterBoundProtocol.PLAYER_SESSION_CAPABILITY_ID);
                send(acknowledgement);
                sessionCapabilityFuture.complete(true);
            } else {
                sessionCapabilityFuture.complete(false);
            }
        }
    }

    @Override
    public void handle(SetChunkOwnerMessage message) {
        chunkOwnerUpdates.add(message);
    }

    @Override
    public void handle(ServerStartedMessage message) {
        serverStartedMessages.add(message);
    }

    @Override
    public void handle(AddChunkSubscriberMessage message) {
        chunkSubscriberAdds.add(message);
    }

    @Override
    public void handle(RemoveChunkSubscriberMessage message) {
        chunkSubscriberRemoves.add(message);
    }

    @Override
    public void handle(AddEntitySubscriberMessage message) {
        entitySubscriberAdds.add(message);
    }

    @Override
    public void handle(RemoveEntitySubscriberMessage message) {
        entitySubscriberRemoves.add(message);
    }

    @Override
    public void handle(ChunkSubscribersSyncMessage message) {
        chunkSubscribersSyncs.add(message);
    }

    @Override
    public void handle(EntitySubscribersSyncMessage message) {
        entitySubscribersSyncs.add(message);
    }

    @Override
    public void handle(ServerInfoUpdateMessage message) {
        synchronized (serverInfoUpdates) {
            serverInfoUpdates.add(message);
        }
    }

    @Override
    public void handle(ServerChangedChunkStatusMessage message) {
        synchronized (chunkStatusChanges) {
            chunkStatusChanges.add(message);
        }
    }

    @Override
    public void handle(ShutdownMessage message) {
        shutdownReceived = true;
    }

    @Override
    public void handle(FileContentMessage message) {
        // Not needed for these tests.
    }

    @Override
    public void handle(DataUpdateMessage message) {
        // Not needed for these tests.
    }

    @Override
    public void channelInactive(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
    }
}
