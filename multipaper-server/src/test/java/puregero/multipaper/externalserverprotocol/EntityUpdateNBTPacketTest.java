package puregero.multipaper.externalserverprotocol;

import net.minecraft.world.entity.Entity;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class EntityUpdateNBTPacketTest {

    @Test
    void returnsTheRegisteredCandidateWhenItsAddSucceeds() {
        Entity candidate = entity("00000000-0000-0000-0000-000000000001");
        AtomicReference<Entity> lookedUp = new AtomicReference<>();
        AtomicReference<Entity> updated = new AtomicReference<>();

        Entity result = EntityUpdateNBTPacket.addEntityOrUpdateExisting(candidate, added -> true, ignored -> {
            lookedUp.set(candidate);
            return null;
        }, (existing, incoming) -> updated.set(existing));

        assertSame(candidate, result);
        assertNull(lookedUp.get(), "successful insertion should not need a fallback lookup");
        assertNull(updated.get(), "successful insertion should not reload the candidate");
    }

    @Test
    void returnsTheCanonicalEntityWhenAConcurrentAddLosesTheUuidRace() {
        Entity candidate = entity("00000000-0000-0000-0000-000000000002");
        Entity canonical = entity("00000000-0000-0000-0000-000000000002");
        AtomicReference<UUID> lookedUp = new AtomicReference<>();

        AtomicReference<Entity> updated = new AtomicReference<>();
        AtomicReference<Entity> incoming = new AtomicReference<>();
        Entity result = EntityUpdateNBTPacket.addEntityOrUpdateExisting(candidate, added -> false, uuid -> {
            lookedUp.set(uuid);
            return canonical;
        }, (existing, candidateNbt) -> {
            updated.set(existing);
            incoming.set(candidateNbt);
        });

        assertSame(canonical, result);
        assertSame(canonical, updated.get(), "incoming NBT must be applied to the canonical entity");
        assertSame(candidate, incoming.get(), "the canonical entity must be refreshed from its matching candidate, not an outer vehicle tag");
        org.junit.jupiter.api.Assertions.assertEquals(candidate.getUUID(), lookedUp.get());
    }

    @Test
    void returnsNullWhenAnEntityWasRejectedAndNoCanonicalEntityExists() {
        Entity candidate = entity("00000000-0000-0000-0000-000000000003");

        AtomicReference<Entity> updated = new AtomicReference<>();
        Entity result = EntityUpdateNBTPacket.addEntityOrUpdateExisting(candidate, added -> false, ignored -> null, (existing, incoming) -> updated.set(existing));

        assertNull(result);
        assertNull(updated.get());
    }

    @Test
    void reportsTheUnavailablePassengerUuidInsteadOfAnOuterVehicleUuid() {
        Entity passenger = entity("00000000-0000-0000-0000-000000000004");
        AtomicReference<UUID> unavailable = new AtomicReference<>();
        AtomicBoolean attemptedInsert = new AtomicBoolean();

        Entity result = EntityUpdateNBTPacket.registerEntityOrReportUnavailable(passenger, false, candidate -> {
            attemptedInsert.set(true);
            return true;
        }, ignored -> null, (existing, incoming) -> {}, unavailable::set);

        assertNull(result);
        assertFalse(attemptedInsert.get(), "an entity must not be registered before its entity chunk is available");
        assertEquals(passenger.getUUID(), unavailable.get(), "unavailable passengers must not mark their outer vehicle as unloaded");
    }

    @Test
    void insertsOrRefreshesOnlyAfterTheCandidateEntityChunkIsAvailable() {
        Entity candidate = entity("00000000-0000-0000-0000-000000000005");
        AtomicReference<Entity> added = new AtomicReference<>();
        AtomicReference<UUID> unavailable = new AtomicReference<>();

        Entity result = EntityUpdateNBTPacket.registerEntityOrReportUnavailable(candidate, true, entity -> {
            added.set(entity);
            return true;
        }, ignored -> null, (existing, incoming) -> {}, unavailable::set);

        assertSame(candidate, result);
        assertSame(candidate, added.get());
        assertNull(unavailable.get());
    }

    @Test
    void earlyUpdateCanBeRecoveredByLaterChunkLoadWithoutDuplicateUuid() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000006");
        Entity earlyCandidate = entity(uuid.toString());
        Entity chunkLoadCandidate = entity(uuid.toString());
        Entity duplicateCandidate = entity(uuid.toString());
        List<Entity> registered = new ArrayList<>();
        AtomicReference<UUID> unavailable = new AtomicReference<>();
        AtomicReference<Entity> refreshedCanonical = new AtomicReference<>();

        Entity earlyResult = EntityUpdateNBTPacket.registerEntityOrReportUnavailable(earlyCandidate, false,
                candidate -> {
                    registered.add(candidate);
                    return true;
                }, ignored -> registered.stream().filter(entity -> entity.getUUID().equals(uuid)).findFirst().orElse(null),
                (existing, incoming) -> refreshedCanonical.set(existing), unavailable::set);
        assertNull(earlyResult, "an incremental update must not register into an unloaded entity chunk");
        assertEquals(uuid, unavailable.get());
        assertTrue(registered.isEmpty(), "the early update must leave no untracked entity behind");

        Entity recovered = EntityUpdateNBTPacket.registerEntityOrReportUnavailable(chunkLoadCandidate, true,
                candidate -> {
                    if (registered.stream().anyMatch(entity -> entity.getUUID().equals(candidate.getUUID()))) return false;
                    registered.add(candidate);
                    return true;
                }, ignored -> registered.stream().filter(entity -> entity.getUUID().equals(uuid)).findFirst().orElse(null),
                (existing, incoming) -> refreshedCanonical.set(existing), unavailable::set);
        assertSame(chunkLoadCandidate, recovered, "the later full chunk image must register the entity");
        assertEquals(1, registered.size());

        Entity retried = EntityUpdateNBTPacket.registerEntityOrReportUnavailable(duplicateCandidate, true,
                candidate -> {
                    if (registered.stream().anyMatch(entity -> entity.getUUID().equals(candidate.getUUID()))) return false;
                    registered.add(candidate);
                    return true;
                }, ignored -> registered.stream().filter(entity -> entity.getUUID().equals(uuid)).findFirst().orElse(null),
                (existing, incoming) -> refreshedCanonical.set(existing), unavailable::set);
        assertSame(chunkLoadCandidate, retried, "a repeated packet must keep and refresh the canonical entity");
        assertSame(chunkLoadCandidate, refreshedCanonical.get());
        assertEquals(1, registered.size(), "recovery and a repeated update must leave exactly one entity with this UUID");
    }

    private static Entity entity(String uuid) {
        Entity entity = org.mockito.Mockito.mock(Entity.class);
        org.mockito.Mockito.when(entity.getUUID()).thenReturn(UUID.fromString(uuid));
        return entity;
    }
}
