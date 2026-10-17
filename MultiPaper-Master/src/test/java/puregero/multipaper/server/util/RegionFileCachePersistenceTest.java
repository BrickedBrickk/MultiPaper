package puregero.multipaper.server.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
}
