package de.sintrax.mobWave.wave;

import de.sintrax.mobWave.MobWave;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Guardian;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class WaveSpawner {

    private final MobWave plugin;
    private final Random random = new Random();

    public WaveSpawner(MobWave plugin) {
        this.plugin = plugin;
    }

    /**
     * Spawnt die Mobs für eine Wave und gibt die Liste der gespawnten Entitäten zurück.
     */
    public List<LivingEntity> spawnWave(WaveType waveType, int waveNumber, Location spawnLocation) {
        List<LivingEntity> spawnedMobs = new ArrayList<>();

        if (spawnLocation == null || spawnLocation.getWorld() == null) {
            plugin.getLogger().warning("[WaveSpawner] Mob-Spawn-Location ist null für Wave " + waveNumber + "!");
            return spawnedMobs;
        }

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("wave_mobs." + waveType.name());
        if (section == null) {
            plugin.getLogger().warning("[WaveSpawner] Keine wave_mobs-Konfiguration für Typ: " + waveType.name());
            return spawnedMobs;
        }

        int base = section.getInt("base", 5);
        double multiplier = section.getDouble("multiplier", 1.0);
        List<String> typeNames = section.getStringList("types");

        if (typeNames.isEmpty()) {
            plugin.getLogger().warning("[WaveSpawner] Keine Mob-Typen in wave_mobs." + waveType.name() + ".types konfiguriert!");
            return spawnedMobs;
        }

        int mobCount = (int) Math.max(1, Math.ceil(base + (waveNumber - 1) * multiplier));

        // Chunk laden (synchron), damit spawnEntity nicht scheitert
        spawnLocation.getWorld().loadChunk(spawnLocation.getChunk());

        // PDC-Schlüssel für Event-Mobs
        NamespacedKey eventMobKey = new NamespacedKey(plugin, "event_mob");

        for (int i = 0; i < mobCount; i++) {
            String typeName = typeNames.get(random.nextInt(typeNames.size()));
            try {
                EntityType entityType = EntityType.valueOf(typeName);

                // 5-Block Radius Versatz um den Spawn-Punkt
                Location spawnLoc = spawnLocation.clone().add(
                        (random.nextDouble() * 10.0) - 5.0,
                        0,
                        (random.nextDouble() * 10.0) - 5.0
                );

                org.bukkit.entity.Entity entity = spawnLocation.getWorld().spawnEntity(spawnLoc, entityType);
                if (entity instanceof LivingEntity mob) {
                    // Als Event-Mob markieren
                    mob.getPersistentDataContainer().set(eventMobKey, PersistentDataType.BYTE, (byte) 1);

                    // Kein automatisches Despawnen
                    if (mob instanceof Mob m) {
                        m.setRemoveWhenFarAway(false);
                    }

                    // Follow-Range setzen – Guardian/ElderGuardian auf 20 Blöcke begrenzen
                    var followRange = mob.getAttribute(Attribute.FOLLOW_RANGE);
                    if (followRange != null) {
                        double range = (mob instanceof Guardian) ? 20.0 : 128.0;
                        followRange.setBaseValue(range);
                    }

                    applyWaveBuffs(mob, waveType, waveNumber);
                    spawnedMobs.add(mob);
                } else {
                    plugin.getLogger().warning("[WaveSpawner] Entity " + typeName + " ist keine LivingEntity, wird ignoriert.");
                    entity.remove();
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[WaveSpawner] Unbekannter Entity-Typ in config: " + typeName);
            } catch (Exception e) {
                plugin.getLogger().warning("[WaveSpawner] Fehler beim Spawnen von " + typeName + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("[WaveSpawner] Wave " + waveNumber + " (" + waveType.name() + "): " + spawnedMobs.size() + "/" + mobCount + " Mobs gespawnt.");
        return spawnedMobs;
    }

    private void applyWaveBuffs(LivingEntity entity, WaveType waveType, int waveNumber) {
        double healthMultiplier = switch (waveType) {
            case EASY -> 0.75;
            case STANDARD -> 1.0;
            case HARD -> 1.2;
            case CHAOS -> 1.5;
            case BOSS -> 4.0;
        };
        // Sanfteres Skalieren: pro Wave +5 % statt +10 %
        double waveScaling = 1.0 + (waveNumber - 1) * 0.05;

        // HP über Attribut-API setzen (nicht via deprecated setMaxHealth)
        var healthAttr = entity.getAttribute(Attribute.MAX_HEALTH);
        if (healthAttr != null) {
            double newHealth = Math.max(1.0, Math.min(
                    healthAttr.getBaseValue() * healthMultiplier * waveScaling, 2048.0));
            healthAttr.setBaseValue(newHealth);
            entity.setHealth(newHealth); // aktuelle HP auf neues Maximum setzen
        }

        // Boss-Wave: Geschwindigkeit erhöhen
        if (waveType == WaveType.BOSS) {
            var speedAttr = entity.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speedAttr != null) {
                speedAttr.setBaseValue(speedAttr.getBaseValue() * 1.3);
            }
        }
    }
}
