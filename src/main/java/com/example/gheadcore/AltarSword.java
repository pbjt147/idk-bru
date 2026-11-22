package com.example.gheadcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Altar's Sword: a netherite sword that drinks blood. Every few hits it drains hearts from what it
 * strikes and gives them to the bearer. Whoever carries it also gets Strength, a gold crown and a yellow
 * glow outline that shows everyone where they are.
 */
public class AltarSword implements Listener {

    private static final String TEAM_NAME = "ghead_crown";

    /** Crown parts: {centre x, centre z, width, depth, bottom y, height}, relative to the top of the head. */
    private static final double[][] CROWN = {
            // band around the head
            {0, 0.27, 0.56, 0.06, 0.00, 0.12}, {0, -0.27, 0.56, 0.06, 0.00, 0.12},
            {-0.27, 0, 0.06, 0.48, 0.00, 0.12}, {0.27, 0, 0.06, 0.48, 0.00, 0.12},
            // tall points on the corners
            {-0.27, -0.27, 0.07, 0.07, 0.12, 0.16}, {0.27, -0.27, 0.07, 0.07, 0.12, 0.16},
            {-0.27, 0.27, 0.07, 0.07, 0.12, 0.16}, {0.27, 0.27, 0.07, 0.07, 0.12, 0.16},
            // short points in between
            {0, 0.27, 0.06, 0.06, 0.12, 0.10}, {0, -0.27, 0.06, 0.06, 0.12, 0.10},
            {-0.27, 0, 0.06, 0.06, 0.12, 0.10}, {0.27, 0, 0.06, 0.06, 0.12, 0.10}};

    private final JavaPlugin plugin;
    private final TweakConfig config;
    private final NamespacedKey swordTag;
    private final NamespacedKey crownTag;

    private final Map<UUID, Integer> hits = new HashMap<>();
    private final Set<UUID> bearers = new HashSet<>();
    private final Map<UUID, List<UUID>> crowns = new HashMap<>();
    private final Set<UUID> glowing = new HashSet<>();
    private final Map<UUID, String> previousTeam = new HashMap<>();
    private int runs = 0;

    public AltarSword(JavaPlugin plugin, TweakConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.swordTag = new NamespacedKey(plugin, "altar_sword");
        this.crownTag = new NamespacedKey(plugin, "crown_part");
    }

    // ------------------------------------------------------------------ the item

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(gradient("The Altar's Sword", 0xFF1E1E, 0xFFC93C)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("A blade that drinks what it cuts.", NamedTextColor.GRAY),
                Component.empty(),
                line("Every few hits it drains blood from", NamedTextColor.RED),
                line("its victim and heals its bearer.", NamedTextColor.RED),
                Component.empty(),
                line("The bearer is crowned in gold,", NamedTextColor.GOLD),
                line("empowered... and seen by all.", NamedTextColor.GOLD)));
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(swordTag, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    public boolean isSword(ItemStack item) {
        if (item == null || item.getType() != Material.NETHERITE_SWORD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(swordTag, PersistentDataType.BYTE);
    }

    /** Red block chips, used for all the "blood" particles (a plain block particle always shows up). */
    public static BlockData bloodData() {
        return Bukkit.createBlockData(Material.REDSTONE_BLOCK);
    }

    private boolean possesses(Player player) {
        if (config.getBoolean(Category.SWORD, "only-while-held")) {
            return isSword(player.getInventory().getItemInMainHand());
        }
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == Material.NETHERITE_SWORD && isSword(item)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the vampire strike

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getEntity() instanceof LivingEntity victim) || victim instanceof ArmorStand) return;
        if (!isSword(player.getInventory().getItemInMainHand())) return;
        if (config.getBoolean(Category.SWORD, "players-only") && !(victim instanceof Player)) return;

        // A little blood on every hit.
        Location chest = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        victim.getWorld().spawnParticle(Particle.BLOCK, chest, 8, 0.25, 0.3, 0.25, 0.05, bloodData());

        int needed = Math.max(1, config.getInt(Category.SWORD, "hits-required"));
        int count = hits.merge(player.getUniqueId(), 1, Integer::sum);
        if (count < needed) return;

        hits.put(player.getUniqueId(), 0);
        // Next tick, so the normal hit finishes first.
        Bukkit.getScheduler().runTask(plugin, () -> drain(player, victim));
    }

    private void drain(Player player, LivingEntity victim) {
        if (!player.isOnline() || player.isDead() || !victim.isValid() || victim.isDead()) return;

        double wanted = config.getDouble(Category.SWORD, "drain-hearts") * 2.0;
        double taken = Math.min(wanted, victim.getHealth());

        bloodExplosion(victim, player);

        double left = victim.getHealth() - wanted;
        if (left <= 0.0) {
            victim.setNoDamageTicks(0);
            victim.damage(victim.getHealth() + 100.0, player); // finishes it off, credited to the bearer
        } else {
            victim.setHealth(left);
        }

        giveHealth(player, taken);
    }

    /** Heals the bearer; whatever doesn't fit becomes absorption hearts (if allowed). */
    private void giveHealth(Player player, double amount) {
        AttributeInstance maxAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttribute == null ? 20.0 : maxAttribute.getValue();

        double heal = Math.min(Math.max(0, max - player.getHealth()), amount);
        player.setHealth(player.getHealth() + heal);

        double extra = amount - heal;
        if (extra > 0 && config.getBoolean(Category.SWORD, "overflow-absorption")) {
            double cap = config.getDouble(Category.SWORD, "max-absorption-hearts") * 2.0;
            player.setAbsorptionAmount(Math.min(cap, Math.max(player.getAbsorptionAmount(), 0) + extra));
        }
    }

    /** Small red explosion on the victim, and a stream of blood flowing to the bearer. */
    private void bloodExplosion(LivingEntity victim, Player bearer) {
        World world = victim.getWorld();
        Location at = victim.getLocation().add(0, victim.getHeight() / 2, 0);

        world.spawnParticle(Particle.EXPLOSION, at, 1);
        world.spawnParticle(Particle.BLOCK, at, 60, 0.4, 0.5, 0.4, 0.15, bloodData());
        world.spawnParticle(Particle.DAMAGE_INDICATOR, at, 10, 0.3, 0.4, 0.3, 0.1);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f);
        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.7f);

        Location to = bearer.getLocation().add(0, 1.0, 0);
        Vector direction = to.toVector().subtract(at.toVector());
        double distance = direction.length();
        if (distance > 0.1 && distance < 64) {
            direction.normalize();
            for (double d = 0; d < distance; d += 0.35) {
                world.spawnParticle(Particle.BLOCK, at.clone().add(direction.clone().multiply(d)),
                        1, 0.02, 0.02, 0.02, 0, bloodData());
            }
        }
        world.spawnParticle(Particle.HEART, to.clone().add(0, 0.8, 0), 6, 0.4, 0.3, 0.4, 0);
        world.playSound(to, Sound.ENTITY_PLAYER_BURP, 0.5f, 0.6f);
    }

    // ------------------------------------------------------------------ the bearer

    public void start() {
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 10L, 2L);
    }

    private void tick() {
        runs++;
        boolean scan = runs % 5 == 0; // twice a second

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();

            if (scan) {
                if (possesses(player)) {
                    bearers.add(id);
                    applyBearerEffects(player);
                } else if (bearers.remove(id)) {
                    removeBearerEffects(player);
                    hits.remove(id);
                }
            }
            if (bearers.contains(id) && config.getBoolean(Category.SWORD, "aura")) aura(player);
        }
    }

    private void applyBearerEffects(Player player) {
        int level = config.getInt(Category.SWORD, "strength-level");
        if (level > 0) {
            // Short and ambient, refreshed twice a second: it fades quickly once the sword is gone.
            player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 60, level - 1, true, false, false));
        }

        if (config.getBoolean(Category.SWORD, "glow-outline")) {
            enableGlow(player);
        } else {
            disableGlow(player);
        }

        if (config.getBoolean(Category.SWORD, "crown")) {
            ensureCrown(player);
        } else {
            removeCrown(player);
        }
    }

    private void removeBearerEffects(Player player) {
        PotionEffect strength = player.getPotionEffect(PotionEffectType.STRENGTH);
        if (strength != null && strength.isAmbient()) player.removePotionEffect(PotionEffectType.STRENGTH);
        disableGlow(player);
        removeCrown(player);
    }

    /** Swirling red blood chips and crimson spores around the bearer. */
    private void aura(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) return;
        World world = player.getWorld();
        Location base = player.getLocation();

        double angle = runs * 0.5;
        for (int i = 0; i < 2; i++) {
            double a = angle + i * Math.PI;
            double y = 0.1 + ((runs * 0.06 + i * 0.8) % 1.7);
            Location l = base.clone().add(Math.cos(a) * 0.7, y, Math.sin(a) * 0.7);
            world.spawnParticle(Particle.BLOCK, l, 1, 0.02, 0.02, 0.02, 0, bloodData());
        }
        world.spawnParticle(Particle.CRIMSON_SPORE, base.clone().add(0, 1.0, 0), 2, 0.5, 0.7, 0.5, 0);
    }

    // ------------------------------------------------------------------ yellow glow outline

    private void enableGlow(Player player) {
        UUID id = player.getUniqueId();
        if (glowing.contains(id)) return;

        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(TEAM_NAME);
        if (team == null) team = board.registerNewTeam(TEAM_NAME);
        team.color(NamedTextColor.YELLOW); // the outline takes the team colour

        Team before = board.getEntryTeam(player.getName());
        if (before != null && !before.getName().equals(TEAM_NAME)) previousTeam.put(id, before.getName());

        team.addEntry(player.getName());
        player.setGlowing(true);
        glowing.add(id);
    }

    private void disableGlow(Player player) {
        UUID id = player.getUniqueId();
        if (!glowing.remove(id)) return;

        player.setGlowing(false);
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(TEAM_NAME);
        if (team != null) team.removeEntry(player.getName());

        String before = previousTeam.remove(id);
        if (before != null) {
            Team old = board.getTeam(before);
            if (old != null) old.addEntry(player.getName());
        }
    }

    // ------------------------------------------------------------------ gold crown

    private void ensureCrown(Player player) {
        List<UUID> parts = crowns.get(player.getUniqueId());
        if (parts != null && crownIntact(player, parts)) return;
        removeCrown(player);
        spawnCrown(player);
    }

    /** A teleport or world change knocks passengers off, so check they're all still riding. */
    private boolean crownIntact(Player player, List<UUID> parts) {
        for (UUID partId : parts) {
            Entity part = Bukkit.getEntity(partId);
            if (part == null || !part.isValid() || !player.equals(part.getVehicle())) return false;
        }
        return true;
    }

    private void spawnCrown(Player player) {
        World world = player.getWorld();
        List<UUID> parts = new ArrayList<>();

        for (double[] box : CROWN) {
            BlockDisplay part = world.spawn(player.getLocation(), BlockDisplay.class, d -> {
                d.setBlock(Material.GOLD_BLOCK.createBlockData());
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(0.6f);
                d.setPersistent(false); // never saved with the world
                d.setTransformation(new Transformation(
                        new Vector3f((float) (box[0] - box[2] / 2), (float) box[4], (float) (box[1] - box[3] / 2)),
                        new AxisAngle4f(0f, 0f, 1f, 0f),
                        new Vector3f((float) box[2], (float) box[5], (float) box[3]),
                        new AxisAngle4f(0f, 0f, 0f, 1f)));
                d.getPersistentDataContainer().set(crownTag, PersistentDataType.BYTE, (byte) 1);
            });
            player.addPassenger(part);
            parts.add(part.getUniqueId());
        }
        crowns.put(player.getUniqueId(), parts);
    }

    private void removeCrown(Player player) {
        List<UUID> parts = crowns.remove(player.getUniqueId());
        if (parts == null) return;
        for (UUID partId : parts) {
            Entity part = Bukkit.getEntity(partId);
            if (part != null) part.remove();
        }
    }

    // ------------------------------------------------------------------ clean-up

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        bearers.remove(player.getUniqueId());
        hits.remove(player.getUniqueId());
        removeBearerEffects(player);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        // The sword drops, so the next scan strips everything. Drop the crown right away.
        removeCrown(event.getEntity());
        hits.remove(event.getEntity().getUniqueId());
    }

    /** Plugin disable: take every effect off every player. */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeBearerEffects(player);
        }
        bearers.clear();
    }

    // ------------------------------------------------------------------ helpers

    private Component gradient(String text, int from, int to) {
        TextComponent.Builder builder = Component.text();
        int last = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            builder.append(Component.text(String.valueOf(text.charAt(i)),
                    TextColor.lerp(i / (float) last, TextColor.color(from), TextColor.color(to))));
        }
        return builder.build();
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
