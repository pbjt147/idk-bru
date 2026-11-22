package com.example.gheadcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The Trial Altar: in roughly one of every hundred newly generated trial chambers, a small tuff altar with an
 * arch over it (a bit like a well) is placed in a room, with The Altar's Sword floating and slowly spinning
 * above the pedestal. Right-click it to take the sword. Use /gheadcore altar to place one by hand.
 */
public class AltarManager implements Listener {

    private static final float SWORD_SCALE = 1.4f;
    private static final int SPIN_PERIOD_TICKS = 10;
    private static final float TWO_PI = (float) (Math.PI * 2);

    private final JavaPlugin plugin;
    private final TweakConfig config;
    private final AltarSword sword;

    private final NamespacedKey markerKey;
    private final NamespacedKey linkKey;

    private final Set<UUID> displays = new HashSet<>();
    private final Map<UUID, Float> angles = new HashMap<>();
    private final Set<String> placed = new HashSet<>();
    private final File file;
    private int tickCount = 0;

    public AltarManager(JavaPlugin plugin, TweakConfig config, AltarSword sword) {
        this.plugin = plugin;
        this.config = config;
        this.sword = sword;
        this.markerKey = new NamespacedKey(plugin, "altar_part");
        this.linkKey = new NamespacedKey(plugin, "altar_display");
        this.file = new File(plugin.getDataFolder(), "altars.yml");
        loadPlaced();
    }

    // ------------------------------------------------------------------ start-up

    public void start() {
        // Pick up altars that were already in the world before this plugin started.
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay d : world.getEntitiesByClass(ItemDisplay.class)) {
                if (d.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) track(d);
            }
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                spin();
            }
        }.runTaskTimer(plugin, 20L, SPIN_PERIOD_TICKS);
    }

    private void loadPlaced() {
        if (!file.exists()) return;
        placed.addAll(YamlConfiguration.loadConfiguration(file).getStringList("placed"));
    }

    private void savePlaced() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("placed", new ArrayList<>(placed));
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save altars.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ finding trial chambers

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) return;
        if (!config.getBoolean(Category.ALTAR, "enabled")) return;

        Chunk chunk = event.getChunk();
        // One tick later, so spawning entities in the fresh chunk is safe.
        Bukkit.getScheduler().runTask(plugin, () -> checkChunk(chunk));
    }

    private void checkChunk(Chunk chunk) {
        if (!chunk.isLoaded()) return;
        World world = chunk.getWorld();

        for (GeneratedStructure structure : chunk.getStructures(Structure.TRIAL_CHAMBERS)) {
            BoundingBox box = structure.getBoundingBox();
            String id = world.getName() + ":" + (int) box.getMinX() + ":" + (int) box.getMinY() + ":" + (int) box.getMinZ();
            if (placed.contains(id)) continue;
            if (!wins(world, box)) continue;

            Location spot = findSpot(chunk, structure, new Random(mix(seedFor(world, box) ^ 0x9E3779B97F4A7C15L)));
            if (spot == null) continue; // maybe the next chunk of this chamber has a room for it

            spawnAltar(spot);
            placed.add(id);
            savePlaced();
            plugin.getLogger().info("Placed a trial altar at " + spot.getBlockX() + " " + spot.getBlockY()
                    + " " + spot.getBlockZ() + " in " + world.getName());
        }
    }

    /** The same chamber always gives the same answer, so the odds don't depend on chunk load order. */
    private boolean wins(World world, BoundingBox box) {
        double chance = config.getDouble(Category.ALTAR, "chance");
        if (chance <= 0) return false;
        Random random = new Random(mix(seedFor(world, box)));
        return random.nextDouble() * 100.0 < chance;
    }

    private long seedFor(World world, BoundingBox box) {
        return world.getSeed()
                ^ ((long) box.getMinX() * 341873128712L)
                ^ ((long) box.getMinY() * 132897987541L)
                ^ ((long) box.getMinZ() * 42317861L);
    }

    private long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // ------------------------------------------------------------------ finding a room for it

    /** Looks through the parts of the chamber that lie in this chunk for a flat, open 3x3 spot. */
    private Location findSpot(Chunk chunk, GeneratedStructure structure, Random random) {
        World world = chunk.getWorld();
        int chunkMinX = chunk.getX() << 4;
        int chunkMinZ = chunk.getZ() << 4;
        List<Location> spots = new ArrayList<>();

        for (StructurePiece piece : structure.getPieces()) {
            BoundingBox pb = piece.getBoundingBox();
            // Stay one block away from the chunk edge so the 3x3 never leaves the chunk.
            int minX = Math.max(chunkMinX + 1, (int) Math.floor(pb.getMinX()));
            int maxX = Math.min(chunkMinX + 14, (int) Math.floor(pb.getMaxX()));
            int minZ = Math.max(chunkMinZ + 1, (int) Math.floor(pb.getMinZ()));
            int maxZ = Math.min(chunkMinZ + 14, (int) Math.floor(pb.getMaxZ()));
            int minY = Math.max(world.getMinHeight() + 1, (int) Math.floor(pb.getMinY()));
            int maxY = Math.min(world.getMaxHeight() - 5, (int) Math.floor(pb.getMaxY()));

            for (int y = minY; y <= maxY && spots.size() < 64; y++) {
                for (int x = minX; x <= maxX && spots.size() < 64; x++) {
                    for (int z = minZ; z <= maxZ && spots.size() < 64; z++) {
                        if (fits(world, x, y, z)) spots.add(new Location(world, x, y, z));
                    }
                }
            }
            if (spots.size() >= 64) break;
        }
        return spots.isEmpty() ? null : spots.get(random.nextInt(spots.size()));
    }

    /** Solid, plain floor under a 3x3 patch, and four blocks of air above it. */
    private boolean fits(World world, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Material floor = world.getBlockAt(x + dx, y - 1, z + dz).getType();
                if (!floor.isSolid() || !floor.isOccluding()) return false;
                if (floor == Material.TRIAL_SPAWNER || floor == Material.VAULT || floor == Material.SPAWNER) return false;
                for (int dy = 0; dy < 4; dy++) {
                    if (!world.getBlockAt(x + dx, y + dy, z + dz).getType().isAir()) return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ building the altar

    /** base = the block layer the altar stands on (the first air block above the floor). */
    public void spawnAltar(Location base) {
        World world = base.getWorld();
        int x = base.getBlockX();
        int y = base.getBlockY();
        int z = base.getBlockZ();

        Material corner = Material.matchMaterial("WAXED_OXIDIZED_CHISELED_COPPER");
        if (corner == null) corner = Material.TUFF_BRICKS;

        // The pedestal: a 3x3 base with a chiseled block in the middle.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Material type;
                if (dx == 0 && dz == 0) {
                    type = Material.CHISELED_TUFF_BRICKS;
                } else if (dx != 0 && dz != 0) {
                    type = corner;
                } else {
                    type = Material.TUFF_BRICK_SLAB;
                }
                world.getBlockAt(x + dx, y, z + dz).setType(type, false);
            }
        }

        // The arch: a post on each corner and a square beam on top, open in the middle like a well.
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                world.getBlockAt(x + sx, y + 1, z + sz).setType(Material.TUFF_BRICK_WALL, true);
                world.getBlockAt(x + sx, y + 2, z + sz).setType(Material.TUFF_BRICK_WALL, true);
                world.getBlockAt(x + sx, y + 3, z + sz).setType(Material.TUFF_BRICKS, false);
            }
        }
        world.getBlockAt(x + 1, y + 3, z).setType(Material.TUFF_BRICKS, false);
        world.getBlockAt(x - 1, y + 3, z).setType(Material.TUFF_BRICKS, false);
        world.getBlockAt(x, y + 3, z + 1).setType(Material.TUFF_BRICKS, false);
        world.getBlockAt(x, y + 3, z - 1).setType(Material.TUFF_BRICKS, false);

        // The sword floats a little above the middle block (its top is at y + 1), inside the arch.
        Location swordLocation = new Location(world, x + 0.5, y + 1.9, z + 0.5);

        ItemDisplay display = world.spawn(swordLocation, ItemDisplay.class, d -> {
            d.setItemStack(sword.create());
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(1.5f);
            d.setTransformation(transform(0f, 0f));
            d.setInterpolationDuration(SPIN_PERIOD_TICKS);
            d.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        });
        applyGlow(display);

        // An invisible click box around the sword, since display entities can't be clicked themselves.
        world.spawn(new Location(world, x + 0.5, y + 1.0, z + 0.5), Interaction.class, i -> {
            i.setInteractionWidth(1.6f);
            i.setInteractionHeight(2.2f);
            i.setResponsive(true);
            PersistentDataContainer pdc = i.getPersistentDataContainer();
            pdc.set(markerKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(linkKey, PersistentDataType.STRING, display.getUniqueId().toString());
        });

        displays.add(display.getUniqueId());
        world.playSound(swordLocation, Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.6f);
    }

    /** Makes sure an altar display shows the sword (altars from older versions held a key). */
    private void track(ItemDisplay display) {
        if (!sword.isSword(display.getItemStack())) display.setItemStack(sword.create());
        applyGlow(display);
        displays.add(display.getUniqueId());
    }

    private void applyGlow(ItemDisplay display) {
        if (config.getBoolean(Category.ALTAR, "glow")) {
            display.setGlowing(true);
            display.setGlowColorOverride(Color.fromRGB(0xFF2020));
        } else {
            display.setGlowing(false);
        }
    }

    /** Upright blade (tilted 45 degrees in its own plane), spun around the vertical axis. */
    private Transformation transform(float angle, float bob) {
        return new Transformation(
                new Vector3f(0f, bob, 0f),
                new AxisAngle4f(angle, 0f, 1f, 0f),
                new Vector3f(SWORD_SCALE, SWORD_SCALE, SWORD_SCALE),
                new AxisAngle4f((float) Math.toRadians(45), 0f, 0f, 1f));
    }

    // ------------------------------------------------------------------ the spinning

    /**
     * Every half second the key is turned a little further, and the client smooths the movement out
     * (like the plate in a microwave). A gentle bob and some particles go with it.
     */
    private void spin() {
        tickCount++;
        double seconds = Math.max(1.0, config.getDouble(Category.ALTAR, "spin-seconds"));
        float step = (float) (TWO_PI * (SPIN_PERIOD_TICKS / 20.0) / seconds);
        Iterator<UUID> iterator = displays.iterator();
        while (iterator.hasNext()) {
            Entity entity = Bukkit.getEntity(iterator.next());
            if (!(entity instanceof ItemDisplay display) || !display.isValid()) {
                iterator.remove();
                continue;
            }

            float angle = (angles.getOrDefault(display.getUniqueId(), 0f) + step) % TWO_PI;
            angles.put(display.getUniqueId(), angle);
            float bob = (float) (0.07 * Math.sin(angle));

            display.setInterpolationDelay(0);
            display.setInterpolationDuration(SPIN_PERIOD_TICKS);
            display.setTransformation(transform(angle, bob));
            applyGlow(display);

            Location at = display.getLocation();
            // Red effects: blood chips and crimson spores swirling around the blade, and a slow drip below.
            at.getWorld().spawnParticle(Particle.BLOCK, at, 3, 0.35, 0.5, 0.35, 0.05, AltarSword.bloodData());
            at.getWorld().spawnParticle(Particle.CRIMSON_SPORE, at, 4, 0.4, 0.6, 0.4, 0);
            if (tickCount % 3 == 0) {
                at.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, at, 1, 0.3, 0.4, 0.3, 0.05);
            }
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemDisplay
                    && entity.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) {
                track((ItemDisplay) entity);
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) displays.remove(entity.getUniqueId());
    }

    // ------------------------------------------------------------------ taking the key

    @EventHandler
    public void onClaim(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Interaction box) || !box.isValid()) return;

        PersistentDataContainer pdc = box.getPersistentDataContainer();
        if (!pdc.has(markerKey, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        Location at = box.getLocation().add(0, 1.0, 0);

        String link = pdc.get(linkKey, PersistentDataType.STRING);
        box.remove(); // removed first, so a doubled click event can't hand out two swords
        if (link != null) {
            try {
                Entity display = Bukkit.getEntity(UUID.fromString(link));
                if (display != null) {
                    displays.remove(display.getUniqueId());
                    angles.remove(display.getUniqueId());
                    display.remove();
                }
            } catch (IllegalArgumentException ignored) {
                // bad link, nothing to remove
            }
        }

        for (ItemStack left : player.getInventory().addItem(sword.create()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }

        World world = player.getWorld();
        world.playSound(at, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7f, 1.4f);
        world.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.7f);
        world.spawnParticle(Particle.EXPLOSION, at, 1);
        world.spawnParticle(Particle.BLOCK, at, 80, 0.5, 0.8, 0.5, 0.2, AltarSword.bloodData());
        world.spawnParticle(Particle.DAMAGE_INDICATOR, at, 20, 0.4, 0.6, 0.4, 0.1);
        player.sendMessage(Component.text("You took ", NamedTextColor.GRAY)
                .append(Component.text("The Altar's Sword", TextColor.color(0xFF3B2F)))
                .append(Component.text(". Everyone will see you coming.", NamedTextColor.GRAY)));
    }
}
