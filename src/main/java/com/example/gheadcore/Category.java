package com.example.gheadcore;

import org.bukkit.Material;

import java.util.List;

/**
 * Every setting in the plugin lives here. Each category is one page in the GUI and one
 * section of config.yml. Cooldowns and durations are the first buttons on each page.
 */
public enum Category {

    TRIDENT("trident", "Trident", "TRIDENT",
            Setting.bool("ult-enabled", "Thunderstorm Ultimate", Material.LIGHTNING_ROD, true),
            Setting.decimal("ult-cooldown", "Ultimate Cooldown (seconds)", Material.CLOCK, 0, 600, 5, 60),
            Setting.decimal("ult-duration", "Ultimate Duration (seconds)", Material.REPEATER, 1, 120, 1, 15),
            Setting.bool("ult-no-attack-cooldown", "No Attack Cooldown During Ultimate", Material.CLOCK, false),
            Setting.decimal("thunder-damage", "Crit Extra Damage", Material.REDSTONE, 0, 40, 0.5, 4),
            Setting.integer("thunder-chance", "Crit Lightning Chance (%)", Material.GLOWSTONE_DUST, 0, 100, 5, 100),
            Setting.bool("real-lightning", "Real Lightning (fire + shock)", Material.BLAZE_POWDER, true),
            Setting.bool("sword-mode", "Sword Mode (speed + sweep)", Material.IRON_SWORD, true),
            Setting.decimal("attack-speed", "Melee Attack Speed (sword = 1.6)", Material.FEATHER, 0.5, 4, 0.1, 1.6),
            Setting.decimal("bonus-damage", "Bonus Melee Damage", Material.GOLD_NUGGET, 0, 20, 0.5, 0),
            Setting.bool("disable-shield", "Disable Shields On Hit", Material.SHIELD, true),
            Setting.decimal("shield-disable-seconds", "Shield Disable Time (seconds)", Material.CLOCK, 0, 10, 0.5, 5),
            Setting.bool("sweep", "Sweeping Attack", Material.STONE_SWORD, true),
            Setting.decimal("sweep-damage", "Sweep Damage", Material.IRON_NUGGET, 0, 10, 0.5, 1)),

    MACE("mace", "Mace", "MACE",
            Setting.bool("hit-cooldown-enabled", "Hit Cooldown After Hitting A Player", Material.MACE, true),
            Setting.decimal("hit-cooldown", "Hit Cooldown (seconds, 90 = 1:30)", Material.CLOCK, 0, 600, 5, 90),
            Setting.bool("hit-cooldown-players-only", "Hit Cooldown Only Applies To Players", Material.PLAYER_HEAD, false),
            Setting.bool("ult-ignores-hit-cooldown", "Ultimate Ignores Hit Cooldown", Material.NETHER_STAR, true),
            Setting.bool("ult-enabled", "Wind Burst Ultimate", Material.WIND_CHARGE, true),
            Setting.decimal("ult-cooldown", "Ultimate Cooldown (seconds, 110 = 1:50)", Material.CLOCK, 0, 600, 5, 110),
            Setting.decimal("ult-duration", "Ultimate Duration (seconds)", Material.REPEATER, 1, 120, 1, 30),
            Setting.integer("windburst-level", "Wind Burst Level", Material.ENCHANTED_BOOK, 1, 5, 1, 2),
            Setting.bool("ult-no-attack-cooldown", "Instant Swing Speed During Ultimate", Material.FEATHER, false)),

    SPEAR("spear", "Spear", "IRON_SPEAR",
            Setting.bool("ult-enabled", "Lunge Ultimate", Material.FIREWORK_ROCKET, true),
            Setting.decimal("ult-cooldown", "Ultimate Cooldown (seconds)", Material.CLOCK, 0, 600, 5, 45),
            Setting.decimal("ult-duration", "Ultimate Duration (seconds)", Material.REPEATER, 1, 120, 1, 20),
            Setting.bool("ult-no-attack-cooldown", "No Attack Cooldown During Ultimate", Material.CLOCK, false),
            Setting.integer("lunge-level", "Lunge Level", Material.ENCHANTED_BOOK, 1, 5, 1, 3),
            Setting.bool("fast-pokes", "Sword-Speed Pokes", Material.IRON_SWORD, true),
            Setting.decimal("attack-speed", "Poke Attack Speed (sword = 1.6)", Material.FEATHER, 0.5, 4, 0.1, 1.6),
            Setting.decimal("bonus-damage", "Extra Damage Per Poke", Material.REDSTONE, 0, 20, 0.5, 2)),

    CROSSBOW("crossbow", "Crossbow", "CROSSBOW",
            Setting.bool("ult-enabled", "Barrage Ultimate", Material.ARROW, true),
            Setting.decimal("ult-cooldown", "Ultimate Cooldown (seconds)", Material.CLOCK, 0, 600, 5, 45),
            Setting.decimal("ult-duration", "Ultimate Duration (seconds)", Material.REPEATER, 1, 120, 1, 15),
            Setting.bool("ult-no-attack-cooldown", "No Attack Cooldown During Ultimate", Material.CLOCK, false),
            Setting.integer("power-level", "Ultimate Power Level", Material.ENCHANTED_BOOK, 1, 10, 1, 5),
            Setting.integer("quick-charge-level", "Ultimate Quick Charge Level", Material.ENCHANTED_BOOK, 1, 10, 1, 5),
            Setting.bool("pierce-shields", "Shoot Through Shields", Material.SHIELD, true),
            Setting.integer("ignite-chance", "Ignite Chance (%)", Material.FIRE_CHARGE, 0, 100, 5, 10),
            Setting.decimal("ignite-seconds", "Ignite Time (seconds)", Material.CLOCK, 1, 30, 1, 4)),

    GOLDEN_APPLE("golden-apple", "Golden Apple", "GOLDEN_APPLE",
            Setting.bool("enabled", "Golden Apple Absorption", Material.GOLDEN_APPLE, true),
            Setting.integer("absorption-level", "Absorption Level", Material.ENCHANTED_BOOK, 1, 10, 1, 4),
            Setting.integer("duration-seconds", "Effect Duration (seconds)", Material.CLOCK, 5, 1200, 5, 120),
            Setting.bool("include-enchanted", "Also Enchanted Golden Apples", Material.ENCHANTED_GOLDEN_APPLE, true)),

    ARMOR("armor", "Armor", "NETHERITE_CHESTPLATE",
            Setting.bool("all-netherite", "All Players' Armor Is Netherite", Material.NETHERITE_CHESTPLATE, false)),

    ALTAR("altar", "Trial Altar", "NETHERITE_SWORD",
            Setting.bool("enabled", "Spawn Altars In New Trial Chambers", Material.NETHERITE_SWORD, true),
            Setting.decimal("chance", "Altar Chance Per Chamber (%)", Material.CLOCK, 0, 100, 0.5, 1),
            Setting.decimal("spin-seconds", "Sword Spin Time (seconds per turn)", Material.REPEATER, 2, 30, 1, 8),
            Setting.bool("glow", "Floating Sword Glows Red", Material.REDSTONE, true)),

    SWORD("sword", "The Altar's Sword", "NETHERITE_SWORD",
            Setting.integer("hits-required", "Hits Needed To Drain", Material.NETHERITE_SWORD, 1, 20, 1, 3),
            Setting.decimal("drain-hearts", "Hearts Drained Per Strike", Material.REDSTONE, 0.5, 10, 0.5, 1),
            Setting.bool("players-only", "Drain Players Only", Material.PLAYER_HEAD, false),
            Setting.bool("overflow-absorption", "Extra Hearts Become Absorption", Material.GOLDEN_APPLE, true),
            Setting.decimal("max-absorption-hearts", "Max Absorption Hearts", Material.APPLE, 0, 20, 1, 10),
            Setting.integer("strength-level", "Bearer Strength Level (0 = none)", Material.BLAZE_POWDER, 0, 10, 1, 5),
            Setting.bool("crown", "Gold Crown On The Bearer", Material.GOLD_BLOCK, true),
            Setting.bool("glow-outline", "Yellow Glow Outline (shows location)", Material.GLOWSTONE_DUST, true),
            Setting.bool("only-while-held", "Bearer Effects Only While Held", Material.LEVER, false),
            Setting.bool("aura", "Red Particle Aura", Material.CRIMSON_FUNGUS, true)),

    GENERAL("general", "General", "COMPARATOR",
            Setting.bool("bar-enabled", "Show Ultimate Bar", Material.CLOCK, true),
            Setting.bool("activate-swap", "Activate With F (Swap Hand)", Material.LEVER, true),
            Setting.bool("activate-right-click", "Activate With Right-Click", Material.LEVER, false),
            Setting.bool("right-click-priority", "Right-Click Beats Shield (when ready)", Material.SHIELD, true),
            Setting.bool("activate-sneak-click", "Activate With Sneak + Right-Click", Material.LEVER, false));

    private final String id;
    private final String displayName;
    private final String iconName;
    private final List<Setting> settings;

    Category(String id, String displayName, String iconName, Setting... settings) {
        this.id = id;
        this.displayName = displayName;
        this.iconName = iconName;
        this.settings = List.of(settings);
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public List<Setting> settings() { return settings; }

    /** Looked up by name so a missing item never breaks the build. */
    public Material icon() {
        Material m = Material.matchMaterial(iconName);
        return m == null ? Material.PAPER : m;
    }
}
