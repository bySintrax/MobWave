package de.sintrax.mobWave.commands;

import de.sintrax.mobWave.MobWave;
import de.sintrax.mobWave.arena.PlayerArena;
import de.sintrax.mobWave.database.PlayerData;
import de.sintrax.mobWave.game.GamePhase;
import de.sintrax.mobWave.game.MobWaveGame;
import de.sintrax.mobWave.loot.ShopManager;
import de.sintrax.mobWave.util.MessageUtil;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ShopCommand implements CommandExecutor, TabCompleter {

    private final MobWave plugin;

    public ShopCommand(MobWave plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur Spieler können diesen Befehl verwenden.");
            return true;
        }

        MobWaveGame game = plugin.getGameManager().getGameByPlayer(player);
        if (game == null) {
            MessageUtil.send(player, "§cDu nimmst an keinem Event teil.");
            return true;
        }
        if (game.getPhase() != GamePhase.EQUIP) {
            MessageUtil.send(player, "§cDer Shop ist nur in der Ausrüstungsphase verfügbar.");
            return true;
        }

        PlayerData data = plugin.getGameManager().loadOrGetPlayerData(player);
        ShopManager shopManager = plugin.getGameManager().getShopManager();

        if (args.length == 0) {
            // Shop-GUI öffnen
            shopManager.openShop(player, data);
            return true;
        }

        // /shop buy <item>
        if (args[0].equalsIgnoreCase("buy") && args.length >= 2) {
            try {
                Material mat = Material.valueOf(args[1].toUpperCase());
                PlayerArena pa = game.getPlayerArena(player.getUniqueId());
                shopManager.processPurchase(player, data, mat, pa);
            } catch (IllegalArgumentException e) {
                MessageUtil.send(player, "§cUnbekanntes Item: §e" + args[1]);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("buy");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            ConfigurationSection shopSection = plugin.getConfig().getConfigurationSection("shop.items");
            if (shopSection == null) return Collections.emptyList();
            List<String> items = new ArrayList<>(shopSection.getKeys(false));
            return items.stream().filter(s -> s.startsWith(args[1].toUpperCase())).toList();
        }
        return Collections.emptyList();
    }
}
