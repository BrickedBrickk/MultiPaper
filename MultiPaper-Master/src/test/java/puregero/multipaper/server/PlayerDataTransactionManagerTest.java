package puregero.multipaper.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataTransactionManagerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void interruptedBatchRollsForwardBeforeAnyPlayerRead() throws Exception {
        String world = tempDirectory.resolve("interrupted-world").toString();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        byte[] aliceAfter = {1, 2, 3};
        byte[] bobAfter = {4, 5, 6};

        assertThrows(IOException.class, () -> PlayerDataTransactionManager.commit(world, transactionId,
                Map.of(alice, aliceAfter, bob, bobAfter), ignored -> { throw new IOException("simulated Master crash cut"); }));

        Path playerData = Path.of(world, "playerdata");
        try (Stream<Path> files = Files.list(playerData)) {
            assertEquals(1, files.count(), "Only one participant should have been applied at the injected crash cut");
        }
        assertArrayEquals(aliceAfter, PlayerDataTransactionManager.readPlayer(world, alice));
        assertArrayEquals(bobAfter, PlayerDataTransactionManager.readPlayer(world, bob));
        assertEquals(0, pendingCount(world), "A completed recovery removes its pending journal");
    }

    @Test
    void everyParticipantWriteCrashCutRecoversTheWholeBatch() throws Exception {
        // A crash can occur after any individual player file has been replaced,
        // including after the final replacement but before the commit marker.
        for (int participantCount = 2; participantCount <= 6; participantCount++) {
            for (int crashAfterWrite = 1; crashAfterWrite <= participantCount; crashAfterWrite++) {
                String world = tempDirectory.resolve("cut-" + participantCount + "-" + crashAfterWrite).toString();
                UUID transactionId = UUID.randomUUID();
                Map<UUID, byte[]> snapshots = new java.util.HashMap<>();
                for (int participant = 0; participant < participantCount; participant++) {
                    UUID playerId = UUID.nameUUIDFromBytes((participantCount + ":" + participant).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    snapshots.put(playerId, new byte[]{(byte) participant, (byte) (participant + 1)});
                }

                int[] writes = {0};
                int cut = crashAfterWrite;
                assertThrows(IOException.class, () -> PlayerDataTransactionManager.commit(world, transactionId, snapshots,
                        ignored -> {
                            if (++writes[0] == cut) throw new IOException("simulated crash after playerdata replacement");
                        }), "expected simulated crash after participant write " + cut);

                // The first normal read performs recovery before exposing any
                // participant file. Every subsequent read must observe the same
                // transaction, never a mixed pre/post batch.
                for (Map.Entry<UUID, byte[]> entry : snapshots.entrySet()) {
                    assertArrayEquals(entry.getValue(), PlayerDataTransactionManager.readPlayer(world, entry.getKey()),
                            "mixed or missing playerdata after " + participantCount + " participants, cut " + cut);
                }
                assertEquals(0, pendingCount(world));
            }
        }
    }

    @Test
    void concurrentOverlappingCommitsNeverExposeAMixedPlayerPair() throws Exception {
        String world = tempDirectory.resolve("concurrent-world").toString();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        Random random = new Random(0x52433554584eL);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            Future<?>[] commits = new Future<?>[128];
            for (int transaction = 0; transaction < commits.length; transaction++) {
                final int value = transaction + 1;
                UUID transactionId = new UUID(random.nextLong(), random.nextLong());
                byte[] firstSnapshot = {(byte) value, 1};
                byte[] secondSnapshot = {(byte) value, 2};
                Map<UUID, byte[]> snapshots = random.nextBoolean()
                        ? Map.of(firstPlayer, firstSnapshot, secondPlayer, secondSnapshot)
                        : Map.of(secondPlayer, secondSnapshot, firstPlayer, firstSnapshot);
                commits[transaction] = workers.submit(() -> {
                    try {
                        PlayerDataTransactionManager.commit(world, transactionId, snapshots);
                    } catch (IOException failure) {
                        throw new RuntimeException(failure);
                    }
                });
            }
            for (Future<?> commit : commits) commit.get();

            byte[] first = PlayerDataTransactionManager.readPlayer(world, firstPlayer);
            byte[] second = PlayerDataTransactionManager.readPlayer(world, secondPlayer);
            assertEquals(first[0], second[0], "overlapping commits must not leave a mixed pair of after-images");
            assertEquals(1, first[1]);
            assertEquals(2, second[1]);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void duplicateCommitIsIdempotentAndCannotRestoreOlderSnapshots() throws Exception {
        String world = tempDirectory.resolve("idempotent-world").toString();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        Map<UUID, byte[]> transaction = Map.of(alice, new byte[]{10}, bob, new byte[]{20});

        PlayerDataTransactionManager.commit(world, transactionId, transaction);
        PlayerDataTransactionManager.writePlayer(world, alice, new byte[]{11});
        PlayerDataTransactionManager.writePlayer(world, bob, new byte[]{21});
        PlayerDataTransactionManager.commit(world, transactionId, transaction);

        assertArrayEquals(new byte[]{11}, PlayerDataTransactionManager.readPlayer(world, alice));
        assertArrayEquals(new byte[]{21}, PlayerDataTransactionManager.readPlayer(world, bob));
    }

    @Test
    void rejectsReusingTransactionIdForDifferentSnapshots() throws Exception {
        String world = tempDirectory.resolve("reused-id-world").toString();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        PlayerDataTransactionManager.commit(world, transactionId, Map.of(alice, new byte[]{1}, bob, new byte[]{2}));
        assertThrows(IOException.class, () -> PlayerDataTransactionManager.commit(world, transactionId,
                Map.of(alice, new byte[]{3}, bob, new byte[]{4})));
        assertArrayEquals(new byte[]{1}, PlayerDataTransactionManager.readPlayer(world, alice));
        assertArrayEquals(new byte[]{2}, PlayerDataTransactionManager.readPlayer(world, bob));
    }

    @Test
    void requiresAtLeastTwoPlayersToAvoidTreatingASingleFileWriteAsATransaction() {
        String world = tempDirectory.resolve("single-player-world").toString();
        assertThrows(IOException.class, () -> PlayerDataTransactionManager.commit(world, UUID.randomUUID(),
                Map.of(UUID.randomUUID(), new byte[]{1})));
    }

    private static long pendingCount(String world) throws IOException {
        Path directory = Path.of(world, "multipaper-playerdata-transactions");
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".txn")).count();
        }
    }
}
