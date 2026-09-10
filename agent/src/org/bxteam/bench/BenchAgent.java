package org.bxteam.bench;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
public final class BenchAgent extends JavaPlugin implements Listener {

    private int[] tickNo = new int[0];
    private long[] durNs = new long[0];
    private int count;
    private int dropped;

    private final Queue<String> marks = new ConcurrentLinkedQueue<>();
    private final Queue<String> heap = new ConcurrentLinkedQueue<>();

    private Path outDir;
    private long enableEpochMs;
    private boolean dumped;

    @Override
    public void onEnable() {
        final String configured = System.getProperty("bench.out");
        this.outDir = Paths.get(configured != null ? configured : getDataFolder().getAbsolutePath());
        try {
            Files.createDirectories(this.outDir);
        } catch (IOException e) {
            getLogger().severe("cannot create output dir: " + e);
        }

        final int capacity = Integer.getInteger("bench.capacity", 20 * 60 * 120);
        this.tickNo = new int[capacity];
        this.durNs = new long[capacity];
        this.enableEpochMs = System.currentTimeMillis();

        getServer().getPluginManager().registerEvents(this, this);

        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            final Runtime rt = Runtime.getRuntime();
            this.heap.add(System.currentTimeMillis() + "," + (rt.totalMemory() - rt.freeMemory()) + "," + rt.totalMemory());
        }, 20L, 20L);

        getLogger().info("armed, capacity=" + capacity + " ticks, out=" + this.outDir);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(final ServerTickEndEvent event) {
        final int c = this.count;
        if (c >= this.tickNo.length) {
            this.dropped++;
            return;
        }
        this.tickNo[c] = event.getTickNumber();
        this.durNs[c] = (long) (event.getTickDuration() * 1_000_000.0D);
        this.count = c + 1;
    }

    @EventHandler
    public void onLoad(final ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) return;
        mark("server_ready");
        try {
            Files.writeString(this.outDir.resolve("ready"), Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("cannot write ready marker: " + e);
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 0) return false;
        switch (args[0]) {
            case "mark" -> {
                if (args.length < 2) return false;
                mark(args[1]);
                sender.sendMessage("[bench] mark " + args[1] + " @tick " + currentTick());
            }
            case "reset" -> {
                this.count = 0;
                this.dropped = 0;
                this.heap.clear();
                mark("reset");
                sender.sendMessage("[bench] buffer reset");
            }
            case "dump" -> {
                dump();
                sender.sendMessage("[bench] dumped " + this.count + " ticks");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void mark(final String label) {
        this.marks.add(currentTick() + "," + System.currentTimeMillis() + "," + label);
    }

    private int currentTick() {
        try {
            return Bukkit.getCurrentTick();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private synchronized void dump() {
        this.dumped = true;
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
            Files.write(this.outDir.resolve("marks.csv"),
                (java.util.stream.Stream.concat(java.util.stream.Stream.of("tick,epoch_ms,label"), this.marks.stream())
                    .reduce((a, b) -> a + "\n" + b).orElse("") + "\n").getBytes(StandardCharsets.UTF_8));
            Files.write(this.outDir.resolve("heap.csv"),
                (java.util.stream.Stream.concat(java.util.stream.Stream.of("epoch_ms,used_bytes,total_bytes"), this.heap.stream())
                    .reduce((a, b) -> a + "\n" + b).orElse("") + "\n").getBytes(StandardCharsets.UTF_8));
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

    private static String json(final String s) {
        return s == null ? "null" : '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    @Override
    public void onDisable() {
        if (!this.dumped) dump();
    }
}
