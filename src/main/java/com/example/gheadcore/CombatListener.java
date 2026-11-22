package com.example.gheadcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class CombatListener implements Listener {

    private final JavaPlugin plugin;
    private final TweakConfig config;
    private final UltimateManager ultimates;
    private final HandAttributes attributes;
    private final NamespacedKey crossbowKey;

    /** True while we are dealing sweep damage, so it doesn't trigger more sweeps. */
    private boolean sweeping = false;

    /** When each player last used a weapon right-click, so the shield in their off hand can be ignored. */
    private final Map<UUID, Long> rightClickUsedAt = new HashMap<>();

    public CombatListener(JavaPlugin plugin, TweakConfig config, UltimateManager ultimates, HandAttributes attributes) {
        this.plugin = plugin;
        this.config = config;
        this.ultimates = ultimates;
        this.attributes = attributes;
        this.crossbowKey = new NamespacedKey(plugin, "crossbow_arrow");
    }

    // ------------------------------------------------------------------ ultimate activation

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!config.getBoolean(Category.GENERAL, "activate-swap")) return;

        Player player = event.getPlayer();
        Ultimate u = Ultimate.of(player.getInventory().getItemInMainHand().getType());
        if (u == null || !ultimates.isEnabled(u)) return;
        if (!player.hasPermission("gheadcore.ultimate")) return;

        event.setCancelled(true); // don't actually swap the item
        ultimates.activate(player, u);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();

        // The off-hand click that follows a weapon click: don't let the shield (or anything else) react to it.
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            Long usedAt = rightClickUsedAt.get(player.getUniqueId());
            if (usedAt != null && System.currentTimeMillis() - usedAt < 250) {
                event.setUseItemInHand(Event.Result.DENY);
            }
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) return;

        Ultimate u = Ultimate.of(player.getInventory().getItemInMainHand().getType());
        if (u == null || !ultimates.isEnabled(u)) return;
        if (!player.hasPermission("gheadcore.ultimate")) return;

        boolean sneakClick = config.getBoolean(Category.GENERAL, "activate-sneak-click") && player.isSneaking();
        boolean plainClick = config.getBoolean(Category.GENERAL, "activate-right-click");
        if (!sneakClick && !plainClick) return;

        if (!sneakClick) {
            // A plain right-click only counts when the ultimate is ready, so loading a crossbow,
            // raising a shield etc. keeps working while it is active or recharging.
            if (!ultimates.canActivate(player, u)) return;

            // Chests, doors, buttons and so on keep working too.
            Block clicked = event.getClickedBlock();
            if (clicked != null && clicked.getType().isInteractable()) return;
        }

        event.setUseItemInHand(Event.Result.DENY); // e.g. don't throw the trident
        if (config.getBoolean(Category.GENERAL, "right-click-priority")) {
            rightClickUsedAt.put(player.getUniqueId(), System.currentTimeMillis());
        }
        ultimates.activate(player, u);
    }

    // ------------------------------------------------------------------ melee + arrows

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player
                && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            if (sweeping) return;
            if (player.getInventory().getItemInMainHand().getType() == Material.TRIDENT) {
                tridentMelee(event, player);
            }
        } else if (event.getDamager() instanceof AbstractArrow arrow) {
            crossbowArrowHit(event, arrow);
        }
    }

    // ------------------------------------------------------------------ mace hit cooldown

    /** Does the mace hit cooldown apply to this swing (right weapon, right target, not bypassed)? */
    private boolean maceCooldownApplies(Player player, Entity victim) {
        if (player.getInventory().getItemInMainHand().getType() != Material.MACE) return false;
        if (ultimates.maceHitCooldownBypassed(player)) return false;
        return victim instanceof Player || !config.getBoolean(Category.MACE, "hit-cooldown-players-only");
    }

    /** Still recharging from the last hit? Then the swing does nothing. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMaceHitCheck(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!maceCooldownApplies(player, event.getEntity())) return;

        // Still recharging: the swing does nothing. The vanilla hotbar cooldown sweep shows the time left.
        if (ultimates.hitCooldownRemaining(player, Ultimate.MACE, System.currentTimeMillis()) > 0) {
            event.setCancelled(true);
        }
    }

    /** A hit that actually landed starts the cooldown. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMaceHitLanded(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!maceCooldownApplies(player, event.getEntity())) return;
        ultimates.startMaceHitCooldown(player);
    }

    private void tridentMelee(EntityDamageByEntityEvent event, Player player) {
        Entity victim = event.getEntity();
        World world = victim.getWorld();

        // Disable the victim's shield, like an axe.
        if (config.getBoolean(Category.TRIDENT, "disable-shield")
                && victim instanceof Player target && target.isBlocking()) {
            int ticks = (int) Math.round(config.getDouble(Category.TRIDENT, "shield-disable-seconds") * 20);
            if (ticks > 0) {
                target.setCooldown(Material.SHIELD, ticks);
                target.clearActiveItem();
                world.playSound(target.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 1f);
            }
        }

        boolean crit = event.isCritical();

        // Thunderstorm: crits hit harder and call lightning.
        if (crit && ultimates.isActive(player, Ultimate.TRIDENT)) {
            double extra = config.getDouble(Category.TRIDENT, "thunder-damage");
            if (extra > 0) event.setDamage(event.getDamage() + extra);

            int chance = config.getInt(Category.TRIDENT, "thunder-chance");
            if (ThreadLocalRandom.current().nextInt(100) < chance) {
                Location loc = victim.getLocation();
                if (config.getBoolean(Category.TRIDENT, "real-lightning")) {
                    LightningStrike bolt = world.strikeLightning(loc);
                    bolt.setCausingPlayer(player);
                } else {
                    world.strikeLightningEffect(loc);
                }
                world.spawnParticle(Particle.ELECTRIC_SPARK, loc.clone().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.3);
            }
        }

        // Sword-style sweeping attack on normal (non-crit) hits.
        if (!crit && config.getBoolean(Category.TRIDENT, "sword-mode")
                && config.getBoolean(Category.TRIDENT, "sweep")) {
            sweep(player, victim);
        }
    }

    private void sweep(Player player, Entity victim) {
        double damage = config.getDouble(Category.TRIDENT, "sweep-damage");
        if (damage <= 0) return;

        boolean hitSomething = false;
        sweeping = true;
        try {
            for (Entity near : victim.getNearbyEntities(1.0, 0.25, 1.0)) {
                if (near == player || near == victim) continue;
                if (!(near instanceof LivingEntity living) || near instanceof ArmorStand) continue;
                if (near instanceof Player other && other.getGameMode() == GameMode.SPECTATOR) continue;
                living.damage(damage, player);
                hitSomething = true;
            }
        } finally {
            sweeping = false;
        }

        if (hitSomething) {
            World world = victim.getWorld();
            world.spawnParticle(Particle.SWEEP_ATTACK, victim.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0);
            world.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);
        }
    }

    private void crossbowArrowHit(EntityDamageByEntityEvent event, AbstractArrow arrow) {
        if (!arrow.getPersistentDataContainer().has(crossbowKey, PersistentDataType.BYTE)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        int chance = config.getInt(Category.CROSSBOW, "ignite-chance");
        if (chance > 0 && ThreadLocalRandom.current().nextInt(100) < chance) {
            int ticks = (int) Math.round(config.getDouble(Category.CROSSBOW, "ignite-seconds") * 20);
            target.setFireTicks(Math.max(target.getFireTicks(), ticks));
        }
    }

    // ------------------------------------------------------------------ crossbows

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        ItemStack bow = event.getBow();
        if (bow == null || bow.getType() != Material.CROSSBOW) return;
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;

        arrow.getPersistentDataContainer().set(crossbowKey, PersistentDataType.BYTE, (byte) 1);

        // Ultimate: Power. Each level adds 25% like the vanilla bow enchantment (level 5 = +150%).
        if (event.getEntity() instanceof Player shooter && ultimates.isActive(shooter, Ultimate.CROSSBOW)) {
            int level = config.getInt(Category.CROSSBOW, "power-level");
            arrow.setDamage(arrow.getDamage() * (1.0 + 0.25 * (level + 1)));
        }
    }

    /** Crossbow bolts ignore shields: drop the target's guard right before the hit lands. */
    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof AbstractArrow arrow)) return;
        if (!arrow.getPersistentDataContainer().has(crossbowKey, PersistentDataType.BYTE)) return;
        if (!config.getBoolean(Category.CROSSBOW, "pierce-shields")) return;

        if (event.getHitEntity() instanceof Player victim && victim.isBlocking()) {
            victim.clearActiveItem();
        }
    }

    // ------------------------------------------------------------------ thunderstorm safety

    /** While your Thunderstorm is active you can't be hurt or set on fire by lightning. */
    @EventHandler(ignoreCancelled = true)
    public void onLightningDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.LIGHTNING) return;
        if (event.getEntity() instanceof Player player && ultimates.isActive(player, Ultimate.TRIDENT)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLightningIgnite(EntityCombustByEntityEvent event) {
        if (!(event.getCombuster() instanceof LightningStrike)) return;
        if (event.getEntity() instanceof Player player && ultimates.isActive(player, Ultimate.TRIDENT)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ golden apples

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Material type = event.getItem().getType();
        boolean normal = type == Material.GOLDEN_APPLE;
        boolean enchanted = type == Material.ENCHANTED_GOLDEN_APPLE;
        if (!normal && !enchanted) return;
        if (!config.getBoolean(Category.GOLDEN_APPLE, "enabled")) return;
        if (enchanted && !config.getBoolean(Category.GOLDEN_APPLE, "include-enchanted")) return;

        Player player = event.getPlayer();
        int amplifier = Math.max(0, config.getInt(Category.GOLDEN_APPLE, "absorption-level") - 1);
        int ticks = config.getInt(Category.GOLDEN_APPLE, "duration-seconds") * 20;

        // One tick later, after the vanilla effect has been applied, so ours takes over.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            PotionEffect existing = player.getPotionEffect(PotionEffectType.ABSORPTION);
            if (existing != null && existing.getAmplifier() > amplifier) return; // never weaken it
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, ticks, amplifier, false, true, true));
        });
    }

    // ------------------------------------------------------------------ cleanup

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        attributes.clear(event.getPlayer());
        ultimates.sweepStrayEnchants(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        rightClickUsedAt.remove(event.getPlayer().getUniqueId());
        attributes.clear(event.getPlayer());
        ultimates.cleanup(event.getPlayer());
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack stack = event.getItemDrop().getItemStack();
        if (ultimates.restore(stack)) event.getItemDrop().setItemStack(stack);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        for (ItemStack drop : event.getDrops()) ultimates.restore(drop);
        ultimates.endAll(event.getEntity());
    }
}
