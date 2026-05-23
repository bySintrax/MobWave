package de.sintrax.mobWave.wave;

import de.sintrax.mobWave.MobWave;
import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

public class WaveTypeSelector {

    private final MobWave plugin;
    private final Random random = new Random();

    public WaveTypeSelector(MobWave plugin) {
        this.plugin = plugin;
    }

    /**
     * Wählt einen Wave-Typ basierend auf der aktuellen Wellennummer.
     */
    public WaveType selectWaveType(int waveNumber) {
        String section;
        if (waveNumber >= 15) {
            section = "wave_chances_late";
        } else if (waveNumber >= 10) {
            section = "wave_chances_mid";
        } else {
            section = "wave_chances_early";
        }

        Map<WaveType, Integer> chances = loadChances(section);
        return weightedRandom(chances);
    }

    /**
     * Gibt die Wahrscheinlichkeiten als Prozentwerte zurück (für Anzeige in Ausrüstungsphase).
     */
    public Map<WaveType, Integer> getChancesForWave(int waveNumber) {
        String section;
        if (waveNumber >= 15) {
            section = "wave_chances_late";
        } else if (waveNumber >= 10) {
            section = "wave_chances_mid";
        } else {
            section = "wave_chances_early";
        }
        return loadChances(section);
    }

    private Map<WaveType, Integer> loadChances(String sectionName) {
        Map<WaveType, Integer> chances = new EnumMap<>(WaveType.class);
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(sectionName);
        if (section == null) {
            // Fallback
            chances.put(WaveType.EASY, 20);
            chances.put(WaveType.STANDARD, 40);
            chances.put(WaveType.HARD, 25);
            chances.put(WaveType.CHAOS, 10);
            chances.put(WaveType.BOSS, 5);
            return chances;
        }
        for (WaveType type : WaveType.values()) {
            chances.put(type, section.getInt(type.name(), 0));
        }
        return chances;
    }

    private WaveType weightedRandom(Map<WaveType, Integer> chances) {
        int total = chances.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return WaveType.STANDARD;
        int roll = random.nextInt(total);
        int cumulative = 0;
        for (Map.Entry<WaveType, Integer> entry : chances.entrySet()) {
            cumulative += entry.getValue();
            if (roll < cumulative) {
                return entry.getKey();
            }
        }
        return WaveType.STANDARD;
    }
}
