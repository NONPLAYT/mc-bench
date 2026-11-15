package org.bxteam.testagent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs console commands at fixed ticks and writes one line of world state per tick. Two runs of the same script
 * that should behave the same (option off vs on, old jar vs new) must produce identical trace files.
 *
 * <p>Script format: {@code @<tick>} starts the commands for that tick (relative to the start), every other
 * non-blank line that does not start with {@code #} is a console command or an {@code api ...} line
 * (plugin-side changes, see {@link #api}).
 */
final class TickScript {
    private static final Material[] TRACED_BLOCKS = {
        Material.COMPARATOR, Material.REDSTONE_WIRE, Material.REDSTONE_LAMP, Material.REPEATER, Material.COMPOSTER,
        Material.HOPPER, Material.PISTON, Material.STICKY_PISTON, Material.OBSERVER, Material.SCULK_SENSOR,
        Material.CALIBRATED_SCULK_SENSOR, Material.SCULK_SHRIEKER,
    };

    private final Plugin plugin;
    private BukkitTask task;

    TickScript(Plugin plugin) {
        this.plugin = plugin;
    }

    /** box = x0,y0,z0,x1,y1,z1 in the first world; chunks in the box must be loaded (forceload) to be traced. */
    String run(Path script, Path trace, int ticks, int[] box) throws IOException {
        if (this.task != null) {
            return "a script is already running";
        }
        final TreeMap<Integer, List<String>> steps = new TreeMap<>();
        int at = 0;
        for (String line : Files.readAllLines(script)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("@")) {
                at = Integer.parseInt(line.substring(1).strip());
                continue;
            }
            steps.computeIfAbsent(at, k -> new ArrayList<>()).add(line);
        }
        Files.createDirectories(trace.toAbsolutePath().getParent());
        final BufferedWriter out = Files.newBufferedWriter(trace);
        final World world = Bukkit.getWorlds().getFirst();
        final int[] tick = {0};
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            try {
                for (String line : steps.getOrDefault(tick[0], List.of())) {
                    if (line.startsWith("api ")) {
                        api(world, line.split(" "));
                    } else {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
                    }
                }
                out.write(tick[0] + " " + snapshot(world, box));
                out.newLine();
                if (++tick[0] >= ticks) {
                    out.close();
                    this.task.cancel();
                    this.task = null;
                    this.plugin.getLogger().info("script done: " + ticks + " ticks traced to " + trace);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, 1L, 1L);
        return "running " + script.getFileName() + " for " + ticks + " ticks, trace " + trace;
    }

    String status() {
        return this.task == null ? "no script running" : "script running";
    }

    // what a plugin does to a block, as opposed to a command: live inventory, live stack, snapshot update, no physics
    private static void api(World world, String[] a) {
        final Block block = world.getBlockAt(Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]));
        switch (a[1]) {
            case "add" -> ((Container) block.getState(false)).getInventory().addItem(new ItemStack(Material.DIAMOND, 40));
            case "amount" -> {
                final org.bukkit.inventory.Inventory inventory = ((Container) block.getState(false)).getInventory();
                inventory.setItem(8, new ItemStack(Material.EMERALD, 1));
                inventory.getItem(8).setAmount(33);
            }
            case "snapshot" -> {
                final Container snapshot = (Container) block.getState();
                snapshot.getSnapshotInventory().addItem(new ItemStack(Material.GOLD_INGOT, 50));
                snapshot.update(true, false);
            }
            case "nophys" -> block.setType(Material.valueOf(a.length > 5 ? a[5].toUpperCase(Locale.ROOT) : "CHEST"), false);
            case "fuel" -> ((Container) block.getState(false)).getInventory().setItem(1, new ItemStack(Material.COAL, 8));
            case "take" -> ((Container) block.getState(false)).getInventory().clear();
            case "vibration" -> {
                final org.bukkit.GameEvent event = org.bukkit.Registry.GAME_EVENT.get(org.bukkit.NamespacedKey.minecraft(a[5]));
                world.sendGameEvent(null, event, new org.bukkit.util.Vector(block.getX() + 0.5, block.getY(), block.getZ() + 0.5));
            }
            case "bloom" -> ((org.bukkit.block.SculkCatalyst) block.getState(false)).bloom(block.getRelative(0, -1, 0), Integer.parseInt(a[5]));
            default -> throw new IllegalArgumentException("unknown api action " + a[1] + " (add|amount|snapshot|nophys|fuel|take|vibration|bloom)");
        }
    }

    private static String snapshot(World world, int[] box) {
        final TreeMap<String, String> lines = new TreeMap<>();
        for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
            for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                for (BlockState state : world.getChunkAt(cx, cz).getTileEntities(false)) {
                    if (!inside(box, state.getX(), state.getY(), state.getZ())) {
                        continue;
                    }
                    final StringBuilder sb = new StringBuilder();
                    if (state instanceof Container container) {
                        sb.append(contents(container.getInventory().getContents()));
                    }
                    if (state instanceof org.bukkit.block.Furnace furnace) {
                        sb.append("burn=").append(furnace.getBurnTime()).append(" cook=").append(furnace.getCookTime());
                    }
                    if (state instanceof org.bukkit.block.BrewingStand stand) {
                        sb.append("brew=").append(stand.getBrewingTime()).append(" fuel=").append(stand.getFuelLevel());
                    }
                    if (state instanceof org.bukkit.block.SculkSensor sensor) {
                        sb.append("freq=").append(sensor.getLastVibrationFrequency());
                    }
                    if (state instanceof org.bukkit.block.SculkShrieker shrieker) {
                        sb.append("warn=").append(shrieker.getWarningLevel());
                    }
                    lines.put(state.getType() + "@" + state.getX() + "," + state.getY() + "," + state.getZ(), sb.toString());
                }
            }
        }
        int sculk = 0;
        for (int x = box[0]; x <= box[3]; x++) {
            for (int z = box[2]; z <= box[5]; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = box[1]; y <= box[4]; y++) {
                    final Block block = world.getBlockAt(x, y, z);
                    final Material type = block.getType();
                    if (type == Material.SCULK || type == Material.SCULK_VEIN) {
                        sculk = sculk * 31 + x * 1000000 + y * 1000 + z;
                    }
                    for (Material traced : TRACED_BLOCKS) {
                        if (type == traced) {
                            lines.put("b@" + x + "," + y + "," + z, block.getBlockData().getAsString(true));
                            break;
                        }
                    }
                }
            }
        }
        lines.put("sculkhash", Integer.toString(sculk));
        int n = 0;
        for (Entity entity : world.getEntities()) {
            final org.bukkit.Location l = entity.getLocation();
            if (!inside(box, l.getBlockX(), l.getBlockY(), l.getBlockZ())) {
                continue;
            }
            final String at = String.format(Locale.ROOT, "%.3f,%.3f,%.3f", l.getX(), l.getY(), l.getZ());
            if (entity instanceof Item item) {
                lines.put("e@" + at + "#" + n++, item.getItemStack().getType() + "x" + item.getItemStack().getAmount());
            } else if (entity instanceof InventoryHolder holder && !(entity instanceof org.bukkit.entity.LivingEntity)) {
                // mobs are left out: their AI wanders differently between two runs of the same build
                lines.put("e@" + at + "#" + n++, entity.getType() + " " + contents(holder.getInventory().getContents()));
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : lines.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(" | ");
        }
        return sb.toString();
    }

    private static boolean inside(int[] box, int x, int y, int z) {
        return x >= box[0] && y >= box[1] && z >= box[2] && x <= box[3] && y <= box[4] && z <= box[5];
    }

    private static String contents(ItemStack[] contents) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && !contents[i].getType().isAir()) {
                sb.append(i).append(':').append(contents[i].getType()).append('x').append(contents[i].getAmount()).append(',');
            }
        }
        return sb.toString();
    }
}
