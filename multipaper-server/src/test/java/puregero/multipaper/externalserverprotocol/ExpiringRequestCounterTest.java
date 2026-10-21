package puregero.multipaper.externalserverprotocol;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class ExpiringRequestCounterTest {

    @Test
    void staleExpiryCannotClearANewerRequestCount() {
        ExpiringRequestCounter<String> counter = new ExpiringRequestCounter<>();

        ExpiringRequestCounter.State first = counter.increment("peer/player");
        ExpiringRequestCounter.State second = counter.increment("peer/player");

        assertEquals(1, first.count());
        assertEquals(2, second.count());
        assertNotSame(first, second);
        assertFalse(counter.expire("peer/player", first));
        assertEquals(3, counter.increment("peer/player").count());
    }

    @Test
    void expiredBurstStartsANewCountWithoutBeingClearedByAnOldTimer() {
        ExpiringRequestCounter<String> counter = new ExpiringRequestCounter<>();

        ExpiringRequestCounter.State firstBurst = counter.increment("peer/player");
        assertTrue(counter.expire("peer/player", firstBurst));

        ExpiringRequestCounter.State nextBurst = counter.increment("peer/player");
        assertEquals(1, nextBurst.count());
        assertNotSame(firstBurst, nextBurst);
        assertFalse(counter.expire("peer/player", firstBurst));
        assertEquals(2, counter.increment("peer/player").count());
    }
}
