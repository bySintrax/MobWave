package de.sintrax.mobWave.arena;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import de.sintrax.mobWave.MobWave;
import de.sintrax.mobWave.util.MessageUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Einmaliger Einrichtungs-Wizard für die Template-Arena.
 * Gespeicherte Koordinaten stammen aus der Template-Welt;
 * beim Spielstart werden N Kopien dieser Welt erstellt.
 *
 * Befehle:  /mw template wizard confirm | skip | next | set <wert>
 */
public class TemplateWizard {

    public enum Step {
        TEMPLATE_WORLD,     // 1. Weltname der Template-Welt festlegen
        LOBBY_SPAWN,        // 2. Lobby-Spawn (in beliebiger geladener Welt)
        FIGHT_REGION,       // 3. Kampfzone (FAWE)
        FIGHT_SPAWN,        // 4. Spieler-Spawn im Kampfbereich
        MOB_SPAWN,          // 5. Monster-Spawn (optional, Fallback = fightSpawn)
        EQUIP_REGION,       // 6. Ausrüstungszone (FAWE)
        EQUIP_SPAWN,        // 7. Spawn im Ausrüstungsbereich
        LOOT_CHESTS,        // 8. Loot-Kisten anklicken
        SHOP_NPC,           // 9. Shop-NPC Position (optional)
        PLAYERS_PER_INST,   // 10. Spieler pro Instanz
        SHOP_EDITOR,        // 11. Shop-Items und Preise festlegen (GUI)
        DONE
    }

    private static final boolean FAWE_AVAILABLE;
    static {
        boolean ok = false;
        try {
            Class.forName("com.sk89q.worldedit.WorldEdit");
            ok = Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit");
        } catch (ClassNotFoundException ignored) {}
        FAWE_AVAILABLE = ok;
    }

    private final MobWave plugin;
    private final Player admin;
    private Step currentStep = Step.TEMPLATE_WORLD;
    private int lootChestsSet = 0;

    // Shop-Editor
    private Inventory shopEditorInv = null;
    private Inventory priceEditorInv = null;
    private int shopEditorEditingSlot = -1;
    private int priceEditorCurrentPrice = 10;
    private boolean shopEditorReady = false;

    public static final String SHOP_EDITOR_TITLE = "§6Shop Editor";
    public static final String PRICE_EDITOR_TITLE = "§6Preis bearbeiten";

    public TemplateWizard(MobWave plugin, Player admin) {
        this.plugin = plugin;
        this.admin = admin;
        msg("§e[Template-Wizard] §fWillkommen! Dieser Wizard richtet die §bTemplate-Arena §fein.");
        msg("§e[Template-Wizard] §fDie Welt wird für jedes Spiel automatisch kopiert – §aeinmalige Einrichtung§f!");
        sendInstruction();
    }

    // ─── Anweisungen ─────────────────────────────────────────────────────────

    public void sendInstruction() {
        switch (currentStep) {
            case TEMPLATE_WORLD ->
                msg("§e[Wizard] §7Schritt 1/10 – Template-Welt: §fGib den §bOrdnernamen §fder Welt an: §a/mw wizard set <weltname>");
            case LOBBY_SPAWN ->
                msg("§e[Wizard] §7Schritt 2/10 – Lobby-Spawn: §fStelle dich in die §bLobby §fund tippe §a/mw wizard confirm§f.");
            case FIGHT_REGION ->
                msg("§e[Wizard] §7Schritt 3/10 – Kampfzone: §fMarkiere mit der §bWorldEdit-Wand §fdie §bKampfzone §fin der Template-Welt und tippe §a/mw wizard confirm§f. §7Oder §a/mw wizard skip §fzum Überspringen.");
            case FIGHT_SPAWN ->
                msg("§e[Wizard] §7Schritt 4/10 – Kampf-Spawn: §fStelle dich (in der Template-Welt) an den §bSpieler-Spawn §fund tippe §a/mw wizard confirm§f.");
            case MOB_SPAWN ->
                msg("§e[Wizard] §7Schritt 5/10 – Monster-Spawn: §fStelle dich an den §bMonster-Spawn §fund bestätige. §7Oder §a/mw wizard skip §fum Kampf-Spawn zu verwenden.");
            case EQUIP_REGION ->
                msg("§e[Wizard] §7Schritt 6/10 – Ausrüstungszone: §fMarkiere die §bAusrüstungszone §fund tippe §a/mw wizard confirm§f. §7Oder §a/mw wizard skip§f.");
            case EQUIP_SPAWN ->
                msg("§e[Wizard] §7Schritt 7/10 – Ausrüstungs-Spawn: §fStelle dich an den §bSpawn im Ausrüstungsbereich §fund tippe §a/mw wizard confirm§f.");
            case LOOT_CHESTS ->
                msg("§e[Wizard] §7Schritt 8/10 – Loot-Kisten §8(" + lootChestsSet + " gesetzt)§f: §fSchau auf eine §bLoot-Kiste §fund tippe §a/mw wizard confirm§f. §a/mw wizard next §fzum Abschließen.");
            case SHOP_NPC ->
                msg("§e[Wizard] §7Schritt 9/10 – Shop-NPC: §fStelle dich an die §bPosition §fwo der Händler-NPC stehen soll und tippe §a/mw wizard confirm§f. §7Oder §a/mw wizard skip §f(kein Shop).");
            case PLAYERS_PER_INST ->
                msg("§e[Wizard] §7Schritt 10/11 – Spieler pro Instanz: §fWie viele Spieler teilen sich eine Instanz? §a/mw wizard set <Anzahl>§f. §8(Aktuell: §e" + plugin.getTemplateConfig().getPlayersPerInstance() + "§8)");
            case SHOP_EDITOR -> {
                if (!shopEditorReady)
                    msg("§e[Wizard] §7Schritt 11/11 – Shop-Editor: §fBereite dich vor (z.B. Items in dein Inventar legen), dann tippe §a/mw wizard confirm§f. §7/mw wizard skip §fum zu überspringen.");
                else
                    msg("§e[Shop-Editor] §fTippe §a/mw wizard confirm §fum den Editor zu öffnen. §a/mw wizard next §fzum Speichern & Abschließen.");
            }
            case DONE ->
                msg("§a[Template-Wizard] §fEinrichtung abgeschlossen! Template-Arena gespeichert.");
        }
    }

    // ─── Befehlshandler ──────────────────────────────────────────────────────

    public void handleConfirm() {
        TemplateConfig cfg = plugin.getTemplateConfig();
        switch (currentStep) {
            case LOBBY_SPAWN -> {
                cfg.setLobbySpawn(admin.getLocation());
                msg("§a✔ Lobby-Spawn gesetzt: §f" + formatLoc(admin.getLocation()));
                advance(Step.FIGHT_REGION);
            }
            case FIGHT_REGION -> {
                if (!FAWE_AVAILABLE) {
                    msg("§cFAWE nicht verfügbar – Kampfzone übersprungen.");
                    advance(Step.FIGHT_SPAWN);
                    return;
                }
                Region region = getFaweSelection();
                if (region == null) return;
                cfg.setFightRegionMin(toRawLoc(admin.getWorld(), region.getMinimumPoint()));
                cfg.setFightRegionMax(toRawLoc(admin.getWorld(), region.getMaximumPoint()));
                msg("§a✔ Kampfzone gesetzt: §f" + formatLoc(cfg.getFightRegionMin())
                        + " §8→ §f" + formatLoc(cfg.getFightRegionMax())
                        + " §8(§7" + region.getVolume() + " Blöcke§8)");
                advance(Step.FIGHT_SPAWN);
            }
            case FIGHT_SPAWN -> {
                cfg.setFightSpawn(toRawLoc(admin.getLocation()));
                msg("§a✔ Kampf-Spawn gesetzt: §f" + formatLoc(admin.getLocation()));
                advance(Step.MOB_SPAWN);
            }
            case MOB_SPAWN -> {
                cfg.setMobSpawn(toRawLoc(admin.getLocation()));
                msg("§a✔ Monster-Spawn gesetzt: §f" + formatLoc(admin.getLocation()));
                advance(Step.EQUIP_REGION);
            }
            case EQUIP_REGION -> {
                if (!FAWE_AVAILABLE) {
                    msg("§cFAWE nicht verfügbar – Ausrüstungszone übersprungen.");
                    advance(Step.EQUIP_SPAWN);
                    return;
                }
                Region region = getFaweSelection();
                if (region == null) return;
                cfg.setEquipRegionMin(toRawLoc(admin.getWorld(), region.getMinimumPoint()));
                cfg.setEquipRegionMax(toRawLoc(admin.getWorld(), region.getMaximumPoint()));
                msg("§a✔ Ausrüstungszone gesetzt: §f" + formatLoc(cfg.getEquipRegionMin())
                        + " §8→ §f" + formatLoc(cfg.getEquipRegionMax())
                        + " §8(§7" + region.getVolume() + " Blöcke§8)");
                advance(Step.EQUIP_SPAWN);
            }
            case EQUIP_SPAWN -> {
                cfg.setEquipSpawn(toRawLoc(admin.getLocation()));
                msg("§a✔ Ausrüstungs-Spawn gesetzt: §f" + formatLoc(admin.getLocation()));
                lootChestsSet = 0;
                advance(Step.LOOT_CHESTS);
            }
            case LOOT_CHESTS -> {
                Block target = admin.getTargetBlockExact(5);
                if (target == null || !(target.getState() instanceof Chest)) {
                    msg("§cSchau direkt auf eine Kiste (max. 5 Blöcke Entfernung)!");
                    return;
                }
                cfg.addLootChest(toRawLoc(target.getLocation()));
                lootChestsSet++;
                msg("§a✔ Loot-Kiste §f" + lootChestsSet + " §agesetzt.");
                sendInstruction();
            }
            case SHOP_NPC -> {
                cfg.setShopNpcLocation(toRawLoc(admin.getLocation()));
                msg("§a✔ Shop-NPC Position gesetzt: §f" + formatLoc(admin.getLocation()));
                advance(Step.PLAYERS_PER_INST);
            }
            case SHOP_EDITOR -> {
                if (!shopEditorReady) {
                    shopEditorReady = true;
                    msg("§e[Shop-Editor] §aBereit! §fLege Items in dein Inventar die du verkaufen möchtest, dann tippe erneut §a/mw wizard confirm §fum den Editor zu öffnen.");
                    msg("§7/mw wizard skip §fzum Überspringen | §a/mw wizard next §fzum späteren Speichern");
                } else if (shopEditorInv == null) {
                    openShopEditor();
                } else {
                    admin.openInventory(shopEditorInv);
                    msg("§e[Shop-Editor] §fInventar erneut geöffnet. §a/mw wizard next §fzum Speichern & Abschließen.");
                }
            }
            default -> sendInstruction();
        }
    }

    public void handleSkip() {
        switch (currentStep) {
            case FIGHT_REGION -> { msg("§7Kampfzone übersprungen."); advance(Step.FIGHT_SPAWN); }
            case MOB_SPAWN    -> { msg("§7Monster-Spawn übersprungen (= Kampf-Spawn)."); advance(Step.EQUIP_REGION); }
            case EQUIP_REGION -> { msg("§7Ausrüstungszone übersprungen."); advance(Step.EQUIP_SPAWN); }
            case SHOP_NPC     -> { msg("§7Shop-NPC übersprungen (kein Shop)."); advance(Step.PLAYERS_PER_INST); }
            case SHOP_EDITOR  -> { msg("§7Shop-Editor übersprungen."); advance(Step.DONE); }
            default -> sendInstruction();
        }
    }

    public void handleNext() {
        if (currentStep == Step.LOOT_CHESTS) {
            if (lootChestsSet == 0) {
                msg("§cMindestens 1 Loot-Kiste muss gesetzt sein!");
                return;
            }
            advance(Step.SHOP_NPC);
        } else if (currentStep == Step.SHOP_EDITOR) {
            if (shopEditorInv != null) {
                admin.closeInventory();
                saveShopEditor();
                msg("§a✔ Shop gespeichert.");
            } else {
                msg("§7Shop-Editor nicht geöffnet – keine Änderungen gespeichert.");
            }
            advance(Step.DONE);
        } else {
            sendInstruction();
        }
    }

    public void handleSet(String[] args) {
        if (args.length == 0) { sendInstruction(); return; }
        switch (currentStep) {
            case TEMPLATE_WORLD -> {
                String worldName = args[0];
                File worldDir = new File(Bukkit.getWorldContainer(), worldName);
                if (!worldDir.exists() || !worldDir.isDirectory()) {
                    msg("§cOrdner '§e" + worldName + "§c' im World-Verzeichnis nicht gefunden! Erstelle die Welt zuerst.");
                    return;
                }
                plugin.getTemplateConfig().setTemplateWorldName(worldName);
                msg("§a✔ Template-Welt gesetzt: §f" + worldName);
                advance(Step.LOBBY_SPAWN);
            }
            case PLAYERS_PER_INST -> {
                try {
                    int count = Integer.parseInt(args[0]);
                    if (count < 1 || count > 20) {
                        msg("§cWert muss zwischen §e1 §cund §e20 §cliegen.");
                        return;
                    }
                    plugin.getTemplateConfig().setPlayersPerInstance(count);
                    msg("§a✔ Spieler pro Instanz: §f" + count);
                    plugin.getTemplateConfig().save();
                    advance(Step.SHOP_EDITOR);
                } catch (NumberFormatException e) {
                    msg("§cUngültige Zahl: §e" + args[0]);
                }
            }
            case SHOP_EDITOR -> {
                // Preis wird jetzt vollständig per GUI eingestellt (kein Befehl mehr nötig)
                msg("§7Nutze den Shop-Editor GUI um Items und Preise festzulegen.");
            }
            default -> sendInstruction();
        }
    }

    public boolean isDone() { return currentStep == Step.DONE; }

    public Step getCurrentStep() { return currentStep; }

    // ─── Shop-Editor ─────────────────────────────────────────────────────────

    /** Öffnet das Shop-Editor-Inventar und befüllt es mit vorhandenen Items aus der Config. */
    private void openShopEditor() {
        shopEditorInv = Bukkit.createInventory(null, 54,
            LegacyComponentSerializer.legacySection().deserialize(SHOP_EDITOR_TITLE));

        // Vorhandene Shop-Items laden
        var shopSection = plugin.getConfig().getConfigurationSection("shop.items");
        int slot = 0;
        if (shopSection != null) {
            for (String matName : shopSection.getKeys(false)) {
                if (slot >= 54) break;
                try {
                    Material mat = Material.valueOf(matName);
                    int price = shopSection.getInt(matName);
                    shopEditorInv.setItem(slot++, buildShopItem(mat, price));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        admin.openInventory(shopEditorInv);
        msg("§e[Shop-Editor] §7Shift-Klick §faus deinem Inventar = §aItem hinzufügen");
        msg("§e[Shop-Editor] §7Linksklick §fauf Item = §ePreis anpassen §8(+/- GUI)");
        msg("§e[Shop-Editor] §7Rechtsklick §fauf Item = §cEntfernen");
        msg("§e[Shop-Editor] §fFertig: §a/mw wizard next §fzum Speichern");
    }

    /** Verarbeitet Klicks im Shop-Editor-Inventar. Wird vom PlayerListener aufgerufen. */
    public void handleShopEditorClick(InventoryClickEvent event) {
        if (shopEditorInv == null) return;
        if (!(event.getWhoClicked() instanceof Player)) return;

        var topInv = event.getView().getTopInventory();
        var clickedInv = event.getClickedInventory();
        if (clickedInv == null) return;

        if (clickedInv.equals(topInv)) {
            // Klick im Shop-Editor selbst
            int slot = event.getSlot();
            ItemStack current = topInv.getItem(slot);
            ClickType click = event.getClick();

            if (click == ClickType.RIGHT && current != null && current.getType() != Material.AIR) {
                // Entfernen – Item zurück ins Inventar geben
                ItemStack clean = new ItemStack(current.getType(), 1);
                admin.getInventory().addItem(clean);
                topInv.setItem(slot, null);
                shopEditorEditingSlot = -1;
                msg("§c✖ §f" + current.getType().name() + " §caus Shop entfernt.");

            } else if (click == ClickType.LEFT) {
                if (current != null && current.getType() != Material.AIR) {
                    // Preis-Editor öffnen
                    openPriceEditor(slot);
                } else {
                    // Leerer Slot + Item in Hand → hinzufügen
                    ItemStack cursor = event.getCursor();
                    if (cursor != null && cursor.getType() != Material.AIR) {
                        topInv.setItem(slot, buildShopItem(cursor.getType(), 10));
                        shopEditorEditingSlot = slot;
                        event.getView().setCursor(null);
                        msg("§a✔ §f" + cursor.getType().name() + " §7hinzugefügt. Linksklick zum Preis einstellen.");
                    }
                }
            }

        } else {
            // Klick im Spieler-Inventar: Shift-Klick → Item zum Shop hinzufügen
            if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
                ItemStack current = event.getCurrentItem();
                if (current == null || current.getType() == Material.AIR) return;

                // Ersten freien Slot suchen
                int emptySlot = -1;
                for (int i = 0; i < topInv.getSize(); i++) {
                    ItemStack s = topInv.getItem(i);
                    if (s == null || s.getType() == Material.AIR) { emptySlot = i; break; }
                }
                if (emptySlot < 0) { msg("§cShop Editor ist voll!"); return; }

                topInv.setItem(emptySlot, buildShopItem(current.getType(), 10));
                shopEditorEditingSlot = emptySlot;
                // Ein Item aus Inventar entfernen
                if (current.getAmount() > 1) {
                    current.setAmount(current.getAmount() - 1);
                } else {
                    event.setCurrentItem(null);
                }
                msg("§a✔ §f" + current.getType().name() + " §7hinzugefügt. Linksklick zum Preis einstellen.");
            }
        }
    }

    /** Öffnet das 9-Slot Preis-Editor-GUI für das Item in shopSlot. */
    private void openPriceEditor(int shopSlot) {
        ItemStack item = shopEditorInv.getItem(shopSlot);
        if (item == null || item.getType() == Material.AIR) return;
        shopEditorEditingSlot = shopSlot;
        priceEditorCurrentPrice = getPriceFromLore(item);

        priceEditorInv = Bukkit.createInventory(null, 9,
                LegacyComponentSerializer.legacySection().deserialize(PRICE_EDITOR_TITLE));
        refreshPriceEditor(item);
        admin.openInventory(priceEditorInv);
    }

    /** Füllt das Preis-Editor-Inventar mit dem aktuellen Zustand. */
    private void refreshPriceEditor(ItemStack item) {
        priceEditorInv.setItem(0, paneItem(Material.RED_STAINED_GLASS_PANE, "§c- 10"));
        priceEditorInv.setItem(1, paneItem(Material.ORANGE_STAINED_GLASS_PANE, "§6- 5"));
        priceEditorInv.setItem(2, paneItem(Material.YELLOW_STAINED_GLASS_PANE, "§e- 1"));
        // Slot 3: aktuelle Anzeige
        ItemStack priceDisplay = new ItemStack(Material.PAPER);
        ItemMeta pm = priceDisplay.getItemMeta();
        if (pm != null) {
            pm.displayName(LegacyComponentSerializer.legacySection().deserialize("§eAktueller Preis: §f" + priceEditorCurrentPrice + " Punkte"));
            pm.lore(List.of(LegacyComponentSerializer.legacySection().deserialize("§7Klicke +/- um den Preis anzupassen")));
            priceDisplay.setItemMeta(pm);
        }
        priceEditorInv.setItem(3, priceDisplay);
        // Slot 4: das Item selbst (Vorschau)
        ItemStack preview = new ItemStack(item.getType());
        ItemMeta im = preview.getItemMeta();
        if (im != null) {
            im.displayName(LegacyComponentSerializer.legacySection().deserialize("§f" + formatMaterialName(item.getType().name())));
            preview.setItemMeta(im);
        }
        priceEditorInv.setItem(4, preview);
        priceEditorInv.setItem(5, paneItem(Material.LIME_STAINED_GLASS_PANE, "§a+ 1"));
        priceEditorInv.setItem(6, paneItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "§b+ 5"));
        priceEditorInv.setItem(7, paneItem(Material.BLUE_STAINED_GLASS_PANE, "§9+ 10"));
        // Slot 8: Speichern
        ItemStack save = new ItemStack(Material.EMERALD);
        ItemMeta sm = save.getItemMeta();
        if (sm != null) {
            sm.displayName(LegacyComponentSerializer.legacySection().deserialize("§aSpeichern"));
            sm.lore(List.of(LegacyComponentSerializer.legacySection().deserialize("§7Preis auf §e" + priceEditorCurrentPrice + " §7Punkte setzen")));
            save.setItemMeta(sm);
        }
        priceEditorInv.setItem(8, save);
    }

    private ItemStack paneItem(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Verarbeitet Klicks im Preis-Editor. Wird vom PlayerListener aufgerufen. */
    public void handlePriceEditorClick(InventoryClickEvent event) {
        if (priceEditorInv == null || shopEditorInv == null || shopEditorEditingSlot < 0) return;
        if (!event.getClickedInventory().equals(priceEditorInv)) return;

        int slot = event.getSlot();
        switch (slot) {
            case 0 -> priceEditorCurrentPrice = Math.max(0, priceEditorCurrentPrice - 10);
            case 1 -> priceEditorCurrentPrice = Math.max(0, priceEditorCurrentPrice - 5);
            case 2 -> priceEditorCurrentPrice = Math.max(0, priceEditorCurrentPrice - 1);
            case 5 -> priceEditorCurrentPrice += 1;
            case 6 -> priceEditorCurrentPrice += 5;
            case 7 -> priceEditorCurrentPrice += 10;
            case 8 -> {
                // Speichern: Preis ins Shop-Editor-Inventar schreiben, zurück zum Shop-Editor
                ItemStack shopItem = shopEditorInv.getItem(shopEditorEditingSlot);
                if (shopItem != null && shopItem.getType() != Material.AIR) {
                    shopEditorInv.setItem(shopEditorEditingSlot, buildShopItem(shopItem.getType(), priceEditorCurrentPrice));
                    msg("§a✔ Preis für §f" + shopItem.getType().name() + " §aauf §e" + priceEditorCurrentPrice + " §7Punkte gesetzt.");
                }
                priceEditorInv = null;
                shopEditorEditingSlot = -1;
                // Zum Shop-Editor zurückkehren (1-Tick-Delay um Inventory-Konflikt zu vermeiden)
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> admin.openInventory(shopEditorInv), 1L);
                return;
            }
            default -> { return; }
        }
        // Anzeige aktualisieren
        ItemStack editedItem = shopEditorInv.getItem(shopEditorEditingSlot);
        if (editedItem != null) refreshPriceEditor(editedItem);
    }

    /** Gibt das Preis-Editor-Inventar zurück (für den PlayerListener). */
    public Inventory getPriceEditorInv() { return priceEditorInv; }

    /** Bricht den Preis-Editor ab und kehrt zum Shop-Editor zurück. */
    public void cancelPriceEditor() {
        priceEditorInv = null;
        shopEditorEditingSlot = -1;
        if (shopEditorInv != null) {
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> admin.openInventory(shopEditorInv), 1L);
        }
    }

    /** Speichert alle Items aus dem Shop-Editor in die config.yml. */
    private void saveShopEditor() {
        plugin.getConfig().set("shop.items", null); // Altes löschen
        int saved = 0;
        for (int i = 0; i < shopEditorInv.getSize(); i++) {
            ItemStack item = shopEditorInv.getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;
            int price = getPriceFromLore(item);
            plugin.getConfig().set("shop.items." + item.getType().name(), price);
            saved++;
        }
        plugin.saveConfig();
        msg("§a✔ Shop gespeichert: §f" + saved + " §aItems.");
    }

    /** Erstellt ein Shop-Item mit Preis in der Lore. */
    private ItemStack buildShopItem(Material mat, int price) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize("§f" + formatMaterialName(mat.name())));
            meta.lore(List.of(
                LegacyComponentSerializer.legacySection().deserialize("§7Preis: §e" + price + " §7Punkte"),
                LegacyComponentSerializer.legacySection().deserialize("§8Rechtsklick zum Entfernen")
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Liest den Preis aus der Lore eines Shop-Items. */
    private int getPriceFromLore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.lore() == null) return 10;
        for (var line : meta.lore()) {
            String plain = PlainTextComponentSerializer.plainText().serialize(line);
            if (plain.contains("Punkte")) {
                for (String part : plain.split(" ")) {
                    try { return Integer.parseInt(part); } catch (NumberFormatException ignored) {}
                }
            }
        }
        return 10;
    }

    private String formatMaterialName(String matName) {
        return Arrays.stream(matName.split("_"))
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase())
                .reduce((a, b) -> a + " " + b).orElse(matName);
    }

    // ─── Hilfsmethoden ───────────────────────────────────────────────────────

    private void advance(Step next) {
        currentStep = next;
        if (next == Step.DONE) {
            plugin.getTemplateConfig().save();
        }
        sendInstruction();
    }

    private Region getFaweSelection() {
        try {
            var actor = BukkitAdapter.adapt(admin);
            var session = WorldEdit.getInstance().getSessionManager().get(actor);
            return session.getSelection(BukkitAdapter.adapt(admin.getWorld()));
        } catch (IncompleteRegionException e) {
            msg("§cKeine vollständige WorldEdit-Selektion! Bitte zuerst mit §a//wand §cmarkieren.");
            return null;
        }
    }

    /** Speichert eine Location ohne Welt-Referenz (für Template-Koordinaten). */
    private Location toRawLoc(Location loc) {
        return new Location(null, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    /** Konvertiert einen BlockVector3 zu einer welt-losen Location. */
    private Location toRawLoc(World world, BlockVector3 vec) {
        return new Location(null, vec.x(), vec.y(), vec.z());
    }

    private String formatLoc(Location loc) {
        if (loc == null) return "§cnull";
        return String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ());
    }

    private void msg(String text) {
        MessageUtil.sendRaw(admin, text);
    }
}
