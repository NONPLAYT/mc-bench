package probe;

import io.papermc.paper.event.entity.EntityMoveEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

// Region workers request sync chunk loads from an event handler; ChunkLoadEvent records which threads run the
// chunk system's main-thread tasks and whether two of them do so at the same time.
public final class SyncLoadProbe extends JavaPlugin implements Listener {
    private volatile int perTick;
    private final AtomicInteger budget = new AtomicInteger();
    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicInteger inHandler = new AtomicInteger();
    private final AtomicInteger maxInHandler = new AtomicInteger();
    private final AtomicLong overlaps = new AtomicLong();
    private final Map<String, AtomicLong> loadEventsByThread = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requestsByThread = new ConcurrentHashMap<>();
    private final Map<String, Boolean> failures = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> this.budget.set(this.perTick), 1L, 1L);
    }

    private static String kind(final Thread thread) {
        final String name = thread.getName();
        if (name.startsWith("Region Ticking")) {
            return "region-worker";
        }
        return name.replaceAll("[0-9]+$", "#");
    }

    @EventHandler
    public void onMove(final EntityMoveEvent event) {
        if (this.perTick == 0 || !kind(Thread.currentThread()).equals("region-worker")) {
            return;
        }
        if (this.budget.getAndDecrement() <= 0) {
            return;
        }
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final int chunkX = (event.getTo().getBlockX() >> 4) + random.nextInt(-40, 41);
        final int chunkZ = (event.getTo().getBlockZ() >> 4) + random.nextInt(-40, 41);
        if (event.getTo().getWorld().isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        this.requested.incrementAndGet();
        this.requestsByThread.computeIfAbsent(kind(Thread.currentThread()), k -> new AtomicLong()).incrementAndGet();
        try {
            event.getTo().getWorld().getChunkAt(chunkX, chunkZ);
            this.completed.incrementAndGet();
        } catch (final Throwable throwable) {
            this.failed.incrementAndGet();
            if (this.failures.putIfAbsent(String.valueOf(throwable), Boolean.TRUE) == null) {
                getLogger().log(java.util.logging.Level.WARNING, "sync load failed", throwable);
            }
        }
    }

    private final Map<String, AtomicLong> moveEvents = new ConcurrentHashMap<>();

    // a protection plugin: every hopper transfer in the overworld is cancelled; the item is never looked at
    @EventHandler
    public void onHopperMove(final org.bukkit.event.inventory.InventoryMoveItemEvent event) {
        final org.bukkit.Location location = event.getSource().getLocation();
        final String world = location == null ? "?" : location.getWorld().getName();
        this.moveEvents.computeIfAbsent(world, k -> new AtomicLong()).incrementAndGet();
        if (PROTECT && world.equals("world")) {
            event.setCancelled(true);
        }
    }

    private final AtomicLong prepares = new AtomicLong();

    @EventHandler
    public void onPrepare(final io.papermc.paper.event.inventory.PrepareHopperTransferEvent event) {
        this.prepares.incrementAndGet();
    }

    private String dump(final boolean full) {
        final java.util.TreeMap<String, String> lines = new java.util.TreeMap<>();
        final org.bukkit.World world = Bukkit.getWorlds().get(0);
        for (final org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            for (final org.bukkit.block.BlockState state : chunk.getTileEntities(false)) {
                if (!(state instanceof org.bukkit.block.Container container)) {
                    continue;
                }
                final StringBuilder sb = new StringBuilder();
                final org.bukkit.inventory.ItemStack[] contents = container.getInventory().getContents();
                for (int i = 0; i < contents.length; i++) {
                    if (contents[i] != null) {
                        sb.append(i).append(':').append(contents[i].getType()).append('x').append(contents[i].getAmount()).append(' ');
                    }
                }
                if (state instanceof org.bukkit.block.Furnace furnace) {
                    sb.append("burn=").append(furnace.getBurnTime()).append(" cook=").append(furnace.getCookTime());
                }
                if (state instanceof org.bukkit.block.BrewingStand stand) {
                    sb.append("brew=").append(stand.getBrewingTime()).append(" fuel=").append(stand.getFuelLevel());
                }
                lines.put(state.getType() + "@" + state.getX() + "," + state.getY() + "," + state.getZ(), sb.toString());
            }
        }
        long items = 0;
        for (final org.bukkit.entity.Entity entity : world.getEntities()) {
            if (entity instanceof org.bukkit.entity.Item item) {
                items += item.getItemStack().getAmount();
            }
        }
        final String text = lines + " drops=" + items;
        if (full) {
            try {
                java.nio.file.Files.writeString(getDataFolder().toPath().resolveSibling("dump-" + System.nanoTime() + ".txt"), String.join("\n", lines.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList()) + "\ndrops=" + items + "\n");
            } catch (final java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
        return "containers " + lines.size() + " drops " + items + " hash " + Integer.toHexString(text.hashCode()) + " tick " + Bukkit.getCurrentTick();
    }

    private static final boolean PROTECT = Boolean.getBoolean("probe.protect");

    private String hoppers() {
        final StringBuilder sb = new StringBuilder("move events " + this.moveEvents + "; items in barrels:");
        for (final org.bukkit.World world : Bukkit.getWorlds()) {
            long items = 0;
            int barrels = 0;
            for (final org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (final org.bukkit.block.BlockState state : chunk.getTileEntities()) {
                    if (state instanceof org.bukkit.block.Barrel barrel) {
                        barrels++;
                        for (final org.bukkit.inventory.ItemStack stack : barrel.getInventory().getContents()) {
                            if (stack != null) {
                                items += stack.getAmount();
                            }
                        }
                    }
                }
            }
            sb.append(' ').append(world.getName()).append('=').append(items).append(" (").append(barrels).append(" barrels)");
        }
        return sb.toString();
    }

    @EventHandler
    public void onChunkLoad(final ChunkLoadEvent event) {
        this.loadEventsByThread.computeIfAbsent(kind(Thread.currentThread()), k -> new AtomicLong()).incrementAndGet();
        final int now = this.inHandler.incrementAndGet();
        try {
            if (now > 1) {
                this.overlaps.incrementAndGet();
            }
            this.maxInHandler.accumulateAndGet(now, Math::max);
            final long until = System.nanoTime() + 20_000L;
            while (System.nanoTime() < until) {
                Thread.onSpinWait();
            }
        } finally {
            this.inHandler.decrementAndGet();
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 0) {
            return false;
        }
        String reply;
        switch (args[0]) {
            case "rate" -> {
                this.perTick = Integer.parseInt(args[1]);
                reply = "rate " + this.perTick + " sync loads per tick";
            }
            case "reset" -> {
                this.requested.set(0);
                this.completed.set(0);
                this.failed.set(0);
                this.overlaps.set(0);
                this.maxInHandler.set(0);
                this.loadEventsByThread.clear();
                this.requestsByThread.clear();
                reply = "reset";
            }
            case "hoppers" -> reply = hoppers();
            case "dump" -> reply = dump(args.length > 1 && args[1].equals("full"));
            case "apiadd" -> {
                // three ways a plugin changes a container: live inventory, mutating a live stack, block state snapshot
                final org.bukkit.World world = Bukkit.getWorlds().get(0);
                final org.bukkit.block.Container live = (org.bukkit.block.Container) world.getBlockAt(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3])).getState(false);
                live.getInventory().addItem(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND, 40));
                final org.bukkit.block.Container second = (org.bukkit.block.Container) world.getBlockAt(Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6])).getState(false);
                second.getInventory().setItem(0, new org.bukkit.inventory.ItemStack(org.bukkit.Material.EMERALD, 1));
                second.getInventory().getItem(0).setAmount(33);
                final org.bukkit.block.Container snapshot = (org.bukkit.block.Container) world.getBlockAt(Integer.parseInt(args[7]), Integer.parseInt(args[8]), Integer.parseInt(args[9])).getState();
                snapshot.getSnapshotInventory().addItem(new org.bukkit.inventory.ItemStack(org.bukkit.Material.GOLD_INGOT, 50));
                snapshot.update(true, false);
                reply = "api changes applied";
            }
            case "unlisten" -> {
                // drop every listener of the spawn events (the test agent counts them) so the no-listener path runs
                com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent.getHandlerList().bake();
                for (final org.bukkit.plugin.RegisteredListener listener : com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent.getHandlerList().getRegisteredListeners()) {
                    com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent.getHandlerList().unregister(listener);
                }
                reply = "pre-spawn listeners left: " + com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent.getHandlerList().getRegisteredListeners().length;
            }
            case "poison" -> {
                // make the block phase of one ticking chunk throw outside any entity tick or event handler
                final org.bukkit.entity.Player player = Bukkit.getOnlinePlayers().iterator().next();
                final org.bukkit.Location at = player.getLocation();
                final net.minecraft.world.level.chunk.LevelChunk chunk = ((org.bukkit.craftbukkit.CraftWorld) at.getWorld()).getHandle()
                    .getChunk((at.getBlockX() >> 4) + 2, (at.getBlockZ() >> 4) + 2);
                java.util.Arrays.fill(chunk.getSections(), null);
                reply = "poisoned chunk " + chunk.getPos();
            }
            case "stats" -> reply = "worker sync loads requested " + this.requested + " completed " + this.completed + " failed " + this.failed
                + "; ChunkLoadEvent by thread " + this.loadEventsByThread
                + "; overlapping ChunkLoadEvent handlers " + this.overlaps + " (max at once " + this.maxInHandler + ")";
            default -> {
                return false;
            }
        }
        sender.sendMessage("[slp] " + reply);
        getLogger().info(reply);
        return true;
    }
}
