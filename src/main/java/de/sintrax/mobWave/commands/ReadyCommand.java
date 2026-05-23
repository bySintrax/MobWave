package de.sintrax.mobWave.commands;

import de.sintrax.mobWave.MobWave;
import de.sintrax.mobWave.game.MobWaveGame;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ReadyCommand implements CommandExecutor {

    private final MobWave plugin;

    public ReadyCommand(MobWave plugin) {
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
            de.sintrax.mobWave.util.MessageUtil.send(player, "§cDu nimmst an keinem Event teil.");
            return true;
        }
        game.setPlayerReady(player);
        return true;
    }
}
