package com.example.gheadcore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Gives players sword-like attack speed (and bonus damage) while they hold a trident or spear.
 * Nothing is written to the items themselves - it is a temporary attribute on the player.
 */
public class HandAttributes {

    private record Applied(Material type, double speed, double bonus) {}

    private final TweakConfig config;
    private final UltimateManager ultimates;
    private final NamespacedKey speedKey;
    private final NamespacedKey damageKey;

    private final Map<UUID, Applied> applied = new HashMap<>();
    private final Map<UUID, Material> settling = new HashMap<>();

    public HandAttributes(JavaPlugin plugin, TweakConfig config, UltimateManager ultimates) {
        this.config = config;
        this.ultimates = ultimates;
        this.speedKey = new NamespacedKey(plugin, "hand_attack_speed");
        this.damageKey = new NamespacedKey(plugin, "hand_attack_damage");
    }

    /** What the player should have right now for the item they're holding (null = nothing). */
    private Applied desired(Player player, Material type) {
        Applied base = baseDesired(type);

        // During an ultimate, optionally remove the attack cooldown completely (huge attack speed).
        Ultimate u = Ultimate.of(type);
        if (u != null && ultimates.isActive(player, u)
                && config.getBoolean(u.category(), "ult-no-attack-cooldown")) {
            return new Applied(type, 100.0, base == null ? 0 : base.bonus());
        }
        return base;
    }

    private Applied baseDesired(Material type) {
        if (type == Material.TRIDENT && config.getBoolean(Category.TRIDENT, "sword-mode")) {
            return new Applied(type, config.getDouble(Category.TRIDENT, "attack-speed"),
                    config.getDouble(Category.TRIDENT, "bonus-damage"));
        }
        if (type.name().endsWith("_SPEAR") && config.getBoolean(Category.SPEAR, "fast-pokes")) {
            return new Applied(type, config.getDouble(Category.SPEAR, "attack-speed"),
                    config.getDouble(Category.SPEAR, "bonus-damage"));
        }
        return null;
    }

    /**
     * Called every couple of ticks. After the held item changes we drop our modifier, wait one cycle so
     * the vanilla item values have settled, then apply the new one.
     */
    public void sync(Player player) {
        UUID id = player.getUniqueId();
        Material type = player.getInventory().getItemInMainHand().getType();
        Applied want = desired(player, type);
        Applied have = applied.get(id);

        if (Objects.equals(want, have)) {
            settling.remove(id);
            return;
        }

        if (have != null) {
            remove(player);
            applied.remove(id);
            settling.put(id, type);
            return;
        }

        if (want == null) {
            settling.remove(id);
            return;
        }

        if (settling.get(id) != type) {
            settling.put(id, type);
            return;
        }

        settling.remove(id);
        apply(player, want);
    }

    private void apply(Player player, Applied want) {
        remove(player);

        AttributeInstance speed = player.getAttribute(Attribute.ATTACK_SPEED);
        AttributeInstance damage = player.getAttribute(Attribute.ATTACK_DAMAGE);

        if (speed != null) {
            double delta = want.speed() - speed.getValue();
            if (Math.abs(delta) > 1.0E-6) {
                speed.addModifier(new AttributeModifier(speedKey, delta,
                        AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
            }
        }
        if (damage != null && want.bonus() > 0) {
            damage.addModifier(new AttributeModifier(damageKey, want.bonus(),
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
        applied.put(player.getUniqueId(), want);
    }

    private void remove(Player player) {
        strip(player.getAttribute(Attribute.ATTACK_SPEED), speedKey);
        strip(player.getAttribute(Attribute.ATTACK_DAMAGE), damageKey);
    }

    private void strip(AttributeInstance instance, NamespacedKey key) {
        if (instance == null) return;
        for (AttributeModifier m : new ArrayList<>(instance.getModifiers())) {
            if (m.getKey().equals(key)) instance.removeModifier(m);
        }
    }

    /** Login/logout: make sure no stale modifier is left on the player. */
    public void clear(Player player) {
        remove(player);
        applied.remove(player.getUniqueId());
        settling.remove(player.getUniqueId());
    }

    public void clearAll() {
        for (Player p : Bukkit.getOnlinePlayers()) clear(p);
    }
}
