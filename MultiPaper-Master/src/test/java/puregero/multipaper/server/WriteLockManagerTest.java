package puregero.multipaper.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriteLockManagerTest {

    @Test
    void chunkReadWaitsUntilEveryOutstandingWriteHasFinished() {
        WriteLockManager manager = new WriteLockManager();
        AtomicInteger reads = new AtomicInteger();

        manager.lockUntilWrite(null, "world", 4, -7);
        manager.lockUntilWrite(null, "world", 4, -7);
        manager.waitForLock("world", 4, -7, reads::incrementAndGet);

        assertEquals(0, reads.get());

        manager.writeCompleted(null, "world", 4, -7);
        assertEquals(0, reads.get());

        manager.writeCompleted(null, "world", 4, -7);
        assertEquals(1, reads.get());
    }
}
