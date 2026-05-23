package de.sintrax.mobWave.commands;

import de.sintrax.mobWave.MobWave;
import de.sintrax.mobWave.team.Team;
import de.sintrax.mobWave.team.TeamManager;
import de.sintrax.mobWave.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /team <create|invite|accept|join|leave|info>
 */
public class TeamCommand implements CommandExecutor, TabCompleter {

    private final MobWave plugin;
    private final TeamManager teamManager;

    public TeamCommand(MobWave plugin) {
        this.plugin      = plugin;
        this.teamManager = plugin.getTeamManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cNur Spieler können diesen Befehl nutzen.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(player, args);
            case "invite" -> handleInvite(player, args);
            case "accept" -> handleAccept(player, args);
            case "join"   -> handleJoin(player, args);
            case "leave"  -> handleLeave(player);
            case "info"   -> handleInfo(player, args);
            default -> { sendHelp(player); yield true; }
        };
    }

    // ─── Sub-Commands ────────────────────────────────────────────────────────

    private boolean handleCreate(Player player, String[] args) {
        if (args.length < 2) {
            MessageUtil.send(player, "§cUsage: /team create <Name>");
            return true;
        }
        String name = args[1];
        if (name.length() > 16) {
            MessageUtil.send(player, "§cDer Team-Name darf maximal 16 Zeichen lang sein.");
            return true;
        }
        if (teamManager.getPlayerTeam(player.getUniqueId()) != null) {
            MessageUtil.send(player, "§cDu bist bereits in einem Team. Verlasse es zuerst mit §e/team leave§c.");
            return true;
        }
        Team team = teamManager.createTeam(name, player);
        if (team == null) {
            MessageUtil.send(player, "§cEin Team mit diesem Namen existiert bereits.");
            return true;
        }
        MessageUtil.send(player, "§aDu hast das Team §e" + name + " §agegründet! Lade Spieler ein mit §e/team invite <Spieler>§a.");
        return true;
    }

    private boolean handleInvite(Player player, String[] args) {
        if (args.length < 2) {
            MessageUtil.send(player, "§cUsage: /team invite <Spieler>");
            return true;
        }
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            MessageUtil.send(player, "§cDu bist in keinem Team. Erstelle eines mit §e/team create <Name>§c.");
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            MessageUtil.send(player, "§cDer Spieler §e" + args[1] + " §cist nicht online.");
            return true;
        }
        if (target.equals(player)) {
            MessageUtil.send(player, "§cDu kannst dich nicht selbst einladen.");
            return true;
        }
        if (teamManager.getPlayerTeam(target.getUniqueId()) != null) {
            MessageUtil.send(player, "§c" + target.getName() + " §cist bereits in einem Team.");
            return true;
        }
        if (team.hasPendingInvite(target.getUniqueId())) {
            MessageUtil.send(player, "§c" + target.getName() + " §chat bereits eine offene Einladung.");
            return true;
        }
        boolean ok = teamManager.invitePlayer(player, target);
        if (!ok) {
            MessageUtil.send(player, "§cEinladung fehlgeschlagen.");
            return true;
        }
        MessageUtil.send(player, "§aEinladung an §e" + target.getName() + " §agesendet.");
        MessageUtil.send(target, "§e" + player.getName() + " §7hat dich in das Team §e" + team.getName()
                + " §7eingeladen. Tritt bei mit §a/team accept " + player.getName() + "§7.");
        return true;
    }

    private boolean handleAccept(Player player, String[] args) {
        if (args.length < 2) {
            MessageUtil.send(player, "§cUsage: /team accept <Einlader>");
            return true;
        }
        if (teamManager.getPlayerTeam(player.getUniqueId()) != null) {
            MessageUtil.send(player, "§cDu bist bereits in einem Team.");
            return true;
        }
        Team team = teamManager.acceptInvite(player, args[1]);
        if (team == null) {
            MessageUtil.send(player, "§cKeine Einladung von §e" + args[1] + " §cgefunden.");
            return true;
        }
        MessageUtil.send(player, "§aDu bist dem Team §e" + team.getName() + " §abeigetreten!");
        // Alle Teammitglieder benachrichtigen
        for (java.util.UUID memberId : team.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && !member.equals(player)) {
                MessageUtil.send(member, "§e" + player.getName() + " §aist dem Team beigetreten!");
            }
        }
        return true;
    }

    private boolean handleJoin(Player player, String[] args) {
        if (args.length < 2) {
            MessageUtil.send(player, "§cUsage: /team join <Name>");
            return true;
        }
        if (teamManager.getPlayerTeam(player.getUniqueId()) != null) {
            MessageUtil.send(player, "§cDu bist bereits in einem Team. Verlasse es mit §e/team leave§c.");
            return true;
        }
        Team team = teamManager.getTeam(args[1]);
        if (team == null) {
            MessageUtil.send(player, "§cDas Team §e" + args[1] + " §cexistiert nicht.");
            return true;
        }
        // Beitreten nur möglich, wenn eine Einladung vorliegt
        if (!team.hasPendingInvite(player.getUniqueId())) {
            MessageUtil.send(player, "§cDu hast keine Einladung zu dem Team §e" + team.getName() + "§c.");
            return true;
        }
        teamManager.joinTeam(team, player);
        MessageUtil.send(player, "§aDu bist dem Team §e" + team.getName() + " §abeigetreten!");
        for (java.util.UUID memberId : team.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && !member.equals(player)) {
                MessageUtil.send(member, "§e" + player.getName() + " §aist dem Team beigetreten!");
            }
        }
        return true;
    }

    private boolean handleLeave(Player player) {
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            MessageUtil.send(player, "§cDu bist in keinem Team.");
            return true;
        }
        String teamName = team.getName();
        // Mitglieder benachrichtigen vor dem Verlassen
        for (java.util.UUID memberId : team.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && !member.equals(player)) {
                MessageUtil.send(member, "§e" + player.getName() + " §chat das Team verlassen.");
            }
        }
        teamManager.leaveTeam(player);
        MessageUtil.send(player, "§7Du hast das Team §e" + teamName + " §7verlassen.");
        return true;
    }

    private boolean handleInfo(Player player, String[] args) {
        Team team;
        if (args.length >= 2) {
            team = teamManager.getTeam(args[1]);
            if (team == null) {
                MessageUtil.send(player, "§cDas Team §e" + args[1] + " §cexistiert nicht.");
                return true;
            }
        } else {
            team = teamManager.getPlayerTeam(player.getUniqueId());
            if (team == null) {
                MessageUtil.send(player, "§cDu bist in keinem Team.");
                return true;
            }
        }

        MessageUtil.send(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
        MessageUtil.send(player, "§6Team: §e" + team.getName());

        StringBuilder members = new StringBuilder();
        for (java.util.UUID memberId : team.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            boolean online = member != null && member.isOnline();
            String nameStr = online ? member.getName() : memberId.toString().substring(0, 8) + "…";
            boolean isLeader = team.isLeader(memberId);
            members.append(isLeader ? "§6★" : "§7").append(nameStr).append("§8, ");
        }
        if (members.length() > 2) members.setLength(members.length() - 2);
        MessageUtil.send(player, "§7Mitglieder: " + members);
        MessageUtil.send(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
        return true;
    }

    private void sendHelp(Player player) {
        MessageUtil.send(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
        MessageUtil.send(player, "§6Team-Befehle§8:");
        MessageUtil.send(player, "  §e/team create <Name> §7– Team gründen");
        MessageUtil.send(player, "  §e/team invite <Spieler> §7– Spieler einladen");
        MessageUtil.send(player, "  §e/team accept <Einlader> §7– Einladung annehmen");
        MessageUtil.send(player, "  §e/team join <Name> §7– Team beitreten (mit Einladung)");
        MessageUtil.send(player, "  §e/team leave §7– Team verlassen");
        MessageUtil.send(player, "  §e/team info [Name] §7– Team-Informationen");
        MessageUtil.send(player, "§7━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    // ─── Tab-Completion ──────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return List.of();
        if (args.length == 1) {
            return List.of("create", "invite", "accept", "join", "leave", "info").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "invite" -> Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
                case "accept" -> Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
                case "join", "info" -> teamManager.getAllTeams().stream()
                        .map(Team::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
                default -> List.of();
            };
        }
        return List.of();
    }
}
