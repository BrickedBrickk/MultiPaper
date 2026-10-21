package puregero.multipaper.externalserverprotocol;

import net.minecraft.world.entity.Entity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Tracks entity removals while delayed or out-of-order replication packets may still arrive. */
final class ExpiringEntityRemovals {

    private final ConcurrentMap<UUID, Marker> removals = new ConcurrentHashMap<>();

    Marker markRemoved(UUID uuid, Entity.RemovalReason reason) {
        Marker marker = new Marker(reason);
        removals.put(uuid, marker);
        return marker;
    }

    Entity.RemovalReason getReason(UUID uuid) {
        Marker marker = removals.get(uuid);
        return marker == null ? null : marker.reason;
    }

    boolean contains(UUID uuid) {
        return removals.containsKey(uuid);
    }

    boolean expire(UUID uuid, Marker expected) {
        return removals.remove(uuid, expected);
    }

    static final class Marker {
        private final Entity.RemovalReason reason;

        private Marker(Entity.RemovalReason reason) {
            this.reason = reason;
        }
    }
}
