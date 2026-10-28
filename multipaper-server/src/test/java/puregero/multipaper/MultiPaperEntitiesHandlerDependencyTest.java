package puregero.multipaper;

import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.vehicle.boat.Boat;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Normal
class MultiPaperEntitiesHandlerDependencyTest {

    @Test
    void ignoresMalformedLeashDependencyForBoatWithoutCasting() {
        Boat boat = mock(Boat.class);
        ClientboundSetEntityLinkPacket packet = mock(ClientboundSetEntityLinkPacket.class);

        assertDoesNotThrow(() -> MultiPaperEntitiesHandler.handleEntityWithDependenicesUpdate(
                boat, new Entity[0], packet));
    }

    @Test
    void stillAppliesPassengerDependenciesToBoat() {
        Boat boat = mock(Boat.class);
        Entity passenger = mock(Entity.class);
        when(boat.getPassengers()).thenReturn(List.of());

        assertDoesNotThrow(() -> MultiPaperEntitiesHandler.handleEntityWithDependenicesUpdate(
                boat, new Entity[]{passenger}, mock(ClientboundSetPassengersPacket.class)));

        verify(passenger).startRiding(boat);
    }

    @Test
    void stillAppliesAndClearsLeashDependenciesForMobs() {
        Mob mob = mock(Mob.class);
        Entity holder = mock(Entity.class);

        MultiPaperEntitiesHandler.handleEntityWithDependenicesUpdate(
                mob, new Entity[]{holder}, mock(ClientboundSetEntityLinkPacket.class));
        MultiPaperEntitiesHandler.handleEntityWithDependenicesUpdate(
                mob, new Entity[0], mock(ClientboundSetEntityLinkPacket.class));

        verify(mob).setLeashedTo(holder, true);
        verify(mob).setLeashedTo(null, true);
    }
}
