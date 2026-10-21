package puregero.multipaper.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PersistContainerTransactionMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PersistContainerTransactionMessage.Snapshot;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerTransactionManagerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void recoversInventoryEntitySnapshotsFromSeparateEntityChunkFiles() throws Exception {
        String world = tempDirectory.resolve("entity-inventory-world").toString();
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        byte[] oldSource = entityChunk(entity(sourceId, 8));
        byte[] oldDestination = entityChunk(entity(destinationId, 0));
        ContainerTransactionManager.begin(new PersistContainerTransactionMessage(world, UUID.randomUUID().toString(), new Snapshot[]{
                new Snapshot("entities", 0, 0, sourceId.toString(), writeTag(entity(sourceId, 8)), writeTag(entity(sourceId, 0))),
                new Snapshot("entities", 1, 0, destinationId.toString(), writeTag(entity(destinationId, 0)), writeTag(entity(destinationId, 8)))
        }));

        assertEquals(0, readEntityItemCount(ContainerTransactionManager.applyForRead(world, "entities", 0, 0, oldSource)));
        assertEquals(8, readEntityItemCount(ContainerTransactionManager.applyForRead(world, "entities", 1, 0, oldDestination)));
        byte[][] persistedSource = new byte[1][];
        ContainerTransactionManager.persistChunk(world, "entities", 0, 0, oldSource, data -> persistedSource[0] = data);
        ContainerTransactionManager.persistChunk(world, "entities", 1, 0, oldDestination, ignored -> { });
        assertEquals(0, readEntityItemCount(persistedSource[0]));
        assertEquals(0, transactionDirectory(world).toFile().listFiles((dir, name) -> name.endsWith(".txn")).length);
    }

    @Test
    void replaysPostTransferBlockEntitiesUntilBothChunksArePersisted() throws Exception {
        String world = tempDirectory.resolve("world").toString();
        byte[] oldSource = chunk(0, 0, blockEntity(1, 64, 1, "source-before"));
        byte[] oldDestination = chunk(1, 0, blockEntity(16, 64, 1, "destination-before"));
        PersistContainerTransactionMessage transaction = transaction(world,
                blockEntity(1, 64, 1, "source-before"), blockEntity(1, 64, 1, "source-after"), 0, 0,
                blockEntity(16, 64, 1, "destination-before"), blockEntity(16, 64, 1, "destination-after"), 1, 0);

        ContainerTransactionManager.begin(transaction);

        assertEquals("source-after", readState(ContainerTransactionManager.applyForRead(world, 0, 0, oldSource)));
        assertEquals("destination-after", readState(ContainerTransactionManager.applyForRead(world, 1, 0, oldDestination)));

        byte[][] persistedSource = new byte[1][];
        ContainerTransactionManager.persistChunk(world, 0, 0, oldSource, data -> persistedSource[0] = data);
        assertEquals("source-after", readState(persistedSource[0]));
        assertTrue(ContainerTransactionManager.applyForRead(world, 1, 0, oldDestination).length > 0,
                "the destination snapshot must remain pending after only the source chunk is written");
        assertEquals(1, transactionDirectory(world).toFile().listFiles((dir, name) -> name.endsWith(".txn")).length);

        byte[][] persistedDestination = new byte[1][];
        ContainerTransactionManager.persistChunk(world, 1, 0, oldDestination, data -> persistedDestination[0] = data);
        assertEquals("destination-after", readState(persistedDestination[0]));
        assertFalse(transactionDirectory(world).toFile().listFiles((dir, name) -> name.endsWith(".txn")).length > 0);
    }

    @Test
    void appliesTransactionsInCommitOrderAndPersistsTheNewestSnapshot() throws Exception {
        String world = tempDirectory.resolve("ordered-world").toString();
        byte[] oldSource = chunk(0, 0, blockEntity(1, 64, 1, "before"));
        byte[] oldDestination = chunk(1, 0, blockEntity(16, 64, 1, "before"));

        ContainerTransactionManager.begin(transaction(world,
                blockEntity(1, 64, 1, "before"), blockEntity(1, 64, 1, "first-source"), 0, 0,
                blockEntity(16, 64, 1, "before"), blockEntity(16, 64, 1, "first-destination"), 1, 0));
        ContainerTransactionManager.begin(transaction(world,
                blockEntity(1, 64, 1, "first-source"), blockEntity(1, 64, 1, "second-source"), 0, 0,
                blockEntity(16, 64, 1, "first-destination"), blockEntity(16, 64, 1, "second-destination"), 1, 0));

        assertEquals("second-source", readState(ContainerTransactionManager.applyForRead(world, 0, 0, oldSource)));
        assertEquals("second-destination", readState(ContainerTransactionManager.applyForRead(world, 1, 0, oldDestination)));
    }

    @Test
    void reloadsPendingTransactionsAndPerChunkProgressFromDisk() throws Exception {
        String world = tempDirectory.resolve("restart-world").toString();
        byte[] oldSource = chunk(0, 0, blockEntity(1, 64, 1, "source-before"));
        byte[] oldDestination = chunk(1, 0, blockEntity(16, 64, 1, "destination-before"));
        ContainerTransactionManager.begin(transaction(world,
                blockEntity(1, 64, 1, "source-before"), blockEntity(1, 64, 1, "source-after"), 0, 0,
                blockEntity(16, 64, 1, "destination-before"), blockEntity(16, 64, 1, "destination-after"), 1, 0));

        byte[][] persistedSource = new byte[1][];
        ContainerTransactionManager.persistChunk(world, 0, 0, oldSource, data -> persistedSource[0] = data);
        ContainerTransactionManager.clearInMemoryStateForTests();

        assertEquals("source-after", readState(ContainerTransactionManager.applyForRead(world, 0, 0, persistedSource[0])));
        assertEquals("destination-after", readState(ContainerTransactionManager.applyForRead(world, 1, 0, oldDestination)));

        ContainerTransactionManager.persistChunk(world, 1, 0, oldDestination, ignored -> { });
        assertEquals(0, transactionDirectory(world).toFile().listFiles((dir, name) -> name.endsWith(".txn")).length);
    }

    @Test
    void restoresCommittedTransactionWhenProgressReplacementWasInterrupted() throws Exception {
        String world = tempDirectory.resolve("interrupted-progress-world").toString();
        byte[] oldSource = chunk(0, 0, blockEntity(1, 64, 1, "source-before"));
        byte[] oldDestination = chunk(1, 0, blockEntity(16, 64, 1, "destination-before"));
        ContainerTransactionManager.begin(transaction(world,
                blockEntity(1, 64, 1, "source-before"), blockEntity(1, 64, 1, "source-after"), 0, 0,
                blockEntity(16, 64, 1, "destination-before"), blockEntity(16, 64, 1, "destination-after"), 1, 0));

        ContainerTransactionManager.persistChunk(world, 0, 0, oldSource, ignored -> { });
        Path directory = transactionDirectory(world);
        Path committed;
        try (var files = Files.list(directory)) {
            committed = files.filter(path -> path.getFileName().toString().endsWith(".txn")).findFirst().orElseThrow();
        }
        Path previous = committed.resolveSibling(committed.getFileName() + "_old");
        Path staged = committed.resolveSibling(committed.getFileName() + "_new");
        Files.move(committed, previous);
        Files.write(staged, new byte[]{1, 2, 3});
        ContainerTransactionManager.clearInMemoryStateForTests();

        assertEquals("destination-after", readState(ContainerTransactionManager.applyForRead(world, 1, 0, oldDestination)),
                "Startup must recover the last committed WAL record even when replacement stopped before promotion");
        assertTrue(Files.exists(committed));
        assertFalse(Files.exists(previous));
        assertFalse(Files.exists(staged));
    }

    @Test
    void discardsUncommittedInitialTransactionStageAfterRestart() throws Exception {
        String world = tempDirectory.resolve("interrupted-initial-commit-world").toString();
        byte[] oldSource = chunk(0, 0, inventoryBlockEntity(1, 8));
        byte[] oldDestination = chunk(1, 0, inventoryBlockEntity(16, 0));
        Path directory = transactionDirectory(world);
        Files.createDirectories(directory);
        Path uncommittedStage = directory.resolve("1-" + UUID.randomUUID() + ".txn_new");

        // Simulate process termination while the first WAL record is staged but
        // before its atomic rename makes it a committed .txn file.
        Files.write(uncommittedStage, new byte[]{0, 0, 0, 3, 0, 0});
        assertEquals(0, directory.toFile().listFiles((dir, name) -> name.endsWith(".txn")).length);

        assertEquals(8, readItemCount(ContainerTransactionManager.applyForRead(world, 0, 0, oldSource)),
                "An uncommitted stage must not apply a transfer after restart");
        assertEquals(0, readItemCount(ContainerTransactionManager.applyForRead(world, 1, 0, oldDestination)));
        assertFalse(Files.exists(uncommittedStage), "Startup must discard the uncommitted initial WAL stage");
        assertEquals(0, directory.toFile().listFiles((dir, name) -> name.endsWith(".txn")).length);

        ContainerTransactionManager.begin(transaction(world,
                inventoryBlockEntity(1, 8), inventoryBlockEntity(1, 0), 0, 0,
                inventoryBlockEntity(16, 0), inventoryBlockEntity(16, 8), 1, 0));
        assertEquals(0, readItemCount(ContainerTransactionManager.applyForRead(world, 0, 0, oldSource)));
        assertEquals(8, readItemCount(ContainerTransactionManager.applyForRead(world, 1, 0, oldDestination)));
    }

    @Test
    void preservesNewInventoryEditsWhileApplyingAnOlderPendingTransfer() throws Exception {
        String world = tempDirectory.resolve("newer-edit-world").toString();
        CompoundTag sourceBefore = inventoryBlockEntity(1, 8);
        CompoundTag sourceAfter = inventoryBlockEntity(1, 0);
        CompoundTag destinationBefore = inventoryBlockEntity(16, 0);
        CompoundTag destinationAfter = inventoryBlockEntity(16, 8);
        byte[] staleSource = chunk(0, 0, sourceBefore);
        byte[] newerDestination = chunk(1, 0, inventoryBlockEntity(16, 12));

        ContainerTransactionManager.begin(transaction(world,
                sourceBefore, sourceAfter, 0, 0,
                destinationBefore, destinationAfter, 1, 0));

        byte[][] persistedDestination = new byte[1][];
        ContainerTransactionManager.persistChunk(world, 1, 0, newerDestination, data -> persistedDestination[0] = data);
        assertEquals(12, readItemCount(persistedDestination[0]), "A later inventory edit must not be replaced by the older transfer snapshot");
        assertEquals(1, transactionDirectory(world).toFile().listFiles((dir, name) -> name.endsWith(".txn")).length,
                "The other chunk must remain recoverable until its state is written");

        byte[][] persistedSource = new byte[1][];
        ContainerTransactionManager.persistChunk(world, 0, 0, staleSource, data -> persistedSource[0] = data);
        assertEquals(0, readItemCount(persistedSource[0]), "The stale source still needs the committed transfer applied");
        assertEquals(0, transactionDirectory(world).toFile().listFiles((dir, name) -> name.endsWith(".txn")).length);
    }

    @Test
    void preservesRepeatedTransfersAcrossMasterRecoveryAndChunkPersistence() throws Exception {
        String world = tempDirectory.resolve("repeated-transfer-recovery-world").toString();
        byte[] originalSource = chunk(0, 0, inventoryBlockEntity(1, 8));
        byte[] originalDestination = chunk(1, 0, inventoryBlockEntity(16, 0));

        ContainerTransactionManager.begin(transaction(world,
                inventoryBlockEntity(1, 8), inventoryBlockEntity(1, 0), 0, 0,
                inventoryBlockEntity(16, 0), inventoryBlockEntity(16, 8), 1, 0));
        ContainerTransactionManager.begin(transaction(world,
                inventoryBlockEntity(1, 0), inventoryBlockEntity(1, 4), 0, 0,
                inventoryBlockEntity(16, 8), inventoryBlockEntity(16, 4), 1, 0));

        // Reload the durable journal as a restarted Master would.
        ContainerTransactionManager.clearInMemoryStateForTests();
        assertEquals(4, readItemCount(ContainerTransactionManager.applyForRead(world, 0, 0, originalSource)));
        assertEquals(4, readItemCount(ContainerTransactionManager.applyForRead(world, 1, 0, originalDestination)));

        byte[][] persistedSource = new byte[1][];
        byte[][] persistedDestination = new byte[1][];
        ContainerTransactionManager.persistChunk(world, 0, 0, originalSource, data -> persistedSource[0] = data);
        ContainerTransactionManager.persistChunk(world, 1, 0, originalDestination, data -> persistedDestination[0] = data);
        assertEquals(4, readItemCount(persistedSource[0]));
        assertEquals(4, readItemCount(persistedDestination[0]));
        assertEquals(0, transactionDirectory(world).toFile().listFiles((dir, name) -> name.endsWith(".txn")).length);

        ContainerTransactionManager.clearInMemoryStateForTests();
        assertEquals(4, readItemCount(ContainerTransactionManager.applyForRead(world, 0, 0, persistedSource[0])));
        assertEquals(4, readItemCount(ContainerTransactionManager.applyForRead(world, 1, 0, persistedDestination[0])));
    }

    @Test
    void doesNotResurrectAContainerRemovedWhileTransferIsPending() throws Exception {
        String world = tempDirectory.resolve("removed-container-world").toString();
        CompoundTag sourceBefore = inventoryBlockEntity(1, 8);
        CompoundTag sourceAfter = inventoryBlockEntity(1, 0);
        CompoundTag destinationBefore = inventoryBlockEntity(16, 0);
        CompoundTag destinationAfter = inventoryBlockEntity(16, 8);
        byte[] staleSource = chunk(0, 0, sourceBefore);
        byte[] destinationAfterRemoval = chunk(1, 0, (CompoundTag) null);

        ContainerTransactionManager.begin(transaction(world,
                sourceBefore, sourceAfter, 0, 0,
                destinationBefore, destinationAfter, 1, 0));

        byte[][] persistedDestination = new byte[1][];
        ContainerTransactionManager.persistChunk(world, 1, 0, destinationAfterRemoval, data -> persistedDestination[0] = data);
        assertEquals(0, readBlockEntityCount(persistedDestination[0]),
                "Replaying an old transaction must not recreate a container removed by a newer chunk edit");

        ContainerTransactionManager.persistChunk(world, 0, 0, staleSource, ignored -> { });
        assertEquals(0, transactionDirectory(world).toFile().listFiles((dir, name) -> name.endsWith(".txn")).length);
    }

    private static PersistContainerTransactionMessage transaction(String world,
                                                                   CompoundTag sourceBefore, CompoundTag sourceAfter, int sourceChunkX, int sourceChunkZ,
                                                                   CompoundTag destinationBefore, CompoundTag destinationAfter, int destinationChunkX, int destinationChunkZ) throws IOException {
        return new PersistContainerTransactionMessage(world, UUID.randomUUID().toString(), new Snapshot[]{
                new Snapshot(sourceChunkX, sourceChunkZ, writeTag(sourceBefore), writeTag(sourceAfter)),
                new Snapshot(destinationChunkX, destinationChunkZ, writeTag(destinationBefore), writeTag(destinationAfter))
        });
    }

    private static CompoundTag blockEntity(int x, int y, int z, String state) {
        CompoundTag tag = new CompoundTag();
        tag.add("id", new StringTag("minecraft:chest"));
        tag.add("x", new IntTag(x));
        tag.add("y", new IntTag(y));
        tag.add("z", new IntTag(z));
        tag.add("state", new StringTag(state));
        return tag;
    }

    private static CompoundTag inventoryBlockEntity(int x, int count) {
        CompoundTag tag = new CompoundTag();
        tag.add("id", new StringTag("minecraft:chest"));
        tag.add("x", new IntTag(x));
        tag.add("y", new IntTag(64));
        tag.add("z", new IntTag(1));
        ListTag items = new ListTag(10, new ArrayList<>());
        if (count > 0) {
            CompoundTag item = new CompoundTag();
            item.add("id", new StringTag("minecraft:diamond"));
            item.add("count", new IntTag(count));
            items.add(item);
        }
        tag.add("Items", items);
        return tag;
    }

    private static CompoundTag entity(UUID uuid, int count) {
        CompoundTag tag = new CompoundTag();
        tag.add("UUID", new IntArrayTag(new int[]{(int) (uuid.getMostSignificantBits() >>> 32), (int) uuid.getMostSignificantBits(),
                (int) (uuid.getLeastSignificantBits() >>> 32), (int) uuid.getLeastSignificantBits()}));
        if (count > 0) {
            ListTag items = new ListTag(10, new ArrayList<>());
            CompoundTag item = new CompoundTag();
            item.add("id", new StringTag("minecraft:diamond"));
            item.add("count", new IntTag(count));
            items.add(item);
            tag.add("Items", items);
        }
        return tag;
    }

    private static byte[] entityChunk(CompoundTag entity) throws IOException {
        CompoundTag root = new CompoundTag();
        root.add("DataVersion", new IntTag(5023));
        root.add("Position", new se.llbit.nbt.IntArrayTag(new int[]{0, 0}));
        ListTag entities = new ListTag(10, new ArrayList<>());
        entities.add(entity);
        root.add("Entities", entities);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DataOutputStream raw = new DataOutputStream(new DeflaterOutputStream(compressed))) {
            raw.writeByte(10);
            raw.writeUTF("");
            root.write(raw);
        }
        return compressed.toByteArray();
    }

    private static int readEntityItemCount(byte[] compressed) throws IOException {
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)))) {
            input.readUnsignedByte();
            input.readUTF();
            CompoundTag root = CompoundTag.read(input).asCompound();
            CompoundTag entity = root.get("Entities").asList().get(0).asCompound();
            ListTag items = entity.get("Items").asList();
            return items == null || items.isEmpty() ? 0 : items.get(0).asCompound().get("count").intValue();
        }
    }

    private static byte[] chunk(int x, int z, CompoundTag blockEntity) throws IOException {
        CompoundTag root = new CompoundTag();
        root.add("DataVersion", new IntTag(5023));
        root.add("xPos", new IntTag(x));
        root.add("zPos", new IntTag(z));
        ListTag blockEntities = new ListTag(10, new ArrayList<>());
        if (blockEntity != null) blockEntities.add(blockEntity);
        root.add("block_entities", blockEntities);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DataOutputStream raw = new DataOutputStream(new DeflaterOutputStream(compressed))) {
            raw.writeByte(10);
            raw.writeUTF("");
            root.write(raw);
        }
        return compressed.toByteArray();
    }

    private static byte[] writeTag(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            // Transaction snapshots use the compound payload format expected by CompoundTag.read.
            tag.write(output);
        }
        return bytes.toByteArray();
    }

    private static String readState(byte[] compressedChunk) throws IOException {
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressedChunk)))) {
            if (input.readUnsignedByte() != 10) throw new IOException("Expected a compound root tag");
            input.readUTF();
            CompoundTag root = CompoundTag.read(input).asCompound();
            return root.get("block_entities").asList().get(0).asCompound().get("state").stringValue();
        }
    }

    private static int readItemCount(byte[] compressedChunk) throws IOException {
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressedChunk)))) {
            if (input.readUnsignedByte() != 10) throw new IOException("Expected a compound root tag");
            input.readUTF();
            CompoundTag root = CompoundTag.read(input).asCompound();
            CompoundTag blockEntity = root.get("block_entities").asList().get(0).asCompound();
            ListTag items = blockEntity.get("Items").asList();
            if (items == null || items.isEmpty()) return 0;
            return items.get(0).asCompound().get("count").intValue();
        }
    }

    private static int readBlockEntityCount(byte[] compressedChunk) throws IOException {
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressedChunk)))) {
            if (input.readUnsignedByte() != 10) throw new IOException("Expected a compound root tag");
            input.readUTF();
            CompoundTag root = CompoundTag.read(input).asCompound();
            return root.get("block_entities").asList().size();
        }
    }

    private Path transactionDirectory(String world) {
        return Path.of(world, "multipaper-container-transactions");
    }
}
