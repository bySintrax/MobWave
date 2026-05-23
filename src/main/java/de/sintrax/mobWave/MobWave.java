package de.sintrax.mobWave;

import de.sintrax.mobWave.arena.TemplateConfig;
import de.sintrax.mobWave.arena.TemplateWizard;
import de.sintrax.mobWave.arena.TemplateWorldManager;
import de.sintrax.mobWave.commands.MobWaveCommand;
import de.sintrax.mobWave.commands.ReadyCommand;
import de.sintrax.mobWave.commands.TeamCommand;
import de.sintrax.mobWave.team.TeamManager;
import de.sintrax.mobWave.database.DatabaseManager;
import de.sintrax.mobWave.game.GameManager;
import de.sintrax.mobWave.listener.PlayerListener;
import de.sintrax.mobWave.placeholder.MobWavePlaceholder;
import de.sintrax.mobWave.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MobWave extends JavaPlugin {

    private static MobWave instance;
    private DatabaseManager databaseManager;
    private GameManager gameManager;
    private TemplateConfig templateConfig;
    private TemplateWorldManager templateWorldManager;
    private TeamManager teamManager;
    private final Map<UUID, TemplateWizard> activeWizards = new HashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        MessageUtil.setPrefix(getConfig().getString("prefix", "§8[§6GlowingParadise§8]§7 "));

        databaseManager = new DatabaseManager(this);
        if (!databaseManager.connect()) {
            getLogger().severe("Datenbankverbindung fehlgeschlagen! Plugin wird deaktiviert.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        databaseManager.createTables();

        // Template-System initialisieren (bereinigt auch alte Instanz-Welten)
        templateWorldManager = new TemplateWorldManager(this);
        templateConfig = new TemplateConfig(this);

        gameManager = new GameManager(this);
        teamManager = new TeamManager();

        registerCommands();
        registerListeners();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new MobWavePlaceholder(this).register();
            getLogger().info("PlaceholderAPI-Hook registriert.");
        }

        getLogger().info("MobWave wurde erfolgreich gestartet!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.stopAllGames();
        }
        if (templateWorldManager != null) {
            templateWorldManager.cleanupAllInstances();
        }
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        getLogger().info("MobWave wurde deaktiviert.");
    }

    private void registerCommands() {
        MobWaveCommand mobWaveCommand = new MobWaveCommand(this);
        getCommand("mobwave").setExecutor(mobWaveCommand);
        getCommand("mobwave").setTabCompleter(mobWaveCommand);
        getCommand("ready").setExecutor(new ReadyCommand(this));
        TeamCommand teamCommand = new TeamCommand(this);
        getCommand("team").setExecutor(teamCommand);
        getCommand("team").setTabCompleter(teamCommand);
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
    }

    public static MobWave getInstance() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    public TemplateConfig getTemplateConfig() {
        return templateConfig;
    }

    public TemplateWorldManager getTemplateWorldManager() {
        return templateWorldManager;
    }

    public Map<UUID, TemplateWizard> getActiveWizards() {
        return activeWizards;
    }

    public TeamManager getTeamManager() {
        return teamManager;
    }
}

