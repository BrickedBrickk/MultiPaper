package puregero.multipaper;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.WeatherData;
import puregero.multipaper.externalserverprotocol.WeatherUpdatePacket;

public class MultiPaperRainHandler {
    private static boolean updatingWeather = false;

    public static void onWeatherChange(WeatherData levelData, String world) {
        if (!updatingWeather) {
            // Run after all rain parameters have been set
            MultiPaper.runSync(() -> MultiPaper.broadcastPacketToExternalServers(new WeatherUpdatePacket(world, levelData)));
        }
    }

    public static void handle(ServerLevel level, boolean raining, boolean thundering, int clearWeatherTime, int rainingTime, int thunderingTime) {
        updatingWeather = true;
        WeatherData levelData = level.getWeatherData();
        levelData.setRaining(raining, org.bukkit.event.weather.WeatherChangeEvent.Cause.UNKNOWN);
        levelData.setThundering(thundering, org.bukkit.event.weather.ThunderChangeEvent.Cause.UNKNOWN);
        levelData.setClearWeatherTime(clearWeatherTime);
        levelData.setRainTime(rainingTime);
        levelData.setThunderTime(thunderingTime);
        updatingWeather = false;
    }
}
