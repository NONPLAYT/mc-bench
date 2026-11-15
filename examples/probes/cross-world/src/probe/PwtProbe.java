package probe;

import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.block.data.type.Hopper;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.ArmorStand;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class PwtProbe extends JavaPlugin implements Listener {
    private NamespacedKey key;
    private volatile String mode = "none";
    private final Map<String, AtomicLong> results = new ConcurrentHashMap<>();
    private final AtomicLong atomicCounter = new AtomicLong();
    private long plainCounter;
    private final Map<Integer, Long> lastAction = new ConcurrentHashMap<>();
    private volatile int everyTicks = 5;

    private static java.lang.reflect.Field staticIgnore;
    private static java.lang.invoke.MethodHandle threadState;
    private static java.lang.reflect.Field threadIgnore;
    private static boolean shared;
    private int cmdStartTick;
    private final java.util.Set<java.util.UUID> watchers = ConcurrentHashMap.newKeySet();
    private final Map<java.util.UUID, World> pearlExpected = new ConcurrentHashMap<>();
    private final Map<World, Location> pads = new ConcurrentHashMap<>();
    private org.bukkit.scheduler.BukkitTask pearlTask;
    private org.bukkit.scheduler.BukkitTask cooldownTask;
    private org.bukkit.scheduler.BukkitTask tntTask;
    private volatile boolean pingPong = true;

    private static String threadKind() {
        String n = Thread.currentThread().getName();
        if (n.equals("Server thread")) return "server";
        if (n.contains("Tick Worker")) return "level";
        return n;
    }

    @EventHandler
    public void onPortal(org.bukkit.event.entity.EntityPortalEvent event) {
        this.record("portal from " + event.getFrom().getWorld().getName() + " on " + threadKind());
    }

    @EventHandler
    public void onPearlTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (event.getCause() == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            this.record("pearl tp " + (event.getFrom().getWorld() == event.getTo().getWorld() ? "same" : "cross") + " on " + threadKind());
        }
    }

    @EventHandler
    public void onRemoveCause(org.bukkit.event.entity.EntityRemoveEvent event) {
        if (this.tagged(event.getEntity())) this.record("remove cause " + event.getCause());
    }

    @EventHandler
    public void onTeleportEntity(org.bukkit.event.entity.EntityTeleportEvent event) {
        if (this.tagged(event.getEntity()) && event.getTo() != null && event.getFrom().getWorld() != event.getTo().getWorld()) this.record("cross " + event.getFrom().getWorld().getName() + " on " + threadKind());
    }

    @EventHandler
    public void onChangedWorld(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        net.minecraft.server.level.ServerPlayer h = ((org.bukkit.craftbukkit.entity.CraftPlayer) event.getPlayer()).getHandle();
        if (h.containerMenu != h.inventoryMenu) this.record("changed world with menu open");
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        this.record("menu closed " + event.getReason() + " on " + threadKind());
    }

    private final AtomicLong workerLoads = new AtomicLong();
    private volatile int workerLoadTick;
    private final java.util.concurrent.atomic.AtomicInteger workerLoadsThisTick = new java.util.concurrent.atomic.AtomicInteger();

    @EventHandler
    public void onEntityMove(io.papermc.paper.event.entity.EntityMoveEvent event) {
        if (!(this.mode.equals("workerload") || this.mode.equals("workertickets") || this.mode.startsWith("workerforeign")) || !Thread.currentThread().getName().startsWith("Region Ticking")) return;
        int tick = Bukkit.getCurrentTick();
        if (tick != this.workerLoadTick) { this.workerLoadTick = tick; this.workerLoadsThisTick.set(0); }
        if (this.workerLoadsThisTick.incrementAndGet() > 4) return;
        World w = event.getEntity().getWorld();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        if (this.mode.startsWith("workerforeign")) {
            World target = other(w);
            Location at = target.getSpawnLocation();
            try {
                switch (this.mode) {
                    case "workerforeign-read" -> target.getBlockAt(at.getBlockX() + r.nextInt(-8, 9), 64, at.getBlockZ() + r.nextInt(-8, 9)).getType();
                    case "workerforeign-set" -> target.getBlockAt(at.getBlockX(), 250, at.getBlockZ()).setType(r.nextBoolean() ? Material.STONE : Material.AIR);
                    case "workerforeign-load" -> target.getChunkAt((at.getBlockX() >> 4) + r.nextInt(30, 60), (at.getBlockZ() >> 4) + r.nextInt(-30, 30)).getBlock(0, 64, 0).getType();
                    default -> { return; }
                }
                this.record(this.mode + " ok");
            } catch (Throwable t) {
                this.record(this.mode + " " + t.getClass().getSimpleName() + (String.valueOf(t.getMessage()).contains("no level can resolve") ? " (unresolvable wait)" : ""));
            }
            return;
        }
        if (this.mode.equals("workertickets")) {
            int ex = event.getTo().getBlockX() >> 4, ez = event.getTo().getBlockZ() >> 4;
            int tx = ex + (r.nextBoolean() ? 1 : -1) * r.nextInt(4, 8), tz = ez + r.nextInt(-7, 8);
            try {
                w.setChunkForceLoaded(tx, tz, !w.isChunkForceLoaded(tx, tz));
                this.record("workertickets ok");
            } catch (Throwable t) {
                this.record("workertickets " + t.getClass().getSimpleName());
            }
            return;
        }
        int cx = (event.getTo().getBlockX() >> 4) + r.nextInt(-40, 41), cz = (event.getTo().getBlockZ() >> 4) + r.nextInt(-40, 41);
        if (w.isChunkLoaded(cx, cz)) return;
        try {
            w.getChunkAt(cx, cz).getBlock(0, 64, 0).getType();
            this.record("workerload ok");
        } catch (Throwable t) {
            this.record("workerload " + t.getClass().getSimpleName());
        }
    }

    private boolean tagged(Entity e) {
        if (e instanceof org.bukkit.entity.Item item) {
            return item.getItemStack().hasItemMeta() && item.getItemStack().getItemMeta().getPersistentDataContainer().has(this.key);
        }
        return e.getPersistentDataContainer().has(this.key);
    }

    private static void buildPortal(World w, int bx, int by, int bz) {
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) w.setChunkForceLoaded((bx >> 4) + cx, (bz >> 4) + cz, true);
        for (int x = bx - 1; x <= bx + 2; x++) for (int y = by - 1; y <= by + 3; y++) {
            boolean frame = x == bx - 1 || x == bx + 2 || y == by - 1 || y == by + 3;
            if (frame) {
                w.getBlockAt(x, y, bz).setType(Material.OBSIDIAN, false);
            } else {
                org.bukkit.block.data.Orientable portal = (org.bukkit.block.data.Orientable) Material.NETHER_PORTAL.createBlockData();
                portal.setAxis(org.bukkit.Axis.X);
                w.getBlockAt(x, y, bz).setBlockData(portal, false);
            }
            w.getBlockAt(x, y, bz - 1).setType(Material.GLASS, false);
            w.getBlockAt(x, y, bz + 1).setType(Material.GLASS, false);
        }
        for (int x = bx - 1; x <= bx + 4; x++) w.getBlockAt(x, by - 1, bz + 3).setType(Material.STONE, false);
    }

    private static boolean ignoringUpdates() {
        try {
            if (shared) return threadIgnore.getBoolean(threadState.invoke());
            return staticIgnore.getBoolean(null);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public void onEnable() {
        try {
            staticIgnore = net.minecraft.world.level.block.entity.BlockEntity.class.getDeclaredField("ignoreBlockEntityUpdates");
            staticIgnore.setAccessible(true);
            try {
                Class<?> ts = Class.forName("org.bxteam.divinemc.async.ThreadState");
                threadState = java.lang.invoke.MethodHandles.publicLookup().findStatic(ts, "current", java.lang.invoke.MethodType.methodType(ts));
                threadIgnore = ts.getField("ignoreBlockEntityUpdates");
                shared = Class.forName("org.bxteam.divinemc.async.LevelThreads").getField("SHARED").getBoolean(null);
            } catch (ClassNotFoundException e) {
                shared = false;
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        this.key = new NamespacedKey(this, "probe");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private void record(String what) {
        this.results.computeIfAbsent(what, k -> new AtomicLong()).incrementAndGet();
    }

    private static World other(World w) {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.get((worlds.indexOf(w) + 1) % worlds.size());
    }

    private static Location anchor(World w) {
        return w.getSpawnLocation();
    }

    @EventHandler
    public void onHopper(InventoryMoveItemEvent event) {
        if (event.getSource().getLocation() == null) return;
        World here = event.getSource().getLocation().getWorld();
        if (event.getSource().getLocation().getBlockY() != 200) return;
        String m = this.mode;
        if (m.equals("none")) return;
        if (m.equals("leak")) {
            int seen = 0;
            for (int i = 0; i < 20000; i++) {
                if (ignoringUpdates()) seen++;
            }
            this.record("leak reads");
            if (seen > 0) this.record("leak seen-true");
            return;
        }
        if (m.equals("counter")) {
            for (int i = 0; i < 100000; i++) {
                this.plainCounter++;
                this.atomicCounter.incrementAndGet();
            }
            this.record("counter events " + Thread.currentThread().getName().split(" ")[0]);
            return;
        }
        Entity e = null;
        for (ArmorStand a : here.getEntitiesByClass(ArmorStand.class)) {
            if (a.getPersistentDataContainer().has(this.key, PersistentDataType.BYTE)) { e = a; break; }
        }
        int tick = Bukkit.getCurrentTick();
        Long last = this.lastAction.get(here.hashCode());
        if (last != null && tick - last < this.everyTicks) return;
        this.lastAction.put(here.hashCode(), (long) tick);
        World target = other(here);
        if (m.equals("cyclic")) {
            if (here != Bukkit.getWorlds().get(0)) return;
            target = Bukkit.getWorlds().get(1);
        }
        Location at = anchor(target);
        try {
            switch (m) {
                case "readblock", "cyclic" -> target.getBlockAt(at.getBlockX(), 64, at.getBlockZ()).getType();
                case "setblock" -> target.getBlockAt(at.getBlockX(), 250, at.getBlockZ()).setType(tick % 2 == 0 ? Material.STONE : Material.AIR);
                case "loadchunk" -> target.getChunkAt((at.getBlockX() >> 4) + 40 + (tick % 7), (at.getBlockZ() >> 4) + 40).getBlock(0, 64, 0).getType();
                case "teleport" -> { if (e == null) return; e.teleport(new Location(target, at.getBlockX() + 0.5, 203, at.getBlockZ() + 0.5)); }
                case "tpplayer" -> {
                    List<org.bukkit.entity.Player> players = here.getPlayers();
                    if (players.isEmpty()) return;
                    if (!players.get(0).teleport(new Location(target, at.getBlockX() + 0.5, 203, at.getBlockZ() + 0.5))) { this.record("tpplayer refused"); return; }
                }
                default -> { return; }
            }
            this.record(m + " ok");
        } catch (Throwable t) {
            this.record(m + " " + t.getClass().getSimpleName() + (String.valueOf(t.getMessage()).contains("no level can resolve") ? " (unresolvable wait)" : ""));
            if (String.valueOf(t.getMessage()).contains("no level can resolve")) this.results.putIfAbsent("first: " + t.getMessage(), new AtomicLong(1));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;
        switch (args[0]) {
            case "arm" -> {
                int n = 0;
                for (World w : Bukkit.getWorlds()) {
                    Location spawn = w.getSpawnLocation();
                    int bx = spawn.getBlockX(), bz = spawn.getBlockZ(), by = 200;
                    w.setChunkForceLoaded(bx >> 4, bz >> 4, true);
                    for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -3; dz <= 3; dz++) {
                        boolean shell = Math.abs(dx) == 3 || Math.abs(dy) == 3 || Math.abs(dz) == 3;
                        w.getBlockAt(bx + dx, by + dy, bz + dz).setType(shell ? Material.BARRIER : Material.AIR, false);
                    }
                    org.bukkit.block.Block h1 = w.getBlockAt(bx, by, bz), h2 = w.getBlockAt(bx + 1, by, bz);
                    h1.setType(Material.HOPPER, false); h2.setType(Material.HOPPER, false);
                    Hopper d1 = (Hopper) h1.getBlockData(); d1.setFacing(BlockFace.EAST); h1.setBlockData(d1, false);
                    Hopper d2 = (Hopper) h2.getBlockData(); d2.setFacing(BlockFace.WEST); h2.setBlockData(d2, false);
                    ((org.bukkit.block.Container) h1.getState(false)).getInventory().addItem(new ItemStack(Material.STONE, 1));
                    Entity bat = w.spawn(new Location(w, bx + 0.5, by + 2, bz + 0.5), ArmorStand.class, b -> {
                        b.setPersistent(true);
                        b.setGravity(false);
                        b.getPersistentDataContainer().set(this.key, PersistentDataType.BYTE, (byte) 1);
                    });
                    n += bat != null ? 1 : 0;
                }
                sender.sendMessage("armed " + n);
            }
            case "mode" -> {
                this.mode = args[1];
                if (args.length > 2) this.everyTicks = Integer.parseInt(args[2]);
                sender.sendMessage("mode " + this.mode + " every " + this.everyTicks);
            }
            case "crash" -> {
                net.minecraft.server.level.ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) Bukkit.getWorld(args[1])).getHandle();
                level.scheduledEvents.schedule("pwtprobe:crash", level.getGameTime() + 20, new net.minecraft.world.level.timers.TimerCallback<net.minecraft.server.MinecraftServer>() {
                    @Override
                    public void handle(net.minecraft.server.MinecraftServer server, net.minecraft.world.level.timers.TimerQueue<net.minecraft.server.MinecraftServer> queue, long time) {
                        throw new IllegalStateException("pwtprobe injected failure on " + Thread.currentThread().getName());
                    }

                    @Override
                    public com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.timers.TimerCallback<net.minecraft.server.MinecraftServer>> codec() {
                        return null;
                    }
                });
                sender.sendMessage("crash scheduled in " + args[1]);
            }
            case "flood" -> {
                int pairs = Integer.parseInt(args[1]);
                for (World w : Bukkit.getWorlds()) {
                    Location spawn = w.getSpawnLocation();
                    int bx = (spawn.getBlockX() >> 4 << 4) + 32, bz = (spawn.getBlockZ() >> 4 << 4);
                    for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) w.setChunkForceLoaded((bx >> 4) + cx, (bz >> 4) + cz, true);
                    int placed = 0;
                    for (int y = 230; y < 250 && placed < pairs; y++) for (int x = 0; x < 64 && placed < pairs; x += 2) for (int z = 0; z < 64 && placed < pairs; z++) {
                        org.bukkit.block.Block a = w.getBlockAt(bx + x, y, bz + z), b = w.getBlockAt(bx + x + 1, y, bz + z);
                        a.setType(Material.HOPPER, false); b.setType(Material.HOPPER, false);
                        Hopper da = (Hopper) a.getBlockData(); da.setFacing(BlockFace.EAST); a.setBlockData(da, false);
                        Hopper db = (Hopper) b.getBlockData(); db.setFacing(BlockFace.WEST); b.setBlockData(db, false);
                        ((org.bukkit.block.Container) a.getState(false)).getInventory().addItem(new ItemStack(Material.STONE, 5));
                        placed++;
                    }
                }
                sender.sendMessage("flooded " + pairs + " pairs per world");
            }
            case "cmdblocks" -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard objectives add pwt dummy");
                for (World w : Bukkit.getWorlds()) {
                    Location spawn = w.getSpawnLocation();
                    org.bukkit.block.Block b = w.getBlockAt(spawn.getBlockX() + 2, 205, spawn.getBlockZ() + 2);
                    w.setChunkForceLoaded(b.getX() >> 4, b.getZ() >> 4, true);
                    b.setType(Material.REPEATING_COMMAND_BLOCK, false);
                    org.bukkit.block.CommandBlock cb = (org.bukkit.block.CommandBlock) b.getState();
                    cb.setCommand("scoreboard players add pp pwt 1");
                    cb.update(true, false);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "execute in " + w.getKey() + " run data merge block " + b.getX() + " " + b.getY() + " " + b.getZ() + " {auto:1b}");
                }
                sender.sendMessage("command blocks placed");
            }
            case "cmdreset" -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players set pp pwt 0");
                this.cmdStartTick = Bukkit.getCurrentTick();
                sender.sendMessage("reset at " + this.cmdStartTick);
            }
            case "cmdstats" -> {
                int ticks = Bukkit.getCurrentTick() - this.cmdStartTick;
                int score = Bukkit.getScoreboardManager().getMainScoreboard().getObjective("pwt").getScore("pp").getScore();
                sender.sendMessage("score " + score + " ticks " + ticks + " expected about " + (ticks * Bukkit.getWorlds().size()) + " lost " + (ticks * Bukkit.getWorlds().size() - score));
            }
            case "portals" -> {
                int items = Integer.parseInt(args[1]), pigs = Integer.parseInt(args[2]);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamerule max_entity_cramming 0");
                World over = Bukkit.getWorlds().get(0), nether = Bukkit.getWorlds().get(1);
                Location ns = nether.getSpawnLocation();
                int nx = ns.getBlockX() + 6, nz = ns.getBlockZ(), y = 100;
                buildPortal(nether, nx, y, nz);
                buildPortal(over, nx * 8, y, nz * 8);
                for (World w : List.of(over, nether)) {
                    int bx = w == over ? nx * 8 : nx, bz = w == over ? nz * 8 : nz;
                    for (org.bukkit.entity.Player p : w.getPlayers()) {
                        this.watchers.add(p.getUniqueId());
                        p.teleport(new Location(w, bx + 3.5, y, bz + 3.5));
                        this.pads.put(w, new Location(w, bx + 1, y - 1, bz + 3));
                        break;
                    }
                }
                World end = Bukkit.getWorlds().get(2);
                for (org.bukkit.entity.Player p : end.getPlayers()) {
                    this.watchers.add(p.getUniqueId());
                    Location at = end.getSpawnLocation().clone().add(0, 40, 0);
                    end.setChunkForceLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4, true);
                    for (int x = -1; x <= 4; x++) end.getBlockAt(at.getBlockX() + x, at.getBlockY() - 1, at.getBlockZ()).setType(Material.STONE, false);
                    p.teleport(at.clone().add(0.5, 0, 0.5));
                    this.pads.put(end, new Location(end, at.getBlockX() + 3, at.getBlockY() - 1, at.getBlockZ()));
                    break;
                }
                Location inside = new Location(over, nx * 8 + 1.0, y, nz * 8 + 0.5);
                for (int i = 0; i < items; i++) {
                    ItemStack stack = new ItemStack(Material.COBBLESTONE, 1);
                    stack.editMeta(m -> m.getPersistentDataContainer().set(this.key, PersistentDataType.BYTE, (byte) 1));
                    org.bukkit.entity.Item item = over.dropItem(inside, stack);
                    item.setUnlimitedLifetime(true);
                    item.setPickupDelay(32767);
                    item.setCanMobPickup(false);
                    item.setVelocity(new org.bukkit.util.Vector());
                }
                for (int i = 0; i < pigs; i++) {
                    over.spawn(inside, org.bukkit.entity.Pig.class, pig -> {
                        pig.setPersistent(true);
                        pig.setRemoveWhenFarAway(false);
                        pig.setAI(false);
                        pig.getPersistentDataContainer().set(this.key, PersistentDataType.BYTE, (byte) 1);
                    });
                }
                if (this.cooldownTask != null) this.cooldownTask.cancel();
                this.cooldownTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                    if (!this.pingPong) return;
                    for (World w : List.of(over, nether)) for (Entity e : w.getEntities()) {
                        if (e.getPortalCooldown() > 0 && this.tagged(e)) e.setPortalCooldown(0);
                    }
                }, 10L, 10L);
                sender.sendMessage("portals built: overworld " + (nx * 8) + "," + y + "," + (nz * 8) + " nether " + nx + "," + y + "," + nz + " watchers " + this.watchers.size() + " pads " + this.pads.size());
            }
            case "asyncread" -> {
                int seconds = Integer.parseInt(args[1]);
                long end = System.currentTimeMillis() + seconds * 1000L;
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    while (System.currentTimeMillis() < end) {
                        for (World w : Bukkit.getWorlds()) {
                            Location sp = w.getSpawnLocation();
                            try {
                                for (int i = 0; i < 64; i++) {
                                    org.bukkit.block.Block b = w.getBlockAt(sp.getBlockX() + (i & 7), 60 + (i >> 3), sp.getBlockZ());
                                    b.getType(); b.getBlockData();
                                }
                                this.record("asyncread ok");
                            } catch (Throwable t) {
                                this.record("asyncread " + t.getClass().getSimpleName());
                            }
                        }
                    }
                });
                sender.sendMessage("asyncread started");
            }
            case "tnt" -> {
                if (this.tntTask != null) { this.tntTask.cancel(); this.tntTask = null; }
                if (args[1].equals("on")) {
                    World nether = Bukkit.getWorlds().get(1);
                    Location ns = nether.getSpawnLocation();
                    int bx = ns.getBlockX() + 16, by = 100, bz = ns.getBlockZ() + 14;
                    nether.setChunkForceLoaded(bx >> 4, bz >> 4, true);
                    this.tntTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                            nether.getBlockAt(bx + dx, by + dy, bz + dz).setType(Material.STONE, false);
                        }
                        nether.spawn(new Location(nether, bx + 0.5, by + 2, bz + 0.5), org.bukkit.entity.TNTPrimed.class, tnt -> tnt.setFuseTicks(1));
                    }, 1L, 1L);
                }
                sender.sendMessage("tnt " + args[1]);
            }
            case "pingpong" -> {
                this.pingPong = args[1].equals("on");
                sender.sendMessage("pingpong " + this.pingPong);
            }
            case "where" -> {
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) {
                    if (!this.tagged(e) || n++ >= 6) continue;
                    Location l = e.getLocation();
                    sb.append(w.getName()).append(' ').append(e.getType()).append(String.format(" %.2f,%.2f,%.2f", l.getX(), l.getY(), l.getZ()))
                      .append(" in=").append(l.getBlock().getType()).append(" cd=").append(e.getPortalCooldown()).append("; ");
                }
                sender.sendMessage(sb.toString());
            }
            case "pcount" -> {
                StringBuilder sb = new StringBuilder();
                long totalItems = 0, totalPigs = 0;
                for (World w : Bukkit.getWorlds()) {
                    long items = 0, pigs = 0;
                    for (Entity e : w.getEntities()) {
                        if (!this.tagged(e)) continue;
                        if (e instanceof org.bukkit.entity.Item item) items += item.getItemStack().getAmount();
                        else if (e instanceof org.bukkit.entity.Pig) pigs++;
                    }
                    totalItems += items; totalPigs += pigs;
                    sb.append(w.getName()).append(" items=").append(items).append(" pigs=").append(pigs).append("; ");
                }
                sb.append("total items=").append(totalItems).append(" pigs=").append(totalPigs);
                sender.sendMessage(sb.toString());
            }
            case "pearls" -> {
                if (this.pearlTask != null) { this.pearlTask.cancel(); this.pearlTask = null; }
                if (args[1].equals("on")) {
                    this.pearlExpected.clear();
                    this.pearlTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                        List<World> worlds = Bukkit.getWorlds();
                        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                            if (this.watchers.contains(p.getUniqueId())) continue;
                            World expected = this.pearlExpected.get(p.getUniqueId());
                            if (expected != null) this.record(p.getWorld() == expected ? "pearl landed" : "pearl missed");
                            World target = worlds.get((worlds.indexOf(p.getWorld()) + 1) % worlds.size());
                            Location pad = this.pads.get(target);
                            if (pad == null) continue;
                            Location at = pad.clone().add(0.5, 1.3, 0.5);
                            target.spawn(at, org.bukkit.entity.EnderPearl.class, pearl -> {
                                pearl.setShooter(p);
                                pearl.setVelocity(new org.bukkit.util.Vector(0, -0.5, 0));
                            });
                            this.pearlExpected.put(p.getUniqueId(), target);
                        }
                    }, 20L, 20L);
                }
                sender.sendMessage("pearls " + args[1]);
            }
            case "menu" -> {
                int opened = 0;
                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                    if (this.watchers.contains(p.getUniqueId())) continue;
                    org.bukkit.block.Block b = p.getLocation().getBlock().getRelative(BlockFace.NORTH);
                    b.setType(Material.CHEST, false);
                    net.minecraft.server.level.ServerPlayer handle = ((org.bukkit.craftbukkit.entity.CraftPlayer) p).getHandle();
                    net.minecraft.world.level.block.entity.BlockEntity be = ((org.bukkit.craftbukkit.CraftWorld) p.getWorld()).getHandle().getBlockEntity(new net.minecraft.core.BlockPos(b.getX(), b.getY(), b.getZ()));
                    handle.openMenu((net.minecraft.world.MenuProvider) be);
                    opened += handle.containerMenu != handle.inventoryMenu ? 1 : 0;
                }
                sender.sendMessage("menus opened " + opened);
            }
            case "reset" -> {
                this.results.clear();
                this.plainCounter = 0;
                this.atomicCounter.set(0);
                sender.sendMessage("reset");
            }
            case "stats" -> {
                StringBuilder sb = new StringBuilder();
                new TreeMap<>(this.results).forEach((k, v) -> sb.append(k).append('=').append(v.get()).append("; "));
                sb.append("counter plain=").append(this.plainCounter).append(" atomic=").append(this.atomicCounter.get());
                sb.append("; players per world:");
                for (World w : Bukkit.getWorlds()) sb.append(' ').append(w.getName()).append('=').append(w.getPlayers().size());
                sb.append("; stands per world:");
                for (World w : Bukkit.getWorlds()) {
                    long c = w.getEntitiesByClass(ArmorStand.class).stream().filter(b -> b.getPersistentDataContainer().has(this.key)).count();
                    sb.append(' ').append(w.getName()).append('=').append(c);
                }
                sender.sendMessage(sb.toString());
            }
            default -> { return false; }
        }
        return true;
    }
}
