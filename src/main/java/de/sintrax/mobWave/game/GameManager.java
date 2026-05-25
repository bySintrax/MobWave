package de.sintrax.mobWave.game;

import de.sintrax.mobWave.MobWave;
import de.sintrax.mobWave.arena.PlayerArena;
import de.sintrax.mobWave.arena.TemplateConfig;
import de.sintrax.mobWave.database.PlayerData;
import de.sintrax.mobWave.loot.ShopManager;
import de.sintrax.mobWave.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GameManager {

    private final MobWave plugin;
    private final Map<String, MobWaveGame> games = new HashMap<>();
    private final Map<UUID, String> playerGameMap = new HashMap<>();
    private final Map<UUID, PlayerData> cachedPlayerData = new ConcurrentHashMap<>();
    /** Spieler, die während EQUIP/WAVE disconnected sind und reconnecten dürfen. */
    private final Set<UUID> disconnectedPlayers = ConcurrentHashMap.newKeySet();
    /** Spieler, die eine andere Instanz zuschauen (UUID → beobachtete gameId). */
    private final Map<UUID, String> spectatingGameId = new ConcurrentHashMap<>();
    private final ShopManager shopManager;

    public GameManager(MobWave plugin) {
        this.plugin = plugin;
        this.shopManager = new ShopManager(plugin);
    }

    public void leaveGame(Player player) {
        disconnectedPlayers.remove(player.getUniqueId());
        spectatingGameId.remove(player.getUniqueId());
        String arenaId = playerGameMap.remove(player.getUniqueId());
        if (arenaId == null) return;
        MobWaveGame game = games.get(arenaId);
        if (game != null) {
            game.removePlayer(player);
        }
    }

    public void startGame(String arenaId) {
        MobWaveGame game = games.get(arenaId);
        if (game == null) return;
        if (game.getPhase() != GamePhase.LOBBY) return;
        game.startEquipPhase();
    }

    public MobWaveGame getGameByPlayer(Player player) {
        String arenaId = playerGameMap.get(player.getUniqueId());
        if (arenaId == null) return null;
        return games.get(arenaId);
    }

    public MobWaveGame getGame(String arenaId) {
        return games.get(arenaId);
    }

    public PlayerData loadOrGetPlayerData(Player player) {
        return cachedPlayerData.computeIfAbsent(player.getUniqueId(),
                uuid -> plugin.getDatabaseManager().loadPlayer(uuid, player.getName()));
    }

    public void unloadPlayerData(Player player) {
        PlayerData data = cachedPlayerData.remove(player.getUniqueId());
        if (data != null) {
            plugin.getDatabaseManager().savePlayerAsync(data);
        }
    }

    /** Entfernt ein beendetes Spiel aus der internen Map und räumt Zuschauer auf. */
    public void cleanupGame(String arenaId) {
        games.remove(arenaId);

        // Zuschauer die dieses Spiel beobachten: zurück in die Hauptwelt schicken
        Location mainSpawn = Bukkit.getWorlds().get(0).getSpawnLocation();
        spectatingGameId.entrySet().removeIf(entry -> {
            if (!entry.getValue().equals(arenaId)) return false;
            Player spectator = Bukkit.getPlayer(entry.getKey());
            if (spectator != null && spectator.isOnline()) {
                spectator.setGameMode(org.bukkit.GameMode.SURVIVAL);
                spectator.teleport(mainSpawn);
                MessageUtil.send(spectator, "§7Das beobachtete Spiel wurde beendet.");
            }
            return true;
        });

        // Disconnected-Spieler deren Spiel endete: Daten speichern und aufräumen
        playerGameMap.entrySet().stream()
                .filter(e -> e.getValue().equals(arenaId))
                .map(Map.Entry::getKey)
                .forEach(uuid -> {
                    disconnectedPlayers.remove(uuid);
                    PlayerData data = cachedPlayerData.remove(uuid);
                    if (data != null) plugin.getDatabaseManager().savePlayerAsync(data);
                });
        playerGameMap.entrySet().removeIf(e -> e.getValue().equals(arenaId));
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public Set<String> getActiveGameIds() {
        return games.keySet();
    }

    /** Gibt true zurück wenn der Spieler gerade eine andere Instanz zuschauen. */
    public boolean isSpectating(UUID uuid) {
        return spectatingGameId.containsKey(uuid);
    }

    /** Meldet einen Spieler als Zuschauer einer Instanz an. */
    public void addSpectator(UUID uuid, String gameId) {
        spectatingGameId.put(uuid, gameId);
    }

    /** Entfernt den Spieler aus dem Zuschauer-Tracking. */
    public void removeSpectator(UUID uuid) {
        spectatingGameId.remove(uuid);
    }

    /** Findet ein aktives Spiel (EQUIP/WAVE) das nicht die eigene Instanz ist. */
    public MobWaveGame findActiveGameToSpectate(String excludeGameId) {
        for (Map.Entry<String, MobWaveGame> entry : games.entrySet()) {
            if (entry.getKey().equals(excludeGameId)) continue;
            GamePhase p = entry.getValue().getPhase();
            if (p == GamePhase.EQUIP || p == GamePhase.WAVE) return entry.getValue();
        }
        return null;
    }

    /**
     * Versucht, den Spieler automatisch einem laufenden oder neuen Spiel zuzuweisen.
     * Wird beim Server-Beitritt aufgerufen.
     */
    public void autoJoinPlayer(Player player) {
        if (playerGameMap.containsKey(player.getUniqueId())) return;
        // Im Template-Modus startet ein Admin das Event manuell für alle
        if (plugin.getTemplateConfig() != null && plugin.getTemplateConfig().isConfigured()) {
            MessageUtil.send(player, "§7Das Event startet bald! Warte auf den Start durch einen Admin.");
        } else {
            MessageUtil.send(player, "§7Es ist noch kein Event eingerichtet. Bitte einen Admin um Hilfe.");
        }
    }

    /**
     * Speichert den Zustand eines disconnecteten Spielers, damit er reconnecten kann.
     * Nur für EQUIP/WAVE-Phase; der Spieler bleibt in playerGameMap eingetragen.
     */
    public void onPlayerDisconnect(Player player, MobWaveGame game) {
        disconnectedPlayers.add(player.getUniqueId());
        game.onPlayerDisconnect(player);
        // Daten speichern, aber Cache behalten (für Reconnect)
        PlayerData data = cachedPlayerData.get(player.getUniqueId());
        if (data != null) plugin.getDatabaseManager().savePlayerAsync(data);
    }

    /**
     * Versucht einen Spieler in sein laufendes Spiel zurückzubringen.
     * @return true wenn Reconnect erfolgreich, false wenn normaler Join nötig
     */
    public boolean tryReconnectPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        if (!disconnectedPlayers.contains(uuid)) return false;
        disconnectedPlayers.remove(uuid);
        String gameId = playerGameMap.get(uuid);
        if (gameId == null) return false;
        MobWaveGame game = games.get(gameId);
        if (game == null || game.getPhase() == GamePhase.ENDED || game.getPhase() == GamePhase.LOBBY) {
            // Spiel beendet während Offline – aufräumen
            playerGameMap.remove(uuid);
            PlayerData data = cachedPlayerData.remove(uuid);
            if (data != null) plugin.getDatabaseManager().savePlayerAsync(data);
            return false;
        }
        PlayerData data = loadOrGetPlayerData(player);
        game.onPlayerReconnect(player, data);
        return true;
    }

    /**
     * Startet ein Template-basiertes Spiel:
     * 1. Countdown anzeigen (Spieler sehen Fortschritt)
     * 2. Gleichzeitig: Instanz-Welten async kopieren
     * 3. Wenn beides fertig: Spieler aufteilen und Spiele starten
     *
     * @param initiator Admin, der den Start ausgelöst hat
     * @param targetPlayers Spieler, die teilnehmen sollen (null = alle ohne laufendes Spiel)
     */
    public void startTemplateGame(Player initiator, List<Player> targetPlayers) {
        TemplateConfig template = plugin.getTemplateConfig();
        if (template == null || !template.isConfigured()) {
            MessageUtil.send(initiator, "§cKeine Template-Arena konfiguriert! Nutze §e/mw template setup§c.");
            return;
        }

        // Spieler sammeln
        List<Player> lobby = new ArrayList<>();
        if (targetPlayers != null) {
            for (Player p : targetPlayers) {
                if (!playerGameMap.containsKey(p.getUniqueId())) lobby.add(p);
            }
        } else {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!playerGameMap.containsKey(p.getUniqueId())) lobby.add(p);
            }
        }

        if (lobby.isEmpty()) {
            MessageUtil.send(initiator, "§cKeine Spieler ohne laufendes Spiel gefunden!");
            return;
        }

        int playersPerInstance = template.getPlayersPerInstance();
        int instanceCount = (int) Math.ceil((double) lobby.size() / playersPerInstance);
        int countdown = plugin.getConfig().getInt("template.startCountdown", 10);

        for (Player p : lobby) {
            MessageUtil.sendRaw(p, MessageUtil.getPrefix()
                    + "§aDas Template-Event startet in §e" + countdown
                    + " §aSekunden! §8(§7" + lobby.size() + " Spieler → §e" + instanceCount + " §7Instanzen§8)");
        }

        // Zwei Flags: Countdown abgelaufen? Welten bereit?
        boolean[] countdownDone = {false};
        boolean[] worldsReady = {false};
        @SuppressWarnings("unchecked")
        List<World>[] worldsRef = new List[1];

        // ── Async: Instanz-Welten erstellen ──
        plugin.getTemplateWorldManager().createInstances(
                template.getTemplateWorldName(),
                instanceCount,
                worlds -> {
                    worldsRef[0] = worlds;
                    worldsReady[0] = true;
                    if (countdownDone[0]) {
                        launchTemplateGames(lobby, worlds, template);
                    }
                    // Andernfalls wartet der Countdown-Callback
                }
        );

        // ── Countdown parallel dazu ──
        runTemplateCountdown(lobby, countdown, () -> {
            countdownDone[0] = true;
            if (worldsReady[0]) {
                launchTemplateGames(lobby, worldsRef[0], template);
            } else {
                for (Player p : lobby) {
                    MessageUtil.sendRaw(p, MessageUtil.getPrefix() + "§7Instanzen werden vorbereitet…");
                }
                // Wenn Welten fertig sind, übernimmt der worlds-Callback
            }
        });
    }

    private void runTemplateCountdown(List<Player> players, int seconds, Runnable onComplete) {
        if (seconds <= 0) {
            onComplete.run();
            return;
        }
        // Nur noch online Spieler beachten
        List<Player> online = players.stream()
                .filter(Player::isOnline)
                .toList();

        for (Player p : online) {
            String color = seconds <= 3 ? "§c" : "§e";
            p.sendTitle(color + seconds, "§7Sekunden bis zum Start", 0, 22, 5);
            float pitch = 0.5f + (10 - Math.min(seconds, 10)) * 0.06f;
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, pitch);
        }

        Bukkit.getScheduler().runTaskLater(plugin,
                () -> runTemplateCountdown(players, seconds - 1, onComplete),
                20L);
    }

    private void launchTemplateGames(List<Player> players, List<World> worlds, TemplateConfig template) {
        if (worlds == null || worlds.isEmpty()) {
            for (Player p : players) {
                if (p.isOnline()) MessageUtil.send(p, "§cFehler beim Erstellen der Spielwelten!");
            }
            return;
        }

        // Nur noch online Spieler aufteilen (Team-Mitglieder zusammen gruppieren)
        List<Player> online = plugin.getTeamManager()
                .groupPlayersByTeam(players.stream().filter(Player::isOnline).toList());
        if (online.isEmpty()) return;

        int playersPerInstance = template.getPlayersPerInstance();

        for (int i = 0; i < worlds.size(); i++) {
            World world = worlds.get(i);
            // Spieler-Gruppe für diese Instanz
            int from = i * playersPerInstance;
            int to = Math.min(from + playersPerInstance, online.size());
            if (from >= online.size()) break;
            List<Player> group = online.subList(from, to);

            String gameId = world.getName();
            Location gameLobbySpawn = template.getLobbySpawn() != null
                    ? template.getLobbySpawn()
                    : world.getSpawnLocation();

            // Pro Spieler eine eigene PlayerArena (getrennte Objekte, gleiche Template-Koordinaten)
            List<PlayerArena> playerArenas = new ArrayList<>();
            for (int j = 0; j < group.size(); j++) {
                playerArenas.add(template.buildArenaForWorld(world));
            }

            // Spiel erstellen und Instanz-Welt für Cleanup vermerken
            MobWaveGame game = new MobWaveGame(plugin, gameId, gameLobbySpawn, playerArenas);
            game.setInstanceWorld(world);
            games.put(gameId, game);

            // Spieler hinzufügen
            for (Player p : group) {
                PlayerData data = loadOrGetPlayerData(p);
                game.addPlayer(p, data);
                playerGameMap.put(p.getUniqueId(), gameId);
            }

            // Sofort starten – Countdown bereits abgelaufen
            game.startEquipPhase();
        }
    }

    /**
     * Wird aufgerufen, wenn eine Spielinstanz beendet wird.
     * Zeigt das globale Ranking-GUI erst, wenn ALLE Instanzen fertig sind.
     */
    public void onGameFinished(MobWaveGame finishedGame) {
        boolean allDone = games.values().stream().allMatch(g -> g.getPhase() == GamePhase.ENDED);

        if (!allDone) {
            finishedGame.broadcastToAll("§7Warte auf andere Instanzen\u2026");
            return;
        }

        // Kombiniertes Ranking aus allen Instanzen erstellen
        List<Map.Entry<UUID, PlayerData>> combinedRanking = games.values().stream()
                .filter(g -> g.getFinalRanking() != null)
                .flatMap(g -> g.getFinalRanking().stream())
                .sorted((a, b) -> Integer.compare(b.getValue().getSessionPoints(), a.getValue().getSessionPoints()))
                .collect(java.util.stream.Collectors.toList());

        // Globalen Gewinner an alle Online-Spieler bekannt geben
        String border = "§7━━━━━━━━━━━━━━━━━━━━━━━━";
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(border);
            p.sendMessage("§6§lMobWave §7– Event beendet!");
            if (!combinedRanking.isEmpty()) {
                Player winner = Bukkit.getPlayer(combinedRanking.get(0).getKey());
                if (winner != null) p.sendMessage("§6§l★ Gewinner: §e" + winner.getName() + " §6§l★");
            }
            p.sendMessage(border);
        }

        // GUI für alle Instanzen anzeigen und Cleanup planen
        for (MobWaveGame game : games.values()) {
            game.showEndGameGui(combinedRanking);
            game.scheduleCleanup();
        }
    }

    public void stopAllGames() {
        for (MobWaveGame game : games.values()) {
            game.forceStop();
        }
        games.clear();
        playerGameMap.clear();
        // Alle gecachten Spielerdaten speichern
        for (PlayerData data : cachedPlayerData.values()) {
            plugin.getDatabaseManager().savePlayer(data);
        }
        cachedPlayerData.clear();
    }
}
