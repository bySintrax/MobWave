package de.sintrax.mobWave.database;

import java.util.UUID;

public class PlayerData {

    private final UUID uuid;
    private String name;
    private int totalPoints;
    private int wavesSurvived;
    private int gamesPlayed;
    private int perfectRuns;

    // Sitzungs-Daten (nicht in DB)
    private int sessionPoints;
    private boolean tookDamageThisWave;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID getUniqueId() { return uuid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getTotalPoints() { return totalPoints; }
    public void setTotalPoints(int totalPoints) { this.totalPoints = totalPoints; }
    public void addPoints(int amount) { this.totalPoints += amount; this.sessionPoints += amount; }

    public int getSessionPoints() { return sessionPoints; }
    public void resetSessionPoints() { this.sessionPoints = 0; }

    public int getWavesSurvived() { return wavesSurvived; }
    public void setWavesSurvived(int wavesSurvived) { this.wavesSurvived = wavesSurvived; }
    public void incrementWavesSurvived() { this.wavesSurvived++; }

    public int getGamesPlayed() { return gamesPlayed; }
    public void setGamesPlayed(int gamesPlayed) { this.gamesPlayed = gamesPlayed; }
    public void incrementGamesPlayed() { this.gamesPlayed++; }

    public int getPerfectRuns() { return perfectRuns; }
    public void setPerfectRuns(int perfectRuns) { this.perfectRuns = perfectRuns; }
    public void incrementPerfectRuns() { this.perfectRuns++; }

    public boolean hasTookDamageThisWave() { return tookDamageThisWave; }
    public void setTookDamageThisWave(boolean tookDamageThisWave) { this.tookDamageThisWave = tookDamageThisWave; }
    public void resetDamageFlag() { this.tookDamageThisWave = false; }

    /** Zieht Punkte vom Session-Guthaben ab (z.B. Shop-Kauf). Stat (totalPoints) bleibt unverändert. */
    public void spendPoints(int amount) {
        this.sessionPoints = Math.max(0, this.sessionPoints - amount);
    }
}
