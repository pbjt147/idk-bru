package com.example.gheadcore;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * "All Players' Armor Is Netherite".
 * <p>
 * Every armor piece a player holds - inventory, worn, ender chest, any chest they open, loot,
 * drops, crafting results - is turned into the netherite version, keeping enchantments, trims, name
 * and its share of durability. Converted pieces are tagged with what they used to be, so switching
 * the option off puts them back.
 */
public class ArmorListener implements Listener {

    private static final String[] TIERS = {"LEATHER_", "CHAINMAIL_", "IRON_", "GOLDEN_", "DIAMOND_", "COPPER_"};
    private static final String[] SLOTS = {"_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS"};

    private static final Set<InventoryType> CONTAINERS = EnumSet.of(
            InventoryType.CHEST, InventoryType.ENDER_CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX,
            InventoryType.HOPPER, InventoryType.DISPENSER, InventoryType.DROPPER);

    private final TweakConfig config;
    private final NamespacedKey originalKey;

    public ArmorListener(JavaPlugin plugin, TweakConfig config) {
        this.config = config;
        this.originalKey = new NamespacedKey(plugin, "armor_original");
    }

    // ------------------------------------------------------------------ conversion

    private boolean enabled() {
        return config.getBoolean(Category.ARMOR, "all-netherite");
    }

    private static String slotSuffix(String materialName) {
        for (String suffix : SLOTS) {
            if (materialName.endsWith(suffix)) return suffix;
        }
        return null;
    }

    private static Material netheriteOf(Material type) {
        String name = type.name();
        String suffix = slotSuffix(name);
        if (suffix == null) return null;
        for (String tier : TIERS) {
            if (name.startsWith(tier)) return Material.matchMaterial("NETHERITE" + suffix);
        }
        return null;
    }

    /**
     * Returns the stack this item should be replaced with, or null if nothing needs to change.
     * (A different Material means a new ItemStack, so callers have to put the result back.)
     */
    public ItemStack process(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        Material type = item.getType();
        if (slotSuffix(type.name()) == null) return null; // not armor: cheap early exit

        if (enabled()) {
            Material target = netheriteOf(type);
            return target == null ? null : swap(item, type, target, true);
        }

        // Option is off: give back whatever we converted earlier.
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String original = meta.getPersistentDataContainer().get(originalKey, PersistentDataType.STRING);
        if (original == null) return null;
        Material back = Material.matchMaterial(original);
        return back == null ? null : swap(item, type, back, false);
    }

    private ItemStack swap(ItemStack item, Material from, Material to, boolean mark) {
        ItemStack out = item.withType(to); // keeps count, enchants, trim, name, lore...
        ItemMeta meta = out.getItemMeta();
        if (meta == null) return null;

        // Keep the same fraction of durability used.
        if (meta instanceof Damageable damageable) {
            int oldMax = from.getMaxDurability();
            int newMax = to.getMaxDurability();
            if (oldMax > 0 && newMax > 0) {
                damageable.setDamage((int) Math.round(damageable.getDamage() * (double) newMax / oldMax));
            }
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (mark) {
            pdc.set(originalKey, PersistentDataType.STRING, from.name());
        } else {
            pdc.remove(originalKey);
        }
        out.setItemMeta(meta);
        return out;
    }

    private void sweepInventory(Inventory inventory) {
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack replacement = process(inventory.getItem(i));
            if (replacement != null) inventory.setItem(i, replacement);
        }
    }

    /** A player's inventory (worn armor included) and ender chest. */
    public void sweep(Player player) {
        sweepInventory(player.getInventory());
        sweepInventory(player.getEnderChest());
    }

    // ------------------------------------------------------------------ where it happens

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sweep(event.getPlayer());
    }

    /** Chests, barrels, shulker boxes, ender chests and so on convert the moment anyone opens them. */
    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        Inventory top = event.getInventory();
        if (CONTAINERS.contains(top.getType())) sweepInventory(top);
        if (event.getPlayer() instanceof Player player) sweep(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Item drop = event.getItem();
        ItemStack replacement = process(drop.getItemStack());
        if (replacement != null) drop.setItemStack(replacement);
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        Item drop = event.getEntity();
        ItemStack replacement = process(drop.getItemStack());
        if (replacement != null) drop.setItemStack(replacement);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLoot(LootGenerateEvent event) {
        List<ItemStack> loot = new ArrayList<>(event.getLoot());
        boolean changed = false;
        for (int i = 0; i < loot.size(); i++) {
            ItemStack replacement = process(loot.get(i));
            if (replacement != null) {
                loot.set(i, replacement);
                changed = true;
            }
        }
        if (changed) event.setLoot(loot);
    }

    @EventHandler
    public void onCraftPreview(PrepareItemCraftEvent event) {
        CraftingInventory crafting = event.getInventory();
        ItemStack replacement = process(crafting.getResult());
        if (replacement != null) crafting.setResult(replacement);
    }
}
