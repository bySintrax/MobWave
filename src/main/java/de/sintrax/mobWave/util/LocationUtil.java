package de.sintrax.mobWave.util;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public class LocationUtil {

    public static void saveLocation(FileConfiguration config, String path, Location loc) {
        config.set(path + ".world", loc.getWorld().getName());
        config.set(path + ".x", loc.getX());
        config.set(path + ".y", loc.getY());
        config.set(path + ".z", loc.getZ());
        config.set(path + ".yaw", loc.getYaw());
        config.set(path + ".pitch", loc.getPitch());
    }

    public static Location loadLocation(ConfigurationSection section) {
        if (section == null) return null;
        String worldName = section.getString("world");
        if (worldName == null) return null;
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) return null;
        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");
        float yaw = (float) section.getDouble("yaw");
        float pitch = (float) section.getDouble("pitch");
        return new Location(world, x, y, z, yaw, pitch);
    }
}
