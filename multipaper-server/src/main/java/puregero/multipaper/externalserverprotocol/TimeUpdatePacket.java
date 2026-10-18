package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;

public class TimeUpdatePacket extends ExternalServerPacket {

    private final String world;
    private final long gameTime;
    private final long dayTime;
    private final boolean force;

    public TimeUpdatePacket(Level level) {
        this(level, false);
    }

    public TimeUpdatePacket(Level level, boolean force) {
        this.world = level.getWorld().getName();
        this.gameTime = level.getGameTime();
        this.dayTime = level.getDefaultClockTime();
        this.force = force;
    }

    public TimeUpdatePacket(FriendlyByteBuf in) {
        world = in.readUtf();
        gameTime = in.readLong();
        dayTime = in.readLong();
        force = in.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeLong(gameTime);
        out.writeLong(dayTime);
        out.writeBoolean(force);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld != null) {
            ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
            if (shouldUpdateTime(level.getGameTime(), gameTime, force)) {
                // We're more than a second behind, update us
                level.serverLevelData.setGameTime(gameTime);
            }
            level.dimensionType().defaultClock().ifPresent(clock -> {
                long currentClockTime = level.clockManager().getInstance(clock).totalTicks();
                if (shouldUpdateTime(currentClockTime, dayTime, force)) {
                    level.clockManager().setTotalTicks(clock, dayTime);
                }
            });
        }
    }

    public static boolean shouldUpdateTime(long localTime, long receivedTime, boolean force) {
        return force || localTime < receivedTime - 20;
    }
}
