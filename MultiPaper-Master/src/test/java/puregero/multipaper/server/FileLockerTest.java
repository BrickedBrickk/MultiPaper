package puregero.multipaper.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FileLockerTest {

    @Test
    void writesAndReplacesFileContents(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("shared-data.dat");
        byte[] first = new byte[]{1, 2, 3, 4};
        byte[] second = new byte[]{5, 6, 7, 8, 9};

        FileLocker.writeBytes(file.toFile(), first);
        assertArrayEquals(first, Files.readAllBytes(file));

        FileLocker.writeBytes(file.toFile(), second);
        assertArrayEquals(second, Files.readAllBytes(file));
    }

    @Test
    void restoresPreviousFileAfterInterruptedFallbackReplacement(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("shared-data.dat");
        Path oldFile = directory.resolve("shared-data.dat_old");
        Path stagedFile = directory.resolve("shared-data.dat_new");
        byte[] previous = new byte[]{10, 20, 30};

        Files.write(oldFile, previous);
        Files.write(stagedFile, new byte[]{40, 50, 60});

        assertArrayEquals(previous, FileLocker.readBytes(file.toFile()));
        assertFalse(Files.exists(oldFile));
        assertFalse(Files.exists(stagedFile));
    }

    @Test
    void keepsCommittedFileAndDiscardsUncommittedStagingFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("transaction.txn");
        Path stagedFile = directory.resolve("transaction.txn_new");
        byte[] committed = new byte[]{1, 3, 5};
        byte[] uncommitted = new byte[]{2, 4, 6};

        Files.write(file, committed);
        Files.write(stagedFile, uncommitted);

        assertArrayEquals(committed, FileLocker.readBytes(file.toFile()));
        assertFalse(Files.exists(stagedFile), "An orphaned stage must not appear as a committed transaction");
    }
}
