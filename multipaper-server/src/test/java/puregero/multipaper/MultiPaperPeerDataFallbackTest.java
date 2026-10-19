package puregero.multipaper;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void missingPeerChunkResponseCompletesWithNullForMasterFallback() throws InterruptedException {
        ExternalServerConnection connection = new ExternalServerConnection();
        connection.externalServer = new ExternalServer("peer", false);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger callbackCount = new AtomicInteger();
        AtomicReference<DataInputStream> result = new AtomicReference<>(new DataInputStream(new java.io.ByteArrayInputStream(new byte[0])));

        assertTrue(connection.registerChunkRequest("world", 8, -3, data -> {
            callbackCount.incrementAndGet();
            result.set(data);
            completed.countDown();
        }, 25));

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertNull(result.get());
        assertTrue(connection.chunkCallbacks.isEmpty());
        assertFalse(connection.isWaitingForChunkData());

        AtomicReference<DataInputStream> duplicateResult = new AtomicReference<>(new DataInputStream(new java.io.ByteArrayInputStream(new byte[0])));
        assertFalse(connection.registerChunkRequest("world", 8, -3, duplicateResult::set, 25));
        assertNull(duplicateResult.get());
        assertTrue(connection.completeChunkRequest("world", 8, -3, new DataInputStream(new java.io.ByteArrayInputStream(new byte[]{1}))));
        assertEquals(1, callbackCount.get());
    }

    @Test
    void missingPeerEntityResponseCompletesWithNullAndAllowsLateLiveUpdate() throws InterruptedException {
        ExternalServerConnection connection = new ExternalServerConnection();
        connection.externalServer = new ExternalServer("peer", false);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger callbackCount = new AtomicInteger();
        AtomicReference<DataInputStream> result = new AtomicReference<>(new DataInputStream(new java.io.ByteArrayInputStream(new byte[0])));

        assertTrue(connection.registerEntityRequest("world", -8, 3, data -> {
            callbackCount.incrementAndGet();
            result.set(data);
            completed.countDown();
        }, 25));

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertNull(result.get());
        assertTrue(connection.entitiesCallbacks.isEmpty());

        assertFalse(connection.completeEntityRequest("world", -8, 3, new DataInputStream(new java.io.ByteArrayInputStream(new byte[]{1}))));
        assertEquals(1, callbackCount.get());
    }

    @Test
    void completedPeerEntityRequestDoesNotFireTimeoutCallback() throws InterruptedException {
        ExternalServerConnection connection = new ExternalServerConnection();
        connection.externalServer = new ExternalServer("peer", false);
        AtomicInteger callbackCount = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);

        assertTrue(connection.registerEntityRequest("world", 8, -3, data -> {
            callbackCount.incrementAndGet();
            completed.countDown();
        }, 50));
        assertTrue(connection.completeEntityRequest("world", 8, -3, null));

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(1, callbackCount.get());
        assertTrue(connection.entitiesCallbacks.isEmpty());
    }

    @Test
    void duplicatePeerEntityRequestPreservesOriginalCallback() {
        ExternalServerConnection connection = new ExternalServerConnection();
        connection.externalServer = new ExternalServer("peer", false);
        AtomicInteger originalCallbackCount = new AtomicInteger();
        AtomicReference<DataInputStream> duplicateResult = new AtomicReference<>(new DataInputStream(new java.io.ByteArrayInputStream(new byte[0])));

        assertTrue(connection.registerEntityRequest("world", 8, -3, data -> originalCallbackCount.incrementAndGet(), 10_000));
        assertFalse(connection.registerEntityRequest("world", 8, -3, duplicateResult::set, 10_000));
        assertNull(duplicateResult.get());
        assertTrue(connection.completeEntityRequest("world", 8, -3, null));
        assertEquals(1, originalCallbackCount.get());
    }

    @Test
    void completedPeerChunkRequestDoesNotFireTimeoutCallback() throws InterruptedException {
        ExternalServerConnection connection = new ExternalServerConnection();
        connection.externalServer = new ExternalServer("peer", false);
        AtomicInteger callbackCount = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);

        assertTrue(connection.registerChunkRequest("world", 8, -3, data -> {
            callbackCount.incrementAndGet();
            completed.countDown();
        }, 50));
        connection.completeChunkRequest("world", 8, -3, null);

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(1, callbackCount.get());
        assertFalse(connection.isWaitingForChunkData());
    }

    private static byte[] deflate(String data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream output = new DeflaterOutputStream(bytes)) {
            output.write(data.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
