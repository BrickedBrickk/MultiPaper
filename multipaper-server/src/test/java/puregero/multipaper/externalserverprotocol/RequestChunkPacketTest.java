package puregero.multipaper.externalserverprotocol;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class RequestChunkPacketTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void unwrapsImposterProtoChunkBeforeSerialization() {
        ServerLevel level = mock(ServerLevel.class);
        ImposterProtoChunk imposter = mock(ImposterProtoChunk.class);
        LevelChunk wrapped = mock(LevelChunk.class);
        SerializableChunkData serialized = mock(SerializableChunkData.class);
        CompoundTag expected = new CompoundTag();
        when(imposter.getWrapped()).thenReturn(wrapped);
        when(serialized.write()).thenReturn(expected);

        try (MockedStatic<SerializableChunkData> serialization = mockStatic(SerializableChunkData.class)) {
            serialization.when(() -> SerializableChunkData.copyOf(level, wrapped)).thenReturn(serialized);

            assertSame(expected, RequestChunkPacket.serializeChunk(level, imposter));

            serialization.verify(() -> SerializableChunkData.copyOf(level, wrapped));
        }
    }

    @Test
    void keepsRegularChunkForSerialization() {
        ServerLevel level = mock(ServerLevel.class);
        ChunkAccess chunk = mock(ChunkAccess.class);
        SerializableChunkData serialized = mock(SerializableChunkData.class);
        CompoundTag expected = new CompoundTag();
        when(serialized.write()).thenReturn(expected);

        try (MockedStatic<SerializableChunkData> serialization = mockStatic(SerializableChunkData.class)) {
            serialization.when(() -> SerializableChunkData.copyOf(level, chunk)).thenReturn(serialized);

            assertSame(expected, RequestChunkPacket.serializeChunk(level, chunk));

            serialization.verify(() -> SerializableChunkData.copyOf(level, chunk));
        }
    }

    @Test
    void incompleteLightingTimesOutWithoutMutatingSharedFuture() throws InterruptedException {
        CompletableFuture<Void> lightFuture = new CompletableFuture<>();
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger readyCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        RequestChunkPacket.awaitLighting(lightFuture, 25, readyCount::incrementAndGet, throwable -> {
            if (throwable instanceof TimeoutException || throwable.getCause() instanceof TimeoutException) {
                failureCount.incrementAndGet();
            }
            failed.countDown();
        });

        assertTrue(failed.await(2, TimeUnit.SECONDS));
        assertFalse(lightFuture.isDone(), "Waiting for one peer request must not time out the shared lighting future");
        assertEquals(0, readyCount.get());
        assertEquals(1, failureCount.get());
    }
}
