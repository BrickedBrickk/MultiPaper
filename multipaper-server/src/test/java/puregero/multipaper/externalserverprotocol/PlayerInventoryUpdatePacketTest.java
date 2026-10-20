package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import puregero.multipaper.PlayerSyncSession;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerInventoryUpdatePacketTest {

    @Test
    void preservesPlayerSessionIdentityInThePacketEncoding() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            java.util.UUID sessionId = java.util.UUID.randomUUID();
            PlayerInventoryUpdatePacket packet = new PlayerInventoryUpdatePacket(
                    java.util.UUID.randomUUID(), sessionId, "backend-a", "items", new CompoundTag()
            );
            packet.write(buffer);

            PlayerInventoryUpdatePacket decoded = new PlayerInventoryUpdatePacket(buffer);
            assertEquals(sessionId, decoded.getPlayerSyncSessionId());
            assertEquals("backend-a", decoded.getPlayerOwnerName());
        } finally {
            buffer.release();
        }
    }

    @Test
    void acceptsAnInventoryUpdateFromTheCurrentPlayerSession() {
        java.util.UUID sessionId = java.util.UUID.randomUUID();

        assertTrue(PlayerSyncSession.matches(sessionId, sessionId, "backend-a", "backend-a"));
    }

    @Test
    void rejectsAnInventoryUpdateFromAPreviousPlayerSession() {
        assertFalse(PlayerSyncSession.matches(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "backend-a", "backend-a"));
    }

    @Test
    void rejectsAnInventoryUpdateWithoutACurrentSessionIdentity() {
        assertFalse(PlayerSyncSession.matches(null, java.util.UUID.randomUUID(), "backend-a", "backend-a"));
    }

    @Test
    void rejectsAnInventoryUpdateFromAPreviousOwnerWithinTheSamePlayerSession() {
        java.util.UUID sessionId = java.util.UUID.randomUUID();

        assertFalse(PlayerSyncSession.matches(sessionId, sessionId, "backend-b", "backend-a"));
    }
}
