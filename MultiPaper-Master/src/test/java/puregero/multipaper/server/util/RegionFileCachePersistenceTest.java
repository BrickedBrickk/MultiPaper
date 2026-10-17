package puregero.multipaper.server.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void sameSizedReplacementIsWrittenToNewSectorsBeforeTheOldSectorsAreReused() throws Exception {
        File regionDirectory = tempDir.resolve("world").resolve("region").toFile();
        Path regionFile = regionDirectory.toPath().resolve("r.0.0.mca");
        byte[] first = new byte[4096];
        byte[] second = new byte[4096];
        byte[] third = new byte[4096];
        new Random(1).nextBytes(first);
        new Random(2).nextBytes(second);
        new Random(3).nextBytes(third);

        try {
            RegionFileCache.putChunkDeflatedDataAsync(regionDirectory, 0, 0, first).join();
            long firstWriteLength = Files.size(regionFile);
            RegionFileCache.putChunkDeflatedDataAsync(regionDirectory, 0, 0, second).join();
            long secondWriteLength = Files.size(regionFile);

            assertTrue(secondWriteLength > firstWriteLength,
                    "An acknowledged chunk must not be overwritten in place during replacement");
            assertArrayEquals(second, RegionFileCache.getChunkDeflatedDataAsync(regionDirectory, 0, 0).join());

            RegionFileCache.putChunkDeflatedDataAsync(regionDirectory, 0, 0, third).join();
            assertEquals(secondWriteLength, Files.size(regionFile),
                    "A later replacement should reuse the sectors freed after publication");
            RegionFileCache.closeAll();

            assertArrayEquals(third, RegionFileCache.getChunkDeflatedDataAsync(regionDirectory, 0, 0).join());
        } finally {
            RegionFileCache.closeAll();
        }
    }

    @Test
    void fragmentedRegionKeepsLatestChunkDataAfterRepeatedReplacementAndReopen() throws Exception {
        File regionDirectory = tempDir.resolve("world").resolve("region").toFile();
        Map<Integer, byte[]> expected = new HashMap<>();
        Random random = new Random(0x26_03_17);

        try {
            for (int round = 0; round < 6; round++) {
                for (int chunkX = 0; chunkX < 32; chunkX++) {
                    byte[] payload = new byte[512 + random.nextInt(24_000)];
                    random.nextBytes(payload);
                    RegionFileCache.putChunkDeflatedDataAsync(regionDirectory, chunkX, 0, payload).join();
                    expected.put(chunkX, payload);
                }
            }

            RegionFileCache.closeAll();
            for (Map.Entry<Integer, byte[]> chunk : expected.entrySet()) {
                assertArrayEquals(chunk.getValue(),
                        RegionFileCache.getChunkDeflatedDataAsync(regionDirectory, chunk.getKey(), 0).join(),
                        "Latest payload should survive fragmented allocation for chunk " + chunk.getKey());
            }
        } finally {
            RegionFileCache.closeAll();
        }
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
