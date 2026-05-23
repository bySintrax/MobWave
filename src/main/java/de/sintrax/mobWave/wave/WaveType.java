package de.sintrax.mobWave.wave;

public enum WaveType {
    EASY("§a§lEASY", "§aLeicht"),
    STANDARD("§e§lSTANDARD", "§eNormal"),
    HARD("§c§lHARD", "§cSchwer"),
    CHAOS("§4§lCHAOS", "§4Chaos"),
    BOSS("§5§lBOSS", "§5Boss");

    private final String displayTag;
    private final String displayName;

    WaveType(String displayTag, String displayName) {
        this.displayTag = displayTag;
        this.displayName = displayName;
    }

    public String getDisplayTag() { return displayTag; }
    public String getDisplayName() { return displayName; }
}
