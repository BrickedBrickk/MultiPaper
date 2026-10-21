package puregero.multipaper.externalserverprotocol;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Tracks request bursts and lets delayed expiry remove only the exact burst it was scheduled for. */
final class ExpiringRequestCounter<K> {

    private final ConcurrentMap<K, State> states = new ConcurrentHashMap<>();

    State increment(K key) {
        return states.compute(key, (ignored, previous) -> new State(previous == null ? 1 : previous.count + 1));
    }

    boolean expire(K key, State expected) {
        return states.remove(key, expected);
    }

    static final class State {
        private final int count;

        private State(int count) {
            this.count = count;
        }

        int count() {
            return count;
        }
    }
}
