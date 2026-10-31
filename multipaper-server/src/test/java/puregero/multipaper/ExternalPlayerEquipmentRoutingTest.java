package puregero.multipaper;

import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ChunkMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExternalPlayerEquipmentRoutingTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void routesRemoteMirrorEquipmentToItsLocalTrackingPlayers() {
        ClientboundSetEquipmentPacket packet = mock(ClientboundSetEquipmentPacket.class);
        ChunkMap.TrackedEntity trackedEntity = mock(ChunkMap.TrackedEntity.class);

        ExternalPlayer.routeEquipmentUpdateToLocalViewers(packet, trackedEntity);

        verify(trackedEntity).sendToTrackingPlayers(packet);
    }

    @Test
    void toleratesEquipmentUpdateBeforeTheMirrorStartsTracking() {
        assertDoesNotThrow(() -> ExternalPlayer.routeEquipmentUpdateToLocalViewers(
                mock(ClientboundSetEquipmentPacket.class), null));
    }
}
