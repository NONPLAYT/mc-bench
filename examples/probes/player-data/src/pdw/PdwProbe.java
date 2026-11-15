package pdw;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class PdwProbe extends JavaPlugin {
    private int counter;

    private static boolean rawContains(UUID id, String mark) {
        File f = new File(Bukkit.getWorldContainer(), "world/players/data/" + id + ".dat");
        try (GZIPInputStream in = new GZIPInputStream(new FileInputStream(f))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.ISO_8859_1).contains(mark);
        } catch (Exception e) {
            return false;
        }
    }

    private static String name(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasCustomName()) return "<none>";
        return PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().customName());
    }

    private ItemStack marked(String mark) {
        ItemStack item = new ItemStack(Material.DIAMOND);
        item.editMeta(m -> m.customName(Component.text(mark)));
        return item;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String result;
        try {
            result = switch (args[0]) {
                case "dat" -> this.dat(Integer.parseInt(args[1]));
                case "adv" -> this.adv();
                case "quit" -> this.quit();
                case "phys" -> this.phys(args[1]);
                default -> "unknown";
            };
        } catch (Throwable t) {
            result = "probe failed: " + t;
            getLogger().log(java.util.logging.Level.SEVERE, "probe failed", t);
        }
        getLogger().info("PDW " + result);
        sender.sendMessage("PDW " + result);
        return true;
    }

    private final java.util.concurrent.atomic.AtomicLong physics = new java.util.concurrent.atomic.AtomicLong();
    private org.bukkit.event.Listener physListener;

    private String phys(String action) {
        switch (action) {
            case "on" -> {
                this.physListener = new org.bukkit.event.Listener() {
                    @org.bukkit.event.EventHandler
                    public void on(org.bukkit.event.block.BlockPhysicsEvent e) { PdwProbe.this.physics.incrementAndGet(); }
                };
                Bukkit.getPluginManager().registerEvents(this.physListener, this);
            }
            case "off" -> org.bukkit.event.HandlerList.unregisterAll(this.physListener);
            default -> { }
        }
        return "phys " + action + " count=" + this.physics.get();
    }

    private String dat(int n) {
        int ok = 0, bad = 0, rawStale = 0;
        for (int i = 0; i < n; i++) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                String mark = String.format("pdw%06dx", ++this.counter);
                p.getInventory().setItem(0, this.marked(mark));
                Bukkit.savePlayers();
                if (!rawContains(p.getUniqueId(), mark)) rawStale++;
                p.getInventory().clear();
                p.loadData();
                if (mark.equals(name(p.getInventory().getItem(0)))) ok++; else bad++;
            }
        }
        return "dat ok=" + ok + " bad=" + bad + " rawStaleImmediatelyAfterSave=" + rawStale;
    }

    private String adv() {
        String[] keys = {"story/mine_stone", "story/upgrade_tools", "story/smelt_iron"};
        int ok = 0, bad = 0;
        for (String key : keys) {
            Advancement adv = Bukkit.getAdvancement(NamespacedKey.minecraft(key));
            for (Player p : Bukkit.getOnlinePlayers()) {
                for (String c : adv.getCriteria()) p.getAdvancementProgress(adv).awardCriteria(c);
            }
            Bukkit.savePlayers();
            Bukkit.reloadData();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getAdvancementProgress(adv).isDone()) ok++; else bad++;
            }
        }
        return "adv ok=" + ok + " bad=" + bad;
    }

    private String quit() {
        NamespacedKey key = new NamespacedKey(this, "mark");
        List<UUID> ids = new ArrayList<>();
        List<Integer> marks = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int m = ++this.counter;
            p.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, m);
            p.setStatistic(Statistic.JUMP, m);
            p.getInventory().setItem(0, this.marked(String.format("pdw%06dx", m)));
            ids.add(p.getUniqueId());
            marks.add(m);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ta fake clear");
        int rawStale = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (!rawContains(ids.get(i), String.format("pdw%06dx", marks.get(i)))) rawStale++;
        }
        int ok = 0, bad = 0;
        for (int i = 0; i < ids.size(); i++) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(ids.get(i));
            Integer pdc = off.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
            int jumps = off.getStatistic(Statistic.JUMP);
            if (marks.get(i).equals(pdc) && jumps == marks.get(i)) ok++; else { bad++; getLogger().warning("quit mismatch " + ids.get(i) + " pdc=" + pdc + " jumps=" + jumps + " want " + marks.get(i)); }
        }
        return "quit ok=" + ok + " bad=" + bad + " rawStaleImmediatelyAfterQuit=" + rawStale;
    }
}
