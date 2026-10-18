package puregero.multipaper.commands;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import puregero.multipaper.ExternalServer;
import puregero.multipaper.MultiPaper;

import java.util.HashSet;
import java.util.Iterator;

public class MPDebugCommand extends Command {
    private static final Logger LOGGER = LogManager.getLogger(MPDebugCommand.class);
    private static MPDebugCommand instance;
    private final HashSet<Player> debugEnabled = new HashSet<>();
    private int ticksUntilUpdate;

    public MPDebugCommand(String command) {
        super(command);
        setPermission("multipaper.command.mpdebug");
        instance = this;
    }

    public static void tick() {
        if (instance != null) {
            instance.tickPlayers();
        }
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, String[] args) {
        if (!testPermission(sender)) return false;

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can execute this command.");
            return false;
        }

        if (debugEnabled.remove(player)) {
            player.sendMessage("MultiPaper debug disabled");
            return true;
        }

        debugEnabled.add(player);

        sender.sendMessage("MultiPaper debug enabled");
        run(player);

        return true;
    }

    private void tickPlayers() {
        if (--ticksUntilUpdate > 0) {
            return;
        }
        ticksUntilUpdate = 5;

        Iterator<Player> iterator = debugEnabled.iterator();
        while (iterator.hasNext()) {
            Player player = iterator.next();

            if (!player.isOnline()) {
                iterator.remove();
                continue;
            }

            try {
                run(player);
            } catch (RuntimeException exception) {
                iterator.remove();
                player.sendMessage(ChatColor.RED + "MultiPaper debug stopped because it could not inspect the current chunks.");
                LOGGER.warn("Could not update MultiPaper debug overlay for {}", player.getName(), exception);
            }
        }
    }

    private void run(Player player) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(((CraftPlayer) player).getHandle());

        if (newChunkHolder != null) {
            ExternalServer owner = newChunkHolder.externalOwner;
            player.sendActionBar(Component.text(owner == null ? "null" : owner.getName()).color(owner == null ? NamedTextColor.WHITE : (owner.isMe() ? NamedTextColor.AQUA : NamedTextColor.RED)));
        }

        for (double x = -2; x <= 2; x += 0.5) {
            for (double z = -2; z <= 2; z += 0.5) {
                Vec3 vec = ((CraftPlayer) player).getHandle().position().add(x, 0.5, z);
                BlockPos pos = new BlockPos((int) vec.x, (int) vec.y, (int) vec.z);
                LevelChunk levelChunk = ((CraftWorld) player.getWorld()).getHandle().getChunkIfLoaded(pos);
                Color color;
                if (MultiPaper.isChunkExternal(levelChunk)) {
                    color = Color.RED;
                } else if (MultiPaper.isChunkLocal(levelChunk)) {
                    color = Color.AQUA;
                } else {
                    // Chunk has no owner, this shouldn't be possible if the player's right up in its face
                    color = Color.WHITE;
                }
                player.spawnParticle(Particle.DUST, vec.x, vec.y, vec.z, 1, new Particle.DustOptions(color, 1));
            }
        }
    }
}
