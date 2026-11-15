package org.bxteam.testagent;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Players that exist only inside the server. They load and generate chunks, get tracked, keep mobs
 * spawning around them and receive chunk packets (serialized and dropped), without a client process
 * eating the CPU the server is being measured on.
 */
final class FakePlayers {
    private static final double TWO_PI = Math.PI * 2.0;

    private final List<Fake> fakes = new ArrayList<>();
    private int nextId;

    private static final class Fake {
        final ServerPlayer player;
        final FakeConnection connection;
        final SplittableRandom random;
        final double homeX;
        final double homeZ;
        final double radius;
        final double speed;
        final double y;
        double targetX;
        double targetZ;

        Fake(ServerPlayer player, FakeConnection connection, SplittableRandom random, double homeX, double homeZ, double radius, double speed, double y) {
            this.player = player;
            this.connection = connection;
            this.random = random;
            this.homeX = homeX;
            this.homeZ = homeZ;
            this.radius = radius;
            this.speed = speed;
            this.y = y;
            this.targetX = homeX;
            this.targetZ = homeZ;
        }
    }

    /**
     * Options: layout=center|grid|cluster, spacing (grid), clusters and separation (cluster: cluster centres on a circle
     * of radius separation), radius (wander radius around each home), speed (blocks per tick, 0.2 walks, 1.0+ forces fresh
     * chunks), y (flight height), seed, world, origin=x,z (layout centre, default the world spawn),
     * targetable (default true: mobs target them, resistance V keeps them alive; false: invulnerable and ignored by mobs).
     */
    String spawn(int count, Map<String, String> options) {
        final MinecraftServer server = ((CraftServer) org.bukkit.Bukkit.getServer()).getServer();
        final World world = org.bukkit.Bukkit.getWorld(options.getOrDefault("world", org.bukkit.Bukkit.getWorlds().getFirst().getName()));
        if (world == null) {
            return "unknown world";
        }
        final ServerLevel level = ((CraftWorld) world).getHandle();
        final String layout = options.getOrDefault("layout", "grid");
        final double spacing = doubleOption(options, "spacing", 160.0);
        final int clusters = (int) doubleOption(options, "clusters", 8.0);
        final double separation = doubleOption(options, "separation", 768.0);
        final double radius = doubleOption(options, "radius", 32.0);
        final double speed = doubleOption(options, "speed", 0.2);
        final double y = doubleOption(options, "y", 140.0);
        final long seed = (long) doubleOption(options, "seed", 1.0);
        final boolean targetable = Boolean.parseBoolean(options.getOrDefault("targetable", "true"));
        final String origin = options.get("origin");
        final double originX;
        final double originZ;
        if (origin != null) {
            final String[] xz = origin.split(",");
            originX = Double.parseDouble(xz[0]);
            originZ = Double.parseDouble(xz[1]);
        } else {
            originX = world.getSpawnLocation().getX();
            originZ = world.getSpawnLocation().getZ();
        }
        final int side = (int) Math.ceil(Math.sqrt(count));

        for (int i = 0; i < count; i++) {
            final double homeX;
            final double homeZ;
            switch (layout) {
                case "center" -> {
                    homeX = originX;
                    homeZ = originZ;
                }
                case "cluster" -> {
                    final double angle = TWO_PI * (i % clusters) / clusters;
                    homeX = originX + Math.cos(angle) * separation;
                    homeZ = originZ + Math.sin(angle) * separation;
                }
                case "grid" -> {
                    homeX = originX + (i % side - side / 2) * spacing;
                    homeZ = originZ + (i / side - side / 2) * spacing;
                }
                default -> {
                    return "unknown layout " + layout + " (center|grid|cluster)";
                }
            }

            final int id = this.nextId++;
            final String name = "fake" + id;
            final GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
            final ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
            final FakeConnection connection = new FakeConnection();
            connection.player = player;
            server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());

            if (player.level() != level) {
                player.teleportTo(level, homeX, y, homeZ, java.util.Set.of(), 0.0F, 0.0F, false, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            player.setGameMode(GameType.SURVIVAL);
            if (targetable) {
                // invulnerability would also hide them from mob targeting (canBeSeenAsEnemy); resistance V blocks the damage instead
                player.getBukkitEntity().addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE,
                    org.bukkit.potion.PotionEffect.INFINITE_DURATION, 4, false, false));
                player.getBukkitEntity().addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SATURATION,
                    org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false));
            } else {
                player.getBukkitEntity().setInvulnerable(true);
            }
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            move(player, homeX, y, homeZ);

            this.fakes.add(new Fake(player, connection, new SplittableRandom(seed * 31L + id), homeX, homeZ, radius, speed, y));
        }
        return "spawned " + count + " fake players (" + layout + "), total " + this.fakes.size();
    }

    void tick() {
        for (int i = this.fakes.size() - 1; i >= 0; i--) {
            final Fake fake = this.fakes.get(i);
            if (fake.player.isRemoved() && !fake.connection.isConnected()) {
                this.fakes.remove(i);
                continue;
            }
            step(fake);
            fake.connection.deliverReplies(fake.player.connection);
            fake.connection.tick();
        }
    }

    private static void step(Fake fake) {
        if (fake.speed <= 0.0) {
            return; // stationary; distance 0 / speed 0 would move the player to NaN
        }
        final ServerPlayer player = fake.player;
        double dx = fake.targetX - player.getX();
        double dz = fake.targetZ - player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < fake.speed) {
            final double angle = fake.random.nextDouble() * TWO_PI;
            final double reach = Math.sqrt(fake.random.nextDouble()) * fake.radius;
            fake.targetX = fake.homeX + Math.cos(angle) * reach;
            fake.targetZ = fake.homeZ + Math.sin(angle) * reach;
            return;
        }
        final double scale = fake.speed / distance;
        move(player, player.getX() + dx * scale, fake.y, player.getZ() + dz * scale);
    }

    // same pair the spectator camera path uses: set the position, then let the chunk map follow
    private static void move(ServerPlayer player, double x, double y, double z) {
        player.absSnapTo(x, y, z, player.getYRot(), player.getXRot());
        player.level().getChunkSource().move(player);
    }

    String clear() {
        int removed = 0;
        for (Fake fake : this.fakes) {
            fake.connection.disconnect(Component.literal("test agent: removed"));
            fake.connection.handleDisconnection();
            removed++;
        }
        this.fakes.clear();
        return "removed " + removed + " fake players";
    }

    String status() {
        long dropped = 0;
        for (Fake fake : this.fakes) {
            dropped += fake.connection.packetsDropped;
        }
        return this.fakes.size() + " fake players, " + dropped + " packets dropped";
    }

    List<ServerPlayer> players() {
        final List<ServerPlayer> players = new ArrayList<>(this.fakes.size());
        for (Fake fake : this.fakes) {
            players.add(fake.player);
        }
        return players;
    }

    int size() {
        return this.fakes.size();
    }

    static double doubleOption(Map<String, String> options, String key, double fallback) {
        final String value = options.get(key);
        return value == null ? fallback : Double.parseDouble(value);
    }
}
