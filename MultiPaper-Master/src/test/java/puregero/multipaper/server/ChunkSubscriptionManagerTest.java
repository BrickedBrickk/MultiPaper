package puregero.multipaper.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkSubscriptionManagerTest {

    @Test
    void classifiesWritesFromAnotherActiveOwner() {
        assertEquals(ChunkSubscriptionManager.ChunkWriteRejection.ACTIVE_OWNER_MISMATCH,
                ChunkSubscriptionManager.classifyChunkWrite(true, false, true));
    }

    @Test
    void classifiesWritesFromFormerOwnerWhenNoOwnerIsActive() {
        assertEquals(ChunkSubscriptionManager.ChunkWriteRejection.NO_ACTIVE_OWNER_PREVIOUS_OWNER_RECORDED,
                ChunkSubscriptionManager.classifyChunkWrite(false, false, true));
    }

    @Test
    void allowsCurrentOwnerAndFirstWriteBootstrap() {
        assertEquals(ChunkSubscriptionManager.ChunkWriteRejection.NONE,
                ChunkSubscriptionManager.classifyChunkWrite(true, true, true));
        assertEquals(ChunkSubscriptionManager.ChunkWriteRejection.NONE,
                ChunkSubscriptionManager.classifyChunkWrite(false, false, false));
    }
}
