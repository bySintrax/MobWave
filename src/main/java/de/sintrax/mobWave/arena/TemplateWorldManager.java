package de.sintrax.mobWave.arena;

import de.sintrax.mobWave.MobWave;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Verwaltet das Kopieren, Laden und Löschen von temporären Instanz-Welten
 * auf Basis der Template-Welt.
 */
public class TemplateWorldManager {

    private final MobWave plugin;
    private final Set<String> activeInstances = new HashSet<>();

    public TemplateWorldManager(MobWave plugin) {
        this.plugin = plugin;
        cleanupOldInstances();
    }

    // ─── Instanzen erstellen ────────────────────────────────────────────────

    /**
     * Kopiert die Template-Welt {@code count} mal asynchron und lädt
     * alle Kopien anschließend synchron auf dem Main-Thread.
     * Der Callback {@code onComplete} wird auf dem Main-Thread aufgerufen.
     */
    public void createInstances(String templateWorldName, int count, Consumer<List<World>> onComplete) {
        File worldContainer = Bukkit.getWorldContainer();
        File templateDir = new File(worldContainer, templateWorldName);

        if (!templateDir.exists() || !templateDir.isDirectory()) {
            plugin.getLogger().warning("Template-Welt nicht gefunden: " + templateWorldName);
            // Main-Thread-Callback mit leerer Liste
            Bukkit.getScheduler().runTask(plugin, () -> onComplete.accept(new ArrayList<>()));
            return;
        }

        // Async: Datei-Kopiervorgang (I/O-intensiv, nicht blockierend)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> createdNames = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String name = "mobwave_inst_" + UUID.randomUUID().toString().substring(0, 8);
                File destDir = new File(worldContainer, name);
                try {
                    copyDirectory(templateDir, destDir);
                    // uid.dat + session.lock entfernen, damit Bukkit es als neue Welt behandelt
                    new File(destDir, "uid.dat").delete();
                    new File(destDir, "session.lock").delete();
                    createdNames.add(name);
                    plugin.getLogger().info("[Template] Instanz kopiert: " + name);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "[Template] Fehler beim Kopieren nach " + name, e);
                }
            }

            // Sync: Welten laden (Bukkit API nur auf Main-Thread)
            Bukkit.getScheduler().runTask(plugin, () -> {
                List<World> worlds = new ArrayList<>();
                for (String name : createdNames) {
                    try {
                        World world = Bukkit.createWorld(new WorldCreator(name));
                        if (world != null) {
                            // Spielrelevante Welt-Einstellungen erzwingen
                            world.setDifficulty(org.bukkit.Difficulty.NORMAL);
                            world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);       // Nur manuelle Spawns
                            world.setGameRule(org.bukkit.GameRule.MOB_GRIEFING, false);          // Kein Mob-Griefing
                            world.setGameRule(org.bukkit.GameRule.DO_IMMEDIATE_RESPAWN, true);   // Kein Todesbildschirm
                            world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, false);
                            world.setGameRule(org.bukkit.GameRule.ANNOUNCE_ADVANCEMENTS, false);
                            world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
                            world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
                            worlds.add(world);
                            activeInstances.add(name);
                            plugin.getLogger().info("[Template] Instanz geladen: " + name);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING,
                                "[Template] Fehler beim Laden der Welt: " + name, e);
                    }
                }
                onComplete.accept(worlds);
            });
        });
    }

    // ─── Instanz löschen ────────────────────────────────────────────────────

    /**
     * Entlädt eine Instanz-Welt und löscht sie asynchron vom Dateisystem.
     */
    public void deleteInstance(World world) {
        if (world == null) return;
        String name = world.getName();
        if (!name.startsWith("mobwave_inst_")) {
            plugin.getLogger().warning("[Template] Versuch, Nicht-Instanz-Welt zu löschen: " + name);
            return;
        }
        activeInstances.remove(name);

        // Alle Spieler aus der Welt entfernen
        Location fallback = Bukkit.getWorlds().get(0).getSpawnLocation();
        for (Player p : new ArrayList<>(world.getPlayers())) {
            p.teleport(fallback);
        }

        if (!Bukkit.unloadWorld(world, false)) {
            plugin.getLogger().warning("[Template] Konnte Welt nicht entladen: " + name);
        }

        File dir = new File(Bukkit.getWorldContainer(), name);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                deleteDirectory(dir);
                plugin.getLogger().info("[Template] Instanz-Welt gelöscht: " + name);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING,
                        "[Template] Fehler beim Löschen der Welt: " + name, e);
            }
        });
    }

    /**
     * Löscht alle aktiven Instanzen – wird beim Plugin-Shutdown aufgerufen.
     */
    public void cleanupAllInstances() {
        for (String name : new ArrayList<>(activeInstances)) {
            World world = Bukkit.getWorld(name);
            if (world != null) {
                deleteInstance(world);
            }
        }
        activeInstances.clear();
    }

    // ─── Startup-Bereinigung ─────────────────────────────────────────────────

    /**
     * Beim Plugin-Start: Überreste aus abgestürzten Server-Sessions bereinigen.
     */
    public void cleanupOldInstances() {
        File worldContainer = Bukkit.getWorldContainer();
        File[] dirs = worldContainer.listFiles(
                f -> f.isDirectory() && f.getName().startsWith("mobwave_inst_"));
        if (dirs == null) return;

        for (File dir : dirs) {
            String name = dir.getName();
            // Falls noch geladen: erst entladen
            World w = Bukkit.getWorld(name);
            if (w != null) {
                Location fallback = Bukkit.getWorlds().get(0).getSpawnLocation();
                for (Player p : new ArrayList<>(w.getPlayers())) {
                    p.teleport(fallback);
                }
                Bukkit.unloadWorld(w, false);
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    deleteDirectory(dir);
                    plugin.getLogger().info("[Template] Veraltete Instanz bereinigt: " + name);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "[Template] Fehler beim Bereinigen von " + name, e);
                }
            });
        }
    }

    // ─── Datei-Hilfsmethoden ─────────────────────────────────────────────────

    private void copyDirectory(File source, File dest) throws IOException {
        if (!dest.exists()) dest.mkdirs();
        File[] children = source.listFiles();
        if (children == null) return;
        for (File child : children) {
            File destChild = new File(dest, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, destChild);
            } else {
                Files.copy(child.toPath(), destChild.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void deleteDirectory(File dir) throws IOException {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
}
