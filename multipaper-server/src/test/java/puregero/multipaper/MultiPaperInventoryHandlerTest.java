package puregero.multipaper;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerEquipment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

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
    void repeatedRandomRemovalConservesExactItemCountsAcrossPlayerCompartments() {
        Random random = new Random(0x4d756c7469506170L);

        for (int iteration = 0; iteration < 256; iteration++) {
            Inventory inventory = new Inventory(null, new PlayerEquipment(null));
            ItemStack diamonds = new ItemStack(Items.DIAMOND);
            int diamondCount = 0;
            int emeraldCount = 0;

            for (List<ItemStack> compartment : inventory.compartments) {
                for (int slot = 0; slot < compartment.size(); slot++) {
                    int roll = random.nextInt(5);
                    if (roll < 2) {
                        int count = random.nextInt(1, 65);
                        compartment.set(slot, new ItemStack(Items.DIAMOND, count));
                        diamondCount += count;
                    } else if (roll == 2) {
                        int count = random.nextInt(1, 65);
                        compartment.set(slot, new ItemStack(Items.EMERALD, count));
                        emeraldCount += count;
                    }
                }
            }

            if (diamondCount == 0) continue;

            int removalCount = random.nextInt(1, Math.min(diamondCount, 64) + 1);
            ItemStack removal = new ItemStack(Items.DIAMOND, removalCount);
            MultiPaperInventoryHandler.removeFromInventory("randomized-test", removal, inventory.compartments);

            assertTrue(removal.isEmpty(), "Iteration " + iteration + " must fully consume the requested removal");
            assertEquals(diamondCount - removalCount, countMatching(inventory.compartments, diamonds),
                    "Iteration " + iteration + " must remove exactly the requested number of diamonds");
            assertEquals(emeraldCount, countMatching(inventory.compartments, new ItemStack(Items.EMERALD)),
                    "Iteration " + iteration + " must leave unrelated items untouched");
        }
    }

    @Test
    void staleReplacementRequiresTheFullSourceStackBeforeItCanBeReconciled() {
        Inventory inventory = new Inventory(null, new PlayerEquipment(null));
        inventory.items.set(0, new ItemStack(Items.DIAMOND, 4));
        ItemStack expectedSource = new ItemStack(Items.DIAMOND, 8);

        assertFalse(MultiPaperInventoryHandler.hasPlayerOwnedItems(expectedSource, ItemStack.EMPTY, inventory.compartments));
        assertEquals(4, inventory.items.get(0).getCount(), "A failed preflight must not partially consume the remaining source stack");

        inventory.items.set(1, new ItemStack(Items.DIAMOND, 4));
        assertTrue(MultiPaperInventoryHandler.hasPlayerOwnedItems(expectedSource, ItemStack.EMPTY, inventory.compartments),
                "The expected source can be reconciled when all of it remains across player-owned slots");
    }

    @Test
    void staleReplacementDoesNotCountDifferentComponentsAsItsSource() {
        Inventory inventory = new Inventory(null, new PlayerEquipment(null));
        ItemStack damaged = new ItemStack(Items.DIAMOND_PICKAXE);
        damaged.setDamageValue(20);
        inventory.items.set(0, damaged);
        ItemStack undamagedSource = new ItemStack(Items.DIAMOND_PICKAXE);

        assertFalse(MultiPaperInventoryHandler.hasPlayerOwnedItems(undamagedSource, ItemStack.EMPTY, inventory.compartments));
        assertEquals(20, inventory.items.get(0).getDamageValue());
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

    private static int countMatching(List<? extends List<ItemStack>> compartments, ItemStack target) {
        int count = 0;
        for (List<ItemStack> compartment : compartments) {
            for (ItemStack stack : compartment) {
                if (ItemStack.isSameItemSameComponents(stack, target)) count += stack.getCount();
            }
        }
        return count;
    }
}
