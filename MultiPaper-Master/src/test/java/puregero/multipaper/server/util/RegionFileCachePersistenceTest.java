package puregero.multipaper.server.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionFileCachePersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void chunkDataCanBeReadAfterCachedRegionFilesAreClosedAndReopened() throws Exception {
        File regionDirectory = tempDir.resolve("world").resolve("region").toFile();
        byte[] expected = "persisted-chunk-data".getBytes(StandardCharsets.UTF_8);

        RegionFileCache.putChunkDeflatedDataAsync(regionDirectory, -33, 48, expected).join();
        RegionFileCache.closeAll();

        assertArrayEquals(expected, RegionFileCache.getChunkDeflatedDataAsync(regionDirectory, -33, 48).join());
        RegionFileCache.closeAll();
    }

    @Test
    void externalChunkSidecarCanBeReadAfterCachedRegionFilesAreClosedAndReopened() throws Exception {
        File regionDirectory = tempDir.resolve("world").resolve("region").toFile();
        int chunkX = 1057;
        int chunkZ = -18;
        byte[] expected = new byte[1_100_000];
        new Random(0x26_03).nextBytes(expected);

        try {
            RegionFileCache.putChunkDeflatedDataAsync(regionDirectory, chunkX, chunkZ, expected).join();
            assertTrue(Files.isRegularFile(regionDirectory.toPath().resolve("c." + chunkX + "." + chunkZ + ".mcc")),
                    "A large chunk should be stored in its external sidecar file");
            RegionFileCache.closeAll();

            assertArrayEquals(expected, RegionFileCache.getChunkDeflatedDataAsync(regionDirectory, chunkX, chunkZ).join());
        } finally {
            RegionFileCache.closeAll();
        }
    }
}
