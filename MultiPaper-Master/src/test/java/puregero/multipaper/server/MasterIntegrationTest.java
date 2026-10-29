package puregero.multipaper.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import puregero.multipaper.mastermessagingprotocol.ChunkKey;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.LockChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.CommitPlayerDataTransactionMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RegisterPlayerSessionMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PingMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PlayerConnectMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PlayerDisconnectMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ForceReadChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.HelloMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadLevelMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadDataMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadJsonMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadPlayerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadUidMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RequestEntityIdBlock;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RequestChunkOwnershipMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.StartMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.SetPortMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.SubscribeChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.SubscribeEntitiesMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.UnlockChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.UnsubscribeChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WillSaveChunkLaterMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WillSaveEntitiesLaterMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteLevelMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteDataMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteJsonMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WritePlayerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ChunkLoadedOnAnotherServerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.IntegerPairMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerBoundMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerStartedMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.SetChunkOwnerMessage;
import puregero.multipaper.server.util.RegionFileCache;
import se.llbit.nbt.CompoundTag;
import se.llbit.nbt.IntArrayTag;
import se.llbit.nbt.IntTag;
import se.llbit.nbt.ListTag;
import se.llbit.nbt.SpecificTag;
import se.llbit.nbt.StringTag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end tests of the master server: two fake MultiPaper servers connect
 * to a real master instance and exercise the chunk ownership, subscription,
 * world storage and player coordination protocols, including crash recovery.
 *
 * Tests are ordered because they build on each other's state on purpose,
 * mirroring how a long-running master accumulates world state.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(60)
class MasterIntegrationTest {

    private static final String WORLD = "testworld";
    private static final byte[] CHUNK_PAYLOAD = "chunk-data-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHUNK_PAYLOAD_2 = "chunk-data-v2-updated".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEVEL_PAYLOAD = "level-dat-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLAYER_PAYLOAD = "player-dat-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENTITIES_PAYLOAD = "entities-bytes".getBytes(StandardCharsets.UTF_8);

    private static MultiPaperServer master;
    private static int port;
    private static TestServerClient serverA;
    private static TestServerClient serverB;

    @BeforeAll
    static void startMaster() throws Exception {
        master = new MultiPaperServer(0);
        Thread.sleep(200);
        port = master.getLocalPort();
        serverA = TestServerClient.connect(port, "server-a");
        serverB = TestServerClient.connect(port, "server-b");
    }

    @AfterAll
    static void stopMaster() {
        try {
            if (serverA != null) serverA.close();
        } catch (InterruptedException ignored) {
        }
        try {
            if (serverB != null) serverB.close();
        } catch (InterruptedException ignored) {
        }
        if (master != null) {
            master.closeServerChannel();
        }
    }

    @Test
    @Order(1)
    void handshakeDeliversMasterSecret() {
        assertEquals(MultiPaperServer.SECRET, serverA.getSecret());
        assertEquals(MultiPaperServer.SECRET, serverB.getSecret());
    }

    @Test
    @Order(2)
    void pingIsAcknowledged() throws Exception {
        ServerBoundMessage reply = serverA.request(new PingMessage());
        assertTrue(((BooleanMessageReply) reply).result);
    }

    @Test
    @Order(3)
    void startCreatesBidirectionalPeerConnections() throws Exception {
        serverA.send(new SetPortMessage(25565));
        serverB.send(new SetPortMessage(25566));

        serverA.send(new StartMessage("127.0.0.1", 25565));
        ServerStartedMessage aStarted = serverB.serverStartedMessages.poll(10, TimeUnit.SECONDS);
        assertEquals("127.0.0.1", aStarted.host);
        assertEquals(25565, aStarted.port);

        serverB.send(new StartMessage("127.0.0.1", 25566));
        ServerStartedMessage bStarted = serverA.serverStartedMessages.poll(10, TimeUnit.SECONDS);
        assertEquals("127.0.0.1", bStarted.host);
        assertEquals(25566, bStarted.port);

        assertTrue(((BooleanMessageReply) serverB.request(new PingMessage())).result,
                "Master ping acts as an ordering barrier after server-b's start message");
        assertNull(serverB.serverStartedMessages.poll(100, TimeUnit.MILLISECONDS),
                "server-b must not receive server-a's start twice when server-b registers after its port");
    }

    @Test
    @Order(4)
    void proxyBackendPortsAreRegisteredWithMaster() throws Exception {
        serverA.send(new SetPortMessage(25566));
        serverB.send(new SetPortMessage(25567));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && (ServerConnection.getConnection(serverA.name).getPort() != 25566 || ServerConnection.getConnection(serverB.name).getPort() != 25567)) {
            Thread.sleep(10);
        }

        assertEquals(25566, ServerConnection.getConnection(serverA.name).getPort());
        assertEquals(25567, ServerConnection.getConnection(serverB.name).getPort());
    }

    @Test
    @Order(5)
    void firstLockerBecomesChunkOwner() throws Exception {
        serverA.send(new LockChunkMessage(WORLD, 0, 0));
        SetChunkOwnerMessage owner = serverA.chunkOwnerUpdates.poll(10, TimeUnit.SECONDS);
        assertEquals("server-a", owner.owner);
        assertEquals(0, owner.cx);
        assertEquals(0, owner.cz);

        serverB.send(new LockChunkMessage(WORLD, 0, 0));
        SetChunkOwnerMessage ownerStillA = serverB.chunkOwnerUpdates.poll(10, TimeUnit.SECONDS);
        assertEquals("server-a", ownerStillA.owner, "Ownership must stay with the first locker");
    }

    @Test
    @Order(6)
    void chunkOwnedByAnotherServerIsRedirected() throws Exception {
        ServerBoundMessage reply = serverB.request(new ReadChunkMessage(WORLD, "region", 0, 0));
        assertTrue(reply instanceof ChunkLoadedOnAnotherServerMessage, "Expected redirect, got " + reply.getClass());
        assertEquals("server-a", ((ChunkLoadedOnAnotherServerMessage) reply).server);
    }

    @Test
    @Order(7)
    void chunkOwnershipCanBeRequestedForcedTransfer() throws Exception {
        ServerBoundMessage replyA = serverA.request(new RequestChunkOwnershipMessage(WORLD, new ChunkKey[]{
                new ChunkKey(WORLD, 0, 0), new ChunkKey(WORLD, 2, 0)
        }));
        assertTrue(((BooleanMessageReply) replyA).result, "Owner must be able to expand its ownership");

        // server-a now owns (2, 0). server-b owns nothing, so its request must be denied.
        ServerBoundMessage denied = serverB.request(new RequestChunkOwnershipMessage(WORLD, new ChunkKey[]{
                new ChunkKey(WORLD, 2, 0)
        }));
        assertFalse(((BooleanMessageReply) denied).result, "A server that owns none of the requested chunks must be denied");

        // Once server-b owns a chunk in the set, it can force-take the rest.
        serverB.send(new LockChunkMessage(WORLD, 3, 0));
        SetChunkOwnerMessage ownerB = pollUntilOwner(serverB, 3, 0, "server-b");
        assertEquals("server-b", ownerB.owner);

        ServerBoundMessage forced = serverB.request(new RequestChunkOwnershipMessage(WORLD, new ChunkKey[]{
                new ChunkKey(WORLD, 2, 0), new ChunkKey(WORLD, 3, 0)
        }));
        assertTrue(((BooleanMessageReply) forced).result, "A server owning one requested chunk must be able to force-take the set");

        // A third server must now see server-b as the owner of (2, 0).
        TestServerClient serverC = TestServerClient.connect(port, "server-c");
        try {
            ServerBoundMessage redirect = serverC.request(new ReadChunkMessage(WORLD, "region", 2, 0));
            assertTrue(redirect instanceof ChunkLoadedOnAnotherServerMessage, "Expected redirect, got " + redirect.getClass());
            assertEquals("server-b", ((ChunkLoadedOnAnotherServerMessage) redirect).server);
        } finally {
            serverC.close();
        }
    }

    @Test
    @Order(8)
    void writtenChunkIsReadableAndVisibleAsLoaded() throws Exception {
        // Write directly without owning the chunk: the data goes to the master's storage.
        ServerBoundMessage writeReply = serverA.request(new WriteChunkMessage(WORLD, "region", 5, 0, CHUNK_PAYLOAD));
        assertTrue(((BooleanMessageReply) writeReply).result);

        ServerBoundMessage readReply = serverA.request(new ReadChunkMessage(WORLD, "region", 5, 0));
        assertArrayEquals(CHUNK_PAYLOAD, ((DataMessageReply) readReply).data);

        // A read subscription alone does not make server-a a safe peer source.
        // Without chunk ownership, server-b must read the master's stored bytes.
        ServerBoundMessage replyB = serverB.request(new ReadChunkMessage(WORLD, "region", 5, 0));
        assertTrue(replyB instanceof DataMessageReply, "Expected master storage read, got " + replyB.getClass());
        assertArrayEquals(CHUNK_PAYLOAD, ((DataMessageReply) replyB).data);
    }

    @Test
    @Order(9)
    void willSaveLaterBlocksReadersUntilTheChunkIsWritten() throws Exception {
        serverA.send(new WillSaveChunkLaterMessage(WORLD, 8, 0));
        // Ping server-a so we know the master has processed the WillSaveChunkLater
        // before server-b tries to read the chunk (messages on one channel are
        // processed in order).
        serverA.request(new PingMessage());

        java.util.concurrent.CompletableFuture<ServerBoundMessage> pendingRead = new java.util.concurrent.CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try {
                ServerBoundMessage reply = serverB.request(new ReadChunkMessage(WORLD, "region", 8, 0));
                pendingRead.complete(reply);
            } catch (Exception e) {
                pendingRead.completeExceptionally(e);
            }
        });
        reader.setDaemon(true);
        reader.start();

        // The read must not complete while the write is pending.
        Thread.sleep(500);
        assertFalse(pendingRead.isDone(), "Read should wait for the pending write");

        ServerBoundMessage writeReply = serverA.request(new WriteChunkMessage(WORLD, "region", 8, 0, CHUNK_PAYLOAD_2));
        assertTrue(((BooleanMessageReply) writeReply).result);

        ServerBoundMessage readReply = pendingRead.get(10, TimeUnit.SECONDS);
        assertArrayEquals(CHUNK_PAYLOAD_2, ((DataMessageReply) readReply).data);
    }

    @Test
    @Order(10)
    void entitiesChunksAreStoredPerChunk() throws Exception {
        ServerBoundMessage writeReply = serverA.request(new WriteChunkMessage(WORLD, "entities", 7, 0, ENTITIES_PAYLOAD));
        assertTrue(((BooleanMessageReply) writeReply).result);

        ServerBoundMessage readReply = serverA.request(new ReadChunkMessage(WORLD, "entities", 7, 0));
        assertArrayEquals(ENTITIES_PAYLOAD, ((DataMessageReply) readReply).data);

        // A storage read alone does not prove the backend has deserialized its entity chunk.
        ServerBoundMessage beforeReady = serverB.request(new ReadChunkMessage(WORLD, "entities", 7, 0));
        assertTrue(beforeReady instanceof DataMessageReply, "Expected Master storage before entity readiness, got " + beforeReady.getClass());
        assertArrayEquals(ENTITIES_PAYLOAD, ((DataMessageReply) beforeReady).data);

        ServerBoundMessage emptyWrite = serverA.request(new WriteChunkMessage(WORLD, "entities", 7, 0, new byte[0]));
        assertTrue(((BooleanMessageReply) emptyWrite).result);
        ServerBoundMessage emptyRead = serverB.request(new ReadChunkMessage(WORLD, "entities", 7, 0));
        assertTrue(emptyRead instanceof DataMessageReply, "Expected empty Master entity data, got " + emptyRead.getClass());
        assertEquals(0, ((DataMessageReply) emptyRead).data.length,
                "An empty entity save must delete stale entity records from Master storage");

        // The backend explicitly subscribes only after its entity data is installed locally.
        serverA.send(new SubscribeEntitiesMessage(WORLD, 7, 0));
        serverA.request(new PingMessage());
        ServerBoundMessage replyB = serverB.request(new ReadChunkMessage(WORLD, "entities", 7, 0));
        assertTrue(replyB instanceof ChunkLoadedOnAnotherServerMessage, "Expected redirect, got " + replyB.getClass());
        assertEquals("server-a", ((ChunkLoadedOnAnotherServerMessage) replyB).server);
    }

    @Test
    @Order(11)
    void forcedEntityReadWaitsForPendingEntityWrite() throws Exception {
        ServerBoundMessage initialWrite = serverA.request(new WriteChunkMessage(WORLD, "entities", 9, 0, ENTITIES_PAYLOAD));
        assertTrue(((BooleanMessageReply) initialWrite).result);

        serverA.send(new WillSaveEntitiesLaterMessage(WORLD, 9, 0));
        serverA.request(new PingMessage());

        java.util.concurrent.CompletableFuture<ServerBoundMessage> pendingRead = new java.util.concurrent.CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try {
                pendingRead.complete(serverB.request(new ForceReadChunkMessage(WORLD, "entities", 9, 0)));
            } catch (Exception e) {
                pendingRead.completeExceptionally(e);
            }
        });
        reader.setDaemon(true);
        reader.start();

        Thread.sleep(500);
        assertFalse(pendingRead.isDone(), "Forced entity read must wait for the pending entity write");

        byte[] updatedEntities = "updated-entities-bytes".getBytes(StandardCharsets.UTF_8);
        ServerBoundMessage updatedWrite = serverA.request(new WriteChunkMessage(WORLD, "entities", 9, 0, updatedEntities));
        assertTrue(((BooleanMessageReply) updatedWrite).result);

        ServerBoundMessage readReply = pendingRead.get(10, TimeUnit.SECONDS);
        assertArrayEquals(updatedEntities, ((DataMessageReply) readReply).data);
    }

    @Test
    @Order(12)
    void levelDatIsSharedThroughTheMaster() throws Exception {
        ServerBoundMessage writeReply = serverA.request(new WriteLevelMessage(WORLD, LEVEL_PAYLOAD));
        assertTrue(((BooleanMessageReply) writeReply).result);

        ServerBoundMessage readReply = serverB.request(new ReadLevelMessage(WORLD));
        assertArrayEquals(LEVEL_PAYLOAD, ((DataMessageReply) readReply).data);
    }

    @Test
    @Order(13)
    void playerDataIsSharedThroughTheMaster() throws Exception {
        String uuid = UUID.randomUUID().toString();
        ServerBoundMessage connectReply = serverA.request(new PlayerConnectMessage(UUID.fromString(uuid)));
        assertTrue(((BooleanMessageReply) connectReply).result);
        ServerBoundMessage writeReply = serverA.request(new WritePlayerMessage(WORLD, uuid, PLAYER_PAYLOAD));
        assertTrue(((BooleanMessageReply) writeReply).result);

        ServerBoundMessage readReply = serverB.request(new ReadPlayerMessage(WORLD, uuid));
        assertArrayEquals(PLAYER_PAYLOAD, ((DataMessageReply) readReply).data);
    }

    @Test
    @Order(14)
    void playerCanOnlyConnectToOneServerAtATime() throws Exception {
        UUID player = UUID.randomUUID();

        ServerBoundMessage first = serverA.request(new PlayerConnectMessage(player));
        assertTrue(((BooleanMessageReply) first).result, "First connect must succeed");

        ServerBoundMessage second = serverB.request(new PlayerConnectMessage(player));
        assertFalse(((BooleanMessageReply) second).result, "Second connect for an online player must fail");

        byte[] currentData = new byte[1024 * 1024];
        new java.util.Random(0x524335).nextBytes(currentData);
        serverA.send(new WritePlayerMessage(WORLD, player.toString(), currentData));

        // PlayerDisconnect has no reply. An ordered ping confirms Master has submitted its
        // disconnect operation, then a per-player queue barrier avoids flooding delayed claims.
        serverA.send(new PlayerDisconnectMessage(player));
        assertTrue(((BooleanMessageReply) serverA.request(new PingMessage())).result);
        PlayerDataOperationQueue.submit(player, () -> { }).get(30, TimeUnit.SECONDS);
        ServerBoundMessage retry = serverB.request(new PlayerConnectMessage(player));
        assertTrue(((BooleanMessageReply) retry).result, "Player must be connectable after the queued final save and disconnect");

        ServerBoundMessage staleWrite = serverA.request(new WritePlayerMessage(WORLD, player.toString(),
                "delayed-former-owner-data".getBytes(StandardCharsets.UTF_8)));
        assertFalse(((BooleanMessageReply) staleWrite).result, "Former owner must not overwrite playerdata after disconnect");
        ServerBoundMessage currentOwnerRead = serverB.request(new ReadPlayerMessage(WORLD, player.toString()));
        assertArrayEquals(currentData, ((DataMessageReply) currentOwnerRead).data,
                "The new owner's loaded snapshot must remain authoritative");

        // A valid save already received by Master must survive a backend socket loss even
        // when its disk write is still queued. channelInactive removes the backend from the
        // live connection list before that queued disk operation can run.
        UUID abruptDisconnectPlayer = UUID.randomUUID();
        assertTrue(((BooleanMessageReply) serverB.request(new PlayerConnectMessage(abruptDisconnectPlayer))).result);
        byte[] beforeDisconnect = "before-disconnect".getBytes(StandardCharsets.UTF_8);
        byte[] finalSave = "final-save-received-before-socket-close".getBytes(StandardCharsets.UTF_8);
        assertTrue(((BooleanMessageReply) serverB.request(new WritePlayerMessage(WORLD,
                abruptDisconnectPlayer.toString(), beforeDisconnect))).result);

        java.util.concurrent.CountDownLatch queuedSaveStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseQueuedSave = new java.util.concurrent.CountDownLatch(1);
        var queueBlocker = PlayerDataOperationQueue.submit(abruptDisconnectPlayer, () -> {
            queuedSaveStarted.countDown();
            try {
                if (!releaseQueuedSave.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release queued playerdata operations");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            }
        });
        assertTrue(queuedSaveStarted.await(5, TimeUnit.SECONDS));
        try {
            serverB.send(new WritePlayerMessage(WORLD, abruptDisconnectPlayer.toString(), finalSave));
            assertTrue(((BooleanMessageReply) serverB.request(new PingMessage())).result,
                    "The player write must be handled before the ordered ping reply");
            var writesDrained = PlayerDataOperationQueue.submit(abruptDisconnectPlayer, () -> { });
            serverB.close();
            long disconnectDeadline = System.currentTimeMillis() + 5_000;
            while (ServerConnection.getConnections().stream()
                    .anyMatch(connection -> "server-b".equals(connection.getBungeeCordName()))
                    && System.currentTimeMillis() < disconnectDeadline) {
                Thread.sleep(10);
            }
            assertFalse(ServerConnection.getConnections().stream()
                            .anyMatch(connection -> "server-b".equals(connection.getBungeeCordName())),
                    "Master must process channelInactive while the accepted save remains queued");
            releaseQueuedSave.countDown();
            queueBlocker.get(10, TimeUnit.SECONDS);
            writesDrained.get(10, TimeUnit.SECONDS);

            ServerBoundMessage persistedFinalSave = serverA.request(
                    new ReadPlayerMessage(WORLD, abruptDisconnectPlayer.toString()));
            assertArrayEquals(finalSave, ((DataMessageReply) persistedFinalSave).data,
                    "A valid final save received before channelInactive must not be discarded");
        } finally {
            releaseQueuedSave.countDown();
        }
        serverB = TestServerClient.connect(port, "server-b");
    }

    @Test
    @Order(17)
    void crashRecoveryFreesOwnershipAndSubscriptions() throws Exception {
        // server-a currently owns (0, 0)/(2, 0) and is subscribed to the chunk at (5, 0).
        serverA.close();

        // The master must stop redirecting chunk reads to the dead server.
        boolean dataVisible = false;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ServerBoundMessage reply = serverB.request(new ReadChunkMessage(WORLD, "region", 5, 0));
            if (reply instanceof DataMessageReply dataReply) {
                assertArrayEquals(CHUNK_PAYLOAD, dataReply.data, "Stored chunk data must survive the owner crashing");
                dataVisible = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(dataVisible, "Chunk must stop being attributed to the crashed server");

        // The surviving server can take ownership of the freed chunk.
        serverB.send(new LockChunkMessage(WORLD, 5, 0));
        SetChunkOwnerMessage owner = pollUntilOwner(serverB, 5, 0, "server-b");
        assertEquals("server-b", owner.owner);

        // And expand ownership using the normal request flow.
        ServerBoundMessage ownershipReply = serverB.request(new RequestChunkOwnershipMessage(WORLD, new ChunkKey[]{
                new ChunkKey(WORLD, 5, 0), new ChunkKey(WORLD, 6, 0)
        }));
        assertTrue(((BooleanMessageReply) ownershipReply).result);

        // Subscribing, then unlocking, must clear ownership and notify the subscriber.
        serverB.send(new puregero.multipaper.mastermessagingprotocol.messages.masterbound.SubscribeChunkMessage(WORLD, 6, 0));
        Thread.sleep(200);
        serverB.send(new UnlockChunkMessage(WORLD, 6, 0));
        SetChunkOwnerMessage cleared = pollUntilOwner(serverB, 6, 0, "");
        assertEquals("", cleared.owner);

        serverB.send(new UnsubscribeChunkMessage(WORLD, 5, 0));
    }

    @Test
    @Order(18)
    void chunkDataRemainsReadableAfterOwnerCrash() throws Exception {
        // server-a crashed in the previous test; its stored data must still be available.
        ServerBoundMessage readReply = serverB.request(new ReadChunkMessage(WORLD, "region", 5, 0));
        if (readReply instanceof ChunkLoadedOnAnotherServerMessage redirect) {
            fail("Chunk should not be owned by anyone, but was attributed to " + redirect.server);
        }
        assertArrayEquals(CHUNK_PAYLOAD, ((DataMessageReply) readReply).data);
    }

    @Test
    @Order(19)
    void entityIdBlocksSkipTheReservedZeroEvenWhenPersistedStateIsZero() throws Exception {
        Path idFile = Path.of("entity-ids", "lastblock.txt").toAbsolutePath();
        Files.createDirectories(idFile.getParent());
        Files.writeString(idFile, "0");
        System.setProperty("multipaper.entityid.file", idFile.toString());

        IntegerPairMessageReply first = (IntegerPairMessageReply) serverB.request(new RequestEntityIdBlock());
        assertEquals(1, first.x, "Entity id zero is reserved as Minecraft's unassigned sentinel");

        IntegerPairMessageReply second = (IntegerPairMessageReply) serverB.request(new RequestEntityIdBlock());
        assertEquals(first.y, second.x, "Sequential ID ranges must be contiguous");
        assertTrue(second.x > first.x, "The second allocated range must advance");
    }

    @Test
    @Order(20)
    void failedChunkWriteIsNotAcknowledgedAndPreservesThePreviousChunk() throws Exception {
        String world = "write-failure-world";
        int chunkX = 17;
        int chunkZ = -21;
        byte[] previousData = "previous-chunk-data".getBytes(StandardCharsets.UTF_8);
        byte[] replacementData = "replacement-chunk-data".getBytes(StandardCharsets.UTF_8);

        ServerBoundMessage initialReply = serverB.request(new WriteChunkMessage(world, "region", chunkX, chunkZ, previousData));
        assertTrue(initialReply instanceof BooleanMessageReply && ((BooleanMessageReply) initialReply).result,
                "The initial chunk write should be acknowledged");

        serverB.send(new WillSaveChunkLaterMessage(world, chunkX, chunkZ));
        assertTrue(((BooleanMessageReply) serverB.request(new PingMessage())).result,
                "The Master should process the pending-save lock before the simulated write failure");
        RegionFileCache.getRegionFile(Path.of(world, "region").toFile(), chunkX, chunkZ).close();

        ServerBoundMessage failedReply = serverB.request(new WriteChunkMessage(world, "region", chunkX, chunkZ, replacementData));
        assertTrue(failedReply instanceof BooleanMessageReply, "A failed write should return a boolean reply");
        assertFalse(((BooleanMessageReply) failedReply).result, "A failed disk write must not be acknowledged as successful");

        RegionFileCache.closeAll();
        ServerBoundMessage readReply = serverB.request(new ReadChunkMessage(world, "region", chunkX, chunkZ));
        assertTrue(readReply instanceof DataMessageReply, "The previous chunk data should remain readable");
        assertArrayEquals(previousData, ((DataMessageReply) readReply).data,
                "A failed replacement must leave the last acknowledged chunk data intact");
        RegionFileCache.closeAll();
    }

    private static SetChunkOwnerMessage pollUntilOwner(TestServerClient client, int cx, int cz, String expectedOwner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            SetChunkOwnerMessage message = client.chunkOwnerUpdates.poll(100, TimeUnit.MILLISECONDS);
            if (message != null && message.cx == cx && message.cz == cz && expectedOwner.equals(message.owner)) {
                return message;
            }
        }
        fail("Timed out waiting for owner update of chunk " + cx + "," + cz + " with owner '" + expectedOwner + "'");
        return null;
    }

    @Test
    @Order(21)
    void savedDataUsesSharedWorldRelativePathsAcrossBackends() throws Exception {
        final Path temporaryWorld = Path.of("build", "tmp", "multipaper-shared-data-" + UUID.randomUUID());
        final String sharedPath = temporaryWorld.resolve("world/dimensions/minecraft/overworld/data/paper/metadata.dat")
            .toString().replace('\\', '/');
        final byte[] firstBackendData = "metadata-from-backend-one".getBytes(StandardCharsets.UTF_8);
        final byte[] secondBackendData = "metadata-from-backend-two".getBytes(StandardCharsets.UTF_8);
        final TestServerClient dataPeer = TestServerClient.connect(port, "saved-data-peer");

        try {
            assertTrue(((BooleanMessageReply) serverB.request(new WriteDataMessage(sharedPath, firstBackendData))).result);
            assertArrayEquals(firstBackendData, ((DataMessageReply) dataPeer.request(new ReadDataMessage(sharedPath))).data);

            assertTrue(((BooleanMessageReply) dataPeer.request(new WriteDataMessage(sharedPath, secondBackendData))).result);
            assertArrayEquals(secondBackendData, ((DataMessageReply) serverB.request(new ReadDataMessage(sharedPath))).data);
        } finally {
            dataPeer.close();
            if (Files.exists(temporaryWorld)) {
                try (var paths = Files.walk(temporaryWorld)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    @Test
    @Order(22)
    void abruptBackendSocketResetDoesNotBreakPeerConnections() throws Exception {
        TestServerClient resetPeer = TestServerClient.connect(port, "reset-peer");
        ServerConnection disconnected = ServerConnection.getConnection(resetPeer.name);
        resetPeer.abort();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && ServerConnection.getConnections().contains(disconnected)) {
            Thread.sleep(10);
        }

        assertFalse(ServerConnection.getConnections().contains(disconnected), "Reset connection must be removed from the live server list");
        assertTrue(((BooleanMessageReply) serverB.request(new PingMessage())).result, "The remaining backend must still receive Master replies");
    }

    @Test
    @Order(23)
    void forcedChunkReadWaitsForEveryOverlappingWrite() throws Exception {
        int chunkX = 20;
        int chunkZ = -4;
        byte[] firstWrite = "overlapping-chunk-write-1".getBytes(StandardCharsets.UTF_8);
        byte[] finalWrite = "overlapping-chunk-write-2-final".getBytes(StandardCharsets.UTF_8);

        serverB.send(new WillSaveChunkLaterMessage(WORLD, chunkX, chunkZ));
        serverB.request(new PingMessage());
        serverB.send(new WillSaveChunkLaterMessage(WORLD, chunkX, chunkZ));
        serverB.request(new PingMessage());
        TestServerClient readerClient = TestServerClient.connect(port, "overlapping-chunk-reader");
        try {
            java.util.concurrent.CompletableFuture<ServerBoundMessage> pendingRead = new java.util.concurrent.CompletableFuture<>();
            Thread reader = new Thread(() -> {
                try {
                    pendingRead.complete(readerClient.request(new ForceReadChunkMessage(WORLD, "region", chunkX, chunkZ)));
                } catch (Exception e) {
                    pendingRead.completeExceptionally(e);
                }
            });
            reader.setDaemon(true);
            reader.start();

            Thread.sleep(500);
            assertFalse(pendingRead.isDone(), "Forced chunk read must wait for both pending writes");

            ServerBoundMessage firstReply = serverB.request(new WriteChunkMessage(WORLD, "region", chunkX, chunkZ, firstWrite));
            assertTrue(((BooleanMessageReply) firstReply).result);
            Thread.sleep(500);
            assertFalse(pendingRead.isDone(), "The first write must not release a read while the second write is pending");

            ServerBoundMessage finalReply = serverB.request(new WriteChunkMessage(WORLD, "region", chunkX, chunkZ, finalWrite));
            assertTrue(((BooleanMessageReply) finalReply).result);

            ServerBoundMessage readReply = pendingRead.get(10, TimeUnit.SECONDS);
            assertArrayEquals(finalWrite, ((DataMessageReply) readReply).data);
        } finally {
            readerClient.close();
        }
    }

    @Test

    @Order(24)
    void forcedEntityReadWaitsForEveryOverlappingWrite() throws Exception {
        int chunkX = -20;
        int chunkZ = 4;
        byte[] firstWrite = "overlapping-entity-write-1".getBytes(StandardCharsets.UTF_8);
        byte[] finalWrite = "overlapping-entity-write-2-final".getBytes(StandardCharsets.UTF_8);

        serverB.send(new WillSaveEntitiesLaterMessage(WORLD, chunkX, chunkZ));
        serverB.request(new PingMessage());
        serverB.send(new WillSaveEntitiesLaterMessage(WORLD, chunkX, chunkZ));
        serverB.request(new PingMessage());
        TestServerClient readerClient = TestServerClient.connect(port, "overlapping-entity-reader");
        try {
            java.util.concurrent.CompletableFuture<ServerBoundMessage> pendingRead = new java.util.concurrent.CompletableFuture<>();
            Thread reader = new Thread(() -> {
                try {
                    pendingRead.complete(readerClient.request(new ForceReadChunkMessage(WORLD, "entities", chunkX, chunkZ)));
                } catch (Exception e) {
                    pendingRead.completeExceptionally(e);
                }
            });
            reader.setDaemon(true);
            reader.start();

            Thread.sleep(500);
            assertFalse(pendingRead.isDone(), "Forced entity read must wait for both pending writes");

            ServerBoundMessage firstReply = serverB.request(new WriteChunkMessage(WORLD, "entities", chunkX, chunkZ, firstWrite));
            assertTrue(((BooleanMessageReply) firstReply).result);
            Thread.sleep(500);
            assertFalse(pendingRead.isDone(), "The first write must not release a read while the second write is pending");

            ServerBoundMessage finalReply = serverB.request(new WriteChunkMessage(WORLD, "entities", chunkX, chunkZ, finalWrite));
            assertTrue(((BooleanMessageReply) finalReply).result);

            ServerBoundMessage readReply = pendingRead.get(10, TimeUnit.SECONDS);
            assertArrayEquals(finalWrite, ((DataMessageReply) readReply).data);
        } finally {
            readerClient.close();
        }
    }

    @Test
    @Order(25)
    void disconnectingWriterReleasesPendingChunkRead() throws Exception {
        String world = "disconnect-pending-chunk-world";
        int chunkX = 27;
        int chunkZ = -13;
        TestServerClient writer = TestServerClient.connect(port, "disconnecting-chunk-writer");
        TestServerClient readerClient = TestServerClient.connect(port, "disconnecting-chunk-reader");

        try {
            writer.send(new WillSaveChunkLaterMessage(world, chunkX, chunkZ));
            writer.request(new PingMessage());

            java.util.concurrent.CompletableFuture<ServerBoundMessage> pendingRead = new java.util.concurrent.CompletableFuture<>();
            Thread reader = new Thread(() -> {
                try {
                    pendingRead.complete(readerClient.request(new ForceReadChunkMessage(world, "region", chunkX, chunkZ)));
                } catch (Exception e) {
                    pendingRead.completeExceptionally(e);
                }
            });
            reader.setDaemon(true);
            reader.start();

            Thread.sleep(300);
            assertFalse(pendingRead.isDone(), "Chunk read should wait for the connected writer");
            writer.close();

            ServerBoundMessage reply = pendingRead.get(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[0], ((DataMessageReply) reply).data,
                    "A disconnected writer must not leave chunk reads blocked forever");
        } finally {
            readerClient.close();
            writer.close();
        }
    }

    @Test
    @Order(26)
    void disconnectingWriterReleasesPendingEntityRead() throws Exception {
        String world = "disconnect-pending-entities-world";
        int chunkX = -27;
        int chunkZ = 13;
        TestServerClient writer = TestServerClient.connect(port, "disconnecting-entity-writer");
        TestServerClient readerClient = TestServerClient.connect(port, "disconnecting-entity-reader");

        try {
            writer.send(new WillSaveEntitiesLaterMessage(world, chunkX, chunkZ));
            writer.request(new PingMessage());

            java.util.concurrent.CompletableFuture<ServerBoundMessage> pendingRead = new java.util.concurrent.CompletableFuture<>();
            Thread reader = new Thread(() -> {
                try {
                    pendingRead.complete(readerClient.request(new ForceReadChunkMessage(world, "entities", chunkX, chunkZ)));
                } catch (Exception e) {
                    pendingRead.completeExceptionally(e);
                }
            });
            reader.setDaemon(true);
            reader.start();

            Thread.sleep(300);
            assertFalse(pendingRead.isDone(), "Entity read should wait for the connected writer");
            writer.close();

            ServerBoundMessage reply = pendingRead.get(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[0], ((DataMessageReply) reply).data,
                    "A disconnected writer must not leave entity reads blocked forever");
        } finally {
            readerClient.close();
            writer.close();
        }
    }

    @Test
    @Order(27)
    void unownedChunkSubscribersDoNotRedirectReadsToEachOther() throws Exception {
        String world = "concurrent-startup-world";
        int chunkX = 41;
        int chunkZ = -29;

        TestServerClient subscriber = TestServerClient.connect(port, "unowned-subscriber");
        TestServerClient reader = TestServerClient.connect(port, "unowned-reader");
        try {
            subscriber.send(new SubscribeChunkMessage(world, chunkX, chunkZ));
            subscriber.request(new PingMessage()); // Ensure the subscription is registered before the read.

            ServerBoundMessage reply = reader.request(new ReadChunkMessage(world, "region", chunkX, chunkZ));
            assertTrue(reply instanceof DataMessageReply, "An unowned subscriber must not be treated as a read owner");
            assertArrayEquals(new byte[0], ((DataMessageReply) reply).data, "A fresh chunk read must fall through to Master storage");
        } finally {
            subscriber.close();
            reader.close();
        }
    }

    @Test
    @Order(28)
    void entityReadersDoNotRedirectToBackendWaitingForFirstRead() throws Exception {
        String world = "concurrent-entity-startup-world";
        int chunkX = 43;
        int chunkZ = -31;
        byte[] entityPayload = "initial-entity-data".getBytes(StandardCharsets.UTF_8);

        TestServerClient firstServer = TestServerClient.connect(port, "entity-startup-first");
        TestServerClient secondServer = TestServerClient.connect(port, "entity-startup-second");
        try {
            firstServer.send(new WillSaveEntitiesLaterMessage(world, chunkX, chunkZ));
            firstServer.request(new PingMessage()); // Ensure the entity write lock is active.

            java.util.concurrent.CompletableFuture<ServerBoundMessage> firstRead = new java.util.concurrent.CompletableFuture<>();
            Thread firstReader = new Thread(() -> {
                try {
                    firstRead.complete(firstServer.request(new ReadChunkMessage(world, "entities", chunkX, chunkZ)));
                } catch (Exception e) {
                    firstRead.completeExceptionally(e);
                }
            });
            firstReader.setDaemon(true);
            firstReader.start();

            Thread.sleep(300);
            assertFalse(firstRead.isDone(), "The first entity read should wait for the pending write");

            java.util.concurrent.CompletableFuture<ServerBoundMessage> secondRead = new java.util.concurrent.CompletableFuture<>();
            Thread secondReader = new Thread(() -> {
                try {
                    secondRead.complete(secondServer.request(new ReadChunkMessage(world, "entities", chunkX, chunkZ)));
                } catch (Exception e) {
                    secondRead.completeExceptionally(e);
                }
            });
            secondReader.setDaemon(true);
            secondReader.start();

            Thread.sleep(300);
            assertFalse(secondRead.isDone(), "A reader must not redirect to a backend waiting for its first entity read");

            ServerBoundMessage writeReply = firstServer.request(new WriteChunkMessage(world, "entities", chunkX, chunkZ, entityPayload));
            assertTrue(((BooleanMessageReply) writeReply).result);
            assertArrayEquals(entityPayload, ((DataMessageReply) firstRead.get(10, TimeUnit.SECONDS)).data);
            assertArrayEquals(entityPayload, ((DataMessageReply) secondRead.get(10, TimeUnit.SECONDS)).data);
        } finally {
            firstServer.close();
            secondServer.close();
        }
    }

    @Test
    @Order(29)
    void concurrentChunkLocksAreAllReleasedWhenOneBackendDisconnects() throws Exception {
        String world = "concurrent-lock-bookkeeping-world";
        String firstName = "lock-bookkeeping-first";
        String secondName = "lock-bookkeeping-second";
        int chunkCountPerServer = 1024;
        TestServerClient firstServer = TestServerClient.connect(port, firstName);
        TestServerClient secondServer = TestServerClient.connect(port, secondName);
        try {
            // Separate Netty event loops mutate the shared per-connection bookkeeping map
            // concurrently while registering many distinct chunk locks.
            for (int i = 0; i < chunkCountPerServer; i++) {
                firstServer.send(new LockChunkMessage(world, i, 0));
                secondServer.send(new LockChunkMessage(world, i, 1));
                firstServer.send(new SubscribeEntitiesMessage(world, i, 2));
                secondServer.send(new SubscribeEntitiesMessage(world, i, 3));
            }
            firstServer.request(new PingMessage());
            secondServer.request(new PingMessage());

            ServerConnection firstConnection = ServerConnection.getConnection(firstName);
            ServerConnection secondConnection = ServerConnection.getConnection(secondName);
            assertTrue(firstConnection != null);
            assertTrue(secondConnection != null);
            for (int i = 0; i < chunkCountPerServer; i++) {
                assertEquals(firstConnection, ChunkSubscriptionManager.getOwner(world, i, 0));
                assertEquals(secondConnection, ChunkSubscriptionManager.getOwner(world, i, 1));
                assertEquals(firstConnection, EntitiesSubscriptionManager.getSubscriber(world, i, 2));
                assertEquals(secondConnection, EntitiesSubscriptionManager.getSubscriber(world, i, 3));
            }

            int sharedSubscriberChunkZ = 4;
            firstServer.send(new SubscribeChunkMessage(world, 0, sharedSubscriberChunkZ));
            secondServer.send(new SubscribeChunkMessage(world, 0, sharedSubscriberChunkZ));
            firstServer.request(new PingMessage());
            secondServer.request(new PingMessage());
            var subscriberSnapshot = ChunkSubscriptionManager.getSubscribers(world, 0, sharedSubscriberChunkZ);
            assertEquals(2, subscriberSnapshot.size());
            firstServer.send(new UnsubscribeChunkMessage(world, 0, sharedSubscriberChunkZ));
            firstServer.request(new PingMessage());
            assertEquals(2, subscriberSnapshot.size(), "A subscriber snapshot must remain stable while the live list changes");
            assertEquals(secondConnection, ChunkSubscriptionManager.getSubscribers(world, 0, sharedSubscriberChunkZ).getFirst());

            firstServer.close();
            firstServer = null;

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean allReleased = false;
            while (System.nanoTime() < deadline) {
                allReleased = true;
                for (int i = 0; i < chunkCountPerServer; i++) {
                    if (ChunkSubscriptionManager.getOwner(world, i, 0) != null
                            || EntitiesSubscriptionManager.getSubscriber(world, i, 2) != null) {
                        allReleased = false;
                        break;
                    }
                }
                if (allReleased) break;
                Thread.sleep(10);
            }
            assertTrue(allReleased, "Disconnect must release every chunk lock tracked during concurrent registration");
            for (int i = 0; i < chunkCountPerServer; i++) {
                assertEquals(secondConnection, ChunkSubscriptionManager.getOwner(world, i, 1),
                        "Disconnecting one backend must preserve the other backend's independently tracked locks");
                assertEquals(secondConnection, EntitiesSubscriptionManager.getSubscriber(world, i, 3),
                        "Disconnecting one backend must preserve the other backend's entity subscriptions");
            }
        } finally {
            if (firstServer != null) firstServer.close();
            secondServer.close();
        }
    }

    @Test
    @Order(30)
    void acknowledgedJsonWriteIsReadBackThroughMasterStorage() throws Exception {
        Path jsonFile = Path.of("build", "tmp", "multipaper-json-" + UUID.randomUUID() + ".json");
        String sharedPath = jsonFile.toString().replace('\\', '/');
        byte[] json = "{\"checkpoint\":42,\"name\":\"master-json-write\"}".getBytes(StandardCharsets.UTF_8);
        TestServerClient jsonReader = TestServerClient.connect(port, "json-storage-reader");

        try {
            ServerBoundMessage writeReply = serverB.request(new WriteJsonMessage(sharedPath, json));
            assertTrue(((BooleanMessageReply) writeReply).result, "Master must acknowledge the durable JSON replacement");

            ServerBoundMessage readReply = jsonReader.request(new ReadJsonMessage(sharedPath));
            assertArrayEquals(json, ((DataMessageReply) readReply).data,
                    "A second backend must read the full acknowledged JSON bytes from Master storage");
        } finally {
            jsonReader.close();
            Files.deleteIfExists(jsonFile);
            Files.deleteIfExists(jsonFile.resolveSibling(jsonFile.getFileName() + "_new"));
            Files.deleteIfExists(jsonFile.resolveSibling(jsonFile.getFileName() + "_old"));
        }
    }

    @Test
    @Order(31)
    void worldUidIsCreatedOnceAndSharedAcrossBackends() throws Exception {
        TestServerClient uidReader = TestServerClient.connect(port, "uid-storage-reader");
        try {
            byte[] uid = ((DataMessageReply) serverB.request(new ReadUidMessage(WORLD))).data;
            assertEquals(16, uid.length);

            byte[] secondUid = ((DataMessageReply) uidReader.request(new ReadUidMessage(WORLD))).data;
            assertArrayEquals(uid, secondUid);
        } finally {
            uidReader.close();
        }
    }

    @Test
    @Order(32)
    void transientEntitySnapshotsReplaceMovedEntitiesAndAcknowledgeFailures() throws Exception {
        int cx = 41;
        int cz = 37;
        UUID storedId = UUID.randomUUID();
        UUID transientId = UUID.randomUUID();
        CompoundTag stored = entityChunk(entity(storedId, "stored"));
        CompoundTag transientSave = entityChunk(entity(storedId, "duplicate"), entity(transientId, "new"));

        assertTrue(((BooleanMessageReply) serverB.request(new WriteChunkMessage(WORLD, "entities", cx, cz,
                compressedNbt(stored), false))).result);
        assertTrue(((BooleanMessageReply) serverB.request(new WriteChunkMessage(WORLD, "entities", cx, cz,
                compressedNbt(transientSave), true))).result);

        byte[] persisted = ((DataMessageReply) serverB.request(new ReadChunkMessage(WORLD, "entities", cx, cz))).data;
        ListTag entities = readCompressedNbt(persisted).get("Entities").asList();
        assertEquals(2, entities.size(), "Transient merge must retain unique entity UUIDs");
        assertEquals("duplicate", entities.get(0).asCompound().get("state").stringValue(),
                "The current transient save must replace an older persisted snapshot for the same UUID");
        assertEquals("new", entities.get(1).asCompound().get("state").stringValue());

        CompoundTag movedOutSnapshot = entityChunk(entity(transientId, "new"));
        assertTrue(((BooleanMessageReply) serverB.request(new WriteChunkMessage(WORLD, "entities", cx, cz,
                compressedNbt(movedOutSnapshot), true))).result);
        byte[] afterMove = ((DataMessageReply) serverB.request(new ReadChunkMessage(WORLD, "entities", cx, cz))).data;
        ListTag afterMoveEntities = readCompressedNbt(afterMove).get("Entities").asList();
        assertEquals(1, afterMoveEntities.size(),
                "An authoritative transient snapshot must not resurrect entities that moved out of the chunk");
        assertEquals("new", afterMoveEntities.get(0).asCompound().get("state").stringValue());

        int failedCx = cx + 1;
        serverB.send(new WillSaveEntitiesLaterMessage(WORLD, failedCx, cz));
        serverB.request(new PingMessage());
        TestServerClient waitingReader = TestServerClient.connect(port, "transient-write-failure-reader");
        try {
            java.util.concurrent.CompletableFuture<ServerBoundMessage> waitingRead = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return waitingReader.request(new ReadChunkMessage(WORLD, "entities", failedCx, cz));
                } catch (Exception exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            });
            Thread.sleep(100);
            assertFalse(waitingRead.isDone(), "Entity readers must wait for the announced write");

            BooleanMessageReply rejected = (BooleanMessageReply) serverB.request(new WriteChunkMessage(WORLD, "entities", failedCx, cz,
                    new byte[]{1, 2, 3}, true));
            assertFalse(rejected.result, "Malformed transient NBT must receive a negative acknowledgment");
            assertTrue(waitingRead.get(10, TimeUnit.SECONDS) instanceof DataMessageReply,
                    "A rejected write must release waiting entity readers instead of leaving a permanent lock");
        } finally {
            waitingReader.close();
        }
    }

    @Test
    @Order(15)
    void delayedOldOwnerChunkSnapshotCannotOverwriteNewOwnerSnapshot() throws Exception {
        int chunkX = 31;
        int chunkZ = 0;
        byte[] oldOwnerSnapshot = chestChunk(32);
        byte[] newOwnerSnapshot = chestChunk(17);

        serverA.send(new LockChunkMessage(WORLD, chunkX, chunkZ));
        assertEquals("server-a", pollUntilOwner(serverA, chunkX, chunkZ, "server-a").owner);

        serverB.send(new SubscribeChunkMessage(WORLD, chunkX, chunkZ));
        serverB.send(new LockChunkMessage(WORLD, chunkX, chunkZ));
        serverB.request(new PingMessage());
        assertEquals("server-a", pollUntilOwner(serverB, chunkX, chunkZ, "server-a").owner,
                "The existing owner remains authoritative until it unlocks");

        // Backend B is already waiting for ownership. Once A unlocks, B becomes owner.
        serverA.send(new UnlockChunkMessage(WORLD, chunkX, chunkZ));
        assertEquals("server-b", pollUntilOwner(serverB, chunkX, chunkZ, "server-b").owner);

        // B persists the new chunk image first. Then A's delayed old snapshot arrives late.
        serverB.send(new WillSaveChunkLaterMessage(WORLD, chunkX, chunkZ));
        serverB.request(new PingMessage());
        ServerBoundMessage newWrite = serverB.request(new WriteChunkMessage(WORLD, "region", chunkX, chunkZ, newOwnerSnapshot));
        assertTrue(((BooleanMessageReply) newWrite).result);

        ServerBoundMessage staleWrite = serverA.request(new WriteChunkMessage(WORLD, "region", chunkX, chunkZ, oldOwnerSnapshot));
        assertFalse(((BooleanMessageReply) staleWrite).result,
                "Master must reject a delayed snapshot from a former owner after ownership handoff");

        ServerBoundMessage readReply = serverB.request(new ForceReadChunkMessage(WORLD, "region", chunkX, chunkZ));
        byte[] persisted = ((DataMessageReply) readReply).data;
        assertArrayEquals(newOwnerSnapshot, persisted, "The Master chunk snapshot must remain the latest owner image");
        assertEquals(17, readChestItemCount(persisted), "The newer owner's chest inventory must remain intact");
    }

    @Test
    @Order(16)
    void delayedFormerOwnerWriteIsRejectedBeforeNextOwnerClaimsChunk() throws Exception {
        int chunkX = 32;
        int chunkZ = 0;
        byte[] currentSnapshot = chestChunk(17);
        byte[] delayedOlderSnapshot = chestChunk(32);

        serverA.send(new SubscribeChunkMessage(WORLD, chunkX, chunkZ));
        serverA.send(new LockChunkMessage(WORLD, chunkX, chunkZ));
        assertEquals("server-a", pollUntilOwner(serverA, chunkX, chunkZ, "server-a").owner);

        assertTrue(((BooleanMessageReply) serverA.request(new WriteChunkMessage(WORLD, "region", chunkX, chunkZ, currentSnapshot))).result);
        serverA.send(new UnlockChunkMessage(WORLD, chunkX, chunkZ));
        assertEquals("", pollUntilOwner(serverA, chunkX, chunkZ, "").owner,
                "The test must submit the stale write during the interval with no current owner");

        ServerBoundMessage staleWrite = serverA.request(new WriteChunkMessage(WORLD, "region", chunkX, chunkZ, delayedOlderSnapshot));
        assertFalse(((BooleanMessageReply) staleWrite).result,
                "Master must reject former-owner writes until a new owner claims the chunk");
        byte[] persisted = ((DataMessageReply) serverA.request(new ForceReadChunkMessage(WORLD, "region", chunkX, chunkZ))).data;
        assertArrayEquals(currentSnapshot, persisted, "The ownerless gap must not replace the current chest NBT");
        assertEquals(17, readChestItemCount(persisted), "The newer chest contents must remain intact");

        serverB.send(new SubscribeChunkMessage(WORLD, chunkX, chunkZ));
        serverB.send(new LockChunkMessage(WORLD, chunkX, chunkZ));
        assertEquals("server-b", pollUntilOwner(serverB, chunkX, chunkZ, "server-b").owner);
    }

    @Test
    @Order(35)
    void delayedFormerOwnerWriteIsRejectedAfterLastSubscriberUnsubscribes() throws Exception {
        int chunkX = 33;
        int chunkZ = 0;
        byte[] currentSnapshot = chestChunk(17);
        byte[] delayedOlderSnapshot = chestChunk(32);
        TestServerClient formerOwner = TestServerClient.connect(port, "last-subscriber-former-owner");
        TestServerClient nextOwner = TestServerClient.connect(port, "last-subscriber-next-owner");
        try {
            formerOwner.send(new SubscribeChunkMessage(WORLD, chunkX, chunkZ));
            formerOwner.send(new LockChunkMessage(WORLD, chunkX, chunkZ));
            assertEquals("last-subscriber-former-owner", pollUntilOwner(formerOwner, chunkX, chunkZ, "last-subscriber-former-owner").owner);
            assertTrue(((BooleanMessageReply) formerOwner.request(new WriteChunkMessage(WORLD, "region", chunkX, chunkZ, currentSnapshot))).result);
            formerOwner.send(new UnlockChunkMessage(WORLD, chunkX, chunkZ));
            assertEquals("", pollUntilOwner(formerOwner, chunkX, chunkZ, "").owner);
            assertTrue(((BooleanMessageReply) formerOwner.request(new UnsubscribeChunkMessage(WORLD, chunkX, chunkZ))).result);

            ServerBoundMessage staleWrite = formerOwner.request(new WriteChunkMessage(WORLD, "region", chunkX, chunkZ, delayedOlderSnapshot));
            assertFalse(((BooleanMessageReply) staleWrite).result,
                    "A delayed former-owner write must remain fenced after its last subscription is removed");
            byte[] persisted = ((DataMessageReply) formerOwner.request(new ForceReadChunkMessage(WORLD, "region", chunkX, chunkZ))).data;
            assertArrayEquals(currentSnapshot, persisted, "Unsubscribe must not make the old snapshot writable again");
            assertEquals(17, readChestItemCount(persisted), "The authoritative chest contents must remain intact");

            // A new owner must still be able to load and write the previously owned chunk.
            nextOwner.send(new SubscribeChunkMessage(WORLD, chunkX, chunkZ));
            nextOwner.send(new LockChunkMessage(WORLD, chunkX, chunkZ));
            assertEquals("last-subscriber-next-owner", pollUntilOwner(nextOwner, chunkX, chunkZ, "last-subscriber-next-owner").owner);
            assertTrue(((BooleanMessageReply) nextOwner.request(new WriteChunkMessage(WORLD, "region", chunkX, chunkZ, chestChunk(23)))).result);
        } finally {
            formerOwner.close();
            nextOwner.close();
        }
    }

    @Test
    @Order(33)
    void durableSameOwnerPlayerTransactionIsIdempotentAndValidatesOwnership() throws Exception {
        TestServerClient editorOwner = TestServerClient.connect(port, "transaction-editor");
        TestServerClient targetOwner = TestServerClient.connect(port, "transaction-target");
        UUID editorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID editorSessionId = UUID.randomUUID();
        UUID targetSessionId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        String world = java.nio.file.Files.createTempDirectory("multipaper-player-transaction-").toString();
        byte[] editorBefore = {0, 0, 0};
        byte[] targetBefore = {0, 0, 0};
        byte[] editorAfter = {1, 2, 3};
        byte[] targetAfter = {4, 5, 6};
        CommitPlayerDataTransactionMessage.Participant[] participants = {
                new CommitPlayerDataTransactionMessage.Participant(editorId, editorSessionId, "transaction-editor", sha256(editorBefore), editorAfter),
                new CommitPlayerDataTransactionMessage.Participant(targetId, targetSessionId, "transaction-editor", sha256(targetBefore), targetAfter)
        };

        try {
            assertTrue(((BooleanMessageReply) editorOwner.request(new PlayerConnectMessage(editorId))).result);
            assertTrue(((BooleanMessageReply) editorOwner.request(new PlayerConnectMessage(targetId))).result);
            assertTrue(((BooleanMessageReply) editorOwner.request(new RegisterPlayerSessionMessage(editorId, editorSessionId))).result);
            assertTrue(((BooleanMessageReply) editorOwner.request(new RegisterPlayerSessionMessage(targetId, targetSessionId))).result);
            assertTrue(((BooleanMessageReply) editorOwner.request(new WritePlayerMessage(world, editorId.toString(), editorBefore))).result);
            assertTrue(((BooleanMessageReply) editorOwner.request(new WritePlayerMessage(world, targetId.toString(), targetBefore))).result);
            CommitPlayerDataTransactionMessage transaction = new CommitPlayerDataTransactionMessage(world, transactionId, participants);

            assertTrue(((BooleanMessageReply) editorOwner.request(transaction)).result,
                    "The active editor owner should commit when both player owners match");
            assertArrayEquals(editorAfter, ((DataMessageReply) editorOwner.request(new ReadPlayerMessage(world, editorId.toString()))).data);
            assertArrayEquals(targetAfter, ((DataMessageReply) editorOwner.request(new ReadPlayerMessage(world, targetId.toString()))).data);

            byte[] laterEditorSave = {7, 8, 9};
            assertTrue(((BooleanMessageReply) editorOwner.request(new WritePlayerMessage(world, editorId.toString(), laterEditorSave))).result);
            assertTrue(((BooleanMessageReply) editorOwner.request(transaction)).result,
                    "A duplicate transaction ID must acknowledge idempotently");
            assertArrayEquals(laterEditorSave, ((DataMessageReply) editorOwner.request(new ReadPlayerMessage(world, editorId.toString()))).data,
                    "Replaying a committed ID must not restore older snapshots");

            byte[] currentEditor = {21, 22};
            byte[] currentTarget = {31, 32};
            assertTrue(((BooleanMessageReply) editorOwner.request(new WritePlayerMessage(world, editorId.toString(), currentEditor))).result);
            assertTrue(((BooleanMessageReply) editorOwner.request(new WritePlayerMessage(world, targetId.toString(), currentTarget))).result);
            CommitPlayerDataTransactionMessage staleBatch = new CommitPlayerDataTransactionMessage(world, UUID.randomUUID(), new CommitPlayerDataTransactionMessage.Participant[]{
                    new CommitPlayerDataTransactionMessage.Participant(editorId, editorSessionId, "transaction-editor", sha256(currentEditor), new byte[]{41}),
                    new CommitPlayerDataTransactionMessage.Participant(targetId, targetSessionId, "transaction-editor", sha256(new byte[]{99}), new byte[]{42})
            });
            assertFalse(((BooleanMessageReply) editorOwner.request(staleBatch)).result,
                    "The Master must reject the complete transaction when any participant before-image is stale");
            assertArrayEquals(currentEditor, ((DataMessageReply) editorOwner.request(new ReadPlayerMessage(world, editorId.toString()))).data,
                    "A stale batch must not write its otherwise-current first participant");
            assertArrayEquals(currentTarget, ((DataMessageReply) editorOwner.request(new ReadPlayerMessage(world, targetId.toString()))).data,
                    "A stale batch must not write its stale participant");

            CommitPlayerDataTransactionMessage freshBatch = new CommitPlayerDataTransactionMessage(world, UUID.randomUUID(), new CommitPlayerDataTransactionMessage.Participant[]{
                    new CommitPlayerDataTransactionMessage.Participant(editorId, editorSessionId, "transaction-editor", sha256(currentEditor), new byte[]{51}),
                    new CommitPlayerDataTransactionMessage.Participant(targetId, targetSessionId, "transaction-editor", sha256(currentTarget), new byte[]{52})
            });
            assertTrue(((BooleanMessageReply) editorOwner.request(freshBatch)).result,
                    "A transaction with current before-images should commit");
            assertArrayEquals(new byte[]{51}, ((DataMessageReply) editorOwner.request(new ReadPlayerMessage(world, editorId.toString()))).data);
            assertArrayEquals(new byte[]{52}, ((DataMessageReply) editorOwner.request(new ReadPlayerMessage(world, targetId.toString()))).data);

            CommitPlayerDataTransactionMessage wrongTargetOwner = new CommitPlayerDataTransactionMessage(world, UUID.randomUUID(), new CommitPlayerDataTransactionMessage.Participant[]{
                    participants[0], new CommitPlayerDataTransactionMessage.Participant(targetId, targetSessionId, "wrong-owner", sha256(targetBefore), targetAfter)
            });
            assertFalse(((BooleanMessageReply) editorOwner.request(wrongTargetOwner)).result,
                    "The Master must reject a stale or incorrect target-owner claim");

            UUID reconnectedEditorSessionId = UUID.randomUUID();
            assertTrue(((BooleanMessageReply) editorOwner.request(
                    new RegisterPlayerSessionMessage(editorId, reconnectedEditorSessionId))).result);
            CommitPlayerDataTransactionMessage staleEpochBatch = new CommitPlayerDataTransactionMessage(world, UUID.randomUUID(), new CommitPlayerDataTransactionMessage.Participant[]{
                    new CommitPlayerDataTransactionMessage.Participant(editorId, editorSessionId, "transaction-editor", sha256(new byte[]{51}), new byte[]{61}),
                    new CommitPlayerDataTransactionMessage.Participant(targetId, targetSessionId, "transaction-editor", sha256(new byte[]{52}), new byte[]{62})
            });
            assertFalse(((BooleanMessageReply) editorOwner.request(staleEpochBatch)).result,
                    "A transaction from an earlier login epoch must not commit after same-backend reconnect");
            assertArrayEquals(new byte[]{51}, ((DataMessageReply) editorOwner.request(new ReadPlayerMessage(world, editorId.toString()))).data);
            assertArrayEquals(new byte[]{52}, ((DataMessageReply) editorOwner.request(new ReadPlayerMessage(world, targetId.toString()))).data);

            CommitPlayerDataTransactionMessage currentEpochBatch = new CommitPlayerDataTransactionMessage(world, UUID.randomUUID(), new CommitPlayerDataTransactionMessage.Participant[]{
                    new CommitPlayerDataTransactionMessage.Participant(editorId, reconnectedEditorSessionId, "transaction-editor", sha256(new byte[]{51}), new byte[]{63}),
                    new CommitPlayerDataTransactionMessage.Participant(targetId, targetSessionId, "transaction-editor", sha256(new byte[]{52}), new byte[]{64})
            });
            assertTrue(((BooleanMessageReply) editorOwner.request(currentEpochBatch)).result,
                    "A transaction carrying the current registered epochs should remain eligible");
            assertArrayEquals(new byte[]{63}, ((DataMessageReply) editorOwner.request(new ReadPlayerMessage(world, editorId.toString()))).data);
            assertArrayEquals(new byte[]{64}, ((DataMessageReply) editorOwner.request(new ReadPlayerMessage(world, targetId.toString()))).data);

            TestServerClient unowned = TestServerClient.connect(port, "transaction-unowned");
            try {
                assertFalse(((BooleanMessageReply) unowned.request(transaction)).result,
                        "A backend owning neither participant must not submit another backend's transaction");
            } finally {
                unowned.close();
            }
        } finally {
            editorOwner.close();
            targetOwner.close();
        }
    }

    @Test
    @Order(34)
    void oneOwnerCannotCommitAnotherOwnersPlayerdataSnapshot() throws Exception {
        TestServerClient editorOwner = TestServerClient.connect(port, "transaction-editor-a");
        TestServerClient targetOwner = TestServerClient.connect(port, "transaction-target-b");
        UUID editorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID editorSessionId = UUID.randomUUID();
        UUID targetSessionId = UUID.randomUUID();
        String world = java.nio.file.Files.createTempDirectory("multipaper-cross-owner-transaction-").toString();
        byte[] editorAfter = {11, 12};
        byte[] targetAfter = {91, 92};

        try {
            assertTrue(((BooleanMessageReply) editorOwner.request(new PlayerConnectMessage(editorId))).result);
            assertTrue(((BooleanMessageReply) targetOwner.request(new PlayerConnectMessage(targetId))).result);
            assertTrue(((BooleanMessageReply) editorOwner.request(new RegisterPlayerSessionMessage(editorId, editorSessionId))).result);
            assertTrue(((BooleanMessageReply) targetOwner.request(new RegisterPlayerSessionMessage(targetId, targetSessionId))).result);
            CommitPlayerDataTransactionMessage transaction = new CommitPlayerDataTransactionMessage(world, UUID.randomUUID(), new CommitPlayerDataTransactionMessage.Participant[]{
                    new CommitPlayerDataTransactionMessage.Participant(editorId, editorSessionId, "transaction-editor-a", sha256(new byte[0]), editorAfter),
                    new CommitPlayerDataTransactionMessage.Participant(targetId, targetSessionId, "transaction-target-b", sha256(new byte[0]), targetAfter)
            });

            assertFalse(((BooleanMessageReply) editorOwner.request(transaction)).result,
                    "A matching owner name in the request is not consent from the target owner");
            assertEquals(0, ((DataMessageReply) editorOwner.request(new ReadPlayerMessage(world, editorId.toString()))).data.length,
                    "Rejected cross-owner transactions must not persist the editor image");
            assertEquals(0, ((DataMessageReply) targetOwner.request(new ReadPlayerMessage(world, targetId.toString()))).data.length,
                    "Rejected cross-owner transactions must not persist the target image");
        } finally {
            editorOwner.close();
            targetOwner.close();
        }
    }

    @Test
    @Order(38)
    void livePlayerSessionEpochRequiresNegotiatedCurrentOwnerAndIsClearedOnDisconnect() throws Exception {
        TestServerClient owner = TestServerClient.connect(port, "session-epoch-owner");
        TestServerClient legacyOwner = TestServerClient.connectLegacy(port, "session-epoch-legacy-owner");
        TestServerClient nextOwner = TestServerClient.connect(port, "session-epoch-next-owner");
        UUID playerId = UUID.randomUUID();
        UUID legacyPlayerId = UUID.randomUUID();
        UUID firstSession = UUID.randomUUID();
        UUID nextSession = UUID.randomUUID();

        try {
            assertTrue(owner.supportsPlayerSessionProtocol());
            assertFalse(legacyOwner.supportsPlayerSessionProtocol());
            assertTrue(((BooleanMessageReply) owner.request(new PlayerConnectMessage(playerId))).result);
            assertTrue(((BooleanMessageReply) legacyOwner.request(new PlayerConnectMessage(legacyPlayerId))).result);

            assertFalse(((BooleanMessageReply) legacyOwner.request(
                    new RegisterPlayerSessionMessage(legacyPlayerId, UUID.randomUUID()))).result,
                    "A peer that did not negotiate session support must not register a session epoch");
            assertFalse(((BooleanMessageReply) nextOwner.request(
                    new RegisterPlayerSessionMessage(playerId, UUID.randomUUID()))).result,
                    "A different backend must not register another owner's player session");
            assertFalse(((BooleanMessageReply) owner.request(
                    new RegisterPlayerSessionMessage(playerId, new UUID(0, 0)))).result,
                    "An empty session epoch must not be registered as a valid transaction epoch");

            assertTrue(((BooleanMessageReply) owner.request(
                    new RegisterPlayerSessionMessage(playerId, firstSession))).result);
            ServerConnection ownerConnection = ServerConnection.getConnection("session-epoch-owner");
            assertEquals(firstSession, ownerConnection.getPlayerSessionId(playerId),
                    "Master must retain the registered session epoch for the active owner");
            assertTrue(((BooleanMessageReply) owner.request(
                    new RegisterPlayerSessionMessage(playerId, nextSession))).result,
                    "A reconnect on the same owner may advance the session epoch");
            assertEquals(nextSession, ownerConnection.getPlayerSessionId(playerId));

            owner.send(new PlayerDisconnectMessage(playerId));
            assertFalse(((BooleanMessageReply) owner.request(
                    new RegisterPlayerSessionMessage(playerId, firstSession))).result,
                    "A disconnecting session must not be re-registered");
            assertNull(ownerConnection.getPlayerSessionId(playerId),
                    "Disconnect must retire the registered session epoch");
            assertTrue(((BooleanMessageReply) owner.request(new PlayerConnectMessage(playerId))).result,
                    "The ordered disconnect must finish before a reconnect registers its new epoch");
            assertTrue(((BooleanMessageReply) owner.request(
                    new RegisterPlayerSessionMessage(playerId, nextSession))).result);
            assertEquals(nextSession, ownerConnection.getPlayerSessionId(playerId));
        } finally {
            owner.close();
            legacyOwner.close();
            nextOwner.close();
        }
    }

    @Test
    @Order(35)
    void playerReadWaitsForEarlierQueuedSave() throws Exception {
        TestServerClient owner = TestServerClient.connect(port, "ordered-player-read-owner");
        UUID playerId = UUID.randomUUID();
        String world = Files.createTempDirectory("multipaper-ordered-player-read-").toString();
        byte[] oldData = {1, 2, 3};
        byte[] newData = {7, 8, 9};
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        try {
            assertTrue(((BooleanMessageReply) owner.request(new PlayerConnectMessage(playerId))).result);
            PlayerDataTransactionManager.writePlayer(world, playerId, oldData);

            PlayerDataOperationQueue.submit(playerId, () -> {
                blockerEntered.countDown();
                try {
                    if (!releaseBlocker.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release playerdata queue blocker");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Playerdata queue blocker interrupted", interrupted);
                }
            });
            assertTrue(blockerEntered.await(5, TimeUnit.SECONDS), "Playerdata queue blocker should start");

            CompletableFuture<ServerBoundMessage> writeReply = CompletableFuture.supplyAsync(() -> {
                try {
                    return owner.request(new WritePlayerMessage(world, playerId.toString(), newData));
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            // The Master replies to this subsequent message only after it has
            // received and enqueued the preceding write on the same TCP channel.
            owner.request(new PingMessage());

            CompletableFuture<ServerBoundMessage> readReply = CompletableFuture.supplyAsync(() -> {
                try {
                    return owner.request(new ReadPlayerMessage(world, playerId.toString()));
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            owner.request(new PingMessage());
            assertFalse(readReply.isDone(), "A read must wait behind the earlier queued player save");

            releaseBlocker.countDown();
            assertTrue(((BooleanMessageReply) writeReply.get(10, TimeUnit.SECONDS)).result);
            assertArrayEquals(newData, ((DataMessageReply) readReply.get(10, TimeUnit.SECONDS)).data,
                    "The ordered read must return the prior save's new image, not stale playerdata");
        } finally {
            releaseBlocker.countDown();
            owner.close();
        }
    }

    @Test
    @Order(35)
    void unacknowledgedMasterBoundPeerCannotCommitPlayerDataTransaction() throws Exception {
        TestServerClient legacyPeer = TestServerClient.connectLegacy(port, "transaction-legacy-peer");
        UUID playerId = UUID.randomUUID();
        UUID secondPlayerId = UUID.randomUUID();
        String world = Files.createTempDirectory("multipaper-legacy-player-transaction-").toString();
        try {
            HelloMessage oldCapabilityAcknowledgement = new HelloMessage(
                    legacyPeer.name, UUID.nameUUIDFromBytes(legacyPeer.name.getBytes(StandardCharsets.UTF_8)));
            oldCapabilityAcknowledgement.setTransactionId(Integer.MIN_VALUE + 1);
            legacyPeer.send(oldCapabilityAcknowledgement);
            assertFalse(legacyPeer.supportsPlayerDataTransactionProtocol(),
                    "A peer acknowledging the old after-image-only marker must remain unsupported");
            assertTrue(((BooleanMessageReply) legacyPeer.request(new PlayerConnectMessage(playerId))).result);
            assertTrue(((BooleanMessageReply) legacyPeer.request(new PlayerConnectMessage(secondPlayerId))).result);

            CommitPlayerDataTransactionMessage transaction = new CommitPlayerDataTransactionMessage(
                    world,
                    UUID.randomUUID(),
                    new CommitPlayerDataTransactionMessage.Participant[]{
                            new CommitPlayerDataTransactionMessage.Participant(
                                    playerId, "transaction-legacy-peer", sha256(new byte[0]), new byte[]{42, 43}),
                            new CommitPlayerDataTransactionMessage.Participant(
                                    secondPlayerId, "transaction-legacy-peer", sha256(new byte[0]), new byte[]{44, 45})
                    });
            assertFalse(((BooleanMessageReply) legacyPeer.request(transaction)).result,
                    "An unacknowledged peer must not commit the new transaction message");
            assertEquals(0, ((DataMessageReply) legacyPeer.request(
                    new ReadPlayerMessage(world, playerId.toString()))).data.length,
                    "A rejected transaction must not persist the first participant snapshot");
            assertEquals(0, ((DataMessageReply) legacyPeer.request(
                    new ReadPlayerMessage(world, secondPlayerId.toString()))).data.length,
                    "A rejected transaction must not persist the second participant snapshot");
        } finally {
            legacyPeer.close();
        }
    }

    @Test
    @Order(36)
    void repeatedHelloRequiresFreshPlayerDataCapabilityAcknowledgement() throws Exception {
        TestServerClient peer = TestServerClient.connect(port, "transaction-renegotiating-peer");
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        String world = Files.createTempDirectory("multipaper-renegotiated-player-transaction-").toString();
        try {
            assertTrue(((BooleanMessageReply) peer.request(new PlayerConnectMessage(firstPlayer))).result);
            assertTrue(((BooleanMessageReply) peer.request(new PlayerConnectMessage(secondPlayer))).result);

            // TCP ordering ensures Master handles this new Hello before the following transaction.
            peer.sendHelloWithoutCapabilityAcknowledgement();
            CommitPlayerDataTransactionMessage transaction = new CommitPlayerDataTransactionMessage(
                    world,
                    UUID.randomUUID(),
                    new CommitPlayerDataTransactionMessage.Participant[]{
                            new CommitPlayerDataTransactionMessage.Participant(firstPlayer, "transaction-renegotiating-peer", sha256(new byte[0]), new byte[]{1}),
                            new CommitPlayerDataTransactionMessage.Participant(secondPlayer, "transaction-renegotiating-peer", sha256(new byte[0]), new byte[]{2})
                    });
            assertFalse(((BooleanMessageReply) peer.request(transaction)).result,
                    "A peer must re-acknowledge after sending a new Hello");
            assertEquals(0, ((DataMessageReply) peer.request(
                    new ReadPlayerMessage(world, firstPlayer.toString()))).data.length);
            assertEquals(0, ((DataMessageReply) peer.request(
                    new ReadPlayerMessage(world, secondPlayer.toString()))).data.length);
        } finally {
            peer.close();
        }
    }

    private static byte[] chestChunk(int itemCount) throws IOException {
        CompoundTag root = new CompoundTag();
        root.add("DataVersion", new IntTag(5023));
        root.add("xPos", new IntTag(31));
        root.add("zPos", new IntTag(0));

        CompoundTag chest = new CompoundTag();
        chest.add("id", new StringTag("minecraft:chest"));
        chest.add("x", new IntTag(496));
        chest.add("y", new IntTag(64));
        chest.add("z", new IntTag(0));
        ListTag items = new ListTag(10, new java.util.ArrayList<>());
        CompoundTag stack = new CompoundTag();
        stack.add("id", new StringTag("minecraft:diamond"));
        stack.add("count", new IntTag(itemCount));
        stack.add("Slot", new se.llbit.nbt.ByteTag((byte) 0));
        items.add(stack);
        chest.add("Items", items);
        ListTag blockEntities = new ListTag(10, new java.util.ArrayList<>());
        blockEntities.add(chest);
        root.add("block_entities", blockEntities);
        return compressedNbt(root);
    }

    private static int readChestItemCount(byte[] compressedChunk) throws IOException {
        CompoundTag root = readCompressedNbt(compressedChunk);
        ListTag blockEntities = root.get("block_entities").asList();
        assertTrue(blockEntities != null && !blockEntities.isEmpty(), "Persisted chunk must contain its chest block entity");
        ListTag items = blockEntities.get(0).asCompound().get("Items").asList();
        assertTrue(items != null && !items.isEmpty(), "Persisted chest must retain its item stack");
        return items.get(0).asCompound().get("count").intValue();
    }

    private static byte[] compressedNbt(CompoundTag root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            output.writeByte(10);
            output.writeUTF("");
            root.write(output);
        }
        return bytes.toByteArray();
    }

    private static CompoundTag readCompressedNbt(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes)))) {
            assertEquals(10, input.readUnsignedByte());
            input.readUTF();
            return CompoundTag.read(input).asCompound();
        }
    }

    private static CompoundTag entityChunk(CompoundTag... entities) {
        CompoundTag chunk = new CompoundTag();
        chunk.add("Entities", new ListTag((byte) 10, new java.util.ArrayList<>(java.util.List.of(entities))));
        return chunk;
    }

    private static CompoundTag entity(UUID id, String state) {
        long most = id.getMostSignificantBits();
        long least = id.getLeastSignificantBits();
        CompoundTag entity = new CompoundTag();
        entity.add("UUID", new IntArrayTag(new int[]{(int) (most >>> 32), (int) most, (int) (least >>> 32), (int) least}));
        entity.add("state", new StringTag(state));
        return entity;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    @Test
    @Order(37)
    void chunkOwnershipDoesNotTransferBeforePreannouncedChestSaveIsPersisted() throws Exception {
        int chunkX = 34;
        int chunkZ = 0;
        String world = Files.createTempDirectory("multipaper-pending-chest-unlock-").toString();
        byte[] savedChest = chestChunk(17);
        TestServerClient owner = TestServerClient.connect(port, "pending-save-owner");
        TestServerClient waiter = TestServerClient.connect(port, "pending-save-waiter");
        try {
            owner.send(new SubscribeChunkMessage(world, chunkX, chunkZ));
            owner.send(new LockChunkMessage(world, chunkX, chunkZ));
            owner.request(new PingMessage());
            assertEquals("pending-save-owner", pollUntilOwner(owner, chunkX, chunkZ, "pending-save-owner").owner);

            waiter.send(new SubscribeChunkMessage(world, chunkX, chunkZ));
            waiter.send(new LockChunkMessage(world, chunkX, chunkZ));
            waiter.request(new PingMessage());
            assertEquals("pending-save-owner", pollUntilOwner(waiter, chunkX, chunkZ, "pending-save-owner").owner);

            // Model Paper announcing its asynchronous save, then requesting unlock
            // before the serialized chunk NBT reaches the Master.
            owner.send(new WillSaveChunkLaterMessage(world, chunkX, chunkZ));
            owner.request(new PingMessage());
            owner.send(new UnlockChunkMessage(world, chunkX, chunkZ));

            // The waiting backend must not acquire ownership or read an older chest
            // image while A's announced save is still outstanding.
            waiter.send(new LockChunkMessage(world, chunkX, chunkZ));
            waiter.request(new PingMessage());
            assertEquals("pending-save-owner", pollUntilOwner(waiter, chunkX, chunkZ, "pending-save-owner").owner,
                    "Master must keep the previous owner until its announced chest save is persisted");

            CompletableFuture<ServerBoundMessage> pendingRead = CompletableFuture.supplyAsync(() -> {
                try {
                    return waiter.request(new ForceReadChunkMessage(world, "region", chunkX, chunkZ));
                } catch (Exception failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            });
            Thread.sleep(200);
            assertFalse(pendingRead.isDone(), "No-holder read must wait for the announced chest save");

            ServerBoundMessage writeReply = owner.request(new WriteChunkMessage(world, "region", chunkX, chunkZ, savedChest));
            assertTrue(((BooleanMessageReply) writeReply).result,
                    "The previous owner must still be authorized to finish its pending save");
            assertEquals("pending-save-waiter", pollUntilOwner(waiter, chunkX, chunkZ, "pending-save-waiter").owner,
                    "Ownership should transfer only after the pending Master write completes");

            byte[] persisted = ((DataMessageReply) pendingRead.get(10, TimeUnit.SECONDS)).data;
            assertArrayEquals(savedChest, persisted, "The new owner must read the exact persisted chest image");
            assertEquals(17, readChestItemCount(persisted), "The new owner must retain every chest item");
        } finally {
            owner.close();
            waiter.close();
        }
    }
}
