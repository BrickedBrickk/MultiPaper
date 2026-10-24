package puregero.multipaper.server.handlers;

import org.junit.jupiter.api.Test;
import se.llbit.nbt.CompoundTag;
import se.llbit.nbt.IntArrayTag;
import se.llbit.nbt.ListTag;
import se.llbit.nbt.SpecificTag;
import se.llbit.nbt.StringTag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WriteChunkHandlerTest {

    @Test
    void transientSnapshotDeduplicatesItsCurrentEntityRecords() {
        UUID duplicate = UUID.randomUUID();
        CompoundTag transientSave = entityChunk(entity(duplicate, "current"), entity(UUID.randomUUID(), "new"));

        CompoundTag merged = WriteChunkHandler.deduplicate(transientSave);

        List<SpecificTag> entities = merged.get("Entities").asList().items;
        assertEquals(2, entities.size());
        assertEquals("current", state(entities.get(0)));
        assertEquals("new", state(entities.get(1)));
        assertFalse(hasDuplicateUuids(entities));
    }

    @Test
    void transientSnapshotDeduplicatesRepeatedRecords() {
        UUID duplicate = UUID.randomUUID();
        CompoundTag transientSave = entityChunk(entity(UUID.randomUUID(), "new"));
        transientSave.get("Entities").asList().add(entity(duplicate, "first"));
        transientSave.get("Entities").asList().add(entity(duplicate, "second"));

        CompoundTag merged = WriteChunkHandler.deduplicate(transientSave);

        List<SpecificTag> entities = merged.get("Entities").asList().items;
        assertEquals(2, entities.size());
        assertEquals("new", state(entities.get(0)));
        assertEquals("first", state(entities.get(1)));
        assertFalse(hasDuplicateUuids(entities));
    }

    private static boolean hasDuplicateUuids(List<SpecificTag> entities) {
        return entities.stream().map(WriteChunkHandlerTest::uuid).distinct().count() != entities.size();
    }

    private static UUID uuid(SpecificTag tag) {
        int[] value = tag.asCompound().get("UUID").intArray();
        long most = ((long) value[0] << 32) | (value[1] & 0xffffffffL);
        long least = ((long) value[2] << 32) | (value[3] & 0xffffffffL);
        return new UUID(most, least);
    }

    private static String state(SpecificTag tag) {
        return tag.asCompound().get("state").stringValue();
    }

    private static CompoundTag entityChunk(CompoundTag... entities) {
        return compoundWithEntities(entities);
    }

    private static CompoundTag compoundWithEntities(CompoundTag... entities) {
        List<SpecificTag> list = new ArrayList<>(List.of(entities));
        CompoundTag chunk = new CompoundTag();
        chunk.add("Entities", new ListTag((byte) 10, list));
        return chunk;
    }

    private static CompoundTag entity(UUID uuid, String state) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        CompoundTag entity = new CompoundTag();
        entity.add("UUID", new IntArrayTag(new int[]{(int) (most >>> 32), (int) most, (int) (least >>> 32), (int) least}));
        entity.add("state", new StringTag(state));
        return entity;
    }
}
