package puregero.multipaper.externalserverprotocol;

import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.handler.codec.compression.Zstd;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.config.MultiPaperConfiguration;
import puregero.multipaper.mastermessagingprotocol.MessageLengthEncoder;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class HelloPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(HelloPacket.class.getSimpleName());

    private final String bungeecordName;
    private final long nanoTime;
    private final int supportedCompressionFlags;

    enum ConnectionDecision {
        ACCEPT,
        IGNORE_DUPLICATE,
        REJECT
    }

    public HelloPacket(String bungeecordName, int supportedCompressionFlags) {
        this(bungeecordName, System.nanoTime(), supportedCompressionFlags);
    }

    public HelloPacket(String bungeecordName, long nanoTime, int supportedCompressionFlags) {
        this.bungeecordName = bungeecordName;
        this.nanoTime = nanoTime;
        this.supportedCompressionFlags = supportedCompressionFlags;
    }

    public HelloPacket(FriendlyByteBuf in) {
        bungeecordName = in.readUtf();
        nanoTime = in.readLong();
        supportedCompressionFlags = in.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(bungeecordName);
        out.writeLong(nanoTime);
        out.writeVarInt(supportedCompressionFlags);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        connection.externalServer = MultiPaper.getConnection().getOrCreateServer(bungeecordName);

        String localName = MultiPaperConfiguration.get().masterConnection.myName;
        boolean preferred = isPreferredConnection(localName, bungeecordName, connection.isOutgoing());
        ExternalServerConnection replacedConnection = null;
        ConnectionDecision decision;
        if (preferred) {
            synchronized (connection.externalServer) {
                ExternalServerConnection previousConnection = connection.externalServer.getConnection();
                decision = decideConnection(true, previousConnection == connection, previousConnection != null,
                        previousConnection == null ? 0 : previousConnection.nanoTime, nanoTime);
                if (decision == ConnectionDecision.ACCEPT) {
                    connection.externalServer.setConnection(connection);
                    if (previousConnection != connection) {
                        replacedConnection = previousConnection;
                    }
                }
            }
        } else {
            decision = ConnectionDecision.REJECT;
        }

        if (decision == ConnectionDecision.REJECT) {
            LOGGER.info("Rejecting non-preferred peer connection to " + bungeecordName);
            connection.getChannel().close();
        } else if (decision == ConnectionDecision.IGNORE_DUPLICATE) {
            // The accepted socket echoes the negotiated nonce during setup. Do not
            // generate a nonce from this JVM: the other endpoint cannot compare clocks.
        } else {
            if (replacedConnection != null) {
                replacedConnection.getChannel().close();
            }
            LOGGER.info("Connected to external server " + bungeecordName);
            initCompression(connection);
            onConnect(connection);
        }
    }

    static ConnectionDecision decideConnection(boolean preferred, boolean sameConnection, boolean hasPreviousConnection,
                                               long previousNanoTime, long candidateNanoTime) {
        if (!preferred) {
            return ConnectionDecision.REJECT;
        }
        if (!hasPreviousConnection) {
            return ConnectionDecision.ACCEPT;
        }
        if (sameConnection) {
            return ConnectionDecision.IGNORE_DUPLICATE;
        }
        return previousNanoTime < candidateNanoTime ? ConnectionDecision.ACCEPT : ConnectionDecision.REJECT;
    }

    static boolean isPreferredConnection(String localName, String peerName, boolean outgoing) {
        // Both sides derive the same direction: the lexicographically first server initiates
        // the connection and the other server accepts it. This avoids comparing nanoTime
        // values produced by different JVM clocks when both sides connect simultaneously.
        return outgoing == (localName.compareTo(peerName) < 0);
    }

    private void initCompression(ExternalServerConnection connection) {
        int compressionType = 0;
        ChannelOutboundHandlerAdapter compressionHandler = null;

        if (MultiPaperConfiguration.get().peerConnection.compressionThreshold > 0 && !(connection.getChannel().remoteAddress() instanceof InetSocketAddress address && address.getAddress().isLoopbackAddress())) {
            if ((supportedCompressionFlags & SetCompressionPacket.ZSTD_COMPRESSION) == SetCompressionPacket.ZSTD_COMPRESSION && Zstd.isAvailable()) {
                compressionType = SetCompressionPacket.ZSTD_COMPRESSION;
                compressionHandler = SetCompressionPacket.createZstdCompressionEncoder();
            } else if ((supportedCompressionFlags & SetCompressionPacket.ZLIB_COMPRESSION) == SetCompressionPacket.ZLIB_COMPRESSION) {
                compressionType = SetCompressionPacket.ZLIB_COMPRESSION;
                compressionHandler = SetCompressionPacket.createZlibCompressionEncoder();
            }
        }

        if (compressionHandler != null) {
            LOGGER.info("Using compression " + compressionHandler.getClass().getSimpleName() + " with " + connection.externalServer.getName());
            ChannelOutboundHandlerAdapter finalCompressionHandler = compressionHandler;
            connection.getChannel().writeAndFlush(new SetCompressionPacket(compressionType)).addListener(future -> {
                if (MultiPaperConfiguration.get().peerConnection.consolidationDelay > 0) {
                    connection.getChannel().pipeline().addFirst("consolidator", new PacketConsolidationHandler());
                }

                connection.getChannel().pipeline()
                        .addFirst("compresser", finalCompressionHandler)
                        .addFirst("prepender", new MessageLengthEncoder());

                // Wait for the other side to initiate the compression
                CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> connection.onConnect.complete(null));
            });
        } else {
            connection.onConnect.complete(null);
        }
    }

    private void onConnect(ExternalServerConnection connection) {
        if (connection.nanoTime != nanoTime) {
            connection.nanoTime = nanoTime;
            connection.send(new HelloPacket(MultiPaperConfiguration.get().masterConnection.myName, nanoTime, connection.getSupportedCompressionFlags()));
        }

        MultiPaper.runSync(() -> {
            for (World world : Bukkit.getWorlds()) {
                connection.send(new SubscribeToWorldPacket(world.getName()));
            }
        });

        if (MultiPaperConfiguration.get().syncSettings.syncScoreboards) {
            MultiPaper.runSync(() -> {
                // Only send scoreboards if we haven't just loaded
                if (MinecraftServer.getServer().getTickCount() > 5) {
                    sendScoreboard(MinecraftServer.getServer().getScoreboard(), connection);
                }
            });
        }
    }

    private void sendScoreboard(ServerScoreboard scoreboard, ExternalServerConnection connection) {
        String scoreboardId = null;

        Set<Objective> set = new HashSet<>();

        for (PlayerTeam scoreboardteam : scoreboard.getPlayerTeams()) {
            connection.send(new ScoreboardUpdatePacket(scoreboardId, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(scoreboardteam, true)));
        }

        for (net.minecraft.world.scores.DisplaySlot slot : net.minecraft.world.scores.DisplaySlot.values()) {
            Objective scoreboardobjective = scoreboard.getDisplayObjective(slot);

            if (scoreboardobjective != null && !set.contains(scoreboardobjective)) {
                List<Packet<?>> list = scoreboard.getStartTrackingPackets(scoreboardobjective);

                for (Packet<?> value : list) {
                    connection.send(new ScoreboardUpdatePacket(scoreboardId, scoreboardobjective.getCriteria(), value));
                }

                set.add(scoreboardobjective);
            }
        }
    }
}
