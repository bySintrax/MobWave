package de.sintrax.mobWave.placeholder;

import de.sintrax.mobWave.MobWave;
import de.sintrax.mobWave.database.PlayerData;
import de.sintrax.mobWave.game.MobWaveGame;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class MobWavePlaceholder extends PlaceholderExpansion {

    private final MobWave plugin;

    public MobWavePlaceholder(MobWave plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "mobwave";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Sintrax";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        PlayerData data = plugin.getGameManager().loadOrGetPlayerData(player);
        MobWaveGame game = plugin.getGameManager().getGameByPlayer(player);

        return switch (params.toLowerCase()) {
            case "points" -> String.valueOf(data.getTotalPoints());
            case "session_points" -> String.valueOf(data.getSessionPoints());
            case "waves_survived" -> String.valueOf(data.getWavesSurvived());
            case "games_played" -> String.valueOf(data.getGamesPlayed());
            case "perfect_runs" -> String.valueOf(data.getPerfectRuns());
            case "current_wave" -> game != null ? String.valueOf(game.getCurrentWave()) : "0";
            case "wave_type" -> game != null && game.getCurrentWaveType() != null
                    ? game.getCurrentWaveType().getDisplayName()
                    : "§7-";
            case "phase" -> game != null ? game.getPhase().name() : "NICHT IM EVENT";
            case "in_game" -> game != null ? "true" : "false";
            default -> null;
        };
    }
}
