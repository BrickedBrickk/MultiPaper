package puregero.multipaper;

import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

@Normal
class ExternalPlayerPacketFilterTest {

    @Test
    void timePacketsFromMirroredPlayersAreNotForwardedToTheRealClient() {
        assertFalse(ExternalPlayer.shouldForwardPacketToOwningServer(new ClientboundSetTimePacket(100L, Map.of())));
    }
}
