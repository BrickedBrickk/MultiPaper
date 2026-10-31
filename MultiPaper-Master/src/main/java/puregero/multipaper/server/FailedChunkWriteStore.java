package puregero.multipaper.server;

import puregero.multipaper.mastermessagingprotocol.ChunkKey;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Durable quarantine records for region chunks whose latest owner save failed. */
final class FailedChunkWriteStore {
    private static final byte[] RECORD = new byte[]{'M', 'P', 'F', 'C', 1};

    private FailedChunkWriteStore() { }

    static void markFailed(ChunkKey key) throws IOException {
        FileLocker.writeBytes(markerFile(key), RECORD);
    }

    static boolean isFailed(ChunkKey key) throws IOException {
        File marker = markerFile(key);
        if (marker.exists() && !marker.isFile()) {
            throw new IOException("Failed-region-write marker path is not a file " + marker);
        }
        if (!marker.isFile()) {
            // A crash during marker replacement can leave only a staging or
            // backup file. FileLocker normally discards an uncommitted `_new`
            // file; for quarantine records, uncertainty must remain fail-closed.
            return recoveryFilesExist(marker);
        }
        byte[] record = FileLocker.readBytes(marker);
        if (!marker.isFile()) return recoveryFilesExist(marker);
        if (!Arrays.equals(RECORD, record)) {
            throw new IOException("Invalid failed-region-write marker " + marker);
        }
        return true;
    }

    static void clearFailed(ChunkKey key) throws IOException {
        File marker = markerFile(key);
        if (!marker.exists() && !recoveryFilesExist(marker)) return;
        FileLocker.readBytes(marker); // Recover any interrupted FileLocker replacement first.
        if (!Files.deleteIfExists(marker.toPath())) return;
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            try (FileChannel directory = FileChannel.open(marker.toPath().getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            } catch (UnsupportedOperationException unsupported) {
                // The marker remains conservative if this filesystem cannot force directory metadata.
            }
        }
    }

    private static File markerFile(ChunkKey key) {
        return new File(new File(new File(key.world, "data"), "multipaper"),
                "failed-region-chunk-" + key.x + "-" + key.z + ".dat");
    }

    private static boolean recoveryFilesExist(File marker) {
        return new File(marker.getParentFile(), marker.getName() + "_old").exists()
                || new File(marker.getParentFile(), marker.getName() + "_new").exists();
    }
}
