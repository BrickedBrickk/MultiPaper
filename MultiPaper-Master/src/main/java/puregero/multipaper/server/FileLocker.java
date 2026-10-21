package puregero.multipaper.server;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Put any files being written into a hashmap, and if they're read while they're
 * being written, return the bytes that are being written instead of reading
 * from the file.
 */
public class FileLocker {

    private static final HashMap<File, byte[]> beingWritten = new HashMap<>();
    private static final HashMap<File, CompletableFuture<Void>> locks = new HashMap<>();

    public static CompletableFuture<CompletableFuture<Void>> createLockAsync(File file) {
        synchronized (locks) {
            if (locks.containsKey(file)) {
                return locks.get(file).thenCompose(value -> createLockAsync(file));
            }

            CompletableFuture<Void> lock = new CompletableFuture<>();

            locks.put(file, lock.thenRun(() -> {
                synchronized (locks) {
                    locks.remove(file);
                }
            }));

            return CompletableFuture.completedFuture(lock);
        }
    }

    public static byte[] readBytes(File file) throws IOException {
        synchronized (beingWritten) {
            if (beingWritten.containsKey(file)) {
                return beingWritten.get(file);
            }

            recoverInterruptedWrite(file.toPath());
            return !file.isFile() ? new byte[0] : Files.readAllBytes(file.toPath());
        }
    }

    public static void writeBytes(File file, byte[] bytes) throws IOException {
        synchronized (beingWritten) {
            while (beingWritten.containsKey(file)) {
                try {
                    // Wait 1 millisecond for file to finish being written
                    beingWritten.wait(1);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            beingWritten.put(file, bytes);
        }

        try {
            file.getParentFile().mkdirs();
            recoverInterruptedWrite(file.toPath());
            safeWrite(file, bytes);
        } finally {
            synchronized (beingWritten) {
                beingWritten.remove(file);
            }
        }
    }

    private static void safeWrite(File file, byte[] bytes) throws IOException {
        File newFile = new File(file.getParentFile(), file.getName() + "_new");
        File oldFile = new File(file.getParentFile(), file.getName() + "_old");

        try (FileChannel channel = FileChannel.open(newFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            // Ask the OS to persist the replacement bytes before making them visible at
            // the target path. Directory metadata is forced below where the platform allows it.
            channel.force(true);
        }

        try {
            Files.move(newFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            safeReplaceFile(file.toPath(), newFile.toPath(), oldFile.toPath());
        }

        // Windows does not expose directory handles to FileChannel. On platforms that do,
        // force the rename metadata so a successful reply survives a host crash as well.
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            try (FileChannel directory = FileChannel.open(file.toPath().getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            } catch (UnsupportedOperationException ignored) {
                // The file contents were still forced; this file system cannot force directory metadata.
            }
        }
    }

    private static void safeReplaceFile(Path file, Path newFile, Path oldFile) throws IOException {
        if (Files.exists(file)) {
            Files.move(file, oldFile, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Files.move(newFile, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            if (!Files.exists(file) && Files.exists(oldFile)) {
                Files.move(oldFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
            throw failure;
        }

        if (Files.exists(oldFile)) {
            Files.delete(oldFile);
        }
    }

    private static void recoverInterruptedWrite(Path file) throws IOException {
        Path oldFile = file.resolveSibling(file.getFileName() + "_old");
        Path newFile = file.resolveSibling(file.getFileName() + "_new");

        if (!Files.exists(file) && Files.exists(oldFile)) {
            Files.move(oldFile, file, StandardCopyOption.REPLACE_EXISTING);
        } else if (Files.exists(oldFile)) {
            Files.delete(oldFile);
        }

        // A staged replacement is never committed until it has been renamed to the target.
        // Keep the last known target (or restore _old above) and discard an orphaned stage.
        Files.deleteIfExists(newFile);
    }
}
