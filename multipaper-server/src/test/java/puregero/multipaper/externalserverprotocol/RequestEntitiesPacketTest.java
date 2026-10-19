package puregero.multipaper.externalserverprotocol;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestEntitiesPacketTest {

    @Test
    void retriesOnlyWhenAChunkHolderExistsWithoutLoadedEntities() {
        NewChunkHolder holder = mock(NewChunkHolder.class);

        when(holder.getEntityChunk()).thenReturn(null);
        assertTrue(RequestEntitiesPacket.shouldRetryForEntityChunk(holder));

        when(holder.getEntityChunk()).thenReturn(mock(ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices.class));
        assertFalse(RequestEntitiesPacket.shouldRetryForEntityChunk(holder));
    }
}
