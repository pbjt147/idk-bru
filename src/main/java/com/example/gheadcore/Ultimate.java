package com.example.gheadcore;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Material;

/** The four ultimates. Each belongs to one kind of item. */
public enum Ultimate {

    TRIDENT(Category.TRIDENT, "Thunderstorm", BossBar.Color.BLUE, 0x5599FF, "\u03A8"),   // Psi, looks like a trident
    MACE(Category.MACE, "Wind Burst", BossBar.Color.WHITE, 0xE6F4FF, "\u2692"),          // hammer and pick
    SPEAR(Category.SPEAR, "Lunge Rush", BossBar.Color.PURPLE, 0xCC88FF, "\u27A4"),       // arrow head
    CROSSBOW(Category.CROSSBOW, "Barrage", BossBar.Color.YELLOW, 0xFFD93D, "\u27B6");    // feathered arrow

    private final Category category;
    private final String displayName;
    private final BossBar.Color barColor;
    private final int textColor;
    private final String symbol;

    Ultimate(Category category, String displayName, BossBar.Color barColor, int textColor, String symbol) {
        this.category = category;
        this.displayName = displayName;
        this.barColor = barColor;
        this.textColor = textColor;
        this.symbol = symbol;
    }

    public Category category() { return category; }
    public String displayName() { return displayName; }
    public BossBar.Color barColor() { return barColor; }
    public int textColor() { return textColor; }
    public String symbol() { return symbol; }

    /** Which ultimate (if any) an item type belongs to. Spears are matched by name (wooden, copper, iron...). */
    public static Ultimate of(Material type) {
        if (type == Material.TRIDENT) return TRIDENT;
        if (type == Material.MACE) return MACE;
        if (type == Material.CROSSBOW) return CROSSBOW;
        if (type.name().endsWith("_SPEAR")) return SPEAR;
        return null;
    }
}
