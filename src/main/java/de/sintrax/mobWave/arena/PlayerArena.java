package de.sintrax.mobWave.arena;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persönliche Spieler-Arena: enthält die Kampfarena, die Ausrüstungsbasis und Shop-Kiste.
 */
public class PlayerArena {

    private final int index;
    private Location fightSpawn;
    private Location mobSpawn;   // Spawn-Punkt der Monster (optional, Fallback: fightSpawn)
    private Location equipSpawn;
    private Location shopNpcLocation;
    private final List<Location> lootChestLocations = new ArrayList<>();
    private boolean occupied;
    private UUID assignedPlayer;

    // FAWE-Regions-Grenzen (Eckpunkte der Kampf- und Ausrüstungszone)
    private Location fightRegionMin;
    private Location fightRegionMax;
    private Location equipRegionMin;
    private Location equipRegionMax;

    public PlayerArena(int index) {
        this.index = index;
    }

    public int getIndex() { return index; }

    public Location getFightSpawn() { return fightSpawn; }
    public void setFightSpawn(Location fightSpawn) { this.fightSpawn = fightSpawn; }

    public Location getMobSpawn() { return mobSpawn != null ? mobSpawn : fightSpawn; }
    public void setMobSpawn(Location mobSpawn) { this.mobSpawn = mobSpawn; }

    public Location getEquipSpawn() { return equipSpawn; }
    public void setEquipSpawn(Location equipSpawn) { this.equipSpawn = equipSpawn; }

    public Location getShopNpcLocation() { return shopNpcLocation; }
    public void setShopNpcLocation(Location shopNpcLocation) { this.shopNpcLocation = shopNpcLocation; }

    public List<Location> getLootChestLocations() { return lootChestLocations; }
    public void addLootChest(Location loc) { lootChestLocations.add(loc); }

    public boolean isOccupied() { return occupied; }
    public void setOccupied(boolean occupied) { this.occupied = occupied; }

    public UUID getAssignedPlayer() { return assignedPlayer; }
    public void setAssignedPlayer(UUID assignedPlayer) { this.assignedPlayer = assignedPlayer; }

    public Location getFightRegionMin() { return fightRegionMin; }
    public void setFightRegionMin(Location fightRegionMin) { this.fightRegionMin = fightRegionMin; }

    public Location getFightRegionMax() { return fightRegionMax; }
    public void setFightRegionMax(Location fightRegionMax) { this.fightRegionMax = fightRegionMax; }

    public Location getEquipRegionMin() { return equipRegionMin; }
    public void setEquipRegionMin(Location equipRegionMin) { this.equipRegionMin = equipRegionMin; }

    public Location getEquipRegionMax() { return equipRegionMax; }
    public void setEquipRegionMax(Location equipRegionMax) { this.equipRegionMax = equipRegionMax; }

    public boolean hasFightRegion() { return fightRegionMin != null && fightRegionMax != null; }
    public boolean hasEquipRegion() { return equipRegionMin != null && equipRegionMax != null; }

    public boolean isConfigured() {
        return fightSpawn != null && equipSpawn != null && !lootChestLocations.isEmpty();
    }

    public void release() {
        this.occupied = false;
        this.assignedPlayer = null;
    }
}

