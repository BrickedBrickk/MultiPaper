package puregero.multipaper;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class MultiPaperPeerDataFallbackTest {

    @Test
    void disconnectedPeerReadFallsBackToMasterData() throws IOException {
        byte[] masterData = deflate("persisted entity data");
        AtomicInteger masterReadCount = new AtomicInteger();
        CompletableFuture<DataInputStream> peerRead = new CompletableFuture<>();

        CompletableFuture<DataInputStream> result = MultiPaper.readPeerDataOrMaster(peerRead, () -> {
            masterReadCount.incrementAndGet();
            return CompletableFuture.completedFuture(masterData);
        });

        peerRead.complete(null);

        try (DataInputStream input = result.join()) {
            assertArrayEquals("persisted entity data".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
        }
        assertEquals(1, masterReadCount.get());
    }

    @Test
    void successfulPeerReadDoesNotReadMaster() throws IOException {
        byte[] peerData = "live peer data".getBytes(StandardCharsets.UTF_8);
        AtomicInteger masterReadCount = new AtomicInteger();
        CompletableFuture<DataInputStream> peerRead = CompletableFuture.completedFuture(new DataInputStream(new java.io.ByteArrayInputStream(peerData)));

        CompletableFuture<DataInputStream> result = MultiPaper.readPeerDataOrMaster(peerRead, () -> {
            masterReadCount.incrementAndGet();
            return CompletableFuture.completedFuture(new byte[0]);
        });

        try (DataInputStream input = result.join()) {
            assertArrayEquals(peerData, input.readAllBytes());
        }
        assertEquals(0, masterReadCount.get());
    }

    @Test
    void disconnectCompletesAndClearsPendingPeerReadCallbacks() {
        ExternalServerConnection connection = new ExternalServerConnection();
        AtomicReference<DataInputStream> chunkResult = new AtomicReference<>(new DataInputStream(new java.io.ByteArrayInputStream(new byte[0])));
        AtomicReference<DataInputStream> entityResult = new AtomicReference<>(new DataInputStream(new java.io.ByteArrayInputStream(new byte[0])));
        connection.chunkCallbacks.put(new ChunkKey("world", 1, 2), chunkResult::set);
        connection.entitiesCallbacks.put(new ChunkKey("world", 3, 4), entityResult::set);

        connection.failPendingDataRequests();

        assertTrue(connection.chunkCallbacks.isEmpty());
        assertTrue(connection.entitiesCallbacks.isEmpty());
        assertNull(chunkResult.get());
        assertNull(entityResult.get());
    }

    private static byte[] deflate(String data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream output = new DeflaterOutputStream(bytes)) {
            output.write(data.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
