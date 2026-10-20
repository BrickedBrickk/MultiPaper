package puregero.multipaper;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** A per-client-session identity shared by the real player and its MultiPaper mirrors. */
public final class PlayerSyncSession {

    private static final Map<Connection, UUID> REAL_PLAYER_SESSIONS = Collections.synchronizedMap(new WeakHashMap<>());

    private PlayerSyncSession() {
    }

    public static UUID getSessionId(ServerPlayer player) {
        if (player instanceof ExternalPlayer externalPlayer) {
            return externalPlayer.playerSyncSessionId;
        }

        Connection connection = player.connection.connection;
        synchronized (REAL_PLAYER_SESSIONS) {
            return REAL_PLAYER_SESSIONS.computeIfAbsent(connection, ignored -> UUID.randomUUID());
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
