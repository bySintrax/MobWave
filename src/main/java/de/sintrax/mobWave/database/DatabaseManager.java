package de.sintrax.mobWave.database;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.sintrax.mobWave.MobWave;
import org.bukkit.Bukkit;

import java.sql.*;
import java.util.UUID;
import java.util.logging.Level;

public class DatabaseManager {

    private final MobWave plugin;
    private final String tablePrefix;

    /** Primär: HikariCP-Verbindungspool */
    private HikariDataSource hikariDataSource;
    /** Fallback: MysqlDataSource (direkte Verbindung je Aufruf) */
    private MysqlDataSource fallbackDs;
    private boolean usingHikari = false;

    public DatabaseManager(MobWave plugin) {
        this.plugin = plugin;
        this.tablePrefix = plugin.getConfig().getString("database.tablePrefix", "mw_");
    }

    // ─── Verbindungsmanagement ───────────────────────────────────────────────

    public boolean connect() {
        String host     = plugin.getConfig().getString("database.host",     "localhost");
        int    port     = plugin.getConfig().getInt("database.port",        3306);
        String dbName   = plugin.getConfig().getString("database.name",     "mobwave");
        String user     = plugin.getConfig().getString("database.user",     "root");
        String password = plugin.getConfig().getString("database.password", "");

        // ── Primär: HikariCP ────────────────────────────────────────────────
        try {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + dbName
                    + "?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8&useUnicode=true&autoReconnect=true");
            cfg.setUsername(user);
            cfg.setPassword(password);
            cfg.setMaximumPoolSize(10);
            cfg.setMinimumIdle(2);
            cfg.setConnectionTimeout(30_000);
            cfg.setIdleTimeout(600_000);
            cfg.setMaxLifetime(1_800_000);
            cfg.setPoolName("MobWave-Pool");
            cfg.addDataSourceProperty("cachePrepStmts",     "true");
            cfg.addDataSourceProperty("prepStmtCacheSize",  "250");
            cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            cfg.addDataSourceProperty("useServerPrepStmts", "true");

            hikariDataSource = new HikariDataSource(cfg);
            try (Connection test = hikariDataSource.getConnection()) { /* Verbindung testen */ }

            usingHikari = true;
            plugin.getLogger().info("Datenbankverbindung via HikariCP hergestellt.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("HikariCP fehlgeschlagen – Fallback auf MySQL Connector J: " + e.getMessage());
            if (hikariDataSource != null) {
                hikariDataSource.close();
                hikariDataSource = null;
            }
        }

        // ── Fallback: MySQL Connector J ─────────────────────────────────────
        try {
            MysqlDataSource ds = new MysqlDataSource();
            ds.setServerName(host);
            ds.setPort(port);
            ds.setDatabaseName(dbName);
            ds.setUser(user);
            ds.setPassword(password);
            ds.setServerTimezone("UTC");

            // Verbindung testen
            try (Connection test = ds.getConnection()) { /* ok */ }

            fallbackDs = ds;
            usingHikari = false;
            plugin.getLogger().info("Datenbankverbindung via MySQL Connector J hergestellt (Fallback).");
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Fehler beim Verbinden zur Datenbank: " + e.getMessage(), e);
            return false;
        }
    }

    public void disconnect() {
        if (usingHikari && hikariDataSource != null) {
            hikariDataSource.close();
        }
        // Fallback nutzt keine persistente Verbindung – nichts zu schließen
    }

    /**
     * Gibt eine Verbindung zurück. Muss immer per try-with-resources geschlossen werden,
     * damit HikariCP-Verbindungen korrekt in den Pool zurückkehren.
     */
    private Connection getConnection() throws SQLException {
        if (usingHikari) {
            return hikariDataSource.getConnection();
        }
        // Fallback: neue Verbindung je Aufruf (wird vom Aufrufer geschlossen)
        return fallbackDs.getConnection();
    }

    // ─── Tabellen ────────────────────────────────────────────────────────────

    public void createTables() {
        String sql = """
            CREATE TABLE IF NOT EXISTS `%splayers` (
                `uuid`           VARCHAR(36) NOT NULL,
                `name`           VARCHAR(16) NOT NULL,
                `total_points`   INT         DEFAULT 0,
                `waves_survived` INT         DEFAULT 0,
                `games_played`   INT         DEFAULT 0,
                `perfect_runs`   INT         DEFAULT 0,
                PRIMARY KEY (`uuid`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """.formatted(tablePrefix);
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            plugin.getLogger().info("Datenbanktabellen erstellt/überprüft.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Fehler beim Erstellen der Tabellen.", e);
        }
    }

    // ─── CRUD ────────────────────────────────────────────────────────────────

    public PlayerData loadPlayer(UUID uuid, String name) {
        String sql = "SELECT * FROM `" + tablePrefix + "players` WHERE `uuid` = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PlayerData data = new PlayerData(uuid, rs.getString("name"));
                    data.setTotalPoints(rs.getInt("total_points"));
                    data.setWavesSurvived(rs.getInt("waves_survived"));
                    data.setGamesPlayed(rs.getInt("games_played"));
                    data.setPerfectRuns(rs.getInt("perfect_runs"));
                    return data;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Fehler beim Laden des Spielers " + uuid, e);
        }
        // Spieler neu anlegen (INSERT IGNORE verhindert Duplikat-Fehler bei Race Conditions)
        PlayerData data = new PlayerData(uuid, name);
        insertPlayer(data);
        return data;
    }

    private void insertPlayer(PlayerData data) {
        String sql = "INSERT IGNORE INTO `" + tablePrefix + "players` (`uuid`, `name`) VALUES (?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, data.getUniqueId().toString());
            ps.setString(2, data.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Fehler beim Erstellen des Spielers.", e);
        }
    }

    /** Synchrones Speichern – nur bei Plugin-Disable verwenden. */
    public void savePlayer(PlayerData data) {
        String sql = "UPDATE `" + tablePrefix + "players` SET `name`=?, `total_points`=?, `waves_survived`=?, `games_played`=?, `perfect_runs`=? WHERE `uuid`=?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, data.getName());
            ps.setInt(2, data.getTotalPoints());
            ps.setInt(3, data.getWavesSurvived());
            ps.setInt(4, data.getGamesPlayed());
            ps.setInt(5, data.getPerfectRuns());
            ps.setString(6, data.getUniqueId().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Fehler beim Speichern des Spielers " + data.getUniqueId(), e);
        }
    }

    /** Asynchrones Speichern – Standard für Laufzeit-Speichervorgänge. */
    public void savePlayerAsync(PlayerData data) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> savePlayer(data));
    }
}
