package com.example.gheadcore;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class GheadCorePlugin extends JavaPlugin {

    private UltimateManager ultimates;
    private HandAttributes attributes;
    private AltarSword sword;

    @Override
    public void onEnable() {
        TweakConfig config = new TweakConfig(this);
        ultimates = new UltimateManager(this, config);
        attributes = new HandAttributes(this, config, ultimates);
        TweakMenu menu = new TweakMenu(config);
        ArmorListener armor = new ArmorListener(this, config);
        sword = new AltarSword(this, config);
        AltarManager altars = new AltarManager(this, config, sword);

        getServer().getPluginManager().registerEvents(new CombatListener(this, config, ultimates, attributes), this);
        getServer().getPluginManager().registerEvents(menu, this);
        getServer().getPluginManager().registerEvents(armor, this);
        getServer().getPluginManager().registerEvents(sword, this);
        getServer().getPluginManager().registerEvents(altars, this);

        // Players already online (e.g. after /reload): clear anything stale from a previous run.
        for (Player p : getServer().getOnlinePlayers()) {
            attributes.clear(p);
            ultimates.sweepStrayEnchants(p);
            armor.sweep(p);
        }

        altars.start();
        sword.start();
        new TweakTask(ultimates, attributes, armor).runTaskTimer(this, 5L, 2L);

        TweakCommand command = new TweakCommand(config, menu, ultimates, altars, sword);
        getCommand("gheadcore").setExecutor(command);
        getCommand("gheadcore").setTabCompleter(command);

        getLogger().info("GheadCORE enabled.");
    }

    @Override
    public void onDisable() {
        if (sword != null) sword.shutdown();
        if (attributes != null) attributes.clearAll();
        if (ultimates != null) ultimates.shutdown();
    }
}
