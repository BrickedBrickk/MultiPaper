package puregero.multipaper;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.haproxy.*;
import io.netty.util.internal.SystemPropertyUtil;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.config.MultiPaperConfiguration;
import puregero.multipaper.externalserverprotocol.*;
import puregero.multipaper.mastermessagingprotocol.MessageBootstrap;
import puregero.multipaper.mastermessagingprotocol.MessageLengthDecoder;
import puregero.multipaper.mastermessagingprotocol.MessageLengthEncoder;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ExternalServerConnection extends ChannelInitializer<SocketChannel> implements Closeable {

    private static final Logger LOGGER = LogManager.getLogger(ExternalServerConnection.class.getSimpleName());
    private static final long CHUNK_REQUEST_TIMEOUT_MILLIS = Long.getLong("multipaper.peer.chunkRequestTimeoutMillis", 15_000L);
    private static final long ENTITY_REQUEST_TIMEOUT_MILLIS = Long.getLong("multipaper.peer.entityRequestTimeoutMillis", 15_000L);

    private Channel channel;
    public long nanoTime = 0;
    public ExternalServer externalServer = null;
    public final CompletableFuture<Void> onConnect = new CompletableFuture<>();
    public HashSet<String> subscribedWorlds = new HashSet<>();
    private static final Queue<List<ExternalPlayer>> externalPlayerListPool = new LinkedList<>();
    private final HashMap<Packet<?>, List<ExternalPlayer>> packetsToSend = new LinkedHashMap<>();
    public final ConcurrentHashMap<ChunkKey, Consumer<DataInputStream>> chunkCallbacks = new ConcurrentHashMap<>();
    private final Set<ChunkKey> pendingChunkRequests = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ChunkKey, Object> timedOutChunkRequests = new ConcurrentHashMap<>();
    public long lastPacketSent = 0;
    public long lastPacketReceived = 0;
    public final ConcurrentHashMap<ChunkKey, Consumer<DataInputStream>> entitiesCallbacks = new ConcurrentHashMap<>();

    public ExternalServerConnection() {

    }

    public ExternalServerConnection(Channel channel) {
        this.channel = channel;
        setupPipeline();
        nanoTime = System.nanoTime();
        this.channel.writeAndFlush(new HelloPacket(MultiPaperConfiguration.get().masterConnection.myName, nanoTime, getSupportedCompressionFlags()));
    }

    public int getSupportedCompressionFlags() {
        int supportedCompressionFlags = SetCompressionPacket.ZLIB_COMPRESSION;
        if (Zstd.isAvailable()) {
            supportedCompressionFlags |= SetCompressionPacket.ZSTD_COMPRESSION;
        }
        return supportedCompressionFlags;
    }

    @Override
    public void initChannel(SocketChannel channel) {
        this.channel = channel;
    }

    public void sendMinecraftHandshake(String address, String secret, int port) {
        // MultiPaper start - Add support for Proxy Protocol
        if (io.papermc.paper.configuration.GlobalConfiguration.get() == null) {
            // Paper config hasn't been loaded yet, try again 100ms later
            CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> sendMinecraftHandshake(address, secret, port));
            return;
        }

        ChannelFuture future2 = channel.newSucceededFuture();

        if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.proxyProtocol) {
            channel.pipeline().addLast("haproxy-encoder", HAProxyMessageEncoder.INSTANCE);
            future2 = channel.writeAndFlush(new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.LOCAL, HAProxyProxiedProtocol.UNKNOWN, null, null, 0, 0));
        }

        future2.addListener(future1 -> {
        // MultiPaper end

        channel.pipeline()
                .addLast("prepender", new MessageLengthEncoder())
                .addLast("encoder", new PacketEncoder<>(net.minecraft.network.protocol.handshake.HandshakeProtocols.SERVERBOUND));
        channel.writeAndFlush(new ClientIntentionPacket(net.minecraft.SharedConstants.getCurrentVersion().protocolVersion(), address + "\00" + secret, port, net.minecraft.network.protocol.handshake.ClientIntent.STATUS))
                .addListener(future -> {
                    if (future.isSuccess()) {
                        setupPipeline();
                    } else if (future.cause() != null) {
                        future.cause().printStackTrace();
                    }
                });

        }); // MultiPaper - Add support for Proxy Protocol
    }

    public void setupPipeline() {
        // Let's yeet minecraft's networking out of here
        while (channel.pipeline().last() != null) {
            channel.pipeline().removeLast();
        }
        channel.config().setAutoRead(true);

        // And add our own
        if (!Boolean.getBoolean("Paper.disableFlushConsolidate")) channel.pipeline().addFirst(new io.netty.handler.flush.FlushConsolidationHandler());

        com.velocitypowered.natives.compression.VelocityCompressor compressor = com.velocitypowered.natives.util.Natives.compress.get().create(-1);
        channel.pipeline()
                .addLast("packetsplitter", new MessageLengthDecoder())
                .addLast("decoder", new ExternalServerPacketDecoder())
                .addLast("packetprepender", new MessageLengthEncoder())
                .addLast("encoder", new ExternalServerPacketEncoder())
                .addLast("packet_handler", new ExternalServerPacketHandler(this));

        // And put it onto our own event loop
        if (MessageBootstrap.getEventLoopGroup() != channel.eventLoop().parent()) {
            if (SystemPropertyUtil.getBoolean("multipaper.netty.useOwnEventLoop", true)) {
                channel.deregister().addListener((ChannelFutureListener) future -> {
                    MessageBootstrap.getEventLoopGroup().register(future.channel()).sync();
                });
            } else {
                LOGGER.info("Using Minecraft's event loop");
            }
        }

        // And when this closes
        channel.closeFuture().addListener(future -> {
            if (future.isDone()) {
        for (ServerPlayer player : DedicatedServer.getServer().getPlayerList().getPlayers()) {
                    if (player instanceof ExternalPlayer && ((ExternalPlayer) player).externalServerConnection == this) {
            MultiPaper.runSync(() -> player.connection.disconnect(net.minecraft.network.chat.Component.literal("External server disconnected")));
                    }
                }
            }
        });
    }

    public Channel getChannel() {
        return channel;
    }

    public void failPendingDataRequests() {
        pendingChunkRequests.clear();
        timedOutChunkRequests.clear();
        failPendingDataRequests(chunkCallbacks);
        failPendingDataRequests(entitiesCallbacks);
    }

    public boolean isWaitingForChunkData() {
        return !pendingChunkRequests.isEmpty();
    }

    public boolean completeChunkRequest(String world, int cx, int cz, DataInputStream data) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        Consumer<DataInputStream> callback;
        synchronized (this) {
            if (timedOutChunkRequests.remove(key) != null) {
                return true;
            }
            callback = chunkCallbacks.remove(key);
            if (callback != null) {
                pendingChunkRequests.remove(key);
            }
        }
        if (callback != null) {
            callback.accept(data);
            return true;
        }
        return false;
    }

    public boolean completeEntityRequest(String world, int cx, int cz, DataInputStream data) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        Consumer<DataInputStream> callback;
        synchronized (this) {
            callback = entitiesCallbacks.remove(key);
        }
        if (callback != null) {
            callback.accept(data);
            return true;
        }
        return false;
    }

    private static void failPendingDataRequests(ConcurrentHashMap<ChunkKey, Consumer<DataInputStream>> callbacks) {
        callbacks.forEach((key, callback) -> {
            if (callbacks.remove(key, callback)) {
                try {
                    callback.accept(null);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to complete a pending peer data request after disconnect", exception);
                }
            }
        });
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    public boolean isOpen() {
        return channel.isOpen();
    }

    public boolean send(ExternalServerPacket packet) {
        if (channel == null || !channel.isOpen()) {
            return false;
        }

        onConnect.thenRun(() -> {
            if (!channel.isOpen()) {
                return;
            }
            if (channel.eventLoop().inEventLoop()) {
                lastPacketSent = System.currentTimeMillis();
                channel.writeAndFlush(packet);
            } else {
                lastPacketSent = System.currentTimeMillis();
                channel.eventLoop().execute(() -> channel.writeAndFlush(packet));
            }
        });
        return true;
    }

    public void tick() {
        // Send the packets after each vanilla tick
        synchronized (packetsToSend) {
            packetsToSend.forEach((packet, players) -> {
                send(new SendPacketPacket(players, packet));
                players.clear();
                externalPlayerListPool.add(players);
            });
            packetsToSend.clear();
        }
    }

    public void sendPacket(ExternalPlayer player, Packet<?> packet) {
        // Combine all the players that the packet's being sent to together
        // so that the packet only needs to be sent to the external server
        // just once, not duplicated for each player
        synchronized (packetsToSend) {
            List<ExternalPlayer> players = packetsToSend.computeIfAbsent(packet, key -> {
                List<ExternalPlayer> list = externalPlayerListPool.poll();
                if (list == null) {
                    list = new ArrayList<>();
                }
                return list;
            });
            if (players.contains(player)) {
                // Duplicate packet with the same message, flush the old one to maintain sending order
                tick();
                sendPacket(player, packet);
                return;
            }
            players.add(player);
        }
    }

    public void requestChunk(String world, int cx, int cz, Consumer<DataInputStream> callback) {
        requestChunk(world, cx, cz, callback, CHUNK_REQUEST_TIMEOUT_MILLIS);
    }

    void requestChunk(String world, int cx, int cz, Consumer<DataInputStream> callback, long timeoutMillis) {
        if (callback != null && !registerChunkRequest(world, cx, cz, callback, timeoutMillis)) {
            return;
        }
        send(new RequestChunkPacket(world, cx, cz));
    }

    boolean registerChunkRequest(String world, int cx, int cz, Consumer<DataInputStream> callback, long timeoutMillis) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        boolean timedOut;
        boolean duplicate;
        synchronized (this) {
            timedOut = timedOutChunkRequests.containsKey(key);
            duplicate = !timedOut && chunkCallbacks.putIfAbsent(key, callback) != null;
            if (!timedOut && !duplicate) {
                pendingChunkRequests.add(key);
            }
        }
        if (timedOut) {
            callback.accept(null);
            return false;
        }
        if (duplicate) {
            LOGGER.warn("A chunk callback already existed for " + world + ", " + cx + ", " + cz + " (new request is to " + externalServer.getName() + ")");
            LOGGER.warn("Stats for " + externalServer.getName() + ": last packet sent=" + (System.currentTimeMillis() - lastPacketSent) + "ms ago; last packet received=" + (System.currentTimeMillis() - lastPacketReceived) + "ms ago");
            callback.accept(null);
            return false;
        }
        CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute(() -> {
            Consumer<DataInputStream> timedOutCallback;
            Object timeoutMarker = new Object();
            synchronized (this) {
                timedOutCallback = chunkCallbacks.remove(key);
                if (timedOutCallback != null) {
                    pendingChunkRequests.remove(key);
                    timedOutChunkRequests.put(key, timeoutMarker);
                }
            }
            if (timedOutCallback != null) {
                LOGGER.warn("Timed out waiting for chunk " + world + "," + cx + "," + cz + " from " + externalServer.getName() + "; falling back to Master storage");
                timedOutCallback.accept(null);
                CompletableFuture.delayedExecutor(2, TimeUnit.MINUTES).execute(() -> timedOutChunkRequests.remove(key, timeoutMarker));
            }
        });
        return true;
    }

    public void requestEntities(String world, int cx, int cz, Consumer<DataInputStream> callback) {
        if (callback != null && !registerEntityRequest(world, cx, cz, callback, ENTITY_REQUEST_TIMEOUT_MILLIS)) {
            return;
        }

        send(new RequestEntitiesPacket(world, cx, cz));
    }

    boolean registerEntityRequest(String world, int cx, int cz, Consumer<DataInputStream> callback, long timeoutMillis) {
        ChunkKey key = new ChunkKey(world, cx, cz);
        boolean duplicate;
        synchronized (this) {
            duplicate = entitiesCallbacks.putIfAbsent(key, callback) != null;
        }
        if (duplicate) {
            LOGGER.warn("An entities callback already existed for " + world + ", " + cx + ", " + cz + " (new request is to " + externalServer.getName() + ")");
            LOGGER.warn("Stats for " + externalServer.getName() + ": last packet sent=" + (System.currentTimeMillis() - lastPacketSent) + "ms ago; last packet received=" + (System.currentTimeMillis() - lastPacketReceived) + "ms ago");
            callback.accept(null);
            return false;
        }
        CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute(() -> {
            Consumer<DataInputStream> timedOutCallback;
            synchronized (this) {
                timedOutCallback = entitiesCallbacks.remove(key);
            }
            if (timedOutCallback != null) {
                LOGGER.warn("Timed out waiting for entities " + world + "," + cx + "," + cz + " from " + externalServer.getName() + "; falling back to Master storage");
                timedOutCallback.accept(null);
            }
        });
        return true;
    }
}
