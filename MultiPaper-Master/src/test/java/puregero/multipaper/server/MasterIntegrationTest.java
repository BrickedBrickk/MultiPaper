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
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PingMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PlayerConnectMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PlayerDisconnectMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ForceReadChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadLevelMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadDataMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadPlayerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RequestEntityIdBlock;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RequestChunkOwnershipMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.StartMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.SetPortMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.SubscribeChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.UnlockChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.UnsubscribeChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WillSaveChunkLaterMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WillSaveEntitiesLaterMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteLevelMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteDataMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WritePlayerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ChunkLoadedOnAnotherServerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.IntegerPairMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerBoundMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerStartedMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.SetChunkOwnerMessage;
import puregero.multipaper.server.util.RegionFileCache;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        ServerStartedMessage existingA = serverB.serverStartedMessages.poll(10, TimeUnit.SECONDS);
        assertEquals("127.0.0.1", existingA.host);
        assertEquals(25565, existingA.port);
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

        // After server-a read the entities chunk it is subscribed, so server-b is redirected.
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

        // PlayerDisconnect has no reply, so poll the stored player state by retrying connect.
        serverA.send(new PlayerDisconnectMessage(player));
        boolean reconnected = false;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ServerBoundMessage retry = serverB.request(new PlayerConnectMessage(player));
            if (((BooleanMessageReply) retry).result) {
                reconnected = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(reconnected, "Player must be connectable after disconnecting");
    }

    @Test
    @Order(15)
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
    @Order(16)
    void chunkDataRemainsReadableAfterOwnerCrash() throws Exception {
        // server-a crashed in the previous test; its stored data must still be available.
        ServerBoundMessage readReply = serverB.request(new ReadChunkMessage(WORLD, "region", 5, 0));
        if (readReply instanceof ChunkLoadedOnAnotherServerMessage redirect) {
            fail("Chunk should not be owned by anyone, but was attributed to " + redirect.server);
        }
        assertArrayEquals(CHUNK_PAYLOAD, ((DataMessageReply) readReply).data);
    }

    @Test
    @Order(17)
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
    @Order(18)
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
    @Order(19)
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
    @Order(20)
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
    @Order(21)
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
    @Order(22)
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
    @Order(23)
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
    @Order(24)
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
    @Order(25)
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
    @Order(26)
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
}
