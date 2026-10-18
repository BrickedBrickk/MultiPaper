package puregero.multipaper.event.player;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Called when a player leaves an external MultiPaper instance.
 */
public class PlayerLeaveExternalServerEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerUniqueId;
    private final String playerName;
    private final String localServerName;

    /**
     * Creates an event for a player leaving this server for another MultiPaper server.
     *
     * @param playerUniqueId  the player's UUID
     * @param playerName      the player's name
     * @param localServerName the local server name
     */
    public PlayerLeaveExternalServerEvent(UUID playerUniqueId, String playerName, String localServerName) {
        super(false);
        this.playerUniqueId = playerUniqueId;
        this.playerName = playerName;
        this.localServerName = localServerName;
    }

    /**
     * Returns the name of this player.
     *
     * @return the player's name
     */
    public String getPlayerName() {
        return this.playerName;
    }

    /**
     * Returns a unique and persistent ID for this player.
     *
     * @return the player's UUID
     */
    public UUID getPlayerUniqueId() {
        return this.playerUniqueId;
    }

    /**
     * Get the bungeecord name of this server.
     *
     * @return the bungeecord name of this server
     */
    public String getLocalServerName() {
        return this.localServerName;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
