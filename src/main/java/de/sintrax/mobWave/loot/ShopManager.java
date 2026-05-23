package de.sintrax.mobWave.loot;

import de.sintrax.mobWave.MobWave;
import de.sintrax.mobWave.arena.PlayerArena;
import de.sintrax.mobWave.database.PlayerData;
import de.sintrax.mobWave.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class ShopManager {

    private final MobWave plugin;

    public ShopManager(MobWave plugin) {
        this.plugin = plugin;
    }

    /**
     * Öffnet das Shop-GUI für den Spieler.
     */
    public void openShop(Player player, PlayerData data) {
        Component title = LegacyComponentSerializer.legacySection()
                .deserialize("§8[§6Shop§8] §7Punkte: §e" + data.getSessionPoints());
        Inventory shopInv = org.bukkit.Bukkit.createInventory(null, 54, title);

        ConfigurationSection shopSection = plugin.getConfig().getConfigurationSection("shop.items");
        if (shopSection == null) {
            MessageUtil.send(player, "§cShop ist nicht konfiguriert.");
            return;
        }

        int slot = 0;
        for (String matName : shopSection.getKeys(false)) {
            if (slot >= 54) break;
            try {
                Material mat = Material.valueOf(matName);
                int price = shopSection.getInt(matName);
                ItemStack displayItem = new ItemStack(mat);
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    meta.displayName(LegacyComponentSerializer.legacySection()
                            .deserialize("§f" + formatMaterial(matName)));
                    meta.lore(List.of(
                            LegacyComponentSerializer.legacySection().deserialize("§7Preis: §e" + price + " §7Punkte"),
                            Component.empty(),
                            LegacyComponentSerializer.legacySection().deserialize("§aLinksklick §7zum Kaufen")
                    ));
                    displayItem.setItemMeta(meta);
                }
                shopInv.setItem(slot++, displayItem);
            } catch (IllegalArgumentException ignored) {}
        }

        player.openInventory(shopInv);
    }

    /**
     * Verarbeitet einen Kauf. Das Item wird direkt ins Inventar des Spielers gelegt.
     * Überschüssige Items werden zu Füßen des Spielers fallen gelassen.
     */
    public void processPurchase(Player player, PlayerData data, Material material, PlayerArena playerArena) {
        ConfigurationSection shopSection = plugin.getConfig().getConfigurationSection("shop.items");
        if (shopSection == null) return;
        int price = shopSection.getInt(material.name(), -1);
        if (price < 0) {
            MessageUtil.send(player, "§cDieses Item ist nicht im Shop.");
            return;
        }
        if (data.getSessionPoints() < price) {
            MessageUtil.send(player, "§cNicht genug Punkte! Du brauchst §e" + price + " §cPunkte.");
            return;
        }
        data.spendPoints(price);
        // Item direkt ins Spieler-Inventar, Überschuss wird fallen gelassen
        var overflow = player.getInventory().addItem(new ItemStack(material, 1));
        for (var item : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        plugin.getDatabaseManager().savePlayerAsync(data);
        MessageUtil.send(player, "§aGekauft: §f" + formatMaterial(material.name()) + " §afür §e" + price + " §aPunkte.");
    }

    private String formatMaterial(String matName) {
        return Arrays.stream(matName.split("_"))
                .map(s -> s.charAt(0) + s.substring(1).toLowerCase())
                .reduce((a, b) -> a + " " + b)
                .orElse(matName);
    }
}
