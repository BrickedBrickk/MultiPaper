package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.Unpooled;
import net.minecraft.resources.Identifier;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerChangeDimensionPacketTest {

    @Test
    void dimensionIsSerializedByRegistryKeyRatherThanBackendLocalBukkitWorldId() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUtf("minecraft:the_nether");
            var dimension = PlayerChangeDimensionPacket.readDimension(buffer);
            assertEquals(Identifier.parse("minecraft:dimension"), dimension.registry());
            assertEquals(Identifier.parse("minecraft:the_nether"), dimension.identifier());
        } finally {
            buffer.release();
        }
    }
}
