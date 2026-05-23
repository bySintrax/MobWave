package de.sintrax.mobWave.loot;

import de.sintrax.mobWave.MobWave;
import de.sintrax.mobWave.arena.PlayerArena;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class LootManager {

    private final MobWave plugin;
    private final Random random = new Random();

    // Waffen-Pool: [Material, MaxDurability-Anteil (0.0-1.0 = volle Haltbarkeit)]
    private static final Object[][] WEAPON_POOL = {
            {Material.WOODEN_SWORD, 1.0},
            {Material.STONE_SWORD, 0.9},
            {Material.IRON_SWORD, 0.7},
            {Material.DIAMOND_SWORD, 0.4},
            {Material.WOODEN_AXE, 1.0},
            {Material.IRON_AXE, 0.7},
            {Material.DIAMOND_AXE, 0.4},
            {Material.BOW, 0.8},
            {Material.CROSSBOW, 0.5}
    };

    private static final Object[][] ARMOR_POOL = {
            {Material.LEATHER_CHESTPLATE, 1.0},
            {Material.IRON_CHESTPLATE, 0.7},
            {Material.DIAMOND_CHESTPLATE, 0.4},
            {Material.LEATHER_LEGGINGS, 1.0},
            {Material.IRON_LEGGINGS, 0.7},
            {Material.DIAMOND_LEGGINGS, 0.4},
            {Material.LEATHER_HELMET, 1.0},
            {Material.IRON_HELMET, 0.7},
            {Material.DIAMOND_HELMET, 0.4},
            {Material.LEATHER_BOOTS, 1.0},
            {Material.IRON_BOOTS, 0.7}
    };

    private static final Material[] POTION_POOL = {
            Material.POTION,
            Material.SPLASH_POTION,
            Material.GOLDEN_APPLE,
            Material.GOLDEN_CARROT
    };

    private static final Material[] BLOCK_POOL = {
            Material.OAK_PLANKS,
            Material.COBBLESTONE,
            Material.DIRT,
            Material.OAK_LOG,
            Material.OBSIDIAN
    };

    public LootManager(MobWave plugin) {
        this.plugin = plugin;
    }

    /**
     * Befüllt alle Loot-Kisten einer Spieler-Arena mit frischem, gemischtem Loot.
     * Jede Kiste bekommt einen zufälligen Mix aus Waffen, Rüstung, Tränken und Blöcken.
     */
    public void fillLootChests(PlayerArena playerArena, int waveNumber) {
        List<Location> chestLocations = playerArena.getLootChestLocations();
        int itemsPerChest = plugin.getConfig().getInt("loot.itemsPerChest", 5);
        for (Location loc : chestLocations) {
            fillChest(loc, generateMixedLoot(waveNumber, itemsPerChest));
        }
    }

    /**
     * Leert alle Loot-Kisten einer Spieler-Arena.
     */
    public void clearLootChests(PlayerArena playerArena) {
        for (Location loc : playerArena.getLootChestLocations()) {
            Block block = loc.getBlock();
            if (block.getState() instanceof Chest chest) {
                chest.getInventory().clear();
            }
        }
    }

    private void fillChest(Location loc, List<ItemStack> items) {
        Block block = loc.getBlock();
        if (!(block.getState() instanceof Chest chest)) return;
        Inventory inv = chest.getInventory();
        inv.clear();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots);
        int placed = 0;
        for (ItemStack item : items) {
            if (placed >= slots.size()) break;
            inv.setItem(slots.get(placed++), item);
        }
    }

    private List<ItemStack> generateWeaponLoot(int waveNumber) {
        List<ItemStack> items = new ArrayList<>();
        int count = 2 + random.nextInt(3);
        // Höhere Waves = bessere Chance auf bessere Waffen
        int maxTier = Math.min(waveNumber / 3, WEAPON_POOL.length - 1);
        for (int i = 0; i < count; i++) {
            int tier = random.nextInt(maxTier + 1);
            Object[] entry = WEAPON_POOL[tier];
            Material mat = (Material) entry[0];
            double durabilityRatio = (double) entry[1];
            items.add(createDamagedItem(mat, durabilityRatio));
        }
        // Pfeile für Bogen
        items.add(new ItemStack(Material.ARROW, 16 + random.nextInt(17)));
        return items;
    }

    private List<ItemStack> generateArmorLoot(int waveNumber) {
        List<ItemStack> items = new ArrayList<>();
        int count = 2 + random.nextInt(3);
        int maxTier = Math.min(waveNumber / 3, ARMOR_POOL.length - 1);
        for (int i = 0; i < count; i++) {
            int tier = random.nextInt(maxTier + 1);
            Object[] entry = ARMOR_POOL[tier];
            Material mat = (Material) entry[0];
            double durabilityRatio = (double) entry[1];
            items.add(createDamagedItem(mat, durabilityRatio));
        }
        return items;
    }

    private List<ItemStack> generatePotionLoot() {
        List<ItemStack> items = new ArrayList<>();
        int count = 2 + random.nextInt(3);
        for (int i = 0; i < count; i++) {
            Material mat = POTION_POOL[random.nextInt(POTION_POOL.length)];
            items.add(new ItemStack(mat, 1));
        }
        return items;
    }

    private List<ItemStack> generateBlockLoot() {
        List<ItemStack> items = new ArrayList<>();
        int count = 2 + random.nextInt(3);
        for (int i = 0; i < count; i++) {
            Material mat = BLOCK_POOL[random.nextInt(BLOCK_POOL.length)];
            items.add(new ItemStack(mat, 16 + random.nextInt(33)));
        }
        return items;
    }

    /**
     * Generiert gemischten Loot für eine Kiste – je nach Wave-Fortschritt besser.
     * Jede Kiste bekommt einen zufälligen Mix aus allen Kategorien.
     */
    private List<ItemStack> generateMixedLoot(int waveNumber, int targetCount) {
        List<ItemStack> items = new ArrayList<>();

        // Mindestens 1 Waffe/Rüstungsteil
        items.addAll(generateWeaponLoot(waveNumber).subList(0, 1));
        items.addAll(generateArmorLoot(waveNumber).subList(0, 1));

        // Rest auffüllen mit zufälliger Kategorie
        int remaining = targetCount - 2;
        for (int i = 0; i < remaining; i++) {
            int cat = random.nextInt(4);
            switch (cat) {
                case 0 -> {
                    var list = generateWeaponLoot(waveNumber);
                    items.add(list.get(random.nextInt(list.size())));
                }
                case 1 -> {
                    var list = generateArmorLoot(waveNumber);
                    items.add(list.get(random.nextInt(list.size())));
                }
                case 2 -> items.add(new ItemStack(POTION_POOL[random.nextInt(POTION_POOL.length)], 1));
                case 3 -> items.add(new ItemStack(BLOCK_POOL[random.nextInt(BLOCK_POOL.length)],
                                                  8 + random.nextInt(17)));
            }
        }
        return items;
    }

    private ItemStack createDamagedItem(Material material, double fullDurabilityRatio) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable damageable) {
            int maxDurability = material.getMaxDurability();
            // Niedrige fullDurabilityRatio = mehr Schaden
            int damage = (int) ((1.0 - fullDurabilityRatio) * maxDurability);
            // Leichter zufälliger Versatz
            int variance = (int) (maxDurability * 0.1);
            damage = Math.max(0, damage + random.nextInt(variance + 1));
            damage = Math.min(maxDurability - 1, damage);
            damageable.setDamage(damage);
            item.setItemMeta(meta);
        }
        return item;
    }
}
