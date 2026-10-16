package puregero.multipaper;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiPaperItemStackSerializationTest {

    @Test
    void serializesEmptyStacksAsAnEmptyCompound() {
        assertTrue(MultiPaper.itemStackToNBT(ItemStack.EMPTY).isEmpty());
    }

    @Test
    void readsEmptyInventorySlotsWithoutInvokingTheItemCodec() {
        assertSame(ItemStack.EMPTY, MultiPaper.itemStackFromNBT(new CompoundTag()));

        CompoundTag slotOnly = new CompoundTag();
        slotOnly.putInt("Slot", 3);
        assertSame(ItemStack.EMPTY, MultiPaper.itemStackFromNBT(slotOnly));
    }
}
