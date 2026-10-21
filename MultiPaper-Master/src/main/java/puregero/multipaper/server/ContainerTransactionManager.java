package puregero.multipaper.server;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PersistContainerTransactionMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.PersistContainerTransactionMessage.Snapshot;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import se.llbit.nbt.CompoundTag;
import se.llbit.nbt.IntArrayTag;
import se.llbit.nbt.IntTag;
import se.llbit.nbt.ListTag;
import se.llbit.nbt.NamedTag;
import se.llbit.nbt.SpecificTag;
import se.llbit.nbt.Tag;

/** Durable write-ahead records for inventory transfers which span chunks. */
public final class ContainerTransactionManager {
    private static final int FORMAT_VERSION = 3;
    private static final int MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;
    private static final Map<String, List<Transaction>> transactionsByWorld = new HashMap<>();
    private static long nextSequence;

    private ContainerTransactionManager() { }

    static synchronized void clearInMemoryStateForTests() {
        transactionsByWorld.clear();
        nextSequence = 0;
    }

    public static synchronized void begin(PersistContainerTransactionMessage message) throws IOException {
        UUID transactionId;
        try {
            transactionId = UUID.fromString(message.transactionId);
        } catch (IllegalArgumentException invalidId) {
            throw new IOException("Invalid container transaction ID", invalidId);
        }

        Set<ChunkFileKey> chunks = new HashSet<>();
        for (Snapshot snapshot : message.snapshots) {
            validateSnapshotSize(snapshot.beforeData());
            validateSnapshotSize(snapshot.afterData());
            if (snapshot.beforeData().length == snapshot.afterData().length
                    && java.util.Arrays.equals(snapshot.beforeData(), snapshot.afterData())) {
                throw new IOException("Container transaction snapshot has identical before and after states");
            }
            chunks.add(new ChunkFileKey(snapshot.path(), snapshot.chunkX(), snapshot.chunkZ()));
        }
        if (chunks.size() < 2) throw new IOException("Container transaction must span multiple chunks");

        List<Transaction> transactions = getTransactions(message.world);
        for (Transaction existing : transactions) {
            if (existing.id.equals(transactionId.toString())) return;
        }
        File directory = transactionDirectory(message.world);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create container transaction directory " + directory);
        Transaction transaction = new Transaction(transactionId.toString(), ++nextSequence, List.of(message.snapshots), new HashSet<>());
        writeTransaction(message.world, transaction);
        transactions.add(transaction);
    }

    public static synchronized byte[] applyForRead(String world, int chunkX, int chunkZ, byte[] data) throws IOException {
        return applyForRead(world, "region", chunkX, chunkZ, data);
    }

    public static synchronized void persistChunk(String world, int chunkX, int chunkZ, byte[] data, ChunkWriter writer) throws IOException {
        persistChunk(world, "region", chunkX, chunkZ, data, writer);
    }

    public static synchronized byte[] applyForRead(String world, String path, int chunkX, int chunkZ, byte[] data) throws IOException {
        return applySnapshots(world, path, chunkX, chunkZ, data, false, null);
    }

    public static synchronized void persistChunk(String world, String path, int chunkX, int chunkZ, byte[] data, ChunkWriter writer) throws IOException {
        applySnapshots(world, path, chunkX, chunkZ, data, true, writer);
    }

    private static byte[] applySnapshots(String world, String path, int chunkX, int chunkZ, byte[] data, boolean persist, ChunkWriter writer) throws IOException {
        List<Transaction> transactions = getTransactions(world);
        ChunkFileKey chunkKey = new ChunkFileKey(path, chunkX, chunkZ);
        List<Transaction> pending = transactions.stream()
                .filter(transaction -> transaction.chunks().contains(chunkKey) && !transaction.persistedChunks.contains(chunkKey))
                .sorted(Comparator.comparingLong(transaction -> transaction.sequence))
                .toList();
        if (pending.isEmpty()) {
            if (persist) writer.write(data);
            return data;
        }

        byte[] updated = replaceInventories(data, path, pending, chunkX, chunkZ);
        if (persist) {
            writer.write(updated);
            for (Transaction transaction : pending) {
                transaction.persistedChunks.add(chunkKey);
                if (transaction.persistedChunks.containsAll(transaction.chunks())) {
                    FileLocker.readBytes(transaction.file(world)); // Run interrupted-replacement recovery before removal.
                    java.nio.file.Files.deleteIfExists(transaction.file(world).toPath());
                    transactions.remove(transaction);
                } else {
                    writeTransaction(world, transaction);
                }
            }
        }
        return updated;
    }

    private static byte[] replaceInventories(byte[] compressedChunk, String path, List<Transaction> transactions, int chunkX, int chunkZ) throws IOException {
        if (compressedChunk.length == 0) throw new IOException("Cannot apply a container transaction to a missing " + path + " chunk " + chunkX + "," + chunkZ);
        CompoundTag chunk;
        String rootName;
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressedChunk)))) {
            int rootType = input.readUnsignedByte();
            if (rootType != 10) throw new IOException("Chunk NBT root is not a compound for " + path + " " + chunkX + "," + chunkZ);
            rootName = input.readUTF();
            chunk = CompoundTag.read(input).asCompound();
        }
        if (chunk == null) throw new IOException("Chunk NBT root is not a compound for " + path + " " + chunkX + "," + chunkZ);

        String listName = path.equals("entities") ? "Entities" : "block_entities";
        ListTag inventories = chunk.get(listName).asList();
        if (inventories == null) inventories = new ListTag((byte) 10, new ArrayList<>());
        for (Transaction transaction : transactions) {
            for (Snapshot snapshot : transaction.snapshots) {
                if (!snapshot.path().equals(path) || snapshot.chunkX() != chunkX || snapshot.chunkZ() != chunkZ) continue;
                CompoundTag before;
                CompoundTag replacement;
                try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(snapshot.data()))) {
                    replacement = CompoundTag.read(input).asCompound();
                }
                if (replacement == null) throw new IOException("Container transaction snapshot is not a compound");
                try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(snapshot.beforeData()))) {
                    before = CompoundTag.read(input).asCompound();
                }
                if (before == null || !sameInventoryIdentity(path, snapshot, before, replacement)) {
                    throw new IOException("Container transaction before/after snapshots identify different inventories");
                }
                int matchingIndex = -1;
                for (int index = 0; index < inventories.size(); index++) {
                    CompoundTag existing = inventories.get(index).asCompound();
                    if (existing != null && matchesInventory(path, snapshot, existing, replacement)) {
                        matchingIndex = index;
                        break;
                    }
                }
                if (matchingIndex >= 0) {
                    CompoundTag existing = inventories.get(matchingIndex).asCompound();
                    Tag currentItems = optionalTag(existing, "Items");
                    Tag beforeItems = optionalTag(before, "Items");
                    Tag afterItems = optionalTag(replacement, "Items");
                    if (beforeItems != null || afterItems != null) {
                        if (Objects.equals(currentItems, beforeItems)) {
                            inventories.set(matchingIndex, replaceOptionalTag(existing, "Items", afterItems));
                        }
                        // If the inventory already matches the post-state, or has diverged since
                        // this transaction, preserve it. The latter is a newer inventory edit.
                    } else if (existing.equals(before)) {
                        inventories.set(matchingIndex, replacement);
                    }
                }
                // A missing inventory owner is a newer removal. Never recreate it
                // solely because an older transaction is being replayed.
            }
        }
        chunk = withTag(chunk, listName, inventories);

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(raw)) {
            output.writeByte(10);
            output.writeUTF(rootName);
            chunk.write(output);
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream output = new DeflaterOutputStream(compressed)) {
            raw.writeTo(output);
        }
        return compressed.toByteArray();
    }

    private static boolean sameInventoryIdentity(String path, Snapshot snapshot, CompoundTag before, CompoundTag after) throws IOException {
        return matchesInventory(path, snapshot, before, after);
    }

    private static boolean matchesInventory(String path, Snapshot snapshot, CompoundTag candidate, CompoundTag replacement) throws IOException {
        if (path.equals("entities")) {
            String id = snapshot.identity();
            if (id.isEmpty()) id = entityUuid(candidate);
            return id.equals(entityUuid(candidate)) && id.equals(entityUuid(replacement));
        }
        int x = intValue(replacement, "x");
        int y = intValue(replacement, "y");
        int z = intValue(replacement, "z");
        return intValue(candidate, "x") == x && intValue(candidate, "y") == y && intValue(candidate, "z") == z;
    }

    private static String entityUuid(CompoundTag entity) throws IOException {
        Tag uuid = entity.get("UUID");
        if (!(uuid instanceof IntArrayTag intArray) || intArray.value.length != 4) {
            throw new IOException("Container entity snapshot is missing a four-int UUID");
        }
        int[] value = intArray.value;
        long most = ((long) value[0] << 32) | (value[1] & 0xffffffffL);
        long least = ((long) value[2] << 32) | (value[3] & 0xffffffffL);
        return new UUID(most, least).toString();
    }

    private static int intValue(CompoundTag tag, String name) throws IOException {
        Tag value = tag.get(name);
        if (value instanceof IntTag intTag) return intTag.intValue();
        throw new IOException("Container block-entity snapshot is missing integer " + name);
    }

    private static void validateSnapshotSize(byte[] data) throws IOException {
        if (data.length == 0 || data.length > MAX_SNAPSHOT_BYTES) {
            throw new IOException("Invalid container snapshot size " + data.length);
        }
    }

    private static Tag optionalTag(CompoundTag compound, String name) {
        Tag tag = compound.get(name);
        return tag == null || tag.isError() ? null : tag;
    }

    private static CompoundTag replaceOptionalTag(CompoundTag original, String name, Tag replacement) {
        List<NamedTag> tags = new ArrayList<>();
        boolean replaced = false;
        for (NamedTag tag : original) {
            if (tag.isNamed(name)) {
                if (!replaced && replacement != null) tags.add(new NamedTag(name, (SpecificTag) replacement));
                replaced = true;
            } else {
                tags.add(tag);
            }
        }
        if (!replaced && replacement != null) tags.add(new NamedTag(name, (SpecificTag) replacement));
        return new CompoundTag(tags);
    }

    private static CompoundTag withTag(CompoundTag original, String name, SpecificTag replacement) {
        List<NamedTag> tags = new ArrayList<>();
        boolean replaced = false;
        for (NamedTag tag : original) {
            if (tag.isNamed(name)) {
                if (!replaced) tags.add(new NamedTag(name, replacement));
                replaced = true;
            } else {
                tags.add(tag);
            }
        }
        if (!replaced) tags.add(new NamedTag(name, replacement));
        return new CompoundTag(tags);
    }

    private static List<Transaction> getTransactions(String world) throws IOException {
        List<Transaction> loaded = transactionsByWorld.get(world);
        if (loaded != null) return loaded;

        File directory = transactionDirectory(world);
        List<Transaction> transactions = new ArrayList<>();
        File[] interruptedWrites = directory.listFiles((parent, name) -> name.endsWith(".txn_old") || name.endsWith(".txn_new"));
        if (interruptedWrites != null) {
            Set<String> targets = new HashSet<>();
            for (File interruptedWrite : interruptedWrites) {
                String name = interruptedWrite.getName();
                targets.add(name.substring(0, name.length() - 4));
            }
            for (String target : targets) {
                // A fallback replacement can crash after moving the committed
                // .txn to .txn_old but before promoting .txn_new. Recover each
                // target before enumerating committed transaction filenames.
                FileLocker.readBytes(new File(directory, target));
            }
        }
        File[] files = directory.listFiles((parent, name) -> name.endsWith(".txn"));
        if (files != null) {
            for (File file : files) transactions.add(readTransaction(world, file));
        }
        transactions.sort(Comparator.comparingLong(transaction -> transaction.sequence));
        for (Transaction transaction : transactions) nextSequence = Math.max(nextSequence, transaction.sequence);
        transactionsByWorld.put(world, transactions);
        return transactions;
    }

    private static Transaction readTransaction(String world, File file) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(FileLocker.readBytes(file)))) {
            int version = input.readInt();
            if (version != 2 && version != FORMAT_VERSION) throw new IOException("Unsupported container transaction format " + version + " in " + file);
            long sequence = input.readLong();
            int count = input.readInt();
            if (count < 2 || count > 16) throw new IOException("Invalid container transaction snapshot count " + count + " in " + file);
            List<Snapshot> snapshots = new ArrayList<>(count);
            Set<ChunkFileKey> chunks = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String path = version >= 3 ? input.readUTF() : "region";
                int cx = input.readInt();
                int cz = input.readInt();
                String identity = version >= 3 ? input.readUTF() : "";
                byte[] before = readSnapshot(input, file);
                byte[] after = readSnapshot(input, file);
                snapshots.add(new Snapshot(path, cx, cz, identity, before, after));
                chunks.add(new ChunkFileKey(path, cx, cz));
            }
            int persistedCount = input.readInt();
            if (persistedCount < 0 || persistedCount > chunks.size()) throw new IOException("Invalid persisted-chunk count in " + file);
            Set<ChunkFileKey> persisted = new HashSet<>();
            for (int i = 0; i < persistedCount; i++) {
                String path = version >= 3 ? input.readUTF() : "region";
                persisted.add(new ChunkFileKey(path, input.readInt(), input.readInt()));
            }
            String fileId = file.getName().substring(0, file.getName().length() - 4);
            String sequencePrefix = sequence + "-";
            if (!fileId.startsWith(sequencePrefix)) throw new IOException("Invalid container transaction filename " + file);
            return new Transaction(fileId.substring(sequencePrefix.length()), sequence, snapshots, persisted);
        }
    }

    private static void writeTransaction(String world, Transaction transaction) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(FORMAT_VERSION);
            output.writeLong(transaction.sequence);
            output.writeInt(transaction.snapshots.size());
            for (Snapshot snapshot : transaction.snapshots) {
                output.writeUTF(snapshot.path());
                output.writeInt(snapshot.chunkX());
                output.writeInt(snapshot.chunkZ());
                output.writeUTF(snapshot.identity());
                writeSnapshot(output, snapshot.beforeData());
                writeSnapshot(output, snapshot.afterData());
            }
            output.writeInt(transaction.persistedChunks.size());
            for (ChunkFileKey chunk : transaction.persistedChunks) {
                output.writeUTF(chunk.path);
                output.writeInt(chunk.chunkX);
                output.writeInt(chunk.chunkZ);
            }
        }
        FileLocker.writeBytes(transaction.file(world), bytes.toByteArray());
    }

    private static byte[] readSnapshot(DataInputStream input, File file) throws IOException {
        int size = input.readInt();
        if (size <= 0 || size > MAX_SNAPSHOT_BYTES) throw new IOException("Invalid container snapshot size in " + file);
        byte[] data = input.readNBytes(size);
        if (data.length != size) throw new IOException("Truncated container transaction " + file);
        return data;
    }

    private static void writeSnapshot(DataOutputStream output, byte[] data) throws IOException {
        output.writeInt(data.length);
        output.write(data);
    }

    @FunctionalInterface
    public interface ChunkWriter {
        void write(byte[] data) throws IOException;
    }

    private record Transaction(String id, long sequence, List<Snapshot> snapshots, Set<ChunkFileKey> persistedChunks) {
        private Set<ChunkFileKey> chunks() {
            Set<ChunkFileKey> chunks = new HashSet<>();
            for (Snapshot snapshot : snapshots) chunks.add(new ChunkFileKey(snapshot.path(), snapshot.chunkX(), snapshot.chunkZ()));
            return chunks;
        }

        private File file(String world) {
            return new File(transactionDirectory(world), sequence + "-" + id + ".txn");
        }
    }

    static File transactionDirectory(String world) {
        return new File(world, "multipaper-container-transactions");
    }

    private record ChunkFileKey(String path, int chunkX, int chunkZ) { }
}
