package de.sintrax.mobWave.util;

import org.bukkit.entity.Player;

public class MessageUtil {

    private static String prefix = "§8[§6GlowingParadise§8]§7 ";

    public static void setPrefix(String prefix) {
        MessageUtil.prefix = prefix;
    }

    public static String getPrefix() {
        return prefix;
    }

    public static void send(Player player, String message) {
        player.sendMessage(prefix + message);
    }

    public static void sendRaw(Player player, String message) {
        player.sendMessage(message);
    }

    public static String color(String message) {
        return message.replace("&", "§");
    }
}
