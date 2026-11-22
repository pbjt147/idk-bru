package com.example.gheadcore;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/** Runs every 2 ticks: attribute sync, ultimate timers and the boss bar. Armor is checked once a second. */
public class TweakTask extends BukkitRunnable {

    private final UltimateManager ultimates;
    private final HandAttributes attributes;
    private final ArmorListener armor;
    private int runs = 0;

    public TweakTask(UltimateManager ultimates, HandAttributes attributes, ArmorListener armor) {
        this.ultimates = ultimates;
        this.attributes = attributes;
        this.armor = armor;
    }

    @Override
    public void run() {
        ultimates.nextTick();
        boolean armorPass = ++runs % 10 == 0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            attributes.sync(player);
            ultimates.tick(player);
            if (armorPass) armor.sweep(player);
        }
    }
}
