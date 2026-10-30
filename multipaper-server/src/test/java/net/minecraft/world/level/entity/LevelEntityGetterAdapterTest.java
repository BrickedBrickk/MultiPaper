package net.minecraft.world.level.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LevelEntityGetterAdapterTest {

    @Test
    void entityTypeQueryReturnsVisibleEntities() {
        EntityLookup<EntityAccess> visibleEntities = new EntityLookup<>();
        TestEntity expected = new TestEntity(17, UUID.randomUUID());
        visibleEntities.add(expected);
        LevelEntityGetterAdapter<EntityAccess> adapter = new LevelEntityGetterAdapter<>(visibleEntities, null);
        List<TestEntity> actual = new ArrayList<>();

        adapter.get(EntityTypeTest.forClass(TestEntity.class), AbortableIterationConsumer.forConsumer(actual::add));

        assertEquals(List.of(expected), actual);
    }

    private record TestEntity(int getId, UUID getUUID) implements EntityAccess {
        @Override
        public BlockPos blockPosition() {
            return BlockPos.ZERO;
        }

        @Override
        public AABB getBoundingBox() {
            return new AABB(0, 0, 0, 1, 1, 1);
        }

        @Override
        public void setLevelCallback(EntityInLevelCallback callback) {
        }

        @Override
        public Stream<? extends EntityAccess> getSelfAndPassengers() {
            return Stream.of(this);
        }

        @Override
        public Stream<? extends EntityAccess> getPassengersAndSelf() {
            return Stream.of(this);
        }

        @Override
        public boolean isRemoved() {
            return false;
        }

        @Override
        public void setRemoved(Entity.RemovalReason removalReason, EntityRemoveEvent.Cause eventCause) {
        }

        @Override
        public boolean shouldBeSaved() {
            return true;
        }

        @Override
        public boolean isAlwaysTicking() {
            return false;
        }
    }
}
