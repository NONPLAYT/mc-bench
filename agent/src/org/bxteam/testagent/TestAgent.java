package org.bxteam.testagent;

import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class TestAgent extends JavaPlugin implements Listener {
    private int[] tickNo = new int[0];
    private long[] durNs = new long[0];
    private int count;
    private int dropped;
    private final Queue<String> marks = new ConcurrentLinkedQueue<>();
    private final Queue<String> heap = new ConcurrentLinkedQueue<>();
    private final FakePlayers fakes = new FakePlayers();
    private final TickScript script = new TickScript(this);
    private Path outDir;
    private long enableEpochMs;
    private final Object spawnLock = new Object();
    private final Map<String, long[]> spawnCounts = new TreeMap<>(); // type -> {pre, preCancelled, spawned}
    private int spawnFromTick = -1;
    private final java.util.concurrent.atomic.LongAdder chunksNew = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder chunksLoaded = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder chunksUnloaded = new java.util.concurrent.atomic.LongAdder();
    private volatile long chunksFromMs = -1;
    private int chunksFromTick;
    private final long[] mobCountSum = new long[net.minecraft.world.entity.MobCategory.values().length];
    private final long[] backoffSum = new long[net.minecraft.world.entity.MobCategory.values().length];
    private long mobCountSamples;
    private long spawnChunkSum, tickingChunkSum, chunkSamples;
    private long distinctSum, staleSum, farSum;
    private java.lang.reflect.Method collectSpawningChunks;

    @Override
    public void onEnable() {
        final String configured = System.getProperty("testagent.out");
        this.outDir = configured != null ? Path.of(configured) : getDataFolder().toPath();
        try {
            Files.createDirectories(this.outDir);
        } catch (IOException e) {
            getLogger().severe("cannot create output dir: " + e);
        }
        final int capacity = Integer.getInteger("testagent.capacity", 20 * 60 * 120);
        this.tickNo = new int[capacity];
        this.durNs = new long[capacity];
        this.enableEpochMs = System.currentTimeMillis();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this.fakes::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            final Runtime rt = Runtime.getRuntime();
            this.heap.add(System.currentTimeMillis() + "," + (rt.totalMemory() - rt.freeMemory()) + "," + rt.totalMemory());
        }, 20L, 20L);
        getLogger().info("armed, capacity=" + capacity + " ticks, out=" + this.outDir);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        final int c = this.count;
        if (c >= this.tickNo.length) {
            this.dropped++;
            return;
        }
        this.tickNo[c] = event.getTickNumber();
        this.durNs[c] = (long) (event.getTickDuration() * 1_000_000.0D);
        this.count = c + 1;
        if (this.spawnFromTick >= 0) {
            // per-player caps as the spawner sees them: counted at the start of the tick plus this tick's spawns
            for (ServerPlayer player : this.fakes.players()) {
                for (int i = 0; i < this.mobCountSum.length; i++) {
                    this.mobCountSum[i] += player.mobCounts[i];
                    this.backoffSum[i] += player.mobBackoffCounts[i];
                }
                this.mobCountSamples++;
            }
            if (event.getTickNumber() % 20 == 0) {
                sampleSpawningChunks();
            }
        }
    }

    // counted only between "spawns reset" and "spawns"; listeners only exist on the agent, so every server pays the same
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreSpawn(PreCreatureSpawnEvent event) {
        if (this.spawnFromTick < 0 || event.getReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        synchronized (this.spawnLock) {
            final long[] c = this.spawnCounts.computeIfAbsent(event.getType().name(), k -> new long[3]);
            c[event.isCancelled() ? 1 : 0]++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (this.spawnFromTick < 0 || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        synchronized (this.spawnLock) {
            this.spawnCounts.computeIfAbsent(event.getEntityType().name(), k -> new long[3])[2]++;
        }
    }

    // reflective: the method is public on Paper and package-private on some forks
    private void sampleSpawningChunks() {
        try {
            for (World world : Bukkit.getWorlds()) {
                final net.minecraft.server.level.ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
                if (this.collectSpawningChunks == null) {
                    this.collectSpawningChunks = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("collectSpawningChunks", java.util.List.class);
                    this.collectSpawningChunks.setAccessible(true);
                }
                final java.util.List<Object> out = new java.util.ArrayList<>();
                this.collectSpawningChunks.invoke(level.getChunkSource().chunkMap, out);
                this.spawnChunkSum += out.size();
                final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.world.level.chunk.LevelChunk> ticking =
                    ((ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickServerLevel) level).moonrise$getPlayerTickingChunks();
                this.tickingChunkSum += ticking.size();
                final it.unimi.dsi.fastutil.longs.LongOpenHashSet distinct = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                for (net.minecraft.world.level.chunk.LevelChunk chunk : ticking) {
                    final net.minecraft.world.level.ChunkPos pos = chunk.getPos();
                    distinct.add(pos.pack());
                    if (level.getChunkSource().getChunkNow(pos.x(), pos.z()) != chunk) {
                        this.staleSum++;
                    }
                    boolean near = false;
                    for (ServerPlayer player : level.players()) {
                        if (Math.abs(player.chunkPosition().x() - pos.x()) <= 8 && Math.abs(player.chunkPosition().z() - pos.z()) <= 8) {
                            near = true;
                            break;
                        }
                    }
                    if (!near) {
                        this.farSum++;
                    }
                }
                this.distinctSum += distinct.size();
            }
            this.chunkSamples++;
        } catch (ReflectiveOperationException e) {
            getLogger().warning("cannot sample spawning chunks: " + e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        if (this.chunksFromMs >= 0) {
            (event.isNewChunk() ? this.chunksNew : this.chunksLoaded).increment();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(org.bukkit.event.world.ChunkUnloadEvent event) {
        if (this.chunksFromMs >= 0) {
            this.chunksUnloaded.increment();
        }
    }

    private String chunks(String[] args) {
        if (args.length > 1 && args[1].equals("reset")) {
            this.chunksNew.reset();
            this.chunksLoaded.reset();
            this.chunksUnloaded.reset();
            this.chunksFromTick = Bukkit.getCurrentTick();
            this.chunksFromMs = System.currentTimeMillis();
            return "chunk counters reset";
        }
        if (this.chunksFromMs < 0) {
            return "chunk counters are off; run: chunks reset";
        }
        final double seconds = Math.max(1e-3, (System.currentTimeMillis() - this.chunksFromMs) / 1000.0);
        final long generated = this.chunksNew.sum(), loaded = this.chunksLoaded.sum(), unloaded = this.chunksUnloaded.sum();
        int loadedNow = 0;
        for (World world : Bukkit.getWorlds()) {
            loadedNow += world.getLoadedChunks().length;
        }
        final String summary = String.format(Locale.ROOT, "chunks over %.1f s (%d ticks): generated %d (%.1f/s), loaded from disk %d (%.1f/s), unloaded %d (%.1f/s), loaded now %d",
            seconds, Bukkit.getCurrentTick() - this.chunksFromTick, generated, generated / seconds, loaded, loaded / seconds, unloaded, unloaded / seconds, loadedNow);
        try {
            Files.writeString(this.outDir.resolve("chunks.txt"), summary + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning("cannot write chunks.txt: " + e);
        }
        return summary;
    }

    private String spawns(String[] args) {
        synchronized (this.spawnLock) {
            if (args.length > 1 && args[1].equals("reset")) {
                this.spawnCounts.clear();
                Arrays.fill(this.mobCountSum, 0);
                Arrays.fill(this.backoffSum, 0);
                this.mobCountSamples = 0;
                this.spawnChunkSum = this.tickingChunkSum = this.chunkSamples = 0;
                this.distinctSum = this.staleSum = this.farSum = 0;
                this.spawnFromTick = Bukkit.getCurrentTick();
                return "spawn counters reset @tick " + this.spawnFromTick;
            }
            if (this.spawnFromTick < 0) {
                return "spawn counters are off; run: spawns reset";
            }
            final int ticks = Math.max(1, Bukkit.getCurrentTick() - this.spawnFromTick);
            long pre = 0, cancelled = 0, spawned = 0;
            final StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, long[]> e : this.spawnCounts.entrySet()) {
                final long[] c = e.getValue();
                pre += c[0];
                cancelled += c[1];
                spawned += c[2];
                sb.append(String.format(Locale.ROOT, "%n  %-24s pre %8d  cancelled %6d  spawned %7d", e.getKey(), c[0], c[1], c[2]));
            }
            final String summary = String.format(Locale.ROOT, "natural spawning over %d ticks: pre-spawn %d (%.2f/tick), cancelled %d, spawned %d (%.2f/tick)",
                ticks, pre, pre / (double) ticks, cancelled, spawned, spawned / (double) ticks);
            if (this.chunkSamples > 0) {
                sb.append(String.format(Locale.ROOT, "%n  chunks (mean of %d samples, all worlds): spawning %.1f  player-ticking %.1f",
                    this.chunkSamples, this.spawnChunkSum / (double) this.chunkSamples, this.tickingChunkSum / (double) this.chunkSamples));
                sb.append(String.format(Locale.ROOT, "%n  player-ticking list: distinct positions %.1f  stale objects %.1f  no player within 8 chunks %.1f",
                    this.distinctSum / (double) this.chunkSamples, this.staleSum / (double) this.chunkSamples, this.farSum / (double) this.chunkSamples));
            }
            if (this.mobCountSamples > 0) {
                sb.append(String.format(Locale.ROOT, "%n  per-player mob counts (mean over %d player-ticks):", this.mobCountSamples));
                for (net.minecraft.world.entity.MobCategory category : net.minecraft.world.entity.MobCategory.values()) {
                    final int i = category.ordinal();
                    sb.append(String.format(Locale.ROOT, "%n  %-28s count %7.2f  backoff %6.2f  cap %d", category.getName(),
                        this.mobCountSum[i] / (double) this.mobCountSamples, this.backoffSum[i] / (double) this.mobCountSamples, category.getMaxInstancesPerChunk()));
                }
            }
            try {
                Files.writeString(this.outDir.resolve("spawns.txt"), summary + sb + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                getLogger().warning("cannot write spawns.txt: " + e);
            }
            return summary + sb;
        }
    }

    @EventHandler
    public void onLoad(ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) {
            return;
        }
        mark("server_ready");
        try {
            Files.writeString(this.outDir.resolve("ready"), Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("cannot write ready marker: " + e);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        final String reply;
        try {
            reply = switch (args[0]) {
                case "fake" -> fakeCommand(args);
                case "mobs" -> mobs(args);
                case "spawns" -> spawns(args);
                case "chunks" -> chunks(args);
                case "script" -> script(args);
                case "mark" -> {
                    if (args.length < 2) {
                        yield "usage: mark <label>";
                    }
                    mark(args[1]);
                    yield "mark " + args[1] + " @tick " + Bukkit.getCurrentTick();
                }
                case "reset" -> {
                    this.count = 0;
                    this.dropped = 0;
                    this.heap.clear();
                    mark("reset");
                    yield "tick buffer reset";
                }
                case "stats" -> stats(args.length > 1 ? Integer.parseInt(args[1]) : 30);
                case "dump" -> {
                    dump();
                    yield "dumped " + this.count + " ticks to " + this.outDir;
                }
                default -> null;
            };
        } catch (RuntimeException e) {
            sender.sendMessage("[testagent] error: " + e);
            getLogger().warning("command failed: " + String.join(" ", args) + ": " + e);
            return true;
        }
        if (reply == null) {
            return false;
        }
        sender.sendMessage("[testagent] " + reply);
        getLogger().info(reply);
        return true;
    }

    private String fakeCommand(String[] args) {
        if (args.length < 2) {
            return "usage: fake spawn <count> [key=value...] | fake clear | fake status";
        }
        return switch (args[1]) {
            case "spawn" -> this.fakes.spawn(Integer.parseInt(args[2]), options(args, 3));
            case "clear" -> this.fakes.clear();
            case "status" -> this.fakes.status();
            default -> "unknown fake subcommand " + args[1];
        };
    }

    // mobs <entity_type> <per_player> [spread=16] [seed=1]: spawned at ground level around every fake player
    private String mobs(String[] args) {
        if (args.length < 3) {
            return "usage: mobs <entity_type> <per_player> [spread=16] [seed=1]";
        }
        final EntityType type = EntityType.valueOf(args[1].toUpperCase(Locale.ROOT));
        final int perPlayer = Integer.parseInt(args[2]);
        final Map<String, String> options = options(args, 3);
        final double spread = FakePlayers.doubleOption(options, "spread", 16.0);
        final SplittableRandom random = new SplittableRandom((long) FakePlayers.doubleOption(options, "seed", 1.0));
        int spawned = 0;
        for (ServerPlayer player : this.fakes.players()) {
            final World world = player.level().getWorld();
            for (int i = 0; i < perPlayer; i++) {
                final int x = (int) Math.floor(player.getX() + (random.nextDouble() * 2.0 - 1.0) * spread);
                final int z = (int) Math.floor(player.getZ() + (random.nextDouble() * 2.0 - 1.0) * spread);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue; // a sync chunk load here would distort what is being measured
                }
                world.spawnEntity(new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5), type);
                spawned++;
            }
        }
        return "spawned " + spawned + " " + type + " around " + this.fakes.size() + " fake players";
    }

    // script run <file> <ticks> [box=x0,y0,z0,x1,y1,z1] [out=<trace file>]; file paths are relative to the server dir
    private String script(String[] args) {
        if (args.length >= 2 && args[1].equals("status")) {
            return this.script.status();
        }
        if (args.length < 4 || !args[1].equals("run")) {
            return "usage: script run <file> <ticks> [box=x0,y0,z0,x1,y1,z1] [out=<trace file>] | script status";
        }
        final Path file = Path.of(args[2]);
        final Map<String, String> options = options(args, 4);
        final String[] b = options.getOrDefault("box", "0,90,0,47,110,47").split(",");
        final int[] box = new int[6];
        for (int i = 0; i < 6; i++) {
            box[i] = Integer.parseInt(b[i]);
        }
        final String name = file.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        final Path trace = options.containsKey("out") ? Path.of(options.get("out")) : this.outDir.resolve("trace-" + name + ".txt");
        try {
            return this.script.run(file, trace, Integer.parseInt(args[3]), box);
        } catch (IOException e) {
            return "cannot read script: " + e;
        }
    }

    private String stats(int seconds) {
        final int n = Math.min(this.count, seconds * 20);
        if (n == 0) {
            return "no ticks recorded";
        }
        final long[] window = Arrays.copyOfRange(this.durNs, this.count - n, this.count);
        Arrays.sort(window);
        double sum = 0;
        for (long v : window) {
            sum += v;
        }
        return String.format(Locale.ROOT, "last %d ticks: mean %.2f ms, p50 %.2f, p95 %.2f, p99 %.2f, max %.2f; players %d (fake %d), entities %d",
            n, sum / n / 1e6, pct(window, 0.50), pct(window, 0.95), pct(window, 0.99), window[n - 1] / 1e6,
            Bukkit.getOnlinePlayers().size(), this.fakes.size(), Bukkit.getWorlds().stream().mapToInt(w -> w.getEntityCount()).sum());
    }

    private static double pct(long[] sorted, double q) {
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(q * (sorted.length - 1)))] / 1e6;
    }

    private static Map<String, String> options(String[] args, int from) {
        final Map<String, String> options = new HashMap<>();
        for (int i = from; i < args.length; i++) {
            final int eq = args[i].indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("expected key=value, got " + args[i]);
            }
            options.put(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        return options;
    }

    /** Fake players spawned by the agent, for probe plugins that depend on TestAgent. */
    public List<org.bukkit.entity.Player> fakePlayers() {
        final List<org.bukkit.entity.Player> players = new ArrayList<>();
        for (ServerPlayer player : this.fakes.players()) {
            players.add(player.getBukkitEntity());
        }
        return players;
    }

    /** Same as {@code ta fake spawn <count> key=value...}; must be called on the server thread. */
    public String spawnFakes(int count, Map<String, String> options) {
        return this.fakes.spawn(count, options);
    }

    /** Labels the current tick, as {@code ta mark}; the label shows up in marks.csv. */
    public void mark(String label) {
        this.marks.add(Bukkit.getCurrentTick() + "," + System.currentTimeMillis() + "," + label);
    }

    private synchronized void dump() {
        try {
            try (Writer w = Files.newBufferedWriter(this.outDir.resolve("ticks.csv"), StandardCharsets.UTF_8)) {
                w.write("tick,duration_ns\n");
                final StringBuilder sb = new StringBuilder(1 << 16);
                for (int i = 0; i < this.count; i++) {
                    sb.append(this.tickNo[i]).append(',').append(this.durNs[i]).append('\n');
                    if (sb.length() > 1 << 15) {
                        w.write(sb.toString());
                        sb.setLength(0);
                    }
                }
                w.write(sb.toString());
            }
            Files.writeString(this.outDir.resolve("marks.csv"), "tick,epoch_ms,label\n" + String.join("\n", this.marks) + "\n", StandardCharsets.UTF_8);
            Files.writeString(this.outDir.resolve("heap.csv"), "epoch_ms,used_bytes,total_bytes\n" + String.join("\n", this.heap) + "\n", StandardCharsets.UTF_8);
            Files.writeString(this.outDir.resolve("agent-meta.json"), meta(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("dump failed: " + e);
        }
    }

    private String meta() {
        final Runtime rt = Runtime.getRuntime();
        return "{\n"
            + "  \"serverName\": " + json(Bukkit.getName()) + ",\n"
            + "  \"serverVersion\": " + json(Bukkit.getVersion()) + ",\n"
            + "  \"bukkitVersion\": " + json(Bukkit.getBukkitVersion()) + ",\n"
            + "  \"javaVersion\": " + json(System.getProperty("java.version")) + ",\n"
            + "  \"javaVendor\": " + json(System.getProperty("java.vendor")) + ",\n"
            + "  \"availableProcessors\": " + rt.availableProcessors() + ",\n"
            + "  \"maxMemoryBytes\": " + rt.maxMemory() + ",\n"
            + "  \"enableEpochMs\": " + this.enableEpochMs + ",\n"
            + "  \"ticksRecorded\": " + this.count + ",\n"
            + "  \"ticksDropped\": " + this.dropped + "\n"
            + "}\n";
    }

    private static String json(String s) {
        return s == null ? "null" : '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    @Override
    public void onDisable() {
        this.fakes.clear();
        dump();
    }
}
