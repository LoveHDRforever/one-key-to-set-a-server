package com.oktsas.onekeytosetaserver.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One managed world.
 *
 * <p>Every world owns one folder in the server root, and its settings live next to its chunks in
 * {@code <folder>/multiverse/world.json}.  That is deliberate: copying, backing up or deleting a world
 * folder carries (or removes) its settings, player inventories and future domain data in one move.</p>
 *
 * <p>Field names deliberately follow Multiverse-Core's worlds.yml semantics so existing users feel at home
 * (alias, pvp, scale, respawnWorld, hunger, autoHeal, keepSpawnInMemory, bedRespawn, allowFlight,
 * playerLimit, worldBlacklist, environment, seed, generator, gameMode, hidden).</p>
 */
public final class MvWorld {
    public static final String CONFIG_DIR = "multiverse";
    public static final String CONFIG_FILE = "world.json";

    // ---- identity
    /** folder name in the server root, this is the stable key */
    public String folder = "";
    /** dimension path (lower case, safe characters); dimension id is onekeytosetaserver:<dimensionPath> */
    public String dimensionPath = "";
    public String alias = "";
    public boolean hidden = false;

    // ---- world creation
    /** NORMAL | NETHER | THE_END */
    public String environment = "NORMAL";
    /** DEFAULT | FLAT | VOID | LARGE_BIOMES | AMPLIFIED */
    public String generator = "DEFAULT";
    public long seed = 0L;

    // ---- per world settings
    /** SURVIVAL | CREATIVE | ADVENTURE | SPECTATOR */
    public String gameMode = "SURVIVAL";
    public boolean pvp = true;
    public boolean allowFlight = false;
    public boolean hunger = true;
    public boolean autoHeal = false;
    public boolean keepSpawnInMemory = true;
    public boolean autoLoad = true;
    public boolean bedRespawn = true;
    /** when true this world keeps its own pool of player items (see PlayerInventoryStore) */
    public boolean separateInventory = false;
    public String respawnWorld = "";
    public double scale = 1.0D;
    public int playerLimit = -1;
    public List<String> worldBlacklist = new ArrayList<>();
    /** 0 = do not touch the border */
    public double borderSize = 0.0D;
    /** spawn point in this world; nullable = world default */
    public Double spawnX;
    public Double spawnY;
    public Double spawnZ;
    public Float spawnYaw;
    public Float spawnPitch;

    // ---- automatic reset
    public boolean autoReset = false;
    public int autoResetHours = 8;
    public boolean autoResetNewSeed = true;
    public boolean autoResetClearData = true;

    public String displayName() {
        return alias == null || alias.isBlank() ? folder : alias;
    }

    public boolean hasSpawn() {
        return spawnX != null && spawnY != null && spawnZ != null;
    }

    public void setSpawn(double x, double y, double z, float yaw, float pitch) {
        this.spawnX = x;
        this.spawnY = y;
        this.spawnZ = z;
        this.spawnYaw = yaw;
        this.spawnPitch = pitch;
    }

    public Path configPath(Path worldFolder) {
        return worldFolder.resolve(CONFIG_DIR).resolve(CONFIG_FILE);
    }

    public void save(Path worldFolder) {
        try {
            Path target = configPath(worldFolder);
            Files.createDirectories(target.getParent());
            Files.writeString(target, new GsonBuilder().setPrettyPrinting().create().toJson(this),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not save world settings for {}", folder, e);
        }
    }

    /** Reads settings from a world folder, creating a default file when absent. */
    public static MvWorld load(Path worldFolder, String dimensionPath, long seedFromLevelDat) {
        Path file = worldFolder.resolve(CONFIG_DIR).resolve(CONFIG_FILE);
        MvWorld world = null;
        if (Files.isRegularFile(file)) {
            try {
                world = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), MvWorld.class);
            } catch (Throwable t) {
                OneKeyToSetAServer.LOGGER.error("[MV] could not read {}, using defaults", file, t);
            }
        }
        if (world == null) {
            world = new MvWorld();
        }
        world.folder = worldFolder.getFileName().toString();
        if (world.dimensionPath == null || world.dimensionPath.isBlank()) {
            world.dimensionPath = dimensionPath;
        }
        if (world.seed == 0L && seedFromLevelDat != 0L) {
            world.seed = seedFromLevelDat;
        }
        world.save(worldFolder);
        return world;
    }
}
