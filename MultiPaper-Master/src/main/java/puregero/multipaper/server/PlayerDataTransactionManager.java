package puregero.multipaper.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Write-ahead journal for a single logical change spanning multiple playerdata files.
 * The caller must update all participating live player mirrors before allowing a
 * normal player save to replace these committed snapshots.
 */
public final class PlayerDataTransactionManager {
    private static final int MAGIC = 0x4d505054; // MPPT
    private static final int VERSION = 1;
    private static final int MAX_PLAYERS = 64;
    private static final int MAX_PLAYER_BYTES = 8 * 1024 * 1024;
    private static final long MAX_TRANSACTION_BYTES = 32L * 1024 * 1024;
    private static final ConcurrentHashMap<Path, Object> WORLD_LOCKS = new ConcurrentHashMap<>();

    private PlayerDataTransactionManager() { }

    public static void commit(String world, UUID transactionId, Map<UUID, byte[]> playerSnapshots) throws IOException {
        commit(world, transactionId, playerSnapshots, ignored -> { });
    }

    static void commit(String world, UUID transactionId, Map<UUID, byte[]> playerSnapshots,
                       AfterPlayerWrite afterPlayerWrite) throws IOException {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(playerSnapshots, "playerSnapshots");
        Objects.requireNonNull(afterPlayerWrite, "afterPlayerWrite");
        Path worldPath = Path.of(world).toAbsolutePath().normalize();
        synchronized (lockFor(worldPath)) {
            recoverPending(worldPath);
            byte[] transaction = encode(transactionId, playerSnapshots);
            byte[] digest = sha256(transaction);
            Path doneFile = doneFile(worldPath, transactionId);
            if (Files.exists(doneFile)) {
                if (!MessageDigest.isEqual(FileLocker.readBytes(doneFile.toFile()), digest)) {
                    throw new IOException("Playerdata transaction ID reused with different contents: " + transactionId);
                }
                return;
            }

            Path pendingFile = pendingFile(worldPath, transactionId);
            FileLocker.writeBytes(pendingFile.toFile(), transaction);
            applyPending(worldPath, pendingFile, afterPlayerWrite);
        }
    }

    public static byte[] readPlayer(String world, UUID playerId) throws IOException {
        Path worldPath = Path.of(world).toAbsolutePath().normalize();
        synchronized (lockFor(worldPath)) {
            recoverPending(worldPath);
            return FileLocker.readBytes(playerFile(worldPath, playerId).toFile());
        }
    }

    public static void writePlayer(String world, UUID playerId, byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        Path worldPath = Path.of(world).toAbsolutePath().normalize();
        synchronized (lockFor(worldPath)) {
            recoverPending(worldPath);
            FileLocker.writeBytes(playerFile(worldPath, playerId).toFile(), data);
        }
    }

    private static void recoverPending(Path worldPath) throws IOException {
        Path directory = transactionDirectory(worldPath);
        if (!Files.isDirectory(directory)) return;
        List<Path> pendingFiles = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".txn"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(pendingFiles::add);
        }
        for (Path pending : pendingFiles) applyPending(worldPath, pending, ignored -> { });
    }

    private static void applyPending(Path worldPath, Path pendingFile, AfterPlayerWrite afterPlayerWrite) throws IOException {
        byte[] transaction = FileLocker.readBytes(pendingFile.toFile());
        Transaction decoded = decode(transaction);
        Path expectedPending = pendingFile(worldPath, decoded.id);
        if (!expectedPending.equals(pendingFile.toAbsolutePath().normalize())) {
            throw new IOException("Playerdata journal filename does not match transaction ID: " + pendingFile);
        }
        byte[] digest = sha256(transaction);
        Path doneFile = doneFile(worldPath, decoded.id);
        if (Files.exists(doneFile)) {
            if (!MessageDigest.isEqual(FileLocker.readBytes(doneFile.toFile()), digest)) {
                throw new IOException("Committed playerdata transaction digest mismatch: " + decoded.id);
            }
            deletePending(pendingFile);
            return;
        }

        for (Map.Entry<UUID, byte[]> entry : decoded.playerSnapshots.entrySet()) {
            FileLocker.writeBytes(playerFile(worldPath, entry.getKey()).toFile(), entry.getValue());
            afterPlayerWrite.accept(entry.getKey());
        }
        FileLocker.writeBytes(doneFile.toFile(), digest);
        deletePending(pendingFile);
    }

    private static byte[] encode(UUID transactionId, Map<UUID, byte[]> playerSnapshots) throws IOException {
        if (playerSnapshots.size() < 2 || playerSnapshots.size() > MAX_PLAYERS) {
            throw new IOException("A playerdata transaction must include 2 to " + MAX_PLAYERS + " players");
        }
        Map<UUID, byte[]> copy = new HashMap<>();
        long totalBytes = 0;
        for (Map.Entry<UUID, byte[]> entry : playerSnapshots.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), "player ID");
            byte[] snapshot = Objects.requireNonNull(entry.getValue(), "player snapshot").clone();
            if (snapshot.length == 0 || snapshot.length > MAX_PLAYER_BYTES) {
                throw new IOException("Invalid playerdata snapshot length " + snapshot.length);
            }
            totalBytes += snapshot.length;
            if (totalBytes > MAX_TRANSACTION_BYTES) throw new IOException("Playerdata transaction is too large");
            if (copy.put(playerId, snapshot) != null) throw new IOException("Duplicate player ID in transaction");
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(transactionId.getMostSignificantBits());
            output.writeLong(transactionId.getLeastSignificantBits());
            output.writeInt(copy.size());
            for (Map.Entry<UUID, byte[]> entry : copy.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                output.writeLong(entry.getKey().getMostSignificantBits());
                output.writeLong(entry.getKey().getLeastSignificantBits());
                output.writeInt(entry.getValue().length);
                output.write(entry.getValue());
            }
        }
        if (bytes.size() > MAX_TRANSACTION_BYTES) throw new IOException("Playerdata transaction is too large");
        return bytes.toByteArray();
    }

    private static Transaction decode(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_TRANSACTION_BYTES) throw new IOException("Invalid playerdata journal size " + bytes.length);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid playerdata journal magic");
            if (input.readInt() != VERSION) throw new IOException("Unsupported playerdata journal version");
            UUID transactionId = new UUID(input.readLong(), input.readLong());
            int count = input.readInt();
            if (count < 2 || count > MAX_PLAYERS) throw new IOException("Invalid playerdata journal participant count " + count);
            Map<UUID, byte[]> snapshots = new HashMap<>();
            long totalBytes = 0;
            for (int i = 0; i < count; i++) {
                UUID playerId = new UUID(input.readLong(), input.readLong());
                int length = input.readInt();
                if (length <= 0 || length > MAX_PLAYER_BYTES) throw new IOException("Invalid playerdata snapshot length " + length);
                totalBytes += length;
                if (totalBytes > MAX_TRANSACTION_BYTES) throw new IOException("Playerdata transaction is too large");
                byte[] snapshot = input.readNBytes(length);
                if (snapshot.length != length) throw new IOException("Truncated playerdata transaction snapshot");
                if (snapshots.put(playerId, snapshot) != null) throw new IOException("Duplicate player ID in transaction");
            }
            if (input.available() != 0) throw new IOException("Trailing bytes in playerdata journal");
            return new Transaction(transactionId, snapshots);
        } catch (java.io.EOFException truncated) {
            throw new IOException("Truncated playerdata journal", truncated);
        }
    }

    private static byte[] sha256(byte[] bytes) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void deletePending(Path pendingFile) throws IOException {
        Files.deleteIfExists(pendingFile);
        Path parent = pendingFile.getParent();
        if (parent != null && !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            } catch (UnsupportedOperationException ignored) {
                // The committed marker remains authoritative if directory fsync is unsupported.
            }
        }
    }

    private static Object lockFor(Path worldPath) {
        return WORLD_LOCKS.computeIfAbsent(worldPath, ignored -> new Object());
    }

    private static Path transactionDirectory(Path worldPath) {
        return worldPath.resolve("multipaper-playerdata-transactions");
    }

    private static Path pendingFile(Path worldPath, UUID transactionId) {
        return transactionDirectory(worldPath).resolve(transactionId + ".txn");
    }

    private static Path doneFile(Path worldPath, UUID transactionId) {
        return transactionDirectory(worldPath).resolve(transactionId + ".done");
    }

    private static Path playerFile(Path worldPath, UUID playerId) {
        return worldPath.resolve("playerdata").resolve(playerId + ".dat");
    }

    @FunctionalInterface
    interface AfterPlayerWrite {
        void accept(UUID playerId) throws IOException;
    }

    private record Transaction(UUID id, Map<UUID, byte[]> playerSnapshots) { }
}
