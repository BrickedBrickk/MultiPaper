package puregero.multipaper;

import net.minecraft.server.level.ServerPlayer;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** A per-client-session identity shared by the real player and its MultiPaper mirrors. */
public final class PlayerSyncSession {

    private static final Map<ServerPlayer, UUID> REAL_PLAYER_SESSIONS = Collections.synchronizedMap(new WeakHashMap<>());

    private PlayerSyncSession() {
    }

    public static UUID getSessionId(ServerPlayer player) {
        if (player instanceof ExternalPlayer externalPlayer) {
            return externalPlayer.playerSyncSessionId;
        }

        synchronized (REAL_PLAYER_SESSIONS) {
            // Inventory changes can be flushed at the end of a tick after a
            // player has disconnected and ServerPlayer.connection was cleared.
            // Keep the session identity on the player object so those queued
            // updates cannot crash the backend or be attributed to a later join.
            return REAL_PLAYER_SESSIONS.computeIfAbsent(player, ignored -> UUID.randomUUID());
        }
    }

    public static String getOwnerName(ServerPlayer player) {
        if (player instanceof ExternalPlayer externalPlayer) {
            return externalPlayer.playerOwnerName;
        }
        return MultiPaperConfiguration.get().masterConnection.myName;
    }

    public static boolean matches(UUID currentSessionId, UUID packetSessionId, String currentOwnerName, String packetOwnerName) {
        return currentSessionId != null && currentSessionId.equals(packetSessionId)
                && currentOwnerName != null && currentOwnerName.equals(packetOwnerName);
    }
}
