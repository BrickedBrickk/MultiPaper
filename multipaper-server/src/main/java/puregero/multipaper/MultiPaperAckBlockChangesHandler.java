package puregero.multipaper;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

public class MultiPaperAckBlockChangesHandler {
    private final static Set<ExternalPlayer> ackBlockChangesQueue = new ObjectLinkedOpenHashSet<>(); // Linked set for faster iteration

    public static void tick() {
        if (!ackBlockChangesQueue.isEmpty()) {
            for (ExternalPlayer player : ackBlockChangesQueue) {
                player.connection.sendPendingMultiPaperBlockChangesAck();
            }
            ackBlockChangesQueue.clear();
        }
    }

    public static void onAckNeeded(ServerPlayer player) {
        if (player instanceof ExternalPlayer externalPlayer) {
            ackBlockChangesQueue.add(externalPlayer);
        }
    }
}
