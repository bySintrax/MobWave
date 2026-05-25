package de.sintrax.mobWave.listener;

import de.sintrax.mobWave.MobWave;
import de.sintrax.mobWave.arena.PlayerArena;
import de.sintrax.mobWave.arena.TemplateConfig;
import de.sintrax.mobWave.arena.TemplateWizard;
import de.sintrax.mobWave.database.PlayerData;
import de.sintrax.mobWave.game.GamePhase;
import de.sintrax.mobWave.game.MobWaveGame;
import de.sintrax.mobWave.util.MessageUtil;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.GameMode;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class PlayerListener implements Listener {

    private final MobWave plugin;

    public PlayerListener(MobWave plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getGameManager().loadOrGetPlayerData(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            // Reconnect prüfen – war der Spieler in einem laufenden Spiel?
            if (plugin.getGameManager().tryReconnectPlayer(player)) return;
            // Normaler Join: Lobby-Spawn teleportieren und Event-Zuweisung
            TemplateConfig cfg = plugin.getTemplateConfig();
            if (cfg != null && cfg.getLobbySpawn() != null && cfg.getLobbySpawn().getWorld() != null) {
                player.teleport(cfg.getLobbySpawn());
            }
            plugin.getGameManager().autoJoinPlayer(player);
        }, 40L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Zuschauer-Modus (beendet eigenes Spiel, schaut anderem zu)
        if (plugin.getGameManager().isSpectating(player.getUniqueId())) {
            plugin.getGameManager().removeSpectator(player.getUniqueId());
            plugin.getGameManager().unloadPlayerData(player);
            return;
        }

        MobWaveGame game = plugin.getGameManager().getGameByPlayer(player);
        if (game != null) {
            GamePhase phase = game.getPhase();
            if (phase == GamePhase.EQUIP || phase == GamePhase.WAVE) {
                // Reconnect-fähiger Disconnect: Spieler bleibt in der Spielinstanz
                plugin.getGameManager().onPlayerDisconnect(player, game);
                return; // PlayerData bleibt im Cache für Reconnect
            }
            // LOBBY oder ENDED: normal entfernen
            plugin.getGameManager().leaveGame(player);
        }
        plugin.getGameManager().unloadPlayerData(player);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        MobWaveGame game = plugin.getGameManager().getGameByPlayer(player);
        if (game == null || game.getPhase() != GamePhase.WAVE) return;

        event.setKeepInventory(false);
        event.getDrops().clear();
        event.setDeathMessage(null);
        game.onPlayerDeath(player);
    }

    /** Nach dem Respawn Spieler zurück zur eigenen Arena-Base teleportieren. */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        MobWaveGame game = plugin.getGameManager().getGameByPlayer(player);
        if (game == null) return;

        if (game.getPhase() == GamePhase.WAVE && game.isDeadThisWave(player.getUniqueId())) {
            // Gestorbener Spieler → Spectator-Modus zur eigenen Arena-Base
            PlayerArena pa = game.getPlayerArena(player.getUniqueId());
            Location respawnLoc = pa != null && pa.getEquipSpawn() != null
                    ? pa.getEquipSpawn() : game.getLobbySpawn();
            if (respawnLoc != null) event.setRespawnLocation(respawnLoc);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                player.setGameMode(GameMode.SPECTATOR);
                MessageUtil.send(player, "§7Du bist Zuschauer bis zur nächsten Ausrüstungsphase.");
            }, 1L);
            return;
        }

        // Normaler Respawn: eigene Ausrüstungsbasis oder Fallback zur Lobby
        PlayerArena pa = game.getPlayerArena(player.getUniqueId());
        Location respawnLoc = pa != null ? pa.getEquipSpawn() : null;
        if (respawnLoc == null) {
            TemplateConfig cfg = plugin.getTemplateConfig();
            respawnLoc = cfg != null ? cfg.getLobbySpawn() : game.getLobbySpawn();
        }
        if (respawnLoc != null) {
            event.setRespawnLocation(respawnLoc);
        }
    }

    /** Verhindert, dass Spieler Items im Event droppen (bleiben sonst bis zur nächsten Wave erhalten). */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        MobWaveGame game = plugin.getGameManager().getGameByPlayer(event.getPlayer());
        if (game == null) return;
        event.setCancelled(true);
    }

    // ─── Blockschutz ────────────────────────────────────────────────────────

    /** Spieler im Event dürfen keine Blöcke abbauen – Feuerblöcke jedoch schon (Feuer löschen). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        MobWaveGame game = plugin.getGameManager().getGameByPlayer(event.getPlayer());
        if (game == null) return;
        Material broken = event.getBlock().getType();
        if (broken == Material.FIRE || broken == Material.SOUL_FIRE) return; // Feuer löschen erlaubt
        event.setCancelled(true);
    }

    /** Spieler im Event dürfen keine Blöcke platzieren.
     *  Taktische Elemente (Lava, Wasser, Feuer) sind nur in der Kampfphase erlaubt. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        MobWaveGame game = plugin.getGameManager().getGameByPlayer(event.getPlayer());
        if (game == null) return;
        Material placed = event.getBlockPlaced().getType();
        // Taktische Elemente nur während der Kampfphase
        if (game.getPhase() == GamePhase.WAVE) {
            if (placed == Material.LAVA || placed == Material.WATER) {
                game.trackPlacedLiquid(event.getBlockPlaced().getLocation());
                return;
            }
            if (placed == Material.POWDER_SNOW || placed == Material.FIRE || placed == Material.SOUL_FIRE) return;
        }
        event.setCancelled(true);
    }

    /** Explosionen durch Entitäten (Creeper, TNT, etc.) hinterlassen keine Blöcke.
     *  Wind-Charges: Selbst-Boost für den Schützen aktivieren. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        // Wind-Charge: Vanilla-Knockback um 5 % verstärken
        if (event.getEntity() instanceof WindCharge wc && wc.getShooter() instanceof Player shooter) {
            MobWaveGame game = plugin.getGameManager().getGameByPlayer(shooter);
            if (game != null) {
                // Im nächsten Tick ist Vanillas Knockback bereits angewendet → einfach skalieren
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                        shooter.setVelocity(shooter.getVelocity().multiply(1.05)), 1L);
            }
        }
        // Wenn irgendein Spiel aktiv ist, werden alle Explosionsblöcke geschützt
        if (!event.blockList().isEmpty()) {
            event.blockList().clear();
        }
    }

    /** Block-Explosionen (z.B. Bettexplosion) hinterlassen keine Blöcke. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().clear();
    }

    /** Schaden während der Ausrüstungsphase komplett deaktivieren. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        MobWaveGame game = plugin.getGameManager().getGameByPlayer(player);
        if (game == null) return;
        if (game.getPhase() == GamePhase.EQUIP || game.getPhase() == GamePhase.LOBBY) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        MobWaveGame game = plugin.getGameManager().getGameByPlayer(player);
        if (game == null || game.getPhase() != GamePhase.WAVE) return;
        game.onPlayerDamaged(player);
    }

    // ─── Mob-Kontrolle ──────────────────────────────────────────────────────

    /** Verhindert natürliches Mob-Spawnen in Event-Instanzwelten. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        String worldName = event.getLocation().getWorld().getName();
        if (!worldName.startsWith("mobwave_inst_")) return;
        // Nur Plugin-Spawns (CUSTOM) erlauben, alles andere canceln
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            event.setCancelled(true);
        }
    }

    /** Event-Monster droppen keinen Loot und geben keine XP. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEventMobDeath(EntityDeathEvent event) {
        NamespacedKey key = new NamespacedKey(plugin, "event_mob");
        if (!event.getEntity().getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    /** Verhindert, dass Event-Monster andere Entitäten außer Spielern angreifen. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMobTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        NamespacedKey key = new NamespacedKey(plugin, "event_mob");
        if (!mob.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;
        // Ziel muss ein Spieler sein
        if (!(event.getTarget() instanceof Player)) {
            event.setCancelled(true);
        }
    }

    /** Verhindert, dass Event-Monster sich gegenseitig beschädigen (direkt und via Projektil). */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMobAttackMob(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player) return; // Spieler-Schaden bleibt erlaubt
        if (!(event.getEntity() instanceof LivingEntity)) return;
        NamespacedKey key = new NamespacedKey(plugin, "event_mob");
        Mob attacker = null;
        if (event.getDamager() instanceof Mob m) {
            attacker = m;
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Mob m) {
            // Pfeil/Wurfmesser/etc. das von einem Event-Mob geschossen wurde
            attacker = m;
        }
        if (attacker != null && attacker.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    /** Verhindert, dass Tränke von Event-Mobs (Witch) andere Monster vergiften. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof LivingEntity thrower)) return;
        NamespacedKey key = new NamespacedKey(plugin, "event_mob");
        if (!thrower.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;
        // Nur Spieler als betroffene Ziele erlauben
        event.getAffectedEntities().removeIf(e -> !(e instanceof Player));
    }

    /** Verhindert, dass verweilende Tränke (Lingering) von Event-Mobs andere Monster treffen. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAreaEffectCloudApply(AreaEffectCloudApplyEvent event) {
        if (!(event.getEntity().getSource() instanceof LivingEntity source)) return;
        NamespacedKey key = new NamespacedKey(plugin, "event_mob");
        if (!source.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;
        event.getAffectedEntities().removeIf(e -> !(e instanceof Player));
    }

    /**
     * Verhündert, dass Spieler Items aus Loot-Kisten zurüclegen können.
     * Kisten können nur entnommen werden.
     * Verarbeitet außerdem Klicks im Shop-GUI.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onShopNpcInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        Player player = event.getPlayer();
        NamespacedKey key = new NamespacedKey(plugin, "shop_npc");
        if (!villager.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        MobWaveGame game = plugin.getGameManager().getGameByPlayer(player);
        if (game == null || game.getPhase() != GamePhase.EQUIP) {
            MessageUtil.send(player, "§cDer Shop ist nur in der Ausrüstungsphase verfügbar.");
            return;
        }
        PlayerData data = plugin.getGameManager().loadOrGetPlayerData(player);
        plugin.getGameManager().getShopManager().openShop(player, data);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Shop-Editor (Wizard) – muss vor dem Shop-GUI-Check stehen
        TemplateWizard wizard = plugin.getActiveWizards().get(player.getUniqueId());
        if (wizard != null && wizard.getCurrentStep() == TemplateWizard.Step.SHOP_EDITOR) {
            String viewTitle = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
            if (viewTitle.contains("Shop Editor")) {
                event.setCancelled(true);
                wizard.handleShopEditorClick(event);
                return;
            }
            if (viewTitle.contains("Preis bearbeiten")) {
                event.setCancelled(true);
                wizard.handlePriceEditorClick(event);
                return;
            }
        }

        MobWaveGame game = plugin.getGameManager().getGameByPlayer(player);

        // Während der Kampfphase: keine Interaktion mit externen Inventaren (z.B. Kisten)
        if (game != null && game.getPhase() == GamePhase.WAVE) {
            InventoryType topType = event.getView().getTopInventory().getType();
            if (topType != InventoryType.CRAFTING && topType != InventoryType.PLAYER) {
                event.setCancelled(true);
                player.sendMessage("§cDu kannst während der Kampfphase nicht auf Kisten zugreifen!");
                return;
            }
        }

        // Shop-GUI Verarbeitung: PlainText serialisieren um Color-Codes zu ignorieren
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (title.contains("[Shop]") || title.contains("Shop")) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
            if (game == null || game.getPhase() != GamePhase.EQUIP) return;

            ItemStack clicked = event.getCurrentItem();
            Material mat = clicked.getType();
            PlayerData data = plugin.getGameManager().loadOrGetPlayerData(player);
            PlayerArena pa = game.getPlayerArena(player.getUniqueId());
            plugin.getGameManager().getShopManager().processPurchase(player, data, mat, pa);
            return;
        }

    }

    /** Schließt der Admin den Preis-Editor ohne zu speichern, kehrt der Shop-Editor zurück. */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        TemplateWizard wizard = plugin.getActiveWizards().get(player.getUniqueId());
        if (wizard == null || wizard.getCurrentStep() != TemplateWizard.Step.SHOP_EDITOR) return;
        // Prüfen ob das geschlossene Inventar der Preis-Editor ist
        if (wizard.getPriceEditorInv() != null && event.getInventory().equals(wizard.getPriceEditorInv())) {
            wizard.cancelPriceEditor();
        }
    }
}
