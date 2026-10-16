package puregero.multipaper;

import net.minecraft.stats.Stat;
import net.minecraft.world.entity.player.Player;
import puregero.multipaper.externalserverprotocol.PlayerStatsIncreasePacket;

import java.util.HashMap;

/**
 * Pool all stat increase together and only update other servers of any increases every so often
 */
public class MultiPaperStatHandler {
    private static final HashMap<Player, HashMap<Stat<?>, Integer>> statIncreases = new HashMap<>();

    public static void onStatIncrease(Player player, Stat<?> stat, int value) {
        HashMap<Stat<?>, Integer> stats = statIncreases.computeIfAbsent(player, key -> new HashMap<>());

        int newValue = (int) Math.min((long) stats.getOrDefault(stat, 0) + (long) value, 2147483647L);
        stats.put(stat, newValue);
    }

    public static void sendIncreases() {
        statIncreases.forEach((player, stats) -> {
            MultiPaper.broadcastPacketToExternalServers(new PlayerStatsIncreasePacket(player, stats));

            stats.clear();
        });
        statIncreases.clear();
    }
}
