package puregero.multipaper.externalserverprotocol;

import net.minecraft.world.entity.Entity;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    private static Entity entity(String uuid) {
        Entity entity = org.mockito.Mockito.mock(Entity.class);
        org.mockito.Mockito.when(entity.getUUID()).thenReturn(UUID.fromString(uuid));
        return entity;
    }
}
