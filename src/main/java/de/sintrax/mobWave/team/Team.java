package de.sintrax.mobWave.team;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Repräsentiert ein Spieler-Team für das MobWave-Event. */
public class Team {

    private final String name;
    private UUID leader;
    private final Set<UUID> members = new LinkedHashSet<>();
    /** Offene Einladungen: UUID des eingeladenen Spielers → UUID des Einladers */
    private final Map<UUID, UUID> pendingInvites = new HashMap<>();

    public Team(String name, UUID leader) {
        this.name   = name;
        this.leader = leader;
        members.add(leader);
    }

    public String getName()   { return name; }
    public UUID   getLeader() { return leader; }
    public void   setLeader(UUID uuid) { this.leader = uuid; }

    /** Alle Mitglieder (inkl. Leader) als unveränderliche Kopie. */
    public Set<UUID> getMembers() { return Collections.unmodifiableSet(members); }

    public boolean isMember(UUID uuid) { return members.contains(uuid); }
    public boolean isLeader(UUID uuid) { return leader.equals(uuid); }

    public void addMember(UUID uuid)    { members.add(uuid); }
    public void removeMember(UUID uuid) { members.remove(uuid); }

    // ─── Einladungen ────────────────────────────────────────────────────────

    public boolean hasPendingInvite(UUID invitee)      { return pendingInvites.containsKey(invitee); }
    public UUID    getInviter(UUID invitee)             { return pendingInvites.get(invitee); }
    public void    addInvite(UUID invitee, UUID inviter){ pendingInvites.put(invitee, inviter); }
    public void    removeInvite(UUID invitee)           { pendingInvites.remove(invitee); }
}
