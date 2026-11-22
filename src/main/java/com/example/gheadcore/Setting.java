package com.example.gheadcore;

import org.bukkit.Material;

/** One configurable value (shown as a button in the GUI). */
public record Setting(String key, String name, Material icon, Kind kind,
                      double min, double max, double step, double def) {

    public enum Kind { INT, DECIMAL, BOOLEAN }

    public static Setting integer(String key, String name, Material icon, int min, int max, int step, int def) {
        return new Setting(key, name, icon, Kind.INT, min, max, step, def);
    }

    public static Setting decimal(String key, String name, Material icon,
                                  double min, double max, double step, double def) {
        return new Setting(key, name, icon, Kind.DECIMAL, min, max, step, def);
    }

    public static Setting bool(String key, String name, Material icon, boolean def) {
        return new Setting(key, name, icon, Kind.BOOLEAN, 0, 1, 1, def ? 1 : 0);
    }

    public Object defaultValue() {
        return switch (kind) {
            case INT -> (int) def;
            case DECIMAL -> def;
            case BOOLEAN -> def != 0;
        };
    }
}
