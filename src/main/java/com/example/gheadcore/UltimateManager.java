package com.example.gheadcore;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles ultimate state (active / cooldown / ready), the boss bar, and the temporary
 * enchantments some ultimates put on your items.
 * <p>
 * Temporary enchantments are tagged on the item so they can always be taken back off:
 * when the ultimate ends, when the item is dropped, when you log in, and by a periodic sweep.
 * Cooldown starts when the ultimate ends.
 */
public class UltimateManager {

    private static final class State {
        boolean active;
        boolean readyNotified = true;
        long activeStart;
        long activeUntil;
        long cooldownStart;
        long cooldownUntil;
    }

    private final TweakConfig config;
    private final NamespacedKey tempKey;

    private final Map<UUID, EnumMap<Ultimate, State>> states = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Set<UUID> shown = new HashSet<>();
    private final Map<UUID, Long> maceHitUntil = new HashMap<>();
    /** When the hotbar cooldown sweep we last started for each player will finish. */
    private final Map<UUID, Long> maceOverlayUntil = new HashMap<>();
    private int tickCounter = 0;

    public UltimateManager(JavaPlugin plugin, TweakConfig config) {
        this.config = config;
        this.tempKey = new NamespacedKey(plugin, "temp_enchant");
    }

    // ------------------------------------------------------------------ queries

    private State state(UUID id, Ultimate u) {
        return states.computeIfAbsent(id, k -> new EnumMap<>(Ultimate.class))
                .computeIfAbsent(u, k -> new State());
    }

    public boolean isEnabled(Ultimate u) {
        return config.getBoolean(u.category(), "ult-enabled");
    }

    public boolean isActive(Player player, Ultimate u) {
        EnumMap<Ultimate, State> mine = states.get(player.getUniqueId());
        if (mine == null) return false;
        State s = mine.get(u);
        return s != null && s.active && System.currentTimeMillis() < s.activeUntil;
    }

    // ------------------------------------------------------------------ mace hit cooldown

    /** True when the mace hit cooldown does not apply right now (switched off, or the ultimate is running). */
    public boolean maceHitCooldownBypassed(Player player) {
        if (!config.getBoolean(Category.MACE, "hit-cooldown-enabled")) return true;
        return isActive(player, Ultimate.MACE) && config.getBoolean(Category.MACE, "ult-ignores-hit-cooldown");
    }

    /** Milliseconds until this player can hit with a mace again (0 = ready). Only meaningful for the mace. */
    public long hitCooldownRemaining(Player player, Ultimate u, long now) {
        if (u != Ultimate.MACE || maceHitCooldownBypassed(player)) return 0;
        Long until = maceHitUntil.get(player.getUniqueId());
        return until == null ? 0 : Math.max(0, until - now);
    }

    public void startMaceHitCooldown(Player player) {
        double seconds = config.getDouble(Category.MACE, "hit-cooldown");
        if (seconds > 0) {
            long now = System.currentTimeMillis();
            maceHitUntil.put(player.getUniqueId(), now + (long) (seconds * 1000));
            syncMaceOverlay(player, now);
        }
    }

    /**
     * Keeps the vanilla hotbar cooldown sweep on the mace in step with the hit cooldown: it runs while
     * you are recharging, and disappears during the ultimate (or when the cooldown is switched off).
     */
    private void syncMaceOverlay(Player player, long now) {
        UUID id = player.getUniqueId();
        long remaining = hitCooldownRemaining(player, Ultimate.MACE, now);
        Long shownUntil = maceOverlayUntil.get(id);

        if (remaining > 0) {
            long target = now + remaining;
            if (shownUntil == null || Math.abs(shownUntil - target) > 200) {
                player.setCooldown(Material.MACE, (int) Math.max(1, Math.round(remaining / 50.0)));
                maceOverlayUntil.put(id, target);
            }
        } else if (shownUntil != null) {
            player.setCooldown(Material.MACE, 0);
            maceOverlayUntil.remove(id);
        }
    }

    /** True when the ultimate is enabled, not running, and off cooldown. */
    public boolean canActivate(Player player, Ultimate u) {
        if (!isEnabled(u)) return false;
        State s = state(player.getUniqueId(), u);
        return !s.active && System.currentTimeMillis() >= s.cooldownUntil;
    }

    // ------------------------------------------------------------------ activation

    public void activate(Player player, Ultimate u) {
        if (!isEnabled(u)) return;

        long now = System.currentTimeMillis();
        State s = state(player.getUniqueId(), u);

        if (s.active) {
            player.sendActionBar(Component.text(u.displayName() + " is already active", NamedTextColor.YELLOW));
            return;
        }
        if (now < s.cooldownUntil) {
            player.sendActionBar(Component.text(
                    String.format(Locale.ROOT, "%s recharging: %.1fs", u.displayName(), (s.cooldownUntil - now) / 1000.0),
                    NamedTextColor.RED));
            return;
        }

        long durationMs = (long) (Math.max(1, config.getDouble(u.category(), "ult-duration")) * 1000);
        s.active = true;
        s.activeStart = now;
        s.activeUntil = now + durationMs;

        applyEnchants(player, u);

        player.sendActionBar(Component.text(u.displayName() + " activated!", TextColor.color(u.textColor()))
                .decoration(TextDecoration.BOLD, true));
        activationEffects(player, u);
    }

    private void activationEffects(Player player, Ultimate u) {
        World world = player.getWorld();
        var loc = player.getLocation().add(0, 1, 0);
        switch (u) {
            case TRIDENT -> {
                world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1.2f);
                world.spawnParticle(Particle.ELECTRIC_SPARK, loc, 40, 0.6, 0.8, 0.6, 0.3);
            }
            case MACE -> {
                world.playSound(loc, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1f, 1f);
                world.spawnParticle(Particle.GUST_EMITTER_SMALL, loc, 1);
            }
            case SPEAR -> {
                world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1.4f);
                world.spawnParticle(Particle.CRIT, loc, 30, 0.5, 0.7, 0.5, 0.4);
            }
            case CROSSBOW -> {
                world.playSound(loc, Sound.ITEM_CROSSBOW_LOADING_END, 1f, 0.8f);
                world.spawnParticle(Particle.FLAME, loc, 25, 0.5, 0.7, 0.5, 0.05);
            }
        }
    }

    // ------------------------------------------------------------------ ending

    private void end(Player player, Ultimate u, boolean notify) {
        State s = state(player.getUniqueId(), u);
        if (!s.active) return;

        long now = System.currentTimeMillis();
        s.active = false;
        s.readyNotified = false;
        s.cooldownStart = now;
        s.cooldownUntil = now + (long) (Math.max(0, config.getDouble(u.category(), "ult-cooldown")) * 1000);

        restoreItems(player, u);

        if (notify) {
            player.sendActionBar(Component.text(u.displayName() + " ended", NamedTextColor.GRAY));
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.2f);
        }
    }

    /** Ends every active ultimate (death, logout). */
    public void endAll(Player player) {
        for (Ultimate u : Ultimate.values()) end(player, u, false);
    }

    /** For /gheadcore resetcooldowns */
    public void resetCooldowns(Player player) {
        endAll(player);
        states.remove(player.getUniqueId());
        maceHitUntil.remove(player.getUniqueId());
        maceOverlayUntil.remove(player.getUniqueId());
        player.setCooldown(Material.MACE, 0);
    }

    /** Logout: finish ultimates and remove the bar (cooldowns are remembered). */
    public void cleanup(Player player) {
        maceOverlayUntil.remove(player.getUniqueId());
        endAll(player);
        hideBar(player);
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    /** Plugin disable: take every temporary enchantment off every online player. */
    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            restoreItems(p, null);
            p.setCooldown(Material.MACE, 0);
            BossBar bar = bars.get(p.getUniqueId());
            if (bar != null) p.hideBossBar(bar);
        }
    }

    // ------------------------------------------------------------------ per-tick

    public void nextTick() {
        tickCounter++;
    }

    public void tick(Player player) {
        long now = System.currentTimeMillis();
        syncMaceOverlay(player, now);
        EnumMap<Ultimate, State> mine = states.get(player.getUniqueId());

        if (mine != null) {
            for (Ultimate u : Ultimate.values()) {
                State s = mine.get(u);
                if (s == null) continue;

                if (s.active) {
                    if (now >= s.activeUntil) {
                        end(player, u, true);
                    } else if (tickCounter % 5 == 0) {
                        applyEnchants(player, u); // also covers items picked up mid-ultimate
                    }
                } else if (!s.readyNotified && s.cooldownUntil > 0 && now >= s.cooldownUntil) {
                    s.readyNotified = true;
                    if (Ultimate.of(player.getInventory().getItemInMainHand().getType()) == u) {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.6f);
                    }
                }
            }
        }

        if (tickCounter % 20 == 0) sweepStrayEnchants(player);
        updateBar(player, now);
    }

    // ------------------------------------------------------------------ boss bar

    private void updateBar(Player player, long now) {
        UUID id = player.getUniqueId();
        Ultimate u = Ultimate.of(player.getInventory().getItemInMainHand().getType());

        if (!config.getBoolean(Category.GENERAL, "bar-enabled") || u == null || !isEnabled(u)) {
            hideBar(player);
            return;
        }

        State s = state(id, u);
        TextColor ultColor = TextColor.color(u.textColor());
        long hitLeft = hitCooldownRemaining(player, u, now);
        String idleLeft = clock(hitLeft);
        TextColor idleLeftColor = hitLeft > 0 ? NamedTextColor.RED : NamedTextColor.DARK_GRAY;
        float progress;
        Component name;
        BossBar.Color color;

        if (s.active) {
            long remaining = Math.max(0, s.activeUntil - now);
            double total = Math.max(1, s.activeUntil - s.activeStart);
            long nextCooldown = (long) (Math.max(0, config.getDouble(u.category(), "ult-cooldown")) * 1000);
            progress = (float) (remaining / total);
            // [symbol] 0:12  ||  [star] 1:50   (left = time left active, right = cooldown that follows)
            name = hud(u.symbol(), ultColor, clock(remaining), NamedTextColor.RED, NamedTextColor.RED,
                    NamedTextColor.GRAY, clock(nextCooldown), NamedTextColor.GRAY, "");
            color = u.barColor();
        } else if (now < s.cooldownUntil) {
            double total = Math.max(1, s.cooldownUntil - s.cooldownStart);
            progress = (float) ((now - s.cooldownStart) / total);
            name = hud(u.symbol(), NamedTextColor.DARK_GRAY, idleLeft, idleLeftColor, NamedTextColor.DARK_RED,
                    NamedTextColor.WHITE, clock(s.cooldownUntil - now), NamedTextColor.WHITE, "");
            color = BossBar.Color.RED;
        } else {
            progress = 1f;
            String hint = activationHint();
            name = hud(u.symbol(), NamedTextColor.GREEN, idleLeft, idleLeftColor, NamedTextColor.GREEN,
                    NamedTextColor.GREEN, "READY", NamedTextColor.GREEN, hint.isEmpty() ? "" : "[" + hint + "]");
            color = BossBar.Color.GREEN;
        }
        progress = Math.max(0f, Math.min(1f, progress));

        BossBar bar = bars.get(id);
        if (bar == null) {
            bar = BossBar.bossBar(name, progress, color, BossBar.Overlay.PROGRESS);
            bars.put(id, bar);
        } else {
            bar.name(name);
            bar.progress(progress);
            bar.color(color);
        }
        if (shown.add(id)) player.showBossBar(bar);
    }

    /** m:ss, rounded up, like 0:54 */
    private String clock(long millis) {
        long seconds = (Math.max(0, millis) + 999) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** Builds: [weapon symbol] left-timer  ||  [star] right-timer  [hint] */
    private Component hud(String symbol, TextColor symbolColor, String leftTime, TextColor leftColor,
                          TextColor dividerColor, TextColor starColor, String rightText, TextColor rightColor,
                          String suffix) {
        TextComponent.Builder b = Component.text()
                .append(Component.text(symbol + " ", symbolColor))
                .append(Component.text(leftTime, leftColor).decoration(TextDecoration.BOLD, true))
                .append(Component.text("  \u25AE\u25AE  ", dividerColor))
                .append(Component.text("\u2726 ", starColor))
                .append(Component.text(rightText, rightColor).decoration(TextDecoration.BOLD, true));
        if (!suffix.isEmpty()) b.append(Component.text(" " + suffix, NamedTextColor.GRAY));
        return b.build();
    }

    private String activationHint() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        if (config.getBoolean(Category.GENERAL, "activate-swap")) keys.add("F");
        if (config.getBoolean(Category.GENERAL, "activate-right-click")) keys.add("right-click");
        if (config.getBoolean(Category.GENERAL, "activate-sneak-click")) keys.add("sneak+click");
        return String.join(" / ", keys);
    }

    private void hideBar(Player player) {
        UUID id = player.getUniqueId();
        if (shown.remove(id)) {
            BossBar bar = bars.get(id);
            if (bar != null) player.hideBossBar(bar);
        }
    }

    // ------------------------------------------------------------------ temporary enchantments

    private Enchantment enchantFor(Ultimate u) {
        return switch (u) {
            case MACE -> Enchantment.WIND_BURST;
            case SPEAR -> Enchantment.LUNGE;
            case CROSSBOW -> Enchantment.QUICK_CHARGE;
            case TRIDENT -> null; // thunderstorm works through events, not an enchantment
        };
    }

    private int levelFor(Ultimate u) {
        return switch (u) {
            case MACE -> config.getInt(Category.MACE, "windburst-level");
            case SPEAR -> config.getInt(Category.SPEAR, "lunge-level");
            case CROSSBOW -> config.getInt(Category.CROSSBOW, "quick-charge-level");
            case TRIDENT -> 0;
        };
    }

    /** Puts the ultimate's enchantment on every matching item the player carries. */
    private void applyEnchants(Player player, Ultimate u) {
        Enchantment ench = enchantFor(u);
        if (ench == null) return;
        int level = Math.max(1, levelFor(u));

        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            if (Ultimate.of(item.getType()) != u) continue;
            if (addTemp(item, u, ench, level)) inv.setItem(i, item);
        }
    }

    private boolean addTemp(ItemStack item, Ultimate u, Enchantment ench, int level) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int existing = meta.getEnchantLevel(ench);

        if (pdc.has(tempKey, PersistentDataType.STRING)) {
            if (existing == level) return false; // already ours and up to date
            meta.addEnchant(ench, level, true);
            item.setItemMeta(meta);
            return true;
        }
        if (existing >= level) return false; // never downgrade an item that's already better

        pdc.set(tempKey, PersistentDataType.STRING, u.name() + "=" + existing);
        meta.addEnchant(ench, level, true);
        item.setItemMeta(meta);
        return true;
    }

    /** Takes our temporary enchantment off one item. Returns true if the item was changed. */
    public boolean restore(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String data = pdc.get(tempKey, PersistentDataType.STRING);
        if (data == null) return false;

        String[] parts = data.split("=");
        int original = 0;
        Enchantment ench = null;
        try {
            ench = enchantFor(Ultimate.valueOf(parts[0]));
            if (parts.length > 1) original = Integer.parseInt(parts[1]);
        } catch (IllegalArgumentException ignored) {
            // unknown marker: just drop it below
        }

        if (ench != null) {
            meta.removeEnchant(ench);
            if (original > 0) meta.addEnchant(ench, original, true);
        }
        pdc.remove(tempKey);
        item.setItemMeta(meta);
        return true;
    }

    /** Restores items for one ultimate (or all of them when {@code only} is null). */
    private void restoreItems(Player player, Ultimate only) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            if (only != null && Ultimate.of(item.getType()) != only) continue;
            if (restore(item)) inv.setItem(i, item);
        }
    }

    /** Removes temporary enchantments that shouldn't be there (no active ultimate for that item). */
    public void sweepStrayEnchants(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;

            Ultimate u = Ultimate.of(item.getType());
            if (u != null && isActive(player, u)) continue;
            if (restore(item)) inv.setItem(i, item);
        }
    }
}
