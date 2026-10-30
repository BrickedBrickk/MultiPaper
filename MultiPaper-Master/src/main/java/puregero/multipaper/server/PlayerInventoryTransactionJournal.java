package puregero.multipaper.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Durable commit decisions and owner-apply acknowledgements for inventory transactions. */
public final class PlayerInventoryTransactionJournal {
    private static final int MAGIC = 0x4d504954; // MPIT
    private static final int VERSION = 2;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_AFTER_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final long MAX_RECORD_BYTES = MAX_AFTER_IMAGE_BYTES * 2L + 256;
    private static final ConcurrentHashMap<Path, Object> DIRECTORY_LOCKS = new ConcurrentHashMap<>();

    private final Path directory;
    private final Object lock;

    public PlayerInventoryTransactionJournal(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.lock = DIRECTORY_LOCKS.computeIfAbsent(this.directory, ignored -> new Object());
    }

    /** Atomically records the complete decision before either participant applies it. */
    public RecordResult recordCommit(PlayerInventoryTransactionCoordinator.TransactionRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        synchronized (lock) {
            CommitRecord existing = read(request.transactionId());
            if (existing != null) {
                if (existing.aborted()) {
                    return existing.request().sameAs(request) ? RecordResult.ABORTED : RecordResult.ID_CONFLICT;
                }
                return existing.request().sameAs(request) ? RecordResult.ALREADY_RECORDED : RecordResult.ID_CONFLICT;
            }
            write(new CommitRecord(request, 0, false));
            return RecordResult.RECORDED;
        }
    }

    /** Persists an abort tombstone so a transaction ID cannot be reused after restart. */
    public AbortResult recordAbort(PlayerInventoryTransactionCoordinator.TransactionRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        synchronized (lock) {
            CommitRecord existing = read(request.transactionId());
            if (existing != null) {
                if (!existing.request().sameAs(request)) {
                    return AbortResult.ID_CONFLICT;
                }
                return existing.aborted() ? AbortResult.ALREADY_ABORTED : AbortResult.COMMIT_ALREADY_RECORDED;
            }
            write(new CommitRecord(request, 0, true));
            return AbortResult.ABORTED;
        }
    }

    /** Persists an idempotent owner apply acknowledgement before returning it. */
    public AckResult markApplied(UUID transactionId, UUID playerId) throws IOException {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            CommitRecord record = read(transactionId);
            if (record == null) {
                return AckResult.NO_COMMIT;
            }
            if (record.aborted()) {
                return AckResult.NO_COMMIT;
            }
            int bit = record.request().editor().playerId().equals(playerId) ? 1
                    : record.request().target().playerId().equals(playerId) ? 2 : 0;
            if (bit == 0) {
                return AckResult.NOT_A_PARTICIPANT;
            }
            if ((record.appliedMask() & bit) != 0) {
                return AckResult.ALREADY_APPLIED;
            }
            int updatedMask = record.appliedMask() | bit;
            write(new CommitRecord(record.request(), updatedMask, false));
            return updatedMask == 3 ? AckResult.BOTH_APPLIED : AckResult.OWNER_APPLIED;
        }
    }

    public CommitRecord get(UUID transactionId) throws IOException {
        synchronized (lock) {
            return read(Objects.requireNonNull(transactionId, "transactionId"));
        }
    }

    /** Loads durable commit and abort decisions in deterministic order. */
    public List<CommitRecord> loadAll() throws IOException {
        synchronized (lock) {
            if (!Files.isDirectory(directory)) {
                return List.of();
            }
            List<Path> files = new ArrayList<>();
            try (var paths = Files.list(directory)) {
                paths.filter(path -> path.getFileName().toString().endsWith(".itx"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(files::add);
            }
            List<CommitRecord> records = new ArrayList<>(files.size());
            for (Path file : files) {
                long size = Files.size(file);
                if (size <= 0 || size > MAX_RECORD_BYTES) {
                    throw new IOException("Invalid inventory transaction record size " + size + ": " + file);
                }
                CommitRecord record = decode(FileLocker.readBytes(file.toFile()));
                if (!recordPath(record.request().transactionId()).equals(file.toAbsolutePath().normalize())) {
                    throw new IOException("Inventory transaction filename does not match record ID: " + file);
                }
                records.add(record);
            }
            return List.copyOf(records);
        }
    }

    public List<CommitRecord> loadCommitted() throws IOException {
        return loadAll().stream().filter(record -> !record.aborted()).toList();
    }

    private CommitRecord read(UUID transactionId) throws IOException {
        Path file = recordPath(transactionId);
        if (!Files.exists(file)) {
            return null;
        }
        long size = Files.size(file);
        if (size <= 0 || size > MAX_RECORD_BYTES) {
            throw new IOException("Invalid inventory transaction record size " + size + ": " + file);
        }
        CommitRecord record = decode(FileLocker.readBytes(file.toFile()));
        if (!record.request().transactionId().equals(transactionId)) {
            throw new IOException("Inventory transaction filename does not match record ID: " + file);
        }
        return record;
    }

    private void write(CommitRecord record) throws IOException {
        FileLocker.writeBytes(recordPath(record.request().transactionId()).toFile(), encode(record));
    }

    private Path recordPath(UUID transactionId) {
        return directory.resolve(transactionId + ".itx");
    }

    private static byte[] encode(CommitRecord record) throws IOException {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
            PlayerInventoryTransactionCoordinator.TransactionRequest request = record.request();
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            writeUuid(output, request.transactionId());
            writeSnapshot(output, request.editor());
            writeSnapshot(output, request.target());
            output.write(request.requestDigest());
            writeAfterImage(output, request.editorAfterImage());
            writeAfterImage(output, request.targetAfterImage());
            output.writeByte(record.aborted() ? 0 : 1);
            output.writeByte(record.appliedMask());
        }
        byte[] payload = payloadBytes.toByteArray();
        if (payload.length + DIGEST_BYTES > MAX_RECORD_BYTES) {
            throw new IOException("Inventory transaction record exceeds size limit");
        }
        ByteArrayOutputStream recordBytes = new ByteArrayOutputStream(payload.length + DIGEST_BYTES);
        recordBytes.write(payload);
        recordBytes.write(sha256(payload));
        return recordBytes.toByteArray();
    }

    private static CommitRecord decode(byte[] bytes) throws IOException {
        if (bytes.length <= DIGEST_BYTES || bytes.length > MAX_RECORD_BYTES) {
            throw new IOException("Invalid inventory transaction record size " + bytes.length);
        }
        byte[] payload = Arrays.copyOf(bytes, bytes.length - DIGEST_BYTES);
        byte[] storedDigest = Arrays.copyOfRange(bytes, bytes.length - DIGEST_BYTES, bytes.length);
        if (!MessageDigest.isEqual(storedDigest, sha256(payload))) {
            throw new IOException("Inventory transaction record checksum mismatch");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid inventory transaction record magic");
            }
            if (input.readInt() != VERSION) {
                throw new IOException("Unsupported inventory transaction record version");
            }
            UUID transactionId = readUuid(input);
            PlayerInventoryTransactionCoordinator.PlayerSnapshot editor = readSnapshot(input);
            PlayerInventoryTransactionCoordinator.PlayerSnapshot target = readSnapshot(input);
            byte[] requestDigest = input.readNBytes(DIGEST_BYTES);
            if (requestDigest.length != DIGEST_BYTES) {
                throw new IOException("Truncated inventory transaction request digest");
            }
            byte[] editorAfterImage = readAfterImage(input);
            byte[] targetAfterImage = readAfterImage(input);
            int decision = input.readUnsignedByte();
            int appliedMask = input.readUnsignedByte();
            if (decision > 1 || appliedMask > 3 || decision == 0 && appliedMask != 0 || input.available() != 0) {
                throw new IOException("Invalid inventory transaction apply state or trailing bytes");
            }
            PlayerInventoryTransactionCoordinator.TransactionRequest request =
                    new PlayerInventoryTransactionCoordinator.TransactionRequest(
                            transactionId, editor, target, requestDigest, editorAfterImage, targetAfterImage);
            return new CommitRecord(request, appliedMask, decision == 0);
        } catch (java.io.EOFException truncated) {
            throw new IOException("Truncated inventory transaction record", truncated);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid inventory transaction record", invalid);
        }
    }

    private static void writeSnapshot(DataOutputStream output,
                                      PlayerInventoryTransactionCoordinator.PlayerSnapshot snapshot) throws IOException {
        writeUuid(output, snapshot.playerId());
        writeUuid(output, snapshot.sessionId());
        output.writeLong(snapshot.revision());
        output.write(snapshot.snapshotDigest());
    }

    private static PlayerInventoryTransactionCoordinator.PlayerSnapshot readSnapshot(DataInputStream input) throws IOException {
        UUID playerId = readUuid(input);
        UUID sessionId = readUuid(input);
        long revision = input.readLong();
        byte[] digest = input.readNBytes(DIGEST_BYTES);
        if (digest.length != DIGEST_BYTES) {
            throw new IOException("Truncated inventory transaction snapshot digest");
        }
        return new PlayerInventoryTransactionCoordinator.PlayerSnapshot(playerId, sessionId, revision, digest);
    }

    private static void writeAfterImage(DataOutputStream output, byte[] afterImage) throws IOException {
        output.writeInt(afterImage.length);
        output.write(afterImage);
    }

    private static byte[] readAfterImage(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_AFTER_IMAGE_BYTES || length > input.available()) {
            throw new IOException("Invalid inventory transaction after-image size " + length);
        }
        return input.readNBytes(length);
    }

    private static void writeUuid(DataOutputStream output, UUID uuid) throws IOException {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static byte[] sha256(byte[] bytes) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    public enum RecordResult {
        RECORDED,
        ALREADY_RECORDED,
        ABORTED,
        ID_CONFLICT
    }

    public enum AbortResult {
        ABORTED,
        ALREADY_ABORTED,
        COMMIT_ALREADY_RECORDED,
        ID_CONFLICT
    }

    public enum AckResult {
        OWNER_APPLIED,
        BOTH_APPLIED,
        ALREADY_APPLIED,
        NO_COMMIT,
        NOT_A_PARTICIPANT
    }

    public record CommitRecord(PlayerInventoryTransactionCoordinator.TransactionRequest request, int appliedMask, boolean aborted) {
        public CommitRecord {
            Objects.requireNonNull(request, "request");
            if (appliedMask < 0 || appliedMask > 3) {
                throw new IllegalArgumentException("appliedMask must be between 0 and 3");
            }
            if (aborted && appliedMask != 0) {
                throw new IllegalArgumentException("An aborted transaction cannot have owner apply acknowledgements");
            }
        }

        public boolean isApplied(UUID playerId) {
            if (request.editor().playerId().equals(playerId)) {
                return (appliedMask & 1) != 0;
            }
            if (request.target().playerId().equals(playerId)) {
                return (appliedMask & 2) != 0;
            }
            return false;
        }

        public boolean allOwnersApplied() {
            return appliedMask == 3;
        }
    }
}
