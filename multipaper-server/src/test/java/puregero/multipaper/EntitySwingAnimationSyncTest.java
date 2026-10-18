package puregero.multipaper;

import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.SwingAnimation;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Normal
class EntitySwingAnimationSyncTest {

    @Test
    void remapsEntityIdAndPreservesSwingAnimation() {
        SwingAnimation animation = new SwingAnimation(SwingAnimationType.STAB, 9);
        ClientboundSwingAnimationPacket source = new ClientboundSwingAnimationPacket(12, InteractionHand.OFF_HAND, animation);

        ClientboundSwingAnimationPacket replicated = MultiPaperEntitiesHandler.copySwingAnimationForEntity(34, source);

        assertEquals(34, replicated.entityId());
        assertEquals(InteractionHand.OFF_HAND, replicated.hand());
        assertEquals(animation, replicated.animation());
    }
}
