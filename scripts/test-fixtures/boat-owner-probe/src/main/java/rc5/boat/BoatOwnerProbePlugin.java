package rc5.boat;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Wolf;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class BoatOwnerProbePlugin extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("rc5leashprobe")) {
            return handleLeashProbe(sender, args);
        }
        if (args.length != 2) return false;
        try {
            Class<?> multiPaper = Class.forName("puregero.multipaper.MultiPaper");
            Method getHolder = multiPaper.getMethod("getChunkHolder", String.class, int.class, int.class);
            Object holder = getHolder.invoke(null, getServer().getWorlds().get(0).getName(),
                    Integer.parseInt(args[0]), Integer.parseInt(args[1]));
            Object owner = holder == null ? null : holder.getClass().getField("externalOwner").get(holder);
            String name = owner == null ? "UNOWNED" : (String) owner.getClass().getMethod("getName").invoke(owner);
            sender.sendMessage("RC5_BOAT_OWNER=" + name);
        } catch (ReflectiveOperationException | NumberFormatException failure) {
            sender.sendMessage("RC5_BOAT_OWNER_ERROR=" + failure);
            getLogger().severe("Could not inspect MultiPaper chunk owner: " + failure);
        }
        return true;
    }

    private boolean handleLeashProbe(CommandSender sender, String[] args) {
        if (args.length != 1) return false;
        World world = getServer().getWorlds().get(0);
        if (args[0].equalsIgnoreCase("setup")) {
            Location fence = new Location(world, 84, 80, 84);
            world.getBlockAt(fence).setType(Material.OAK_FENCE);
            LeashHitch hitch = (LeashHitch) world.spawnEntity(new Location(world, 84.5, 80.5, 84.5), EntityType.LEASH_KNOT);
            Wolf wolf = (Wolf) world.spawnEntity(new Location(world, 82.5, 80, 82.5), EntityType.WOLF);
            wolf.setAI(false);
            boolean attached = wolf.setLeashHolder(hitch);
            sender.sendMessage("RC5_LEASH_SETUP wolf=" + wolf.getUniqueId() + " holder=" + hitch.getUniqueId() + " attached=" + attached);
            return true;
        }
        if (args[0].equalsIgnoreCase("state")) {
            Wolf wolf = world.getEntitiesByClass(Wolf.class).stream()
                    .filter(candidate -> candidate.getLocation().distanceSquared(new Location(world, 82.5, 80, 82.5)) < 16)
                    .findFirst().orElse(null);
            if (wolf == null) {
                sender.sendMessage("RC5_LEASH_STATE missing");
                return true;
            }
            Entity holder = wolf.getLeashHolder();
            sender.sendMessage("RC5_LEASH_STATE wolf=" + wolf.getUniqueId() + " leashed=" + wolf.isLeashed()
                    + " holder=" + (holder == null ? "none" : holder.getUniqueId())
                    + " holderType=" + (holder == null ? "none" : holder.getType())
                    + " knots=" + String.join(";", world.getEntitiesByClass(LeashHitch.class).stream()
                        .map(knot -> knot.getUniqueId() + "@" + knot.getLocation().getBlockX() + ","
                                + knot.getLocation().getBlockY() + "," + knot.getLocation().getBlockZ())
                        .toList()));
            return true;
        }
        return false;
    }
}
