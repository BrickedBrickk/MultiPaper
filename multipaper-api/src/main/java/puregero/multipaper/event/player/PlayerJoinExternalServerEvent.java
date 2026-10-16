package puregero.multipaper.event.player;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Called when a player joins an external MultiPaper instance.
 */
public class PlayerJoinExternalServerEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    private final UUID playerUniqueId;
    private final String playerName;
    private final String localServerName;


    public PlayerJoinExternalServerEvent(UUID playerUniqueId, String playerName, String localeServerName) {
        super(false);
        this.playerUniqueId = playerUniqueId;
        this.playerName = playerName;
        this.localServerName = localeServerName;
    }

    /**
     * Returns the name of this player
     */
    public String getPlayerName() {
        return this.playerName;
    }

    /**
     * Returns a unique and persistent id for this entity
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
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
