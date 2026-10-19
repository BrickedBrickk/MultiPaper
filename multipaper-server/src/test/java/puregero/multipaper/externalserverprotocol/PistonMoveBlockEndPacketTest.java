package puregero.multipaper.externalserverprotocol;

import net.minecraft.world.level.block.Blocks;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class PistonMoveBlockEndPacketTest {

    @Test
    void stickyPistonRetractionFallbackOnlyRemovesAnActualPistonHead() {
        assertTrue(PistonMoveBlockEndPacket.shouldRemoveRetractionHead(Blocks.PISTON_HEAD.defaultBlockState()));
        assertFalse(PistonMoveBlockEndPacket.shouldRemoveRetractionHead(Blocks.AIR.defaultBlockState()));
        assertFalse(PistonMoveBlockEndPacket.shouldRemoveRetractionHead(Blocks.REDSTONE_BLOCK.defaultBlockState()));
        assertFalse(PistonMoveBlockEndPacket.shouldRemoveRetractionHead(Blocks.MOVING_PISTON.defaultBlockState()));
    }
}
