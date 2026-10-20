package puregero.multipaper;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class MultiPaperContainerHandlerTest {

    @Test
    void failedExternalTransferRestoresDestinationAndReturnsOriginalSourceStack() {
        SimpleContainer destination = new SimpleContainer(1);
        destination.setItem(0, new ItemStack(Items.DIAMOND, 8));
        ItemStack originalDestination = destination.getItem(0).copy();
        ItemStack originalSource = new ItemStack(Items.DIAMOND, 5);

        destination.getItem(0).grow(5);
        ItemStack returnedSource = MultiPaperContainerHandler.rollbackFailedExternalTransfer(
                destination, 0, originalDestination, originalSource
        );

        assertEquals(8, destination.getItem(0).getCount());
        assertEquals(5, returnedSource.getCount());
    }

    @Test
    void externalAdditionUsesRequestedSlotAndMergesOnlyMatchingItems() {
        SimpleContainer destination = new SimpleContainer(2);
        destination.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        ItemStack addition = new ItemStack(Items.DIAMOND, 4);

        assertTrue(MultiPaperContainerHandler.addItemToSlot(destination, 1, addition));
        assertEquals(3, destination.getItem(0).getCount());
        assertEquals(4, destination.getItem(1).getCount());
        assertTrue(MultiPaperContainerHandler.addItemToSlot(destination, 1, new ItemStack(Items.DIAMOND, 2)));
        assertEquals(6, destination.getItem(1).getCount());
        assertFalse(MultiPaperContainerHandler.addItemToSlot(destination, 1, new ItemStack(Items.EMERALD, 1)));
        assertEquals(6, destination.getItem(1).getCount());
    }
}
