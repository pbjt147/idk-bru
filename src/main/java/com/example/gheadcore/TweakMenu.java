package com.example.gheadcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** The in-game settings GUI: /gheadcore */
public class TweakMenu implements Listener {

    /** Order and position of the category buttons on the main page. */
    private static final Category[] ORDER = {
            Category.TRIDENT, Category.MACE, Category.SPEAR, Category.CROSSBOW,
            Category.GOLDEN_APPLE, Category.ARMOR, Category.ALTAR, Category.SWORD, Category.GENERAL};
    private static final int[] SLOTS = {10, 12, 14, 16, 19, 21, 23, 25, 31};

    private static final int BACK_SLOT = 27;
    private static final int RESET_SLOT = 35;

    private final TweakConfig config;

    public TweakMenu(TweakConfig config) {
        this.config = config;
    }

    /** category == null means the main page. */
    private static final class Holder implements InventoryHolder {
        final Category category;
        Inventory inventory;

        Holder(Category category) {
            this.category = category;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ------------------------------------------------------------------ opening

    public void openMain(Player player) {
        Holder holder = new Holder(null);
        Inventory inv = Bukkit.createInventory(holder, 36, Component.text("GheadCORE", NamedTextColor.DARK_GRAY));
        holder.inventory = inv;

        fill(inv);
        inv.setItem(4, button(Material.BOOK, "How ultimates work", NamedTextColor.YELLOW, List.of(
                line("Hold a trident, mace, spear or crossbow", NamedTextColor.GRAY),
                line("and press F to activate (right-click and", NamedTextColor.GRAY),
                line("sneak-click can be turned on in General).", NamedTextColor.GRAY),
                line("The boss bar at the top shows the timers.", NamedTextColor.GRAY))));

        for (int i = 0; i < ORDER.length; i++) {
            inv.setItem(SLOTS[i], button(ORDER[i].icon(), ORDER[i].displayName(), NamedTextColor.GOLD, List.of(
                    line("Click to edit settings.", NamedTextColor.YELLOW))));
        }
        player.openInventory(inv);
    }

    private void openCategory(Player player, Category category) {
        Holder holder = new Holder(category);
        Inventory inv = Bukkit.createInventory(holder, 36,
                Component.text(category.displayName() + " Settings", NamedTextColor.DARK_GRAY));
        holder.inventory = inv;

        populate(inv, category);
        player.openInventory(inv);
    }

    /** Settings fill two rows of seven: slots 10-16 and 19-25. */
    private int settingSlot(int index) {
        return 10 + (index / 7) * 9 + (index % 7);
    }

    private void populate(Inventory inv, Category category) {
        fill(inv);
        inv.setItem(4, button(category.icon(), category.displayName(), NamedTextColor.GOLD, List.of()));

        List<Setting> settings = category.settings();
        for (int i = 0; i < settings.size(); i++) {
            inv.setItem(settingSlot(i), settingButton(settings.get(i), config.display(category, settings.get(i))));
        }

        inv.setItem(BACK_SLOT, button(Material.ARROW, "Back", NamedTextColor.YELLOW,
                List.of(line("Return to the main page.", NamedTextColor.GRAY))));
        inv.setItem(RESET_SLOT, button(Material.BARRIER, "Reset to defaults", NamedTextColor.RED,
                List.of(line("Restore every value on this page.", NamedTextColor.GRAY))));
    }

    // ------------------------------------------------------------------ clicking

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder holder)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != top) return;

        if (!player.hasPermission("gheadcore.config")) {
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        if (holder.category == null) {
            mainClick(player, slot);
        } else {
            categoryClick(player, event, holder.category, top, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void mainClick(Player player, int slot) {
        for (int i = 0; i < ORDER.length; i++) {
            if (SLOTS[i] == slot) {
                click(player);
                openCategory(player, ORDER[i]);
                return;
            }
        }
    }

    private void categoryClick(Player player, InventoryClickEvent event, Category category, Inventory top, int slot) {
        if (slot == BACK_SLOT) {
            click(player);
            openMain(player);
            return;
        }
        if (slot == RESET_SLOT) {
            click(player);
            config.reset(category);
            populate(top, category);
            return;
        }

        List<Setting> settings = category.settings();
        for (int i = 0; i < settings.size(); i++) {
            if (settingSlot(i) != slot) continue;

            int direction = event.isRightClick() ? -1 : 1;
            config.adjust(category, settings.get(i), direction, event.isShiftClick());
            click(player);
            populate(top, category);
            return;
        }
    }

    // ------------------------------------------------------------------ item builders

    private ItemStack settingButton(Setting setting, String current) {
        List<Component> lore = new ArrayList<>();
        lore.add(line("Current: " + current, NamedTextColor.WHITE));
        lore.add(Component.empty());

        if (setting.kind() == Setting.Kind.BOOLEAN) {
            lore.add(line("Click to toggle", NamedTextColor.YELLOW));
        } else {
            String step = numberText(setting.step());
            lore.add(line("Left-click: +" + step, NamedTextColor.GREEN));
            lore.add(line("Right-click: -" + step, NamedTextColor.RED));
            lore.add(line("Shift-click: x10", NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(line("Range: " + numberText(setting.min()) + " to " + numberText(setting.max()),
                    NamedTextColor.DARK_GRAY));
        }
        return button(setting.icon(), setting.name(), NamedTextColor.GOLD, lore);
    }

    private ItemStack button(Material material, String name, NamedTextColor color, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(line(name, color).decoration(TextDecoration.BOLD, true));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inv) {
        inv.clear();
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        meta.displayName(Component.text(" "));
        glass.setItemMeta(meta);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, glass);
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private String numberText(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }
}
