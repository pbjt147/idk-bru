package com.example.gheadcore;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/** Reads and writes config.yml. Changes made in the GUI apply instantly. */
public class TweakConfig {

    private final JavaPlugin plugin;

    public TweakConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Loads config.yml from disk and fills in any missing default values. */
    public void reload() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();
        for (Category cat : Category.values()) {
            for (Setting s : cat.settings()) {
                c.addDefault(path(cat, s.key()), s.defaultValue());
            }
        }
        c.options().copyDefaults(true);

        // v2: the mace ultimate cooldown default became 1:50. Move servers still on the old default (60).
        if (c.getInt("config-version", 1) < 2) {
            if (c.getDouble("mace.ult-cooldown") == 60.0) c.set("mace.ult-cooldown", 110.0);
            c.set("config-version", 2);
        }
        // v3: the mace "instant swing speed" toggle is no longer on by default (the hit cooldown replaced that idea).
        if (c.getInt("config-version", 1) < 3) {
            c.set("mace.ult-no-attack-cooldown", false);
            c.set("config-version", 3);
        }
        // v4: the mace hit cooldown now applies to every entity (mobs too) by default.
        if (c.getInt("config-version", 1) < 4) {
            c.set("mace.hit-cooldown-players-only", false);
            c.set("config-version", 4);
        }
        plugin.saveConfig();
    }

    private String path(Category c, String key) {
        return c.id() + "." + key;
    }

    public double getDouble(Category c, String key) {
        return plugin.getConfig().getDouble(path(c, key));
    }

    public int getInt(Category c, String key) {
        return plugin.getConfig().getInt(path(c, key));
    }

    public boolean getBoolean(Category c, String key) {
        return plugin.getConfig().getBoolean(path(c, key));
    }

    /** direction: +1 or -1. big: shift-click, moves 10 steps at once. */
    public void adjust(Category c, Setting s, int direction, boolean big) {
        String p = path(c, s.key());
        FileConfiguration cfg = plugin.getConfig();

        if (s.kind() == Setting.Kind.BOOLEAN) {
            cfg.set(p, !cfg.getBoolean(p));
        } else {
            double step = s.step() * (big ? 10 : 1);
            double next = cfg.getDouble(p) + direction * step;
            next = Math.max(s.min(), Math.min(s.max(), next));
            if (s.kind() == Setting.Kind.INT) {
                cfg.set(p, (int) Math.round(next));
            } else {
                cfg.set(p, Math.round(next * 100.0) / 100.0);
            }
        }
        plugin.saveConfig();
    }

    public void reset(Category c) {
        for (Setting s : c.settings()) {
            plugin.getConfig().set(path(c, s.key()), s.defaultValue());
        }
        plugin.saveConfig();
    }

    public String display(Category c, Setting s) {
        String p = path(c, s.key());
        FileConfiguration cfg = plugin.getConfig();
        return switch (s.kind()) {
            case BOOLEAN -> cfg.getBoolean(p) ? "Enabled" : "Disabled";
            case INT -> String.valueOf(cfg.getInt(p));
            case DECIMAL -> {
                double v = cfg.getDouble(p);
                yield v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
            }
        };
    }
}
