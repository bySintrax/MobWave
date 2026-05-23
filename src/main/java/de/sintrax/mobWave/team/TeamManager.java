package de.sintrax.mobWave.team;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Verwaltet alle Teams (session-only, keine DB-Persistenz). */
public class TeamManager {

    /** Team-Name (lowercase) → Team */
    private final Map<String, Team> teams = new HashMap<>();
    /** Spieler-UUID → Team-Name (lowercase) */
    private final Map<UUID, String> playerTeam = new HashMap<>();

    // ─── Abfragen ───────────────────────────────────────────────────────────

    /** Gibt das Team zurück, in dem sich dieser Spieler befindet, oder null. */
    public Team getPlayerTeam(UUID uuid) {
        String name = playerTeam.get(uuid);
        return name != null ? teams.get(name) : null;
    }

    /** Gibt das Team mit dem angegebenen Namen zurück (case-insensitive) oder null. */
    public Team getTeam(String name) {
        return teams.get(name.toLowerCase());
    }

    /** Alle vorhandenen Teams. */
    public Collection<Team> getAllTeams() {
        return Collections.unmodifiableCollection(teams.values());
    }

    // ─── Erstellen / Auflösen ───────────────────────────────────────────────

    /**
     * Erstellt ein neues Team. Der Ersteller wird automatisch Leader und Mitglied.
     * @return das neue Team, oder null wenn ein Team mit diesem Namen schon existiert oder
     *         der Spieler bereits in einem Team ist.
     */
    public Team createTeam(String name, Player leader) {
        String key = name.toLowerCase();
        if (teams.containsKey(key)) return null;
        if (playerTeam.containsKey(leader.getUniqueId())) return null;

        Team team = new Team(name, leader.getUniqueId());
        teams.put(key, team);
        playerTeam.put(leader.getUniqueId(), key);
        return team;
    }

    /**
     * Löst ein Team auf und entfernt alle Mitglieder.
     */
    public void disbandTeam(Team team) {
        for (UUID uuid : team.getMembers()) {
            playerTeam.remove(uuid);
        }
        teams.remove(team.getName().toLowerCase());
    }

    // ─── Beitreten / Verlassen ──────────────────────────────────────────────

    /**
     * Fügt einen Spieler einem Team hinzu.
     * @return false, wenn der Spieler bereits in einem Team ist.
     */
    public boolean joinTeam(Team team, Player player) {
        if (playerTeam.containsKey(player.getUniqueId())) return false;
        team.addMember(player.getUniqueId());
        playerTeam.put(player.getUniqueId(), team.getName().toLowerCase());
        team.removeInvite(player.getUniqueId());
        return true;
    }

    /**
     * Entfernt einen Spieler aus seinem Team.
     * Wenn der Spieler Leader war, wird das Team aufgelöst (oder Leadership übergeben).
     * @return true wenn der Spieler ein Team hatte.
     */
    public boolean leaveTeam(Player player) {
        String teamKey = playerTeam.remove(player.getUniqueId());
        if (teamKey == null) return false;

        Team team = teams.get(teamKey);
        if (team == null) return true;

        team.removeMember(player.getUniqueId());

        if (team.isLeader(player.getUniqueId())) {
            // Nächstes Mitglied als Leader setzen oder Team auflösen
            UUID next = team.getMembers().stream().findFirst().orElse(null);
            if (next == null) {
                teams.remove(teamKey);
            } else {
                team.setLeader(next);
            }
        }
        return true;
    }

    // ─── Einladungen ────────────────────────────────────────────────────────

    /**
     * Schickt eine Einladung: inviter lädt invitee in sein Team ein.
     * @return false, wenn inviter kein Team hat oder invitee bereits in einem Team ist.
     */
    public boolean invitePlayer(Player inviter, Player invitee) {
        Team team = getPlayerTeam(inviter.getUniqueId());
        if (team == null) return false;
        if (playerTeam.containsKey(invitee.getUniqueId())) return false;
        team.addInvite(invitee.getUniqueId(), inviter.getUniqueId());
        return true;
    }

    /**
     * Spieler nimmt eine Einladung an.
     * Findet automatisch das Team, das inviterName eingeladen hat.
     * @return das Team, dem beigetreten wurde, oder null bei Fehler.
     */
    public Team acceptInvite(Player player, String inviterName) {
        // Suche Team, das diesen Spieler eingeladen hat (und dessen Leader/Mitglied inviterName heißt)
        for (Team team : teams.values()) {
            if (!team.hasPendingInvite(player.getUniqueId())) continue;
            UUID inviterUuid = team.getInviter(player.getUniqueId());
            // Prüfen ob inviterName stimmt (Spieler könnte offline sein)
            org.bukkit.entity.Entity inviterEntity = org.bukkit.Bukkit.getEntity(inviterUuid);
            String iName = inviterEntity instanceof Player ip ? ip.getName() : inviterUuid.toString();
            if (!iName.equalsIgnoreCase(inviterName)) continue;

            if (!joinTeam(team, player)) return null;
            return team;
        }
        return null;
    }

    // ─── Hilfsmethode für launchTemplateGames ───────────────────────────────

    /**
     * Sortiert die Spielerliste so, dass Team-Mitglieder benachbart sind.
     * Dadurch landen sie beim Aufteilen in Gruppen (subList) in derselben Instanz.
     */
    public List<Player> groupPlayersByTeam(List<Player> players) {
        List<Player>   result  = new ArrayList<>();
        List<UUID>     added   = new ArrayList<>();

        for (Player p : players) {
            if (added.contains(p.getUniqueId())) continue;
            result.add(p);
            added.add(p.getUniqueId());

            // Direkt die Team-Mitglieder nachziehen
            Team team = getPlayerTeam(p.getUniqueId());
            if (team == null) continue;
            for (UUID memberId : team.getMembers()) {
                if (added.contains(memberId)) continue;
                Player member = org.bukkit.Bukkit.getPlayer(memberId);
                if (member == null || !member.isOnline()) continue;
                if (!players.contains(member)) continue;
                result.add(member);
                added.add(memberId);
            }
        }
        return result;
    }
}
