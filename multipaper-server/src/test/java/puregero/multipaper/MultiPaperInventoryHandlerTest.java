package puregero.multipaper;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerEquipment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

@Normal
class MultiPaperInventoryHandlerTest {

    @Test
    void standaloneInventoryChangesAreNotQueuedForServerSynchronization() {
        Inventory inventory = new Inventory(null, new PlayerEquipment(null));

        inventory.items.set(0, new ItemStack(Items.APPLE));

        assertFalse(inventory.items.isDirty);
        assertFalse(inventory.items.markDirty());
    }
}
