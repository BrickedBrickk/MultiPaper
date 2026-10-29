package puregero.multipaper.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadLevelMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadPlayerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ReadUidMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PlayerConnectMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.ForceReadChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.LockChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PingMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.SubscribeChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.UnlockChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WillSaveChunkLaterMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WriteLevelMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WritePlayerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PersistContainerTransactionMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PersistContainerTransactionMessage.Snapshot;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.DataMessageReply;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.SetChunkOwnerMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.ServerBoundMessage;
import se.llbit.nbt.CompoundTag;
import se.llbit.nbt.IntTag;
import se.llbit.nbt.IntArrayTag;
import se.llbit.nbt.ListTag;
import se.llbit.nbt.StringTag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasterProcessRestartPersistenceTest {

    private static final String WORLD = "process-restart-world";
    private static final String PLAYER_UUID = "c51c8d95-c2e8-4e7a-a0b6-03a2f6a44ec9";
    private static final int CHUNK_X = -33;
    private static final int CHUNK_Z = 48;
    private static final byte[] CHUNK_DATA = "persisted-chunk-data".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEVEL_DATA = "persisted-level-data".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLAYER_DATA = "persisted-player-data".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(60)
    void worldAndPlayerDataSurviveGracefulShutdownAndMasterJarRestart() throws Exception {
        int masterPort = findFreePort();
        int proxyPort = findFreePort();
        assertTrue(masterPort != proxyPort, "Master and proxy need distinct ports");
        Path masterDirectory = tempDir.resolve("master");
        masterDirectory.toFile().mkdirs();

        Process firstMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient firstClient = null;
        try {
            awaitListening(firstMaster, masterPort);
            firstClient = TestServerClient.connect(masterPort, "restart-writer");
            writePersistentData(firstClient);
            firstClient.close();
            firstClient = null;

            requestGracefulExit(firstMaster);
            assertTrue(firstMaster.waitFor(15, TimeUnit.SECONDS), "First Master did not stop after exit");
            assertEquals(0, firstMaster.exitValue(), "First Master process should exit successfully");
        } finally {
            closeClient(firstClient);
            stopIfRunning(firstMaster);
        }

        Process secondMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient secondClient = null;
        try {
            awaitListening(secondMaster, masterPort);
            secondClient = TestServerClient.connect(masterPort, "restart-reader");
            assertPersistentData(secondClient, "Restarted Master");

            secondClient.close();
            secondClient = null;
            requestGracefulExit(secondMaster);
            assertTrue(secondMaster.waitFor(15, TimeUnit.SECONDS), "Second Master did not stop after exit");
            assertEquals(0, secondMaster.exitValue(), "Second Master process should exit successfully");
        } finally {
            closeClient(secondClient);
            stopIfRunning(secondMaster);
        }
    }

    @Test
    @Timeout(90)
    void populatedChestChunkSurvivesMasterRestartAndNewOwnerClaim() throws Exception {
        int masterPort = findFreePort();
        int proxyPort = findFreePort();
        assertTrue(masterPort != proxyPort, "Master and proxy need distinct ports");
        Path masterDirectory = tempDir.resolve("chest-owner-restart-master");
        Files.createDirectories(masterDirectory);
        String world = "chest-owner-restart-world";
        int chunkX = -3;
        int chunkZ = 14;
        byte[] chestBeforeRestart = chestChunk(chunkX, chunkZ, 17);
        byte[] chestAfterRestart = chestChunk(chunkX, chunkZ, 29);

        Process firstMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient firstOwner = null;
        try {
            awaitListening(firstMaster, masterPort);
            firstOwner = TestServerClient.connect(masterPort, "chest-restart-owner-a");
            firstOwner.send(new SubscribeChunkMessage(world, chunkX, chunkZ));
            firstOwner.send(new LockChunkMessage(world, chunkX, chunkZ));
            awaitChunkOwner(firstOwner, chunkX, chunkZ, "chest-restart-owner-a");
            assertAcknowledged(firstOwner.request(new WriteChunkMessage(world, "region", chunkX, chunkZ, chestBeforeRestart)), "owned chest chunk");
            assertEquals(17, readChestItemCount(readData(firstOwner.request(new ForceReadChunkMessage(world, "region", chunkX, chunkZ)), "First Master", "chest chunk")));
            firstOwner.close();
            firstOwner = null;

            requestGracefulExit(firstMaster);
            assertTrue(firstMaster.waitFor(15, TimeUnit.SECONDS), "First Master did not stop after exit");
            assertEquals(0, firstMaster.exitValue(), "First Master process should exit successfully");
        } finally {
            closeClient(firstOwner);
            stopIfRunning(firstMaster);
        }

        Process secondMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient nextOwner = null;
        try {
            awaitListening(secondMaster, masterPort);
            nextOwner = TestServerClient.connect(masterPort, "chest-restart-owner-b");
            nextOwner.send(new SubscribeChunkMessage(world, chunkX, chunkZ));
            nextOwner.send(new LockChunkMessage(world, chunkX, chunkZ));
            awaitChunkOwner(nextOwner, chunkX, chunkZ, "chest-restart-owner-b");
            byte[] restored = readData(nextOwner.request(new ForceReadChunkMessage(world, "region", chunkX, chunkZ)), "Restarted Master", "chest chunk");
            assertEquals(17, readChestItemCount(restored), "The exact populated chest stack must survive Master restart");

            assertAcknowledged(nextOwner.request(new WriteChunkMessage(world, "region", chunkX, chunkZ, chestAfterRestart)), "new-owner chest update");
            assertEquals(29, readChestItemCount(readData(nextOwner.request(new ForceReadChunkMessage(world, "region", chunkX, chunkZ)), "Restarted Master", "updated chest chunk")));
            nextOwner.close();
            nextOwner = null;
            requestGracefulExit(secondMaster);
            assertTrue(secondMaster.waitFor(15, TimeUnit.SECONDS), "Restarted Master did not stop after exit");
            assertEquals(0, secondMaster.exitValue(), "Restarted Master process should exit successfully");
        } finally {
            closeClient(nextOwner);
            stopIfRunning(secondMaster);
        }
    }

    @Test
    @Timeout(90)
    void populatedChestChunkSurvivesAbruptMasterTerminationAfterAcknowledgedWrite() throws Exception {
        int masterPort = findFreePort();
        int proxyPort = findFreePort();
        assertTrue(masterPort != proxyPort, "Master and proxy need distinct ports");
        Path masterDirectory = tempDir.resolve("chest-abrupt-restart-master");
        Files.createDirectories(masterDirectory);
        String world = "chest-abrupt-restart-world";
        int chunkX = -19;
        int chunkZ = 27;
        byte[] acknowledgedChest = chestChunk(chunkX, chunkZ, 41);

        Process firstMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient firstOwner = null;
        try {
            awaitListening(firstMaster, masterPort);
            firstOwner = TestServerClient.connect(masterPort, "abrupt-chest-writer");
            firstOwner.send(new SubscribeChunkMessage(world, chunkX, chunkZ));
            firstOwner.send(new LockChunkMessage(world, chunkX, chunkZ));
            awaitChunkOwner(firstOwner, chunkX, chunkZ, "abrupt-chest-writer");
            assertAcknowledged(firstOwner.request(new WriteChunkMessage(world, "region", chunkX, chunkZ, acknowledgedChest)),
                    "acknowledged populated chest chunk");
            assertEquals(41, readChestItemCount(readData(firstOwner.request(
                    new ForceReadChunkMessage(world, "region", chunkX, chunkZ)), "First Master", "pre-crash chest chunk")));

            // The write acknowledgement promises persistence has completed. Kill only
            // this disposable Master process to exercise recovery without its shutdown path.
            firstOwner.close();
            firstOwner = null;
            firstMaster.destroyForcibly();
            assertTrue(firstMaster.waitFor(10, TimeUnit.SECONDS), "First disposable Master did not terminate forcibly");
        } finally {
            closeClient(firstOwner);
            stopIfRunning(firstMaster);
        }

        Process recoveredMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient nextOwner = null;
        try {
            awaitListening(recoveredMaster, masterPort);
            nextOwner = TestServerClient.connect(masterPort, "abrupt-chest-new-owner");
            nextOwner.send(new SubscribeChunkMessage(world, chunkX, chunkZ));
            nextOwner.send(new LockChunkMessage(world, chunkX, chunkZ));
            awaitChunkOwner(nextOwner, chunkX, chunkZ, "abrupt-chest-new-owner");

            byte[] restored = readData(nextOwner.request(new ForceReadChunkMessage(world, "region", chunkX, chunkZ)),
                    "Abruptly recovered Master", "chest chunk");
            assertArrayEquals(acknowledgedChest, restored,
                    "Abrupt Master termination after ACK must preserve the exact populated chest chunk NBT");
            assertEquals(41, readChestItemCount(restored), "Every acknowledged chest item must survive abrupt Master termination");

            nextOwner.close();
            nextOwner = null;
            requestGracefulExit(recoveredMaster);
            assertTrue(recoveredMaster.waitFor(15, TimeUnit.SECONDS), "Recovered Master did not stop cleanly");
            assertEquals(0, recoveredMaster.exitValue(), "Recovered Master should exit successfully");
        } finally {
            closeClient(nextOwner);
            stopIfRunning(recoveredMaster);
        }
    }

    @Test
    @Timeout(120)
    void populatedChestHandoffSurvivesAbruptMasterTerminationAfterAcknowledgedWrite() throws Exception {
        int masterPort = findFreePort();
        int proxyPort = findFreePort();
        assertTrue(masterPort != proxyPort, "Master and proxy need distinct ports");
        Path masterDirectory = tempDir.resolve("chest-handoff-abrupt-restart-master");
        Files.createDirectories(masterDirectory);
        String world = "chest-handoff-abrupt-restart-world";
        int chunkX = -23;
        int chunkZ = 31;
        byte[] initialChest = chestChunk(chunkX, chunkZ, 11);
        byte[] finalChest = chestChunk(chunkX, chunkZ, 41);

        Process firstMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient oldOwner = null;
        TestServerClient waitingOwner = null;
        try {
            awaitListening(firstMaster, masterPort);
            oldOwner = TestServerClient.connect(masterPort, "abrupt-handoff-old-owner");
            oldOwner.send(new SubscribeChunkMessage(world, chunkX, chunkZ));
            oldOwner.send(new LockChunkMessage(world, chunkX, chunkZ));
            awaitChunkOwner(oldOwner, chunkX, chunkZ, "abrupt-handoff-old-owner");

            waitingOwner = TestServerClient.connect(masterPort, "abrupt-handoff-waiting-owner");
            waitingOwner.send(new SubscribeChunkMessage(world, chunkX, chunkZ));
            waitingOwner.send(new LockChunkMessage(world, chunkX, chunkZ));
            awaitChunkOwner(waitingOwner, chunkX, chunkZ, "abrupt-handoff-old-owner");
            assertAcknowledged(oldOwner.request(new WriteChunkMessage(world, "region", chunkX, chunkZ, initialChest)),
                    "initial chest snapshot");

            // Model Paper's final asynchronous save during unload. Ownership must
            // stay with A until this exact new chest snapshot has reached Master.
            oldOwner.send(new WillSaveChunkLaterMessage(world, chunkX, chunkZ));
            oldOwner.request(new PingMessage());
            oldOwner.send(new UnlockChunkMessage(world, chunkX, chunkZ));
            assertAcknowledged(oldOwner.request(new WriteChunkMessage(world, "region", chunkX, chunkZ, finalChest)),
                    "final chest snapshot before ownership transfer");
            awaitChunkOwner(waitingOwner, chunkX, chunkZ, "abrupt-handoff-waiting-owner");

            byte[] handedOff = readData(waitingOwner.request(new ForceReadChunkMessage(world, "region", chunkX, chunkZ)),
                    "First Master", "handed-off chest chunk");
            assertArrayEquals(finalChest, handedOff, "New owner must receive the final pre-unload chest image");
            assertEquals(41, readChestItemCount(handedOff), "No chest items may be lost at the ownership boundary");

            oldOwner.close();
            oldOwner = null;
            waitingOwner.close();
            waitingOwner = null;
            firstMaster.destroyForcibly();
            assertTrue(firstMaster.waitFor(10, TimeUnit.SECONDS), "First disposable Master did not terminate forcibly");
        } finally {
            closeClient(oldOwner);
            closeClient(waitingOwner);
            stopIfRunning(firstMaster);
        }

        Process recoveredMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient recoveredOwner = null;
        try {
            awaitListening(recoveredMaster, masterPort);
            recoveredOwner = TestServerClient.connect(masterPort, "abrupt-handoff-recovered-owner");
            recoveredOwner.send(new SubscribeChunkMessage(world, chunkX, chunkZ));
            recoveredOwner.send(new LockChunkMessage(world, chunkX, chunkZ));
            awaitChunkOwner(recoveredOwner, chunkX, chunkZ, "abrupt-handoff-recovered-owner");

            byte[] restored = readData(recoveredOwner.request(new ForceReadChunkMessage(world, "region", chunkX, chunkZ)),
                    "Abruptly recovered Master", "handed-off chest chunk");
            assertArrayEquals(finalChest, restored,
                    "Exact chest snapshot must survive both owner handoff and abrupt Master termination");
            assertEquals(41, readChestItemCount(restored), "Every handed-off chest item must survive Master recovery");

            recoveredOwner.close();
            recoveredOwner = null;
            requestGracefulExit(recoveredMaster);
            assertTrue(recoveredMaster.waitFor(15, TimeUnit.SECONDS), "Recovered Master did not stop cleanly");
            assertEquals(0, recoveredMaster.exitValue(), "Recovered Master should exit successfully");
        } finally {
            closeClient(recoveredOwner);
            stopIfRunning(recoveredMaster);
        }
    }

    @Test
    @Timeout(90)
    void worldUidSurvivesGracefulAndCrashMasterRestarts() throws Exception {
        int masterPort = findFreePort();
        int proxyPort = findFreePort();
        assertTrue(masterPort != proxyPort, "Master and proxy need distinct ports");
        Path masterDirectory = tempDir.resolve("world-uid-restart-master");
        Files.createDirectories(masterDirectory);
        String world = "world-uid-restart";

        Process firstMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient firstClient = null;
        byte[] initialUid;
        try {
            awaitListening(firstMaster, masterPort);
            firstClient = TestServerClient.connect(masterPort, "uid-first-reader");
            initialUid = readWorldUid(firstClient, world, "First Master");
            firstClient.close();
            firstClient = null;
            requestGracefulExit(firstMaster);
            assertTrue(firstMaster.waitFor(15, TimeUnit.SECONDS), "First Master did not stop after exit");
            assertEquals(0, firstMaster.exitValue(), "First Master process should exit successfully");
        } finally {
            closeClient(firstClient);
            stopIfRunning(firstMaster);
        }

        Process secondMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient secondClient = null;
        try {
            awaitListening(secondMaster, masterPort);
            secondClient = TestServerClient.connect(masterPort, "uid-graceful-restart-reader");
            assertArrayEquals(initialUid, readWorldUid(secondClient, world, "Gracefully restarted Master"));
            secondClient.close();
            secondClient = null;
            secondMaster.destroyForcibly();
            assertTrue(secondMaster.waitFor(10, TimeUnit.SECONDS), "Master process did not terminate forcibly");
        } finally {
            closeClient(secondClient);
            stopIfRunning(secondMaster);
        }

        Process recoveredMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient recoveredClient = null;
        try {
            awaitListening(recoveredMaster, masterPort);
            recoveredClient = TestServerClient.connect(masterPort, "uid-crash-recovery-reader");
            assertArrayEquals(initialUid, readWorldUid(recoveredClient, world, "Crash-recovered Master"));
            recoveredClient.close();
            recoveredClient = null;
            requestGracefulExit(recoveredMaster);
            assertTrue(recoveredMaster.waitFor(15, TimeUnit.SECONDS), "Recovered Master did not stop after exit");
            assertEquals(0, recoveredMaster.exitValue(), "Recovered Master process should exit successfully");
        } finally {
            closeClient(recoveredClient);
            stopIfRunning(recoveredMaster);
        }
    }

    @Test
    @Timeout(60)
    void worldAndPlayerDataSurviveUnexpectedMasterTerminationAfterWriteAcknowledgement() throws Exception {
        int masterPort = findFreePort();
        int proxyPort = findFreePort();
        assertTrue(masterPort != proxyPort, "Master and proxy need distinct ports");
        Path masterDirectory = tempDir.resolve("crashed-master");
        masterDirectory.toFile().mkdirs();

        Process firstMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient firstClient = null;
        try {
            awaitListening(firstMaster, masterPort);
            firstClient = TestServerClient.connect(masterPort, "crash-writer");
            writePersistentData(firstClient);
            firstClient.close();
            firstClient = null;

            firstMaster.destroyForcibly();
            assertTrue(firstMaster.waitFor(10, TimeUnit.SECONDS), "Master process did not terminate forcibly");
        } finally {
            closeClient(firstClient);
            stopIfRunning(firstMaster);
        }

        Process recoveredMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient reader = null;
        try {
            awaitListening(recoveredMaster, masterPort);
            reader = TestServerClient.connect(masterPort, "crash-reader");
            assertPersistentData(reader, "Recovered Master");

            reader.close();
            reader = null;
            requestGracefulExit(recoveredMaster);
            assertTrue(recoveredMaster.waitFor(15, TimeUnit.SECONDS), "Recovered Master did not stop after exit");
            assertEquals(0, recoveredMaster.exitValue(), "Recovered Master process should exit successfully");
        } finally {
            closeClient(reader);
            stopIfRunning(recoveredMaster);
        }
    }

    @Test
    @Timeout(60)
    void pendingContainerTransactionSurvivesAbruptMasterRestart() throws Exception {
        int masterPort = findFreePort();
        int proxyPort = findFreePort();
        assertTrue(masterPort != proxyPort, "Master and proxy need distinct ports");
        String world = "pending-container-transaction-world";
        Path masterDirectory = tempDir.resolve("pending-container-master");
        Files.createDirectories(masterDirectory);
        Path transactionDirectory = masterDirectory.resolve(world).resolve("multipaper-container-transactions");
        byte[] oldSource = inventoryChunk(0, blockEntity(1, "source-before"));
        byte[] oldDestination = inventoryChunk(1, blockEntity(16, "destination-before"));
        byte[] newSource = writeSnapshot(blockEntity(1, "source-after"));
        byte[] newDestination = writeSnapshot(blockEntity(16, "destination-after"));
        byte[] finalSource = writeSnapshot(blockEntity(1, "source-after-recovery"));
        byte[] finalDestination = writeSnapshot(blockEntity(16, "destination-after-recovery"));

        Process firstMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient writer = null;
        try {
            awaitListening(firstMaster, masterPort);
            writer = TestServerClient.connect(masterPort, "container-transaction-writer");
            assertAcknowledged(writer.request(new WriteChunkMessage(world, "region", 0, 0, oldSource)), "source chunk");
            assertAcknowledged(writer.request(new WriteChunkMessage(world, "region", 1, 0, oldDestination)), "destination chunk");
            ServerBoundMessage commitReply = writer.request(new PersistContainerTransactionMessage(world, UUID.randomUUID().toString(), new Snapshot[]{
                    new Snapshot(0, 0, writeSnapshot(blockEntity(1, "source-before")), newSource),
                    new Snapshot(1, 0, writeSnapshot(blockEntity(16, "destination-before")), newDestination)
            }));
            assertAcknowledged(commitReply, "container transaction");
            assertEquals(1, transactionFileCount(transactionDirectory), "The acknowledged transaction must be on disk before the Master is killed");

            firstMaster.destroyForcibly();
            assertTrue(firstMaster.waitFor(10, TimeUnit.SECONDS), "Master process did not terminate forcibly");
        } finally {
            closeClient(writer);
            stopIfRunning(firstMaster);
        }

        Process recoveredMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient recoveryClient = null;
        try {
            awaitListening(recoveredMaster, masterPort);
            recoveryClient = TestServerClient.connect(masterPort, "container-transaction-recovery");
            byte[] recoveredSource = readData(recoveryClient.request(new ReadChunkMessage(world, "region", 0, 0)), "Recovered Master", "source chunk");
            byte[] recoveredDestination = readData(recoveryClient.request(new ReadChunkMessage(world, "region", 1, 0)), "Recovered Master", "destination chunk");
            assertEquals("source-after", readBlockEntityState(recoveredSource));
            assertEquals("destination-after", readBlockEntityState(recoveredDestination));
            assertEquals(1, transactionFileCount(transactionDirectory), "Reads must not discard the pending transaction before chunk writes are durable");

            assertAcknowledged(recoveryClient.request(new WriteChunkMessage(world, "region", 0, 0, recoveredSource)), "recovered source chunk");
            assertEquals(1, transactionFileCount(transactionDirectory), "The transaction must remain until the destination is durable too");
            assertAcknowledged(recoveryClient.request(new WriteChunkMessage(world, "region", 1, 0, recoveredDestination)), "recovered destination chunk");
            assertEquals(0, transactionFileCount(transactionDirectory), "The transaction should be removed after both chunks are durable");

            ServerBoundMessage secondCommitReply = recoveryClient.request(new PersistContainerTransactionMessage(world, UUID.randomUUID().toString(), new Snapshot[]{
                    new Snapshot(0, 0, writeSnapshot(blockEntity(1, "source-after")), finalSource),
                    new Snapshot(1, 0, writeSnapshot(blockEntity(16, "destination-after")), finalDestination)
            }));
            assertAcknowledged(secondCommitReply, "post-recovery container transaction");
            assertEquals(1, transactionFileCount(transactionDirectory), "The second edit must be durable before the recovered Master is killed");

            recoveredMaster.destroyForcibly();
            assertTrue(recoveredMaster.waitFor(10, TimeUnit.SECONDS), "Recovered Master process did not terminate forcibly");
        } finally {
            closeClient(recoveryClient);
            stopIfRunning(recoveredMaster);
        }

        Process finalMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient finalReader = null;
        try {
            awaitListening(finalMaster, masterPort);
            finalReader = TestServerClient.connect(masterPort, "container-transaction-final-reader");
            byte[] finalRecoveredSource = readData(finalReader.request(new ReadChunkMessage(world, "region", 0, 0)), "Restarted Master", "source chunk");
            byte[] finalRecoveredDestination = readData(finalReader.request(new ReadChunkMessage(world, "region", 1, 0)), "Restarted Master", "destination chunk");
            assertEquals("source-after-recovery", readBlockEntityState(finalRecoveredSource));
            assertEquals("destination-after-recovery", readBlockEntityState(finalRecoveredDestination));
            assertEquals(1, transactionFileCount(transactionDirectory), "The second transaction must remain until its chunk snapshots are durable");
            assertAcknowledged(finalReader.request(new WriteChunkMessage(world, "region", 0, 0, finalRecoveredSource)), "post-recovery source chunk");
            assertAcknowledged(finalReader.request(new WriteChunkMessage(world, "region", 1, 0, finalRecoveredDestination)), "post-recovery destination chunk");
            assertEquals(0, transactionFileCount(transactionDirectory));

            finalReader.close();
            finalReader = null;
            requestGracefulExit(finalMaster);
            assertTrue(finalMaster.waitFor(15, TimeUnit.SECONDS), "Final Master did not stop cleanly");

            Process lastMaster = startMaster(masterDirectory, masterPort, proxyPort);
            TestServerClient lastReader = null;
            try {
                awaitListening(lastMaster, masterPort);
                lastReader = TestServerClient.connect(masterPort, "container-transaction-last-reader");
                assertEquals("source-after-recovery", readBlockEntityState(readData(lastReader.request(new ReadChunkMessage(world, "region", 0, 0)), "Last Master", "source chunk")));
                assertEquals("destination-after-recovery", readBlockEntityState(readData(lastReader.request(new ReadChunkMessage(world, "region", 1, 0)), "Last Master", "destination chunk")));
                assertEquals(0, transactionFileCount(transactionDirectory));
            } finally {
                closeClient(lastReader);
                stopIfRunning(lastMaster);
            }
        } finally {
            closeClient(finalReader);
            stopIfRunning(finalMaster);
        }
    }

    @Test
    @Timeout(60)
    void pendingEntityInventoryTransactionSurvivesAbruptMasterRestart() throws Exception {
        int masterPort = findFreePort();
        int proxyPort = findFreePort();
        assertTrue(masterPort != proxyPort, "Master and proxy need distinct ports");
        String world = "pending-entity-inventory-transaction-world";
        Path masterDirectory = tempDir.resolve("pending-entity-inventory-master");
        Files.createDirectories(masterDirectory);
        Path transactionDirectory = masterDirectory.resolve(world).resolve("multipaper-container-transactions");
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        byte[] oldSource = entityChunk(entity(sourceId, 8));
        byte[] oldDestination = entityChunk(entity(destinationId, 0));
        byte[] sourceBefore = writeSnapshot(entity(sourceId, 8));
        byte[] sourceAfter = writeSnapshot(entity(sourceId, 0));
        byte[] destinationBefore = writeSnapshot(entity(destinationId, 0));
        byte[] destinationAfter = writeSnapshot(entity(destinationId, 8));

        Process firstMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient writer = null;
        try {
            awaitListening(firstMaster, masterPort);
            writer = TestServerClient.connect(masterPort, "entity-transaction-writer");
            assertAcknowledged(writer.request(new WriteChunkMessage(world, "entities", 0, 0, oldSource)), "source entity chunk");
            assertAcknowledged(writer.request(new WriteChunkMessage(world, "entities", 1, 0, oldDestination)), "destination entity chunk");
            ServerBoundMessage commitReply = writer.request(new PersistContainerTransactionMessage(world, UUID.randomUUID().toString(), new Snapshot[]{
                    new Snapshot("entities", 0, 0, sourceId.toString(), sourceBefore, sourceAfter),
                    new Snapshot("entities", 1, 0, destinationId.toString(), destinationBefore, destinationAfter)
            }));
            assertAcknowledged(commitReply, "entity inventory transaction");
            assertEquals(1, transactionFileCount(transactionDirectory), "The acknowledged transaction must be durable before the Master is killed");

            firstMaster.destroyForcibly();
            assertTrue(firstMaster.waitFor(10, TimeUnit.SECONDS), "Master process did not terminate forcibly");
        } finally {
            closeClient(writer);
            stopIfRunning(firstMaster);
        }

        Process recoveredMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient recoveryClient = null;
        try {
            awaitListening(recoveredMaster, masterPort);
            recoveryClient = TestServerClient.connect(masterPort, "entity-transaction-recovery");
            byte[] recoveredSource = readData(recoveryClient.request(new ReadChunkMessage(world, "entities", 0, 0)), "Recovered Master", "source entity chunk");
            byte[] recoveredDestination = readData(recoveryClient.request(new ReadChunkMessage(world, "entities", 1, 0)), "Recovered Master", "destination entity chunk");
            assertEquals(0, readEntityItemCount(recoveredSource));
            assertEquals(8, readEntityItemCount(recoveredDestination));
            assertEquals(1, transactionFileCount(transactionDirectory), "Reads must retain the WAL until both entity chunks are durable");

            assertAcknowledged(recoveryClient.request(new WriteChunkMessage(world, "entities", 0, 0, recoveredSource)), "recovered source entity chunk");
            assertEquals(1, transactionFileCount(transactionDirectory), "The transaction must remain until the destination entity chunk is durable too");
            assertAcknowledged(recoveryClient.request(new WriteChunkMessage(world, "entities", 1, 0, recoveredDestination)), "recovered destination entity chunk");
            assertEquals(0, transactionFileCount(transactionDirectory), "The transaction should be removed after both entity chunks are durable");

            recoveryClient.close();
            recoveryClient = null;
            requestGracefulExit(recoveredMaster);
            assertTrue(recoveredMaster.waitFor(15, TimeUnit.SECONDS), "Recovered Master did not stop cleanly");
        } finally {
            closeClient(recoveryClient);
            stopIfRunning(recoveredMaster);
        }

        Process finalMaster = startMaster(masterDirectory, masterPort, proxyPort);
        TestServerClient finalReader = null;
        try {
            awaitListening(finalMaster, masterPort);
            finalReader = TestServerClient.connect(masterPort, "entity-transaction-final-reader");
            assertEquals(0, readEntityItemCount(readData(finalReader.request(new ReadChunkMessage(world, "entities", 0, 0)), "Final Master", "source entity chunk")));
            assertEquals(8, readEntityItemCount(readData(finalReader.request(new ReadChunkMessage(world, "entities", 1, 0)), "Final Master", "destination entity chunk")));
            finalReader.close();
            finalReader = null;
            requestGracefulExit(finalMaster);
            assertTrue(finalMaster.waitFor(15, TimeUnit.SECONDS), "Final Master did not stop cleanly");
        } finally {
            closeClient(finalReader);
            stopIfRunning(finalMaster);
        }
    }

    private static void writePersistentData(TestServerClient client) throws Exception {
        assertAcknowledged(client.request(new WriteChunkMessage(WORLD, "region", CHUNK_X, CHUNK_Z, CHUNK_DATA)), "chunk data");
        assertAcknowledged(client.request(new WriteLevelMessage(WORLD, LEVEL_DATA)), "level data");
        assertAcknowledged(client.request(new PlayerConnectMessage(UUID.fromString(PLAYER_UUID))), "player ownership");
        assertAcknowledged(client.request(new WritePlayerMessage(WORLD, PLAYER_UUID, PLAYER_DATA)), "player data");
    }

    private static byte[] readWorldUid(TestServerClient client, String world, String masterDescription) throws Exception {
        ServerBoundMessage reply = client.request(new ReadUidMessage(world));
        assertTrue(reply instanceof DataMessageReply, masterDescription + " should return the world UUID");
        byte[] uid = ((DataMessageReply) reply).data;
        assertEquals(16, uid.length, masterDescription + " should return a complete UUID");
        return uid;
    }

    private static void assertPersistentData(TestServerClient client, String masterDescription) throws Exception {
        assertArrayEquals(CHUNK_DATA, readData(client.request(new ReadChunkMessage(WORLD, "region", CHUNK_X, CHUNK_Z)), masterDescription, "chunk data"));
        assertArrayEquals(LEVEL_DATA, readData(client.request(new ReadLevelMessage(WORLD)), masterDescription, "level data"));
        assertArrayEquals(PLAYER_DATA, readData(client.request(new ReadPlayerMessage(WORLD, PLAYER_UUID)), masterDescription, "player data"));
    }

    private static void assertAcknowledged(ServerBoundMessage reply, String dataType) {
        assertTrue(reply instanceof BooleanMessageReply && ((BooleanMessageReply) reply).result,
                "Master must acknowledge the " + dataType + " write before termination");
    }

    private static byte[] readData(ServerBoundMessage reply, String masterDescription, String dataType) {
        assertTrue(reply instanceof DataMessageReply, masterDescription + " should return stored " + dataType);
        return ((DataMessageReply) reply).data;
    }

    private static CompoundTag blockEntity(int x, String state) {
        CompoundTag tag = new CompoundTag();
        tag.add("id", new StringTag("minecraft:chest"));
        tag.add("x", new IntTag(x));
        tag.add("y", new IntTag(64));
        tag.add("z", new IntTag(1));
        tag.add("state", new StringTag(state));
        return tag;
    }

    private static byte[] writeSnapshot(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            tag.write(output);
        }
        return bytes.toByteArray();
    }

    private static CompoundTag entity(UUID uuid, int itemCount) {
        CompoundTag entity = new CompoundTag();
        entity.add("UUID", new IntArrayTag(new int[]{
                (int) (uuid.getMostSignificantBits() >>> 32), (int) uuid.getMostSignificantBits(),
                (int) (uuid.getLeastSignificantBits() >>> 32), (int) uuid.getLeastSignificantBits()
        }));
        if (itemCount > 0) {
            CompoundTag item = new CompoundTag();
            item.add("id", new StringTag("minecraft:diamond"));
            item.add("count", new IntTag(itemCount));
            ListTag items = new ListTag(10, new ArrayList<>());
            items.add(item);
            entity.add("Items", items);
        }
        return entity;
    }

    private static byte[] entityChunk(CompoundTag entity) throws IOException {
        CompoundTag root = new CompoundTag();
        root.add("DataVersion", new IntTag(5023));
        root.add("Position", new IntArrayTag(new int[]{0, 0}));
        ListTag entities = new ListTag(10, new ArrayList<>());
        entities.add(entity);
        root.add("Entities", entities);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(compressed))) {
            output.writeByte(10);
            output.writeUTF("");
            root.write(output);
        }
        return compressed.toByteArray();
    }

    private static int readEntityItemCount(byte[] compressedChunk) throws IOException {
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressedChunk)))) {
            if (input.readUnsignedByte() != 10) throw new IOException("Entity chunk root is not a compound");
            input.readUTF();
            CompoundTag root = CompoundTag.read(input).asCompound();
            ListTag entities = root.get("Entities").asList();
            if (entities == null || entities.isEmpty()) return 0;
            ListTag items = entities.get(0).asCompound().get("Items").asList();
            return items == null || items.isEmpty() ? 0 : items.get(0).asCompound().get("count").intValue();
        }
    }

    private static byte[] inventoryChunk(int chunkX, CompoundTag blockEntity) throws IOException {
        CompoundTag root = new CompoundTag();
        root.add("DataVersion", new IntTag(5023));
        root.add("xPos", new IntTag(chunkX));
        root.add("zPos", new IntTag(0));
        ListTag blockEntities = new ListTag(10, new ArrayList<>());
        blockEntities.add(blockEntity);
        root.add("block_entities", blockEntities);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(compressed))) {
            output.writeByte(10);
            output.writeUTF("");
            root.write(output);
        }
        return compressed.toByteArray();
    }

    private static byte[] chestChunk(int chunkX, int chunkZ, int count) throws IOException {
        CompoundTag chest = blockEntity(chunkX * 16, "chest-count-" + count);
        chest.add("z", new IntTag(chunkZ * 16));
        CompoundTag item = new CompoundTag();
        item.add("id", new StringTag("minecraft:diamond"));
        item.add("count", new IntTag(count));
        item.add("Slot", new IntTag(0));
        ListTag items = new ListTag(10, new ArrayList<>());
        items.add(item);
        chest.add("Items", items);
        return inventoryChunk(chunkX, chest);
    }

    private static int readChestItemCount(byte[] compressedChunk) throws IOException {
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressedChunk)))) {
            if (input.readUnsignedByte() != 10) throw new IOException("Chunk root is not a compound");
            input.readUTF();
            CompoundTag root = CompoundTag.read(input).asCompound();
            ListTag blockEntities = root.get("block_entities").asList();
            if (blockEntities == null || blockEntities.isEmpty()) throw new IOException("Chunk has no chest block entity");
            ListTag items = blockEntities.get(0).asCompound().get("Items").asList();
            if (items == null || items.isEmpty()) return 0;
            return items.get(0).asCompound().get("count").intValue();
        }
    }

    private static void awaitChunkOwner(TestServerClient client, int chunkX, int chunkZ, String expectedOwner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            SetChunkOwnerMessage update = client.chunkOwnerUpdates.poll(100, TimeUnit.MILLISECONDS);
            if (update != null && update.cx == chunkX && update.cz == chunkZ && expectedOwner.equals(update.owner)) {
                return;
            }
        }
        throw new AssertionError("Timed out waiting for chunk " + chunkX + "," + chunkZ + " owner " + expectedOwner);
    }

    private static String readBlockEntityState(byte[] compressedChunk) throws IOException {
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressedChunk)))) {
            if (input.readUnsignedByte() != 10) throw new IOException("Chunk root is not a compound");
            input.readUTF();
            CompoundTag root = CompoundTag.read(input).asCompound();
            return root.get("block_entities").asList().get(0).asCompound().get("state").stringValue();
        }
    }

    private static long transactionFileCount(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".txn")).count();
        }
    }

    private static Process startMaster(Path workingDirectory, int masterPort, int proxyPort) throws IOException {
        Path jar = Path.of(System.getProperty("user.dir"), "..", "libs", "multipaper-master-3.0.0.jar").normalize().toAbsolutePath();
        assertTrue(jar.toFile().isFile(), "Master JAR not found: " + jar);
        String javaExecutable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", javaExecutable).toString();
        return new ProcessBuilder(java, "-Dlogging.enabled=false", "-jar", jar.toString(), "127.0.0.1:" + masterPort, Integer.toString(proxyPort))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static void awaitListening(Process process, int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("Master process exited before listening with code " + process.exitValue());
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException ignored) {
                Thread.sleep(50);
            }
        }
        throw new IOException("Timed out waiting for Master port " + port);
    }

    private static void requestGracefulExit(Process process) throws IOException {
        process.getOutputStream().write("exit\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
    }

    private static void closeClient(TestServerClient client) throws InterruptedException {
        if (client != null) {
            client.close();
        }
    }

    private static void stopIfRunning(Process process) throws Exception {
        if (process.isAlive()) {
            requestGracefulExit(process);
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Could not terminate the disposable Master process");
            }
        }
    }
}
