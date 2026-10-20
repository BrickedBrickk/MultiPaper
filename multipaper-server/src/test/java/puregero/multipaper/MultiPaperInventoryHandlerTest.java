package puregero.multipaper;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerEquipment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class MultiPaperInventoryHandlerTest {

    @Test
    void standaloneInventoryChangesAreNotQueuedForServerSynchronization() {
        Inventory inventory = new Inventory(null, new PlayerEquipment(null));

        inventory.items.set(0, new ItemStack(Items.APPLE));

        assertFalse(inventory.items.isDirty);
        assertFalse(inventory.items.markDirty());
    }

    @Test
    void staleInventoryCompensationRemovesItemsFromPlayerInventory() {
        Inventory inventory = new Inventory(null, new PlayerEquipment(null));
        inventory.items.set(0, new ItemStack(Items.DIAMOND, 5));
        ItemStack duplicateToRemove = new ItemStack(Items.DIAMOND, 3);

        MultiPaperInventoryHandler.removeFromInventory("test", duplicateToRemove, inventory.compartments);

        assertEquals(2, inventory.items.get(0).getCount());
        assertTrue(duplicateToRemove.isEmpty());
    }

    @Test
    void inventoryUpdateSuppressionIsRestoredWhenMutationThrows() {
        assertFalse(MultiPaperInventoryHandler.updatingInventory);

        assertThrows(IllegalStateException.class, () -> MultiPaperInventoryHandler.runWithInventoryUpdatesSuppressed(() -> {
            assertTrue(MultiPaperInventoryHandler.updatingInventory);
            throw new IllegalStateException("simulated failed inventory load");
        }));

        assertFalse(MultiPaperInventoryHandler.updatingInventory);
    }

    @Test
    void nestedInventoryUpdateSuppressionRestoresTheOuterState() {
        MultiPaperInventoryHandler.updatingInventory = true;
        try {
            MultiPaperInventoryHandler.runWithInventoryUpdatesSuppressed(() -> {
                assertTrue(MultiPaperInventoryHandler.updatingInventory);
                MultiPaperInventoryHandler.runWithInventoryUpdatesSuppressed(() -> assertTrue(MultiPaperInventoryHandler.updatingInventory));
                assertTrue(MultiPaperInventoryHandler.updatingInventory);
            });

            assertTrue(MultiPaperInventoryHandler.updatingInventory);
        } finally {
            MultiPaperInventoryHandler.updatingInventory = false;
        }
    }
}
