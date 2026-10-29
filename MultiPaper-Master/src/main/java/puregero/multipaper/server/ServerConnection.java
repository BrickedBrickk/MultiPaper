package puregero.multipaper.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.SocketChannel;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.*;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerBoundMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.SetSecretMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ShutdownMessage;
import puregero.multipaper.server.handlers.*;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class ServerConnection extends MasterBoundMessageHandler {
    private final SocketChannel channel;

    private String name;
    private long lastPing = System.currentTimeMillis();
    private final CircularTimer timer = new CircularTimer();
    public final HashSet<UUID> playerUUIDs = new HashSet<>();
    private final Set<UUID> disconnectingPlayers = ConcurrentHashMap.newKeySet();
    private final List<Player> players = new ArrayList<>();
    private double tps;
    private int port = -1;
    private volatile boolean started;
    private String host;
    private UUID uuid;
    private volatile boolean playerDataTransactionCapabilityChallengeSent;
    private volatile boolean playerDataTransactionProtocolSupported;
    private volatile boolean playerSessionCapabilityChallengeSent;
    private volatile boolean playerSessionProtocolSupported;
    private final Map<UUID, UUID> playerSessionIds = new ConcurrentHashMap<>();
    private final Map<String, ServerAnnouncement> announcedServers = new ConcurrentHashMap<>();

    /**
     * This connection map may include dead servers! Check if a server is alive
     * with `connections` before trying to send any data!
     */
    private static final Map<String, ServerConnection> connectionMap = new ConcurrentHashMap<>();
    private static final List<ServerConnection> connections = new CopyOnWriteArrayList<>();

    public static void shutdown() {
        broadcastAll(new ShutdownMessage());
    }

    public static void shutdownAndWait() {
        while (!connections.isEmpty()) {
            shutdown();

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public ServerConnection(SocketChannel channel) {
        this.channel = channel;
    }

    public static boolean isAlive(String bungeecordName) {
        return connections.contains(getConnection(bungeecordName)) && getConnection(bungeecordName).isOnline();
    }

    public static ServerConnection getConnection(String bungeecordName) {
        return connectionMap.get(bungeecordName);
    }

    public static List<ServerConnection> getConnections() {
        return connections;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public void send(ServerBoundMessage message) {
       channel.writeAndFlush(message);
    }

    public void send(ServerBoundMessage message, Consumer<MasterBoundMessage> callback) {
        send(setCallback(message, callback));
    }

    public CompletableFuture<MasterBoundMessage> sendAndAwaitReply(ServerBoundMessage message, long timeout, TimeUnit unit) {
        CompletableFuture<MasterBoundMessage> reply = setCallbackFuture(message, timeout, unit);
        channel.writeAndFlush(message).addListener(write -> {
            if (!write.isSuccess()) {
                reply.completeExceptionally(write.cause());
            }
        });
        return reply;
    }

    public void sendReply(ServerBoundMessage message, MasterBoundMessage inReplyTo) {
        message.setTransactionId(inReplyTo.getTransactionId());
        send(message);
    }

    public static void broadcastAll(ServerBoundMessage message) {
        connections.forEach(connection -> connection.send(message));
    }

    public void broadcastOthers(ServerBoundMessage message) {
        connections.forEach(connection -> {
            if (connection != ServerConnection.this) {
                connection.send(message);
            }
        });
    }

    public boolean isOnline() {
        return lastPing > System.currentTimeMillis() - 5000 && tps > 0;
    }

    @Override
    public void handle(HelloMessage message) {
        if (message.getTransactionId() == MasterBoundProtocol.PLAYER_DATA_TRANSACTION_CAPABILITY_ID) {
            if (playerDataTransactionCapabilityChallengeSent
                    && Objects.equals(name, message.name)
                    && Objects.equals(uuid, message.serverUuid)) {
                playerDataTransactionProtocolSupported = true;
            }
            return;
        }
        if (message.getTransactionId() == MasterBoundProtocol.PLAYER_SESSION_CAPABILITY_ID) {
            if (playerSessionCapabilityChallengeSent
                    && Objects.equals(name, message.name)
                    && Objects.equals(uuid, message.serverUuid)) {
                playerSessionProtocolSupported = true;
            }
            return;
        }

        // Every new Hello starts a fresh negotiation. Do not carry a previous
        // capability grant across a peer identity or protocol re-handshake.
        playerDataTransactionProtocolSupported = false;
        playerSessionProtocolSupported = false;
        playerDataTransactionCapabilityChallengeSent = false;
        playerSessionCapabilityChallengeSent = false;
        name = message.name;
        host = ((InetSocketAddress) getAddress()).getAddress().getHostAddress();
        uuid = message.serverUuid;

        synchronized (connections) {
            connections.add(this);
            ServerConnection oldConnectionWithSameName = connectionMap.put(name, this);

            if ("false".equalsIgnoreCase(System.getProperty("allow.multiple.connections", "false"))
                    && oldConnectionWithSameName != null
                    && !oldConnectionWithSameName.uuid.equals(uuid)
                    && connections.contains(oldConnectionWithSameName)) {
                System.out.println("# -------------------------------------------------------------------------- #");
                System.out.println("  WARNING: Two servers have connected with the same name!");
                System.out.println("  1. " + oldConnectionWithSameName.getBungeeCordName() + ": " + oldConnectionWithSameName.getHost() + " (" + oldConnectionWithSameName.uuid + ")");
                System.out.println("  2. " + this.getBungeeCordName() + ": " + this.getHost() + " (" + this.uuid + ")");
                System.out.println("  If this is expected, add -Dallow.multiple.connections to your command line");
                System.out.println("# -------------------------------------------------------------------------- #");
            }
        }

        System.out.println("Connection from " + getAddress() + " (" + name + ")");

        SetSecretMessage secret = new SetSecretMessage(MultiPaperServer.SECRET);
        secret.setTransactionId(MasterBoundProtocol.PLAYER_DATA_TRANSACTION_CAPABILITY_ID);
        playerDataTransactionCapabilityChallengeSent = true;
        send(secret);

        SetSecretMessage sessionSecret = new SetSecretMessage(MultiPaperServer.SECRET);
        sessionSecret.setTransactionId(MasterBoundProtocol.PLAYER_SESSION_CAPABILITY_ID);
        playerSessionCapabilityChallengeSent = true;
        send(sessionSecret);
    }

    @Override
    public boolean onMessage(MasterBoundMessage message) {
        lastPing = System.currentTimeMillis();
        return false;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        java.util.concurrent.CompletableFuture<Void> chunkWritesDrained = ChunkLockManager.releaseAll(this);
        EntitiesLockManager.releaseAll(this);
        EntitiesSubscriptionManager.unsubscribeAll(this);
        chunkWritesDrained.whenComplete((ignored, failure) -> {
            if (failure != null) {
                System.err.println("Could not drain accepted chunk writes before releasing ownership for " + name);
                failure.printStackTrace();
            }
            ChunkSubscriptionManager.unsubscribeAndUnlockAll(this);
        });

        synchronized (connections) {
            synchronized (playerUUIDs) {
                for (UUID playerId : playerUUIDs) {
                    markPlayerDisconnecting(playerId);
                }
            }
            connections.remove(this);
        }
        playerSessionIds.clear();

        System.out.println(ctx.channel().remoteAddress() + " (" + name + ") closed");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof SocketException) {
            ctx.close();
            return;
        }

        ctx.fireExceptionCaught(cause);
    }

    public String getBungeeCordName() {
        return name;
    }

    public CircularTimer getTimer() {
        return timer;
    }

    public boolean hasPlayer(UUID uuid) {
        synchronized (playerUUIDs) {
            return playerUUIDs.contains(uuid);
        }
    }

    public boolean addPlayer(UUID uuid) {
        synchronized (playerUUIDs) {
            return playerUUIDs.add(uuid);
        }
    }

    public boolean removePlayer(UUID uuid) {
        synchronized (playerUUIDs) {
            boolean removed = playerUUIDs.remove(uuid);
            disconnectingPlayers.remove(uuid);
            playerSessionIds.remove(uuid);
            return removed;
        }
    }

    public boolean supportsPlayerSessionProtocol() {
        return playerSessionProtocolSupported && connections.contains(this) && channel.isActive();
    }

    public UUID getPlayerSessionId(UUID playerId) {
        return playerSessionIds.get(playerId);
    }

    public boolean registerPlayerSession(UUID playerId, UUID sessionId) {
        if (!hasPlayer(playerId) || isPlayerDisconnecting(playerId) || !supportsPlayerSessionProtocol()
                || sessionId == null || sessionId.equals(new UUID(0, 0))) {
            return false;
        }
        playerSessionIds.put(playerId, sessionId);
        return true;
    }

    public void markPlayerDisconnecting(UUID uuid) {
        disconnectingPlayers.add(uuid);
    }

    public boolean isPlayerDisconnecting(UUID uuid) {
        return disconnectingPlayers.contains(uuid);
    }

    public boolean supportsPlayerDataTransactionProtocol() {
        return playerDataTransactionProtocolSupported && connections.contains(this) && channel.isActive();
    }

    public List<Player> getPlayers() {
        return players;
    }

    public double getTps() {
        return tps;
    }

    public void setTps(double tps) {
        if (this.tps == -1) {
            throw new IllegalStateException("Trying to set " + getBungeeCordName() + "'s tps to " + tps + " when it is marked as offline (" + this.tps + " tps)");
        }

        this.tps = tps;
    }

    public SocketAddress getAddress() {
        return channel.remoteAddress();
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean hasStarted() {
        return started;
    }

    public void setStarted(boolean started) {
        this.started = started;
    }

    public boolean markServerAnnounced(ServerConnection server) {
        String serverName = server.getBungeeCordName();
        ServerAnnouncement announcement = new ServerAnnouncement(server, server.getHost(), server.getPort());
        return !announcement.equals(announcedServers.put(serverName, announcement));
    }

    private record ServerAnnouncement(ServerConnection connection, String host, int port) {}

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    @Override
    public void handle(PingMessage message) {
        PingHandler.handle(this, message);
    }

    @Override
    public void handle(CallDataStorageMessage message) {
        CallDataStorageHandler.handle(this, message);
    }

    @Override
    public void handle(ChunkChangedStatusMessage message) {
        ChunkChangedStatusHandler.handle(this, message);
    }

    @Override
    public void handle(CommitPlayerDataTransactionMessage message) {
        puregero.multipaper.server.handlers.CommitPlayerDataTransactionHandler.handle(this, message);
    }

    @Override
    public void handle(DownloadFileMessage message) {
        DownloadFileHandler.handle(this, message);
    }

    @Override
    public void handle(ForceReadChunkMessage message) {
        ForceReadChunkHandler.handle(this, message);
    }

    @Override
    public void handle(LockChunkMessage message) {
        LockChunkHandler.handle(this, message);
    }

    @Override
    public void handle(PlayerConnectMessage message) {
        PlayerConnectHandler.handle(this, message);
    }

    @Override
    public void handle(PlayerDisconnectMessage message) {
        PlayerDisconnectHandler.handle(this, message);
    }

    @Override
    public void handle(RegisterPlayerSessionMessage message) {
        RegisterPlayerSessionHandler.handle(this, message);
    }

    @Override
    public void handle(ReadAdvancementMessage message) {
        ReadAdvancementsHandler.handle(this, message);
    }

    @Override
    public void handle(ReadChunkMessage message) {
        ReadChunkHandler.handle(this, message);
    }

    @Override
    public void handle(ReadDataMessage message) {
        ReadDataHandler.handle(this, message);
    }

    @Override
    public void handle(ReadJsonMessage message) {
        ReadJsonHandler.handle(this, message);
    }

    @Override
    public void handle(ReadLevelMessage message) {
        ReadLevelHandler.handle(this, message);
    }

    @Override
    public void handle(ReadPlayerMessage message) {
        ReadPlayerHandler.handle(this, message);
    }

    @Override
    public void handle(ReadStatsMessage message) {
        ReadStatsHandler.handle(this, message);
    }

    @Override
    public void handle(ReadUidMessage message) {
        ReadUidHandler.handle(this, message);
    }

    @Override
    public void handle(RequestChunkOwnershipMessage message) {
        RequestChunkOwnershipHandler.handle(this, message);
    }

    @Override
    public void handle(RequestFilesToSyncMessage message) {
        RequestFilesToSyncHandler.handle(this, message);
    }

    @Override
    public void handle(SetPortMessage message) {
        SetPortHandler.handle(this, message);
    }

    @Override
    public void handle(StartMessage message) {
        StartHandler.handle(this, message);
    }

    @Override
    public void handle(SubscribeChunkMessage message) {
        SubscribeChunkHandler.handle(this, message);
    }

    @Override
    public void handle(SubscribeEntitiesMessage message) {
        SubscribeEntitiesHandler.handle(this, message);
    }

    @Override
    public void handle(SyncChunkOwnerToAllMessage message) {
        SyncChunkOwnerToAllHandler.handle(this, message);
    }

    @Override
    public void handle(SyncChunkSubscribersMessage message) {
        SyncChunkSubscribersHandler.handle(this, message);
    }

    @Override
    public void handle(SyncEntitiesSubscribersMessage message) {
        SyncEntitiesSubscribersHandler.handle(this, message);
    }

    @Override
    public void handle(UnlockChunkMessage message) {
        UnlockChunkHandler.handle(this, message);
    }

    @Override
    public void handle(UnsubscribeChunkMessage message) {
        UnsubscribeChunkHandler.handle(this, message);
    }

    @Override
    public void handle(UnsubscribeEntitiesMessage message) {
        UnsubscribeEntitiesHandler.handle(this, message);
    }

    @Override
    public void handle(UploadFileMessage message) {
        UploadFileHandler.handle(this, message);
    }

    @Override
    public void handle(WillSaveChunkLaterMessage message) {
        WillSaveChunkHandler.handle(this, message);
    }

    @Override
    public void handle(WillSaveEntitiesLaterMessage message) {
        WillSaveEntitiesHandler.handle(this, message);
    }

    @Override
    public void handle(WriteAdvancementsMessage message) {
        WriteAdvancementsHandler.handle(this, message);
    }

    @Override
    public void handle(WriteChunkMessage message) {
        WriteChunkHandler.handle(this, message);
    }

    @Override
    public void handle(WriteDataMessage message) {
        WriteDataHandler.handle(this, message);
    }

    @Override
    public void handle(WriteJsonMessage message) {
        WriteJsonHandler.handle(this, message);
    }

    @Override
    public void handle(WriteLevelMessage message) {
        WriteLevelHandler.handle(this, message);
    }

    @Override
    public void handle(WritePlayerMessage message) {
        WritePlayerHandler.handle(this, message);
    }

    @Override
    public void handle(WriteStatsMessage message) {
        WriteStatsHandler.handle(this, message);
    }

    @Override
    public void handle(WriteTickTimeMessage message) {
        WriteTickTimeHandler.handle(this, message);
    }

    @Override
    public void handle(WriteUidMessage message) {
        WriteUidHandler.handle(this, message);
    }

    @Override
    public void handle(PersistContainerTransactionMessage message) {
        try {
            ContainerTransactionManager.begin(message);
            sendReply(new puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply(true), message);
        } catch (Exception failure) {
            System.err.println("Failed to persist container transaction " + message.transactionId + " for world " + message.world);
            failure.printStackTrace();
            sendReply(new puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply(false), message);
        }
    }

    @Override
    public void handle(RequestEntityIdBlock message) {
        RequestEntityIdBlockHandler.handle(this, message);
    }
}
