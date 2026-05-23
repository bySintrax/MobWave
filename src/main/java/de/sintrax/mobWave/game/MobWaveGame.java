package de.sintrax.mobWave.game;

import de.sintrax.mobWave.MobWave;
import de.sintrax.mobWave.arena.PlayerArena;
import de.sintrax.mobWave.database.PlayerData;
import de.sintrax.mobWave.loot.LootManager;
import de.sintrax.mobWave.util.MessageUtil;
import de.sintrax.mobWave.wave.WaveType;
import de.sintrax.mobWave.wave.WaveTypeSelector;
import de.sintrax.mobWave.wave.WaveSpawner;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public class MobWaveGame {

    private final MobWave plugin;
    private final String gameId;
    private final Location lobbySpawn;
    private final List<PlayerArena> availableArenas;
    private GamePhase phase = GamePhase.LOBBY;
    private int currentWave = 0;
    private WaveType currentWaveType;

    private final Map<UUID, PlayerData> participants = new LinkedHashMap<>();
    private final Map<UUID, PlayerArena> playerArenaMap = new HashMap<>();
    private final Set<UUID> readyPlayers = new HashSet<>();
    private final Map<UUID, List<LivingEntity>> playerMobs = new HashMap<>();
    private final Set<UUID> deadPlayersThisWave = new HashSet<>();

    private BossBar bossBar;
    private BukkitTask equipTimerTask;
    private BukkitTask waveCheckTask;
    private BukkitTask mobAiTask;
    private BukkitTask compassTask;

    /** Wave-Verlauf für die Siegerehrung. */
    private final List<WaveResult> waveHistory = new ArrayList<>();

    /** Ergebnis einer einzelnen Wave (Nummer, Typ, Erfolg). */
    private record WaveResult(int waveNumber, WaveType type, boolean completed) {}

    /** Gespeichertes Ranking für globale Siegerehrung (gesetzt wenn diese Instanz fertig ist). */
    private List<Map.Entry<UUID, PlayerData>> finalRanking;

    /** Platzierte Flüssigkeiten (Lava/Wasser), die nach jeder Wave entfernt werden. */
    private final Set<Location> placedLiquidBlocks = new HashSet<>();

    /** Shop-NPC Entitäten in dieser Spielinstanz (UUID zum späteren Entfernen). */
    private final Set<UUID> shopNpcUuids = new HashSet<>();
    /** Welche PlayerArenas haben bereits einen NPC erhalten (Object-Identity). */
    private final Set<PlayerArena> npcSpawnedArenas = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Wenn gesetzt: temporäre Instanz-Welt, die nach Spielende gelöscht wird. */
    private World instanceWorld = null;

    private final WaveTypeSelector waveTypeSelector;
    private final WaveSpawner waveSpawner;
    private final LootManager lootManager;

    private final int maxWaves;
    private final int equipTimeout;
    private final int waveCompletedPoints;
    private final int perfectRunBonus;

    public MobWaveGame(MobWave plugin, String gameId, Location lobbySpawn, List<PlayerArena> arenas) {
        this.plugin = plugin;
        this.gameId = gameId;
        this.lobbySpawn = lobbySpawn;
        this.availableArenas = arenas != null ? new ArrayList<>(arenas) : new ArrayList<>();
        this.waveTypeSelector = new WaveTypeSelector(plugin);
        this.waveSpawner = new WaveSpawner(plugin);
        this.lootManager = new LootManager(plugin);

        this.maxWaves = plugin.getConfig().getInt("event.maxWaves", 20);
        this.equipTimeout = plugin.getConfig().getInt("event.equipPhaseTimeout", 90);
        this.waveCompletedPoints = plugin.getConfig().getInt("points.waveCompleted", 5);
        this.perfectRunBonus = plugin.getConfig().getInt("points.perfectRunBonus", 5);

        this.bossBar = BossBar.bossBar(
                Component.text("§8[§6GlowingParadise§8] §7Warte auf Spieler…"),
                1.0f,
                BossBar.Color.YELLOW,
                BossBar.Overlay.PROGRESS
        );
    }

    // ─── Spieler-Verwaltung ─────────────────────────────────────────────────

    public void addPlayer(Player player, PlayerData data) {
        participants.put(player.getUniqueId(), data);
        player.showBossBar(bossBar);
        player.teleport(lobbySpawn);
        player.setGameMode(GameMode.ADVENTURE);
        updateLobbyBossBar();
        broadcastToAll("§e" + player.getName() + " §7ist der Arena beigetreten! §8(" + participants.size() + "/" + getMaxPlayers() + ")");
    }

    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        participants.remove(uuid);
        readyPlayers.remove(uuid);
        player.hideBossBar(bossBar);
        PlayerArena pa = playerArenaMap.remove(uuid);
        if (pa != null) pa.release();
        clearPlayerMobs(uuid);
        if (phase == GamePhase.EQUIP) {
            checkAllReady();
        }
    }

    /** Wird aufgerufen wenn ein Spieler während EQUIP/WAVE disconnectet. */
    public void onPlayerDisconnect(Player player) {
        UUID uuid = player.getUniqueId();
        player.hideBossBar(bossBar);
        broadcastToAll("§7" + player.getName() + " §8hat die Verbindung getrennt.");
        if (phase == GamePhase.EQUIP) {
            // Auto-ready damit Wave nicht blockiert wird
            readyPlayers.add(uuid);
            checkAllReady();
        } else if (phase == GamePhase.WAVE) {
            // Mobs killen und Spieler als tot markieren
            clearPlayerMobs(uuid);
            deadPlayersThisWave.add(uuid);
        }
    }

    /** Wird aufgerufen wenn ein vorher disconnecteter Spieler zurückkommt. */
    public void onPlayerReconnect(Player player, PlayerData data) {
        UUID uuid = player.getUniqueId();
        player.showBossBar(bossBar);
        if (phase == GamePhase.EQUIP) {
            broadcastToAll("§e" + player.getName() + " §7hat die Verbindung wiederhergestellt.");
            // Ready-Status zurücksetzen – Spieler soll selbst entscheiden
            readyPlayers.remove(uuid);
            player.setGameMode(GameMode.ADVENTURE);
            PlayerArena pa = playerArenaMap.get(uuid);
            if (pa != null && pa.getEquipSpawn() != null) player.teleport(pa.getEquipSpawn());
            MessageUtil.send(player, "§aWillkommen zurück! Du spielst weiter in deiner Instanz.");
        } else if (phase == GamePhase.WAVE) {
            // Wave gilt als Niederlage für diesen Spieler – er wartet auf die nächste Ausrüstungsphase.
            // deadPlayersThisWave bleibt gesetzt → Spieler gilt weiter als ausgeschieden.
            player.setGameMode(GameMode.ADVENTURE);
            if (lobbySpawn != null) player.teleport(lobbySpawn);
            MessageUtil.send(player, "§cDu hast die laufende Wave verpasst.");
            MessageUtil.send(player, "§7Du steigst in der nächsten Ausrüstungsphase wieder ein.");
        }
    }

    // ─── Lobby-Phase ────────────────────────────────────────────────────────

    private void updateLobbyBossBar() {
        int min = plugin.getConfig().getInt("event.minPlayers", 2);
        bossBar.name(Component.text("§8[§6GlowingParadise§8] §7Warte auf Spieler… §8(§e" + participants.size() + "§8/§e" + min + "§8)"));
        bossBar.progress(Math.min(1.0f, (float) participants.size() / min));
    }

    public void startLobbyCountdown() {
        phase = GamePhase.LOBBY;
        updateLobbyBossBar();
    }

    // ─── Ausrüstungsphase ───────────────────────────────────────────────────

    public void startEquipPhase() {
        phase = GamePhase.EQUIP;
        readyPlayers.clear();
        currentWave++;

        // Spieler teleportieren und Loot befüllen
        for (UUID uuid : participants.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            PlayerArena pa = playerArenaMap.get(uuid);
            if (pa == null) {
                pa = getNextFreeArena();
                if (pa == null) {
                    MessageUtil.send(player, "§cKeine freie Spieler-Arena verfügbar!");
                    continue;
                }
                pa.setOccupied(true);
                pa.setAssignedPlayer(uuid);
                playerArenaMap.put(uuid, pa);
                // Loot-Kisten nur einmal befüllen (beim ersten Zuweisen der Arena)
                lootManager.fillLootChests(pa, currentWave);
                // Shop-NPC einmalig pro Arena spawnen
                spawnShopNpcIfNeeded(pa);
            }
            player.teleport(pa.getEquipSpawn());
            player.getInventory().clear();
            player.setGameMode(GameMode.SURVIVAL);

            // Wave-Typ-Wahrscheinlichkeiten anzeigen
            sendWaveChancesInfo(player);
        }

        updateEquipBossBar();

        // Auto-Start nach Timeout
        equipTimerTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (phase == GamePhase.EQUIP) {
                broadcastToAll("§7Zeit abgelaufen! Wave startet automatisch…");
                startCountdownToWave(plugin.getConfig().getInt("event.waveStartCountdown", 5));
            }
        }, equipTimeout * 20L);
    }

    private void sendWaveChancesInfo(Player player) {
        Map<de.sintrax.mobWave.wave.WaveType, Integer> chances = waveTypeSelector.getChancesForWave(currentWave);
        int total = chances.values().stream().mapToInt(Integer::intValue).sum();
        MessageUtil.send(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
        MessageUtil.send(player, "§6Wave §e" + currentWave + " §7– Nächste Wave-Typ-Chancen:");
        for (Map.Entry<WaveType, Integer> entry : chances.entrySet()) {
            if (entry.getValue() <= 0) continue;
            int percent = total > 0 ? (entry.getValue() * 100 / total) : 0;
            MessageUtil.send(player, "  " + entry.getKey().getDisplayName() + "§7: §e" + percent + "%");
        }
        MessageUtil.send(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void updateEquipBossBar() {
        bossBar.name(Component.text("§7Ausrüstung wählen… §8| §7Bereit: §e" + readyPlayers.size() + "§8/§e" + participants.size()));
        bossBar.progress(participants.isEmpty() ? 1.0f : (float) readyPlayers.size() / participants.size());
        bossBar.color(BossBar.Color.GREEN);
    }

    public void setPlayerReady(Player player) {
        if (phase != GamePhase.EQUIP) {
            MessageUtil.send(player, "§cDu kannst dich nur in der Ausrüstungsphase bereit melden.");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (readyPlayers.contains(uuid)) {
            MessageUtil.send(player, "§cDu bist bereits bereit.");
            return;
        }
        readyPlayers.add(uuid);
        MessageUtil.send(player, "§aDu bist bereit! Warte auf die anderen Spieler…");
        broadcastToAll("§e" + player.getName() + " §aist bereit! §8(§e" + readyPlayers.size() + "§8/§e" + participants.size() + "§8)");
        updateEquipBossBar();
        checkAllReady();
    }

    private void checkAllReady() {
        if (!participants.isEmpty() && readyPlayers.containsAll(participants.keySet())) {
            if (equipTimerTask != null) {
                equipTimerTask.cancel();
                equipTimerTask = null;
            }
            int countdown = plugin.getConfig().getInt("event.waveStartCountdown", 5);
            broadcastToAll("§aAlle Spieler sind bereit! Wave startet in §e" + countdown + " §aSekunden…");
            startCountdownToWave(countdown);
        }
    }

    /**
     * Countdown mit Ton und Title vor dem Wellenstart.
     * Wird rekursiv jede Sekunde aufgerufen.
     */
    private void startCountdownToWave(int seconds) {
        if (phase != GamePhase.EQUIP) return;
        if (seconds <= 0) {
            startWavePhase();
            return;
        }
        for (UUID uuid : participants.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            String color = seconds <= 3 ? "§c" : "§e";
            p.sendTitle(color + seconds, "§7Sekunden bis zur Kampfphase", 0, 22, 5);
            // Tonhöhe steigt mit sinkendem Countdown (tiefer Ton → höherer Ton)
            float pitch = 0.5f + (5 - seconds) * 0.15f;
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, pitch);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> startCountdownToWave(seconds - 1), 20L);
    }

    // ─── Kampfphase ─────────────────────────────────────────────────────────

    public void startWavePhase() {
        if (equipTimerTask != null) {
            equipTimerTask.cancel();
            equipTimerTask = null;
        }
        phase = GamePhase.WAVE;
        deadPlayersThisWave.clear();
        currentWaveType = waveTypeSelector.selectWaveType(currentWave);
        broadcastToAll("§7Wave §e" + currentWave + " §7startet! Typ: " + currentWaveType.getDisplayTag());

        for (UUID uuid : participants.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            PlayerData data = participants.get(uuid);
            data.resetDamageFlag();
            PlayerArena pa = playerArenaMap.get(uuid);
            if (pa == null) continue;
            player.teleport(pa.getFightSpawn());
            player.setGameMode(GameMode.SURVIVAL);

            // Tracker-Kompass geben (Slot 8)
            giveTrackerCompass(player);

            // Mobs an separatem Mob-Spawn spawnen (Fallback: Spieler-Spawn)
            List<LivingEntity> mobs = waveSpawner.spawnWave(currentWaveType, currentWave, pa.getMobSpawn());
            playerMobs.put(uuid, mobs);

            // Sofort auf den Spieler zielen lassen
            for (LivingEntity mob : mobs) {
                if (mob instanceof Mob m) m.setTarget(player);
            }
        }

        updateWaveBossBar();
        startWaveCheckTask();

        // Mob-KI: alle 2 Sekunden Ziel auf den Spieler erzwingen
        mobAiTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateMobTargets, 40L, 40L);
        // Kompass: alle 2 Sekunden auf nächstes Monster zeigen
        compassTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateCompassTargets, 40L, 40L);
    }

    private void startWaveCheckTask() {
        waveCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {            // Nur lebende Teilnehmer prüfen (gestorbene Spieler gelten nicht als Siegbedingung)
            Set<UUID> alivePlayers = new HashSet<>(participants.keySet());
            alivePlayers.removeAll(deadPlayersThisWave);

            // Wenn alle Spieler gestorben sind – Wave als gescheitert werten
            if (alivePlayers.isEmpty()) {
                waveCheckTask.cancel();
                waveCheckTask = null;
                onWaveFailed();
                return;
            }

            boolean allDone = true;
            int totalMobs = 0;
            for (UUID uuid : alivePlayers) {
                List<LivingEntity> mobs = playerMobs.getOrDefault(uuid, Collections.emptyList());
                mobs.removeIf(mob -> !mob.isValid() || mob.isDead());
                totalMobs += mobs.size();
                if (!mobs.isEmpty()) allDone = false;
            }

            updateWaveBossBarMobCount(totalMobs);

            if (allDone) {
                waveCheckTask.cancel();
                waveCheckTask = null;
                onWaveCompleted();
            }
        }, 60L, 20L); // 3s initial delay, dann jede Sekunde prüfen
    }

    private void updateWaveBossBar() {
        int totalMobs = playerMobs.values().stream().mapToInt(List::size).sum();
        bossBar.name(Component.text("§7Wave §e" + currentWave + " §8[" + currentWaveType.getDisplayTag() + "§8] §7| §cMobs: §f" + totalMobs + " §7verbleibend"));
        bossBar.color(BossBar.Color.RED);
        bossBar.progress(1.0f);
    }

    private void updateWaveBossBarMobCount(int remaining) {
        bossBar.name(Component.text("§7Wave §e" + currentWave + " §8[" + currentWaveType.getDisplayTag() + "§8] §7| §cMobs: §f" + remaining + " §7verbleibend"));
    }

    // ─── Wave-Abschluss ─────────────────────────────────────────────────────

    private void onWaveCompleted() {
        broadcastToAll("§aWave §e" + currentWave + " §ageschafft!");
        waveHistory.add(new WaveResult(currentWave, currentWaveType, true));
        for (UUID uuid : participants.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            PlayerData data = participants.get(uuid);
            if (player != null && data != null) {
                data.addPoints(waveCompletedPoints);
                data.incrementWavesSurvived();
                MessageUtil.send(player, "§a+" + waveCompletedPoints + " Punkte §7für Wave " + currentWave + "!");

                if (!data.hasTookDamageThisWave()) {
                    data.addPoints(perfectRunBonus);
                    data.incrementPerfectRuns();
                    MessageUtil.send(player, "§6✦ Perfect Run! §e+" + perfectRunBonus + " Bonuspunkte!");
                }
                plugin.getDatabaseManager().savePlayerAsync(data);
            }
        }

        if (currentWave >= maxWaves) {
            endGame();
            return;
        }

        // KI- und Kompass-Tasks beenden
        cancelWaveTasks();
        cleanupLiquidBlocks();

        // Spieler zurücksetzen: Inventar leeren, Effekte entfernen, heilen
        for (UUID uuid : participants.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            resetPlayerForNextRound(player);
        }

        Bukkit.getScheduler().runTaskLater(plugin, this::startEquipPhase, 60L); // 3 Sekunden Pause
    }

    /** Alle Spieler sind gestorben – keine Punkte, Weiter zur nächsten Ausrüstungsphase. */
    private void onWaveFailed() {
        broadcastToAll("§cAlle Spieler sind gestorben! Die Wave zählt nicht.");
        waveHistory.add(new WaveResult(currentWave, currentWaveType, false));
        if (currentWave >= maxWaves) {
            endGame();
            return;
        }

        // KI- und Kompass-Tasks beenden
        cancelWaveTasks();
        cleanupLiquidBlocks();

        // Spieler zurücksetzen
        for (UUID uuid : participants.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            resetPlayerForNextRound(player);
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::startEquipPhase, 60L);
    }

    public void onPlayerDeath(Player player) {
        UUID uuid = player.getUniqueId();
        deadPlayersThisWave.add(uuid);
        clearPlayerMobs(uuid);
        MessageUtil.send(player, "§cDu bist gestorben! Du nimmst an der nächsten Wave teil.");
        broadcastToAll("§c" + player.getName() + " §7ist gestorben und sammelt keine Punkte für diese Wave.");
        // Spieler bleibt im Event – wird in nächster Phase wieder eingebunden
    }

    public void onPlayerDamaged(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData data = participants.get(uuid);
        if (data != null) {
            data.setTookDamageThisWave(true);
        }
    }

    // ─── Spielende ──────────────────────────────────────────────────────────

    public void endGame() {
        // Alle laufenden Tasks beenden
        cancelWaveTasks();
        if (waveCheckTask != null) { waveCheckTask.cancel(); waveCheckTask = null; }
        if (equipTimerTask != null) { equipTimerTask.cancel(); equipTimerTask = null; }

        phase = GamePhase.ENDED;

        // Platzierte Flüssigkeiten entfernen
        cleanupLiquidBlocks();

        // Ranking vor dem Reset erstellen (Session-Punkte noch aktuell)
        finalRanking = participants.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().getSessionPoints(), a.getValue().getSessionPoints()))
                .collect(Collectors.toList());

        // Statistiken speichern (gamesPlayed++) – Session-Punkte erst nach der GUI resetten
        for (Map.Entry<UUID, PlayerData> entry : participants.entrySet()) {
            entry.getValue().incrementGamesPlayed();
            plugin.getDatabaseManager().savePlayerAsync(entry.getValue());
        }

        // GameManager benachrichtigen – zeigt globales GUI sobald alle Instanzen fertig
        plugin.getGameManager().onGameFinished(this);
    }

    /** Räumt die Spielinstanz auf (von GameManager nach der Siegerehrung aufgerufen). */
    public void scheduleCleanup() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            removeShopNpcs();

            // Session-Punkte jetzt zurücksetzen
            for (Map.Entry<UUID, PlayerData> entry : participants.entrySet()) {
                entry.getValue().resetSessionPoints();
            }

            Location returnLoc = Bukkit.getWorlds().get(0).getSpawnLocation();
            for (UUID uuid : participants.keySet()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(bossBar);
                    player.getInventory().clear();
                    player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
                    var healthAttr = player.getAttribute(Attribute.MAX_HEALTH);
                    double maxHp = healthAttr != null ? healthAttr.getValue() : 20.0;
                    player.setHealth(maxHp);
                    player.setFoodLevel(20);
                    player.setSaturation(20.0f);
                    player.teleport(returnLoc);
                    player.setGameMode(GameMode.SURVIVAL);
                }
                PlayerArena pa = playerArenaMap.get(uuid);
                if (pa != null) pa.release();
            }
            participants.clear();
            playerArenaMap.clear();
            playerMobs.clear();
            plugin.getGameManager().cleanupGame(gameId);

            if (instanceWorld != null) {
                plugin.getTemplateWorldManager().deleteInstance(instanceWorld);
                instanceWorld = null;
            } else {
                Bukkit.broadcastMessage(de.sintrax.mobWave.util.MessageUtil.getPrefix() + "§7Server-Neustart in §e30 §7Sekunden!");
                int[] countdownSeconds = {25, 10, 5, 3, 2, 1};
                for (int s : countdownSeconds) {
                    final int remaining = s;
                    Bukkit.getScheduler().runTaskLater(plugin,
                        () -> Bukkit.broadcastMessage(de.sintrax.mobWave.util.MessageUtil.getPrefix()
                            + "§7Server-Neustart in §e" + remaining + " §7Sekunden!"),
                        (30 - s) * 20L);
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Bukkit.broadcastMessage(de.sintrax.mobWave.util.MessageUtil.getPrefix() + "§cServer wird neu gestartet…");
                    Bukkit.getServer().spigot().restart();
                }, 600L);
            }
        }, 300L); // 15 Sekunden
    }

    // ─── Siegerehrung-GUI ───────────────────────────────────────────────────

    /** Öffnet die animierte Wellen-Auswertung für alle Spieler. */
    public void showEndGameGui(List<Map.Entry<UUID, PlayerData>> ranking) {
        List<WaveResult> history = new ArrayList<>(waveHistory);
        int rows = Math.max(3, Math.min(6, ((history.size() - 1) / 9) + 2));
        int size = rows * 9;

        for (UUID uuid : new ArrayList<>(participants.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            Inventory inv = Bukkit.createInventory(null, size,
                    LegacyComponentSerializer.legacySection().deserialize("§6MobWave §8– §7Wellen-Übersicht"));
            player.openInventory(inv);
            animateWaveSlots(player, inv, history, 0, ranking);
        }
    }

    /** Befüllt die Inventar-Slots mit Wave-Ergebnissen – ein Slot alle 4 Ticks. */
    private void animateWaveSlots(Player player, Inventory inv, List<WaveResult> history,
                                   int index, List<Map.Entry<UUID, PlayerData>> ranking) {
        if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inv) return;

        if (index >= history.size()) {
            // Animation fertig – Ranking nach kurzer Pause einfügen
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> showRankingInGui(player, inv, ranking), 20L);
            return;
        }

        WaveResult result = history.get(index);
        ItemStack item = new ItemStack(result.completed() ? Material.LIME_DYE : Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String symbol  = result.completed() ? "§a✔" : "§c✗";
            String color   = result.completed() ? "§a" : "§c";
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(
                    color + "§lWelle " + result.waveNumber() + " " + symbol));
            meta.lore(List.of(
                LegacyComponentSerializer.legacySection().deserialize("§7Typ: " + result.type().getDisplayName()),
                LegacyComponentSerializer.legacySection().deserialize(
                    result.completed() ? "§aGeschafft §a✔" : "§cGescheitert §c✗")
            ));
            item.setItemMeta(meta);
        }

        inv.setItem(index, item);
        player.playSound(player.getLocation(),
                result.completed() ? Sound.ENTITY_EXPERIENCE_ORB_PICKUP : Sound.ENTITY_VILLAGER_NO,
                0.5f, result.completed() ? 1.2f : 0.8f);

        Bukkit.getScheduler().runTaskLater(plugin,
                () -> animateWaveSlots(player, inv, history, index + 1, ranking), 4L);
    }

    /** Zeigt das Ranking in der letzten Zeile der Siegerehrung an. */
    private void showRankingInGui(Player player, Inventory inv, List<Map.Entry<UUID, PlayerData>> ranking) {
        if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inv) return;

        int invSize = inv.getSize();
        int rankRow = invSize - 9;

        // Trennlinie (graue Glasscheiben)
        ItemStack separator = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta sepMeta = separator.getItemMeta();
        if (sepMeta != null) { sepMeta.displayName(Component.empty()); separator.setItemMeta(sepMeta); }
        for (int i = rankRow; i < invSize; i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) {
                inv.setItem(i, separator.clone());
            }
        }

        // Ranking-Einträge (max. 9 Spieler)
        String[] medals = {"§6§l★", "§7§l✦", "§c§l✧"};
        for (int i = 0; i < Math.min(ranking.size(), 9); i++) {
            Map.Entry<UUID, PlayerData> entry = ranking.get(i);
            Player p = Bukkit.getPlayer(entry.getKey());
            String name = p != null ? p.getName() : entry.getValue().getName();

            ItemStack rankItem = new ItemStack(Material.PAPER);
            ItemMeta meta = rankItem.getItemMeta();
            if (meta != null) {
                String medal = i < 3 ? medals[i] : "§8" + (i + 1) + ".";
                meta.displayName(LegacyComponentSerializer.legacySection().deserialize(
                        medal + " §f" + name));
                meta.lore(List.of(LegacyComponentSerializer.legacySection().deserialize(
                        "§7Punkte: §e" + entry.getValue().getSessionPoints())));
                rankItem.setItemMeta(meta);
            }
            inv.setItem(rankRow + i, rankItem);
        }

        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
    }

    public void forceStop() {
        if (waveCheckTask != null) waveCheckTask.cancel();
        if (equipTimerTask != null) equipTimerTask.cancel();
        cancelWaveTasks();
        removeShopNpcs();
        for (UUID uuid : participants.keySet()) {
            clearPlayerMobs(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.hideBossBar(bossBar);
            PlayerArena pa = playerArenaMap.get(uuid);
            if (pa != null) pa.release();
        }
        participants.clear();
        playerArenaMap.clear();
        playerMobs.clear();
        phase = GamePhase.ENDED;
        if (instanceWorld != null) {
            plugin.getTemplateWorldManager().deleteInstance(instanceWorld);
            instanceWorld = null;
        }
    }

    // ─── Hilfsmethoden ──────────────────────────────────────────────────────

    /** Bricht KI- und Kompass-Task ab (nach Welle oder Spielende). */
    private void cancelWaveTasks() {
        if (mobAiTask != null) { mobAiTask.cancel(); mobAiTask = null; }
        if (compassTask != null) { compassTask.cancel(); compassTask = null; }
    }

    /** Erzwingt jede 2 Sekunden, dass alle Event-Monster ihren Spieler targeten. */
    private void updateMobTargets() {
        for (Map.Entry<UUID, List<LivingEntity>> entry : playerMobs.entrySet()) {
            Player target = Bukkit.getPlayer(entry.getKey());
            if (target == null || !target.isOnline()) continue;
            for (LivingEntity mob : entry.getValue()) {
                if (!mob.isValid() || mob.isDead()) continue;
                if (mob instanceof Mob m && !(target.equals(m.getTarget()))) {
                    m.setTarget(target);
                }
            }
        }
    }

    /** Aktualisiert den Kompass jedes Spielers auf das nächste lebende Monster. */
    private void updateCompassTargets() {
        for (Map.Entry<UUID, List<LivingEntity>> entry : playerMobs.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            LivingEntity nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (LivingEntity mob : entry.getValue()) {
                if (!mob.isValid() || mob.isDead()) continue;
                double dist = mob.getLocation().distanceSquared(player.getLocation());
                if (dist < nearestDist) { nearestDist = dist; nearest = mob; }
            }
            if (nearest != null) player.setCompassTarget(nearest.getLocation());
        }
    }

    /** Gibt dem Spieler einen Tracker-Kompass (erster freier Slot via addItem). */
    private void giveTrackerCompass(Player player) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize("§6Mob-Tracker"));
            meta.lore(List.of(
                LegacyComponentSerializer.legacySection().deserialize("§7Zeigt auf das nächste Event-Monster")
            ));
            compass.setItemMeta(meta);
        }
        var overflow = player.getInventory().addItem(compass);
        overflow.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
    }

    /** Setzt einen Spieler für die nächste Runde zurück: Inventar leeren, Effekte entfernen, heilen. */
    private void resetPlayerForNextRound(Player player) {
        player.getInventory().clear();
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        var healthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = healthAttr != null ? healthAttr.getValue() : 20.0;
        player.setHealth(maxHp);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
    }

    /** Nächste freie PlayerArena aus dem Instanz-Pool. */
    private PlayerArena getNextFreeArena() {        for (PlayerArena pa : availableArenas) {
            if (!pa.isOccupied()) return pa;
        }
        return null;
    }
    /** Spawnt den Shop-NPC für eine Arena, falls noch keiner existiert. */
    private void spawnShopNpcIfNeeded(PlayerArena pa) {
        if (npcSpawnedArenas.contains(pa)) return;
        Location npcLoc = pa.getShopNpcLocation();
        if (npcLoc == null || npcLoc.getWorld() == null) return;
        Villager npc = npcLoc.getWorld().spawn(npcLoc, Villager.class);
        npc.setAI(false);
        npc.setInvulnerable(true);
        npc.setSilent(true);
        npc.customName(Component.text("§6[§eShop§6]"));
        npc.setCustomNameVisible(true);
        npc.setProfession(Villager.Profession.NITWIT);
        npc.getPersistentDataContainer().set(
            new NamespacedKey(plugin, "shop_npc"),
            PersistentDataType.BYTE, (byte) 1);
        shopNpcUuids.add(npc.getUniqueId());
        npcSpawnedArenas.add(pa);
    }

    /** Entfernt alle Shop-NPCs dieser Spielinstanz. */
    private void removeShopNpcs() {
        for (UUID id : shopNpcUuids) {
            org.bukkit.entity.Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        shopNpcUuids.clear();
        npcSpawnedArenas.clear();
    }
    private void clearPlayerMobs(UUID uuid) {
        List<LivingEntity> mobs = playerMobs.remove(uuid);
        if (mobs != null) {
            mobs.forEach(mob -> { if (mob.isValid()) mob.remove(); });
        }
    }

    /** Trackt einen von Spielern platzierten Flüssigkeitsblock zum späteren Cleanup. */
    public void trackPlacedLiquid(Location loc) {
        placedLiquidBlocks.add(loc.clone());
    }

    /** Entfernt alle getrackten Flüssigkeitsblöcke (Lava/Wasser → Luft). */
    private void cleanupLiquidBlocks() {
        for (Location loc : placedLiquidBlocks) {
            if (loc.getWorld() != null) loc.getBlock().setType(Material.AIR);
        }
        placedLiquidBlocks.clear();
    }

    public void broadcastToAll(String message) {
        for (UUID uuid : participants.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) MessageUtil.sendRaw(player, MessageUtil.getPrefix() + message);
        }
    }

    private int getMaxPlayers() {
        return plugin.getConfig().getInt("event.maxPlayers", 10);
    }

    // ─── Getter ─────────────────────────────────────────────────────────────

    public void setInstanceWorld(World world) { this.instanceWorld = world; }
    public World getInstanceWorld() { return instanceWorld; }
    public Location getLobbySpawn() { return lobbySpawn; }

    public GamePhase getPhase() { return phase; }
    public String getGameId() { return gameId; }
    public List<Map.Entry<UUID, PlayerData>> getFinalRanking() { return finalRanking; }
    public int getCurrentWave() { return currentWave; }
    public WaveType getCurrentWaveType() { return currentWaveType; }
    public Map<UUID, PlayerData> getParticipants() { return participants; }
    public boolean isParticipant(UUID uuid) { return participants.containsKey(uuid); }
    public PlayerArena getPlayerArena(UUID uuid) { return playerArenaMap.get(uuid); }

    public boolean canJoin() {
        return phase == GamePhase.LOBBY && participants.size() < getMaxPlayers();
    }
}
