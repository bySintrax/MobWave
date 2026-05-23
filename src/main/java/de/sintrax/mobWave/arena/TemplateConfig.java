package de.sintrax.mobWave.arena;

import de.sintrax.mobWave.MobWave;
import de.sintrax.mobWave.util.LocationUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Konfiguration für die Template-Welt.
 * Koordinaten für fightSpawn, mobSpawn, equipSpawn etc. werden ohne Weltreferenz
 * gespeichert (world = null) und erst beim Erstellen einer Instanz-Welt mit der
 * konkreten Welt verknüpft.
 * Der lobbySpawn hingegen liegt in einer echten, dauerhaft geladenen Welt.
 */
public class TemplateConfig {

    private final MobWave plugin;
    private final File configFile;

    private String templateWorldName = "";
    private int playersPerInstance = 4;

    // Lobby in einer dauerhaft geladenen Welt (z.B. Hub)
    private Location lobbySpawn;
    private String lobbySpawnWorldName; // Fallback: Weltname falls Welt beim Start noch nicht geladen war

    // Template-Koordinaten (world = null, werden bei Instanzerstellung gefüllt)
    private Location fightSpawn;
    private Location mobSpawn;
    private Location equipSpawn;
    private Location fightRegionMin;
    private Location fightRegionMax;
    private Location equipRegionMin;
    private Location equipRegionMax;
    private Location shopNpcLocation;
    private final List<Location> lootChestLocations = new ArrayList<>();

    public TemplateConfig(MobWave plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "template.yml");
        load();
    }

    // ─── Persistenz ────────────────────────────────────────────────────────

    public void load() {
        if (!configFile.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        templateWorldName = config.getString("templateWorldName", "");
        playersPerInstance = config.getInt("playersPerInstance", 4);

        // Lobby-Spawn mit echter Weltreferenz (lazy-resolve falls Welt noch nicht geladen)
        if (config.contains("lobbySpawn")) {
            lobbySpawnWorldName = config.getString("lobbySpawn.world");
            lobbySpawn = LocationUtil.loadLocation(config.getConfigurationSection("lobbySpawn"));
            if (lobbySpawn == null && lobbySpawnWorldName != null) {
                // Welt noch nicht verfügbar – Koordinaten als Rohwert speichern, world = null
                lobbySpawn = loadRawLoc(config.getConfigurationSection("lobbySpawn"));
                plugin.getLogger().warning("[TemplateConfig] Lobby-Welt '" + lobbySpawnWorldName + "' noch nicht geladen, wird später aufgelöst.");
            }
        }

        // Template-Koordinaten (kein World-Lookup nötig)
        fightSpawn    = loadRawLoc(config.getConfigurationSection("fightSpawn"));
        mobSpawn      = loadRawLoc(config.getConfigurationSection("mobSpawn"));
        equipSpawn    = loadRawLoc(config.getConfigurationSection("equipSpawn"));
        fightRegionMin = loadRawLoc(config.getConfigurationSection("fightRegionMin"));
        fightRegionMax = loadRawLoc(config.getConfigurationSection("fightRegionMax"));
        equipRegionMin = loadRawLoc(config.getConfigurationSection("equipRegionMin"));
        equipRegionMax = loadRawLoc(config.getConfigurationSection("equipRegionMax"));
        shopNpcLocation = loadRawLoc(config.getConfigurationSection("shopNpc"));

        lootChestLocations.clear();
        List<? extends ConfigurationSection> chestSections = config.getMapList("lootChests")
                .stream()
                .map(m -> {
                    YamlConfiguration tmp = new YamlConfiguration();
                    tmp.createSection("c", (java.util.Map<String, Object>) m);
                    return tmp.getConfigurationSection("c");
                })
                .toList();
        for (ConfigurationSection s : chestSections) {
            Location loc = loadRawLoc(s);
            if (loc != null) lootChestLocations.add(loc);
        }
    }

    public void save() {
        FileConfiguration config = new YamlConfiguration();
        config.set("templateWorldName", templateWorldName);
        config.set("playersPerInstance", playersPerInstance);

        if (lobbySpawn != null) {
            LocationUtil.saveLocation(config, "lobbySpawn", lobbySpawn);
        }
        saveRawLoc(config, "fightSpawn", fightSpawn);
        saveRawLoc(config, "mobSpawn", mobSpawn);
        saveRawLoc(config, "equipSpawn", equipSpawn);
        saveRawLoc(config, "fightRegionMin", fightRegionMin);
        saveRawLoc(config, "fightRegionMax", fightRegionMax);
        saveRawLoc(config, "equipRegionMin", equipRegionMin);
        saveRawLoc(config, "equipRegionMax", equipRegionMax);
        saveRawLoc(config, "shopNpc", shopNpcLocation);

        List<java.util.Map<String, Object>> chestList = new ArrayList<>();
        for (Location loc : lootChestLocations) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("x", loc.getX());
            m.put("y", loc.getY());
            m.put("z", loc.getZ());
            chestList.add(m);
        }
        config.set("lootChests", chestList);

        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Fehler beim Speichern von template.yml", e);
        }
    }

    // ─── Hilfs-Methoden ─────────────────────────────────────────────────────

    private void saveRawLoc(FileConfiguration config, String path, Location loc) {
        if (loc == null) return;
        config.set(path + ".x", loc.getX());
        config.set(path + ".y", loc.getY());
        config.set(path + ".z", loc.getZ());
        config.set(path + ".yaw", (double) loc.getYaw());
        config.set(path + ".pitch", (double) loc.getPitch());
    }

    private Location loadRawLoc(ConfigurationSection section) {
        if (section == null) return null;
        // world = null intentional – wird beim Instanz-Aufbau durch toInstance() ersetzt
        return new Location(
                null,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch")
        );
    }

    /**
     * Setzt die Welt einer Template-Koordinate auf die Instanz-Welt.
     */
    public Location toInstance(Location raw, World instanceWorld) {
        if (raw == null) return null;
        return new Location(instanceWorld, raw.getX(), raw.getY(), raw.getZ(), raw.getYaw(), raw.getPitch());
    }

    /**
     * Erstellt eine PlayerArena für eine konkrete Instanz-Welt.
     * Alle Koordinaten stammen aus dem Template, die Welt wird ersetzt.
     */
    public PlayerArena buildArenaForWorld(World world) {
        PlayerArena pa = new PlayerArena(0);
        pa.setFightSpawn(toInstance(fightSpawn, world));
        pa.setMobSpawn(toInstance(mobSpawn, world));
        pa.setEquipSpawn(toInstance(equipSpawn, world));
        pa.setFightRegionMin(toInstance(fightRegionMin, world));
        pa.setFightRegionMax(toInstance(fightRegionMax, world));
        pa.setEquipRegionMin(toInstance(equipRegionMin, world));
        pa.setEquipRegionMax(toInstance(equipRegionMax, world));
        pa.setShopNpcLocation(toInstance(shopNpcLocation, world));
        for (Location chest : lootChestLocations) {
            pa.addLootChest(toInstance(chest, world));
        }
        return pa;
    }

    public boolean isConfigured() {
        return !templateWorldName.isEmpty()
                && fightSpawn != null
                && equipSpawn != null
                && !lootChestLocations.isEmpty();
    }

    // ─── Getters / Setters ──────────────────────────────────────────────────

    public String getTemplateWorldName() { return templateWorldName; }
    public void setTemplateWorldName(String name) { this.templateWorldName = name; }

    public int getPlayersPerInstance() { return playersPerInstance; }
    public void setPlayersPerInstance(int count) { this.playersPerInstance = count; }

    public Location getLobbySpawn() {
        // Lazy-resolve: Welt war beim Start nicht geladen, jetzt erneut versuchen
        if (lobbySpawn != null && lobbySpawn.getWorld() == null && lobbySpawnWorldName != null) {
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(lobbySpawnWorldName);
            if (world != null) {
                lobbySpawn.setWorld(world);
                plugin.getLogger().info("[TemplateConfig] Lobby-Welt '" + lobbySpawnWorldName + "' aufgelöst.");
            }
        }
        return lobbySpawn;
    }
    public void setLobbySpawn(Location loc) {
        this.lobbySpawn = loc;
        if (loc != null && loc.getWorld() != null) this.lobbySpawnWorldName = loc.getWorld().getName();
    }

    public Location getFightSpawn() { return fightSpawn; }
    public void setFightSpawn(Location loc) { this.fightSpawn = loc; }

    public Location getMobSpawn() { return mobSpawn; }
    public void setMobSpawn(Location loc) { this.mobSpawn = loc; }

    public Location getEquipSpawn() { return equipSpawn; }
    public void setEquipSpawn(Location loc) { this.equipSpawn = loc; }

    public Location getFightRegionMin() { return fightRegionMin; }
    public void setFightRegionMin(Location loc) { this.fightRegionMin = loc; }

    public Location getFightRegionMax() { return fightRegionMax; }
    public void setFightRegionMax(Location loc) { this.fightRegionMax = loc; }

    public Location getEquipRegionMin() { return equipRegionMin; }
    public void setEquipRegionMin(Location loc) { this.equipRegionMin = loc; }

    public Location getEquipRegionMax() { return equipRegionMax; }
    public void setEquipRegionMax(Location loc) { this.equipRegionMax = loc; }

    public Location getShopNpcLocation() { return shopNpcLocation; }
    public void setShopNpcLocation(Location loc) { this.shopNpcLocation = loc; }

    public List<Location> getLootChestLocations() { return lootChestLocations; }
    public void addLootChest(Location loc) { lootChestLocations.add(loc); }
    public void clearLootChests() { lootChestLocations.clear(); }
}
