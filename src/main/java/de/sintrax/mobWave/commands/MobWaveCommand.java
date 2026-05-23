package de.sintrax.mobWave.commands;

import de.sintrax.mobWave.MobWave;
import de.sintrax.mobWave.arena.TemplateWizard;
import de.sintrax.mobWave.game.MobWaveGame;
import de.sintrax.mobWave.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

public class MobWaveCommand implements CommandExecutor, TabCompleter {

    private final MobWave plugin;

    public MobWaveCommand(MobWave plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur Spieler können diesen Befehl verwenden.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start"  -> handleStart(player, args);
            case "stop"   -> handleStop(player, args);
            case "info"   -> handleInfo(player, args);
            case "stats"  -> handleStats(player, args);
            case "wizard" -> handleWizard(player, args);
            default -> sendHelp(player);
        }
        return true;
    }

    private void handleStart(Player player, String[] args) {
        if (!player.hasPermission("mobwave.admin")) {
            MessageUtil.send(player, "§cKeine Berechtigung.");
            return;
        }
        // Kein Argument → Template-Modus
        if (args.length < 2) {
            de.sintrax.mobWave.arena.TemplateConfig cfg = plugin.getTemplateConfig();
            if (cfg == null || !cfg.isConfigured()) {
                MessageUtil.send(player, "§cKein Template konfiguriert. Nutze §e/mw wizard §cum das Template einzurichten.");
                return;
            }
            plugin.getGameManager().startTemplateGame(player, null);
            return;
        }
        // Kein manueller Arena-Start mehr – nur Template-Modus
        MessageUtil.send(player, "§cNur Template-basierter Start unterstützt. Nutze §e/mw start §fohne Argumente.");
    }

    private void handleStop(Player player, String[] args) {
        if (!player.hasPermission("mobwave.admin")) {
            MessageUtil.send(player, "§cKeine Berechtigung.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(player, "§cVerwendung: /mw stop <arena-id>");
            return;
        }
        MobWaveGame game = plugin.getGameManager().getGame(args[1]);
        if (game == null) {
            MessageUtil.send(player, "§cKein aktives Event in Arena §e" + args[1] + "§c.");
            return;
        }
        game.forceStop();
        MessageUtil.send(player, "§aEvent in Arena §e" + args[1] + " §agestoppt.");
    }

    private void handleInfo(Player player, String[] args) {
        if (args.length < 2) {
            // Template-Status
            de.sintrax.mobWave.arena.TemplateConfig cfg = plugin.getTemplateConfig();
            MessageUtil.sendRaw(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
            MessageUtil.sendRaw(player, "§6Template-Arena §7– Status");
            MessageUtil.sendRaw(player, "§7Welt: §f" + (cfg.getTemplateWorldName().isEmpty() ? "§cNicht gesetzt" : cfg.getTemplateWorldName()));
            MessageUtil.sendRaw(player, "§7Spieler/Instanz: §f" + cfg.getPlayersPerInstance());
            MessageUtil.sendRaw(player, "§7Lobby-Spawn: §f" + (cfg.getLobbySpawn() != null ? "§a✔" : "§cNicht gesetzt"));
            MessageUtil.sendRaw(player, "§7Kampf-Spawn: §f" + (cfg.getFightSpawn() != null ? "§a✔" : "§cNicht gesetzt"));
            MessageUtil.sendRaw(player, "§7Ausrüstungs-Spawn: §f" + (cfg.getEquipSpawn() != null ? "§a✔" : "§cNicht gesetzt"));
            MessageUtil.sendRaw(player, "§7Loot-Kisten: §f" + cfg.getLootChestLocations().size());
            MessageUtil.sendRaw(player, "§7Konfiguriert: §f" + (cfg.isConfigured() ? "§aJa" : "§cNein"));
            // Laufende Spiele
            MessageUtil.sendRaw(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
            MessageUtil.sendRaw(player, "§6Laufende Events");
            Set<String> activeIds = plugin.getGameManager().getActiveGameIds();
            if (activeIds.isEmpty()) {
                MessageUtil.sendRaw(player, "§7Keine aktiven Events.");
            } else {
                for (String id : activeIds) {
                    MobWaveGame game = plugin.getGameManager().getGame(id);
                    MessageUtil.sendRaw(player, "  §f" + id + " §8| §7Phase: §e" + game.getPhase().name()
                            + " §7| Wave: §e" + game.getCurrentWave()
                            + " §7| Spieler: §e" + game.getParticipants().size());
                }
            }
            MessageUtil.sendRaw(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
            return;
        }
        // /mw info <game-id>: aktives Spiel anzeigen
        MobWaveGame game = plugin.getGameManager().getGame(args[1]);
        if (game == null) {
            MessageUtil.send(player, "§cKein aktives Event mit ID §e" + args[1] + "§c.");
            return;
        }
        MessageUtil.send(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
        MessageUtil.send(player, "§6Spiel-ID: §f" + game.getGameId());
        MessageUtil.send(player, "§7Phase: §f" + game.getPhase());
        MessageUtil.send(player, "§7Wave: §f" + game.getCurrentWave());
        MessageUtil.send(player, "§7Spieler: §f" + game.getParticipants().size());
        MessageUtil.send(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void handleStats(Player player, String[] args) {
        Player target = player;
        if (args.length >= 2) {
            target = org.bukkit.Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                MessageUtil.send(player, "§cSpieler §e" + args[1] + " §cnicht gefunden oder nicht online.");
                return;
            }
        }
        de.sintrax.mobWave.database.PlayerData data = plugin.getGameManager().loadOrGetPlayerData(target);
        MessageUtil.sendRaw(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
        MessageUtil.sendRaw(player, "§6Stats: §f" + target.getName());
        MessageUtil.sendRaw(player, "§7Gesamtpunkte §8(Stat)§7: §e" + data.getTotalPoints());
        MessageUtil.sendRaw(player, "§7Waves überlebt: §e" + data.getWavesSurvived());
        MessageUtil.sendRaw(player, "§7Events gespielt: §e" + data.getGamesPlayed());
        MessageUtil.sendRaw(player, "§7Perfect Runs: §e" + data.getPerfectRuns());
        MessageUtil.sendRaw(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void handleWizard(Player player, String[] args) {
        if (!player.hasPermission("mobwave.admin")) {
            MessageUtil.send(player, "§cKeine Berechtigung.");
            return;
        }

        TemplateWizard wizard = plugin.getActiveWizards().get(player.getUniqueId());
        if (wizard == null) {
            wizard = new TemplateWizard(plugin, player);
            plugin.getActiveWizards().put(player.getUniqueId(), wizard);
            return;
        }

        String subCmd = args.length >= 2 ? args[1].toLowerCase() : "";
        String[] subArgs = args.length >= 3 ? Arrays.copyOfRange(args, 2, args.length) : new String[0];

        switch (subCmd) {
            case "confirm" -> wizard.handleConfirm();
            case "next"    -> wizard.handleNext();
            case "skip"    -> wizard.handleSkip();
            case "set"     -> wizard.handleSet(subArgs);
            default        -> wizard.sendInstruction();
        }

        if (wizard.isDone()) {
            plugin.getActiveWizards().remove(player.getUniqueId());
        }
    }

    private void sendHelp(Player player) {
        MessageUtil.sendRaw(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
        MessageUtil.sendRaw(player, "§6§lMobWave §7– Befehle");
        MessageUtil.sendRaw(player, "§e/mw info [arena] §7– Informationen");
        MessageUtil.sendRaw(player, "§e/mw stats [spieler] §7– Statistiken anzeigen");
        if (player.hasPermission("mobwave.admin")) {
            MessageUtil.sendRaw(player, "§c/mw wizard §7– Template einrichten");
            MessageUtil.sendRaw(player, "§c/mw start §7– Event starten");
            MessageUtil.sendRaw(player, "§c/mw stop <id> §7– Event stoppen");
        }
        MessageUtil.sendRaw(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("info", "stats"));
            if (sender.hasPermission("mobwave.admin")) {
                subs.addAll(List.of("wizard", "start", "stop"));
            }
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("wizard")) {
            if (!(sender instanceof Player p)) return Collections.emptyList();
            TemplateWizard wiz = plugin.getActiveWizards().get(p.getUniqueId());
            if (wiz == null) return Collections.emptyList();
            return wizardActionsForStep(wiz.getCurrentStep()).stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
            return plugin.getGameManager().getActiveGameIds().stream()
                    .filter(id -> id.startsWith(args[1].toLowerCase())).toList();
        }

        // /mw wizard set <wert> → kontextabhängige Vorschläge
        if (args.length == 3 && args[0].equalsIgnoreCase("wizard") && args[1].equalsIgnoreCase("set")) {
            if (!(sender instanceof Player p)) return Collections.emptyList();
            TemplateWizard wiz = plugin.getActiveWizards().get(p.getUniqueId());
            if (wiz == null) return Collections.emptyList();
            return wizardSetSuggestions(wiz.getCurrentStep(), args[2]);
        }

        return Collections.emptyList();
    }

    /** Gibt sinnvolle Aktionen für einen Wizard-Schritt zurück. */
    private List<String> wizardActionsForStep(TemplateWizard.Step step) {
        return switch (step) {
            case TEMPLATE_WORLD   -> List.of("set");
            case LOBBY_SPAWN      -> List.of("confirm");
            case FIGHT_REGION     -> List.of("confirm", "skip");
            case FIGHT_SPAWN      -> List.of("confirm");
            case MOB_SPAWN        -> List.of("confirm", "skip");
            case EQUIP_REGION     -> List.of("confirm", "skip");
            case EQUIP_SPAWN      -> List.of("confirm");
            case LOOT_CHESTS      -> List.of("confirm", "next");
            case SHOP_NPC         -> List.of("confirm", "skip");
            case PLAYERS_PER_INST -> List.of("set");
            case SHOP_EDITOR      -> List.of("confirm", "next", "skip", "set");
            default               -> List.of("confirm", "next", "skip", "set");
        };
    }

    /** Gibt Wert-Vorschläge für /mw wizard set <wert> zurück, je nach Schritt. */
    private List<String> wizardSetSuggestions(TemplateWizard.Step step, String typed) {
        if (step == TemplateWizard.Step.TEMPLATE_WORLD) {
            File worldContainer = org.bukkit.Bukkit.getWorldContainer();
            File[] dirs = worldContainer.listFiles(f -> f.isDirectory()
                    && !f.getName().startsWith("mobwave_inst_"));
            if (dirs == null) return Collections.emptyList();
            List<String> worlds = new ArrayList<>();
            for (File d : dirs) {
                if (d.getName().toLowerCase().startsWith(typed.toLowerCase())) {
                    worlds.add(d.getName());
                }
            }
            return worlds;
        }
        if (step == TemplateWizard.Step.PLAYERS_PER_INST) {
            return List.of("1", "2", "3", "4", "5", "6", "8", "10").stream()
                    .filter(s -> s.startsWith(typed)).toList();
        }
        return Collections.emptyList();
    }
}
