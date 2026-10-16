package puregero.multipaper;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

public class MultiPaperInactiveTracker {
    private final ServerLevel level;
    private final Set<Entity> entitiesToSendChangesThisTick = new LinkedHashSet<>();

    public MultiPaperInactiveTracker(ServerLevel level) {
        this.level = level;
    }

    public void track(Entity entity) {
        if (MultiPaperConfiguration.get().optimizations.ticksPerInactiveEntityTracking > 1
            && ((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity) entity).moonrise$getTrackedEntity() != null) {
            entity.sendChangesThisTick = true;
            entitiesToSendChangesThisTick.add(entity);
        }
    }

    public boolean tick() {
        int tickCount = level.getServer().getTickCount();
        if (MultiPaperConfiguration.get().optimizations.ticksPerInactiveEntityTracking > 1 && tickCount % MultiPaperConfiguration.get().optimizations.ticksPerInactiveEntityTracking != 0) {
            Iterator<Entity> iterator = entitiesToSendChangesThisTick.iterator();
            while (iterator.hasNext()) {
                Entity entity = iterator.next();
                var trackedEntity = ((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity) entity).moonrise$getTrackedEntity();
                if (trackedEntity == null) {
                    entity.sendChangesThisTick = false;
                    iterator.remove();
                    continue;
                }

                var chunkSystemEntity = (ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity) entity;
                var chunkData = chunkSystemEntity.moonrise$getChunkData();
                ((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerTrackedEntity) trackedEntity).moonrise$tick(chunkData.nearbyPlayers);
                if (((ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerTrackedEntity) trackedEntity).moonrise$hasPlayers()
                    || chunkSystemEntity.moonrise$getChunkStatus().isOrAfter(net.minecraft.server.level.FullChunkStatus.ENTITY_TICKING)) {
                    trackedEntity.serverEntity.sendChanges();
                }
                entity.sendChangesThisTick = false;
                iterator.remove();
            }
            return true;
        }

        return false;
    }
}
