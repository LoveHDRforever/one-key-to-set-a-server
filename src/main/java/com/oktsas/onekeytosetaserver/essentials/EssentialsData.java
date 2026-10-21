package com.oktsas.onekeytosetaserver.essentials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the Essentials style commands need to remember: the operator config (teleport delay, fees,
 * cooldowns, home limit, main city world), the shared spawn/warps and one record per player (homes, the
 * {@code /back} queue, toggles, ignores, nick, mute, last seen).
 *
 * <p>One file: {@code <server root>/multiverse/essentials.json}. Keeping it in one place makes it easy for
 * the owner to hand-edit and to back up.</p>
 */
public final class EssentialsData {

    private EssentialsData() {
    }

    // ------------------------------------------------------------------ stored shapes

    /** A place in a world - used for homes, warps, the spawn and the {@code /back} queue. */
    public static final class Point {
        public String world = "";
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
        public String label = "";
        public long at;

        public static Point of(ServerPlayer player, String label) {
            Point point = new Point();
            point.world = player.level().dimension().location().toString();
            point.x = player.getX();
            point.y = player.getY();
            point.z = player.getZ();
            point.yaw = player.getYRot();
            point.pitch = player.getXRot();
            point.label = label;
            point.at = System.currentTimeMillis();
            return point;
        }
    }

    /** Per player state. */
    public static final class PlayerData {
        public Map<String, Point> homes = new LinkedHashMap<>();
        public List<Point> backs = new ArrayList<>();
        public boolean tptoggle;
        public boolean afk;
        public boolean god;
        public boolean fly;
        public boolean vanish;
        public boolean socialspy;
        public Set<String> ignore = new LinkedHashSet<>();
        public String nick = "";
        /** Epoch millis until which the player is muted; {@link Long#MAX_VALUE} means forever. */
        public long mutedUntil;
        public String muteReason = "";
        public long lastSeen;
        public long firstJoin;
        public String lastName = "";
    }

    /** The whole file. */
    public static final class Root {
        public Config config = new Config();
        public Point spawn;
        public Map<String, Point> warps = new LinkedHashMap<>();
        /** uuid -> {@code itemId|command} for {@code /powertool}. */
        public Map<String, String> powertools = new LinkedHashMap<>();
        public Map<String, PlayerData> players = new LinkedHashMap<>();
    }

    /** Operator tunables - all of them are documented in the mod manual. */
    public static final class Config {
        public int adminLevel = 3;
        public int teleportDelaySeconds = 3;
        public boolean interruptOnJump = true;
        public boolean interruptOnHurt = true;
        public int tpaTimeoutSeconds = 120;
        public int tpaCooldownSeconds = 30;
        public int homeCooldownSeconds = 5;
        public int warpCooldownSeconds = 5;
        public int rtpCooldownSeconds = 60;
        public int maxHomes = 5;
        public int backQueueSize = 2;
        public boolean teleportFeesEnabled = false;
        public double feeTpa = 10.0D;
        public double feeTpahere = 10.0D;
        public double feeHome = 0.0D;
        public double feeWarp = 20.0D;
        public double feeBack = 0.0D;
        public double feeSpawn = 0.0D;
        public double feeRtp = 50.0D;
        public double feeTppos = 0.0D;
        public int rtpMinRadius = 1000;
        public int rtpMaxRadius = 5000;
        public int rtpAttempts = 30;
        /** Folder name of the main city world: {@code /spawn} always goes there. */
        public String mainWorld = "mainland";
    }

    // ------------------------------------------------------------------ state

    private static Root root = new Root();
    private static Path file;

    public static Config config() {
        return root.config;
    }

    public static PlayerData of(UUID player) {
        if (player == null) {
            return new PlayerData();
        }
        return root.players.computeIfAbsent(player.toString(), ignored -> new PlayerData());
    }

    public static Map<String, Point> warps() {
        return root.warps;
    }

    public static Point warp(String name) {
        return name == null ? null : root.warps.get(name.toLowerCase(Locale.ROOT));
    }

    public static boolean setWarp(String name, Point point) {
        if (name == null || name.isBlank()) {
            return false;
        }
        root.warps.put(name.toLowerCase(Locale.ROOT), point);
        save();
        return true;
    }

    public static boolean removeWarp(String name) {
        boolean removed = name != null && root.warps.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public static Point spawn() {
        return root.spawn;
    }

    public static void setSpawn(Point point) {
        root.spawn = point;
        save();
    }

    /** Pushes "where the player was" onto the bounded {@code /back} queue (newest last). */
    public static void pushBack(UUID player, Point point) {
        PlayerData data = of(player);
        data.backs.add(point);
        int limit = Math.max(1, config().backQueueSize);
        while (data.backs.size() > limit) {
            data.backs.remove(0);
        }
        save();
    }

    /** Takes the most recent entry off the queue, or null when there is none. */
    public static Point popBack(UUID player) {
        PlayerData data = of(player);
        if (data.backs.isEmpty()) {
            return null;
        }
        Point point = data.backs.remove(data.backs.size() - 1);
        save();
        return point;
    }

    // ------------------------------------------------------------------ mute

    public static boolean isMuted(UUID player) {
        if (player == null) {
            return false;
        }
        PlayerData data = root.players.get(player.toString());
        return data != null && (data.mutedUntil == Long.MAX_VALUE || data.mutedUntil > System.currentTimeMillis());
    }

    public static void mute(UUID player, long until, String reason) {
        PlayerData data = of(player);
        data.mutedUntil = until;
        data.muteReason = reason == null ? "" : reason;
        save();
    }

    public static void unmute(UUID player) {
        PlayerData data = of(player);
        data.mutedUntil = 0L;
        data.muteReason = "";
        save();
    }

    /** Human readable remaining mute time, or an empty string. */
    public static String muteRemaining(UUID player) {
        PlayerData data = player == null ? null : root.players.get(player.toString());
        if (data == null || data.mutedUntil == 0L) {
            return "";
        }
        if (data.mutedUntil == Long.MAX_VALUE) {
            return "永久";
        }
        long left = data.mutedUntil - System.currentTimeMillis();
        if (left <= 0L) {
            return "";
        }
        long seconds = left / 1000L;
        if (seconds < 60L) {
            return seconds + " 秒";
        }
        if (seconds < 3600L) {
            return (seconds / 60L) + " 分钟";
        }
        if (seconds < 86400L) {
            return (seconds / 3600L) + " 小时";
        }
        return (seconds / 86400L) + " 天";
    }

    /** Players that are muted right now: name -> remaining, for {@code /mutelist}. */
    public static Map<String, String> mutedPlayers() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, PlayerData> entry : root.players.entrySet()) {
            PlayerData data = entry.getValue();
            if (data.mutedUntil == Long.MAX_VALUE || data.mutedUntil > System.currentTimeMillis()) {
                String name = data.lastName == null || data.lastName.isBlank() ? entry.getKey() : data.lastName;
                result.put(name, (data.mutedUntil == Long.MAX_VALUE ? "永久" : muteRemaining(UUID.fromString(entry.getKey())))
                        + (data.muteReason.isBlank() ? "" : "（" + data.muteReason + "）"));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ powertool

    public static String powertool(UUID player) {
        return player == null ? null : root.powertools.get(player.toString());
    }

    public static void setPowertool(UUID player, String itemId, String command) {
        if (player == null) {
            return;
        }
        if (itemId == null || itemId.isBlank() || command == null || command.isBlank()) {
            root.powertools.remove(player.toString());
        } else {
            root.powertools.put(player.toString(), itemId + "|" + command);
        }
        save();
    }

    // ------------------------------------------------------------------ storage

    public static void loadAll(Path gameDir) {
        file = gameDir.resolve("multiverse").resolve("essentials.json");
        root = new Root();
        try {
            if (Files.isRegularFile(file)) {
                Root loaded = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), Root.class);
                if (loaded != null) {
                    root = loaded;
                    if (root.config == null) {
                        root.config = new Config();
                    }
                    if (root.warps == null) {
                        root.warps = new LinkedHashMap<>();
                    }
                    if (root.powertools == null) {
                        root.powertools = new LinkedHashMap<>();
                    }
                    if (root.players == null) {
                        root.players = new LinkedHashMap<>();
                    }
                }
            }
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Ess] could not read {}", file, t);
        }
        save();
        OneKeyToSetAServer.LOGGER.info("[Ess] {} home owner(s), {} warp(s), spawn={}, main world={}",
                root.players.size(), root.warps.size(), root.spawn != null, root.config.mainWorld);
    }

    public static void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardCharsets.UTF_8);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Ess] could not write {}", file, t);
        }
    }

    // ------------------------------------------------------------------ lookups

    /** Finds a stored player id by last known name (used by {@code /unmute}). */
    public static UUID findByName(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, PlayerData> entry : root.players.entrySet()) {
            PlayerData data = entry.getValue();
            if (name.equalsIgnoreCase(data.lastName)) {
                try {
                    return UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** "上次在线" as readable text, or null when this player was never seen. */
    public static String lastSeen(String name) {
        if (name == null) {
            return null;
        }
        for (PlayerData data : root.players.values()) {
            if (!name.equalsIgnoreCase(data.lastName)) {
                continue;
            }
            if (data.lastSeen <= 0L) {
                return "未知";
            }
            long minutes = Math.max(0L, (System.currentTimeMillis() - data.lastSeen) / 60_000L);
            if (minutes < 1L) {
                return "刚刚";
            }
            if (minutes < 60L) {
                return minutes + " 分钟前";
            }
            if (minutes < 1440L) {
                return (minutes / 60L) + " 小时前";
            }
            return (minutes / 1440L) + " 天前";
        }
        return null;
    }

    public static void touch(UUID player, String name) {
        PlayerData data = of(player);
        data.lastName = name == null ? data.lastName : name;
        if (data.firstJoin <= 0L) {
            data.firstJoin = System.currentTimeMillis();
        }
        save();
    }

    public static void markOffline(UUID player, String name) {
        PlayerData data = of(player);
        data.lastName = name == null ? data.lastName : name;
        data.lastSeen = System.currentTimeMillis();
        data.afk = false;
        save();
    }

    /** The level a stored point lives in, or null when that world is not loaded. */
    public static ServerLevel levelOf(MinecraftServer server, Point point) {
        if (server == null || point == null) {
            return null;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.resources.ResourceLocation.tryParse(point.world);
        if (key == null) {
            return null;
        }
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey
                .create(net.minecraft.core.registries.Registries.DIMENSION, key));
        if (level != null) {
            return level;
        }
        for (com.oktsas.onekeytosetaserver.world.MvWorld world
                : com.oktsas.onekeytosetaserver.world.MvWorldManager.all()) {
            if (com.oktsas.onekeytosetaserver.world.MvWorldManager.dimensionKey(world).location().equals(key)) {
                return com.oktsas.onekeytosetaserver.world.MvWorldManager.levelOf(world);
            }
        }
        return null;
    }

    /** The main city level, or the overworld when the configured folder is not loaded. */
    public static ServerLevel mainLevel(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        com.oktsas.onekeytosetaserver.world.MvWorld main =
                com.oktsas.onekeytosetaserver.world.MvWorldManager.byName(root.config.mainWorld);
        ServerLevel level = main == null
                ? null : com.oktsas.onekeytosetaserver.world.MvWorldManager.levelOf(main);
        return level != null ? level : server.overworld();
    }

    public static double fee(String what) {
        Config config = root.config;
        return switch (what) {
            case "tpa" -> config.feeTpa;
            case "tpahere" -> config.feeTpahere;
            case "home" -> config.feeHome;
            case "warp" -> config.feeWarp;
            case "back" -> config.feeBack;
            case "spawn" -> config.feeSpawn;
            case "rtp" -> config.feeRtp;
            case "tppos" -> config.feeTppos;
            default -> 0.0D;
        };
    }
}
