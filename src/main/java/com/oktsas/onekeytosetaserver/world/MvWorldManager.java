package com.oktsas.onekeytosetaserver.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oktsas.onekeytosetaserver.ManagedWorlds;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Owns every managed world: the registry, the generated dimension datapack, and the runtime
 * load / unload / create / delete / reset operations.
 *
 * <p>Two load paths exist on purpose:</p>
 * <ul>
 *   <li>at startup a datapack entry is written for every world the server should have on boot
 *       (no mixin, plain vanilla world loading),</li>
 *   <li>anything marked with {@value #HOTLOAD_MARKER}, or created/loaded while the server runs, goes
 *       through {@link RuntimeDimensionLoader}.</li>
 * </ul>
 */
public final class MvWorldManager {
    /** Marker file that keeps a world out of the startup datapack so it can be loaded at runtime. */
    public static final String HOTLOAD_MARKER = ".hotload";

    private static final Set<String> IGNORED = Set.of(
            "config", "defaultconfigs", "mods", "libraries", "logs", "crash-reports", "assets", "versions",
            "saves", "resourcepacks", "shaderpacks", "kubejs", "local", "cache", "plugins", "mohist-config",
            "delete", "tacz", "immersive_paintings", "build", ".gradle", ".git", ".mixin.out", "run",
            "patchouli_books", "serverconfig", "downloads", "backups", "world_backups");

    private static final Map<String, MvWorld> WORLDS = new LinkedHashMap<>();
    private static final List<MvWorld> RUNTIME_ONLY = new ArrayList<>();

    private static Path gameDir;
    private static String mainLevelName = "world";

    private MvWorldManager() {
    }

    // ------------------------------------------------------------------ startup

    public static void startupScan() {
        try {
            gameDir = FMLPaths.GAMEDIR.get();
            mainLevelName = readLevelName(gameDir);
            Path mainWorld = gameDir.resolve(mainLevelName);
            Path datapackRoot = mainWorld.resolve("datapacks").resolve("oktsas_generated");
            Path dimensionDir = datapackRoot.resolve("data").resolve(OneKeyToSetAServer.MODID).resolve("dimension");

            WORLDS.clear();
            RUNTIME_ONLY.clear();
            ManagedWorlds.clear();

            List<Path> folders = findWorldFolders(gameDir);
            int declared = 0;
            for (Path dir : folders) {
                String folder = dir.getFileName().toString();
                MvWorld world = MvWorld.load(dir, sanitize(folder), readSeed(dir));
                WORLDS.put(world.dimensionPath, world);
                if (!world.autoLoad) {
                    OneKeyToSetAServer.LOGGER.info("[MV] world {} is not auto-loaded, use /mv load {}", folder, folder);
                    continue;
                }
                if (Files.exists(dir.resolve(HOTLOAD_MARKER))) {
                    RUNTIME_ONLY.add(world);
                    OneKeyToSetAServer.LOGGER.info("[MV] world {} marked for runtime loading", folder);
                    continue;
                }
                Files.createDirectories(dimensionDir);
                writePackMcmeta(datapackRoot);
                Files.writeString(dimensionDir.resolve(world.dimensionPath + ".json"),
                        new Gson().toJson(dimensionJsonTree(world)), StandardCharsets.UTF_8);
                ManagedWorlds.register(world.dimensionPath, world.folder);
                declared++;
            }
            OneKeyToSetAServer.LOGGER.info("[MV] startup scan: {} world(s) known, {} in datapack, {} runtime-only",
                    WORLDS.size(), declared, RUNTIME_ONLY.size());
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] startup scan failed", t);
        }
    }

    public static List<MvWorld> runtimeOnlyWorlds() {
        return RUNTIME_ONLY;
    }

    // ------------------------------------------------------------------ registry

    public static Collection<MvWorld> all() {
        return WORLDS.values();
    }

    public static MvWorld byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        MvWorld direct = WORLDS.get(sanitize(wanted));
        if (direct != null) {
            return direct;
        }
        for (MvWorld world : WORLDS.values()) {
            if (world.folder.equalsIgnoreCase(name) || world.displayName().equalsIgnoreCase(name)) {
                return world;
            }
        }
        return null;
    }

    public static Path gameDir() {
        return gameDir;
    }

    public static Path folderOf(MvWorld world) {
        return gameDir.resolve(world.folder);
    }

    public static ResourceKey<Level> dimensionKey(MvWorld world) {
        return ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(OneKeyToSetAServer.MODID, world.dimensionPath));
    }

    public static ServerLevel levelOf(MvWorld world) {
        MinecraftServer server = currentServer();
        return server == null ? null : server.getLevel(dimensionKey(world));
    }

    // ------------------------------------------------------------------ load / unload

    public static boolean loadRuntime(MvWorld world) {
        MinecraftServer server = currentServer();
        if (server == null) {
            return false;
        }
        ManagedWorlds.register(world.dimensionPath, world.folder);
        boolean ok = RuntimeDimensionLoader.load(server, world);
        if (ok && RUNTIME_ONLY.contains(world)) {
            RUNTIME_ONLY.remove(world);
        }
        if (ok) {
            // A managed world cannot keep a spawn point of its own in its level data (see applyDefaultSpawn), so
            // the mod records one itself; without it /mv tp and every respawn used the main world's 0,0,0.
            applyDefaultSpawn(world);
        }
        return ok;
    }

    // ------------------------------------------------------------------ spawn point

    /**
     * The surface of 0/0 of a managed world, or y=64 for a void world.
     *
     * <p>This is what the owner asked for ("建议修改为 0 ~ 0"): a new world's spawn must be on the surface
     * instead of at y=0 inside the rock.</p>
     */
    public static BlockPos surfaceSpawn(MvWorld world, ServerLevel level) {
        if (world != null && "VOID".equalsIgnoreCase(world.generator)) {
            // nothing to stand on in a void world; 64 is the height a void lobby is normally built at
            return new BlockPos(0, 64, 0);
        }
        BlockPos safe = com.oktsas.onekeytosetaserver.teleport.MvTeleporter.findSafeSpot(level, 0, 0, -1);
        if (safe == null || safe.getY() <= level.getMinBuildHeight() + 2) {
            // "the heightmap says the surface is the bottom of the world" means we have no data for that column;
            // 64 keeps the player out of the bedrock in every case
            return new BlockPos(0, 64, 0);
        }
        return safe;
    }

    /**
     * Records a spawn point for a managed world that does not have one yet: {@code 0, y, 0} on the surface.
     *
     * <h2>Why the level data cannot hold it</h2>
     * <p>Managed worlds have no {@code level.dat} of their own (their chunks live in a plain folder), so the
     * server wraps the <b>main</b> world's level data in a {@code DerivedLevelData}. Its
     * {@code setSpawn(BlockPos, float)} is literally an empty method and {@code getSpawnPos()} forwards to the
     * main world - which is why "spawn point" of a managed world has to be kept by this mod, in
     * {@code multiverse/world.json}, and why a player who entered or respawned in such a world ended up at the
     * main world's spawn coordinates (0,0,0, i.e. inside the rock, on the owner's server).</p>
     *
     * @return the spawn that was written, or null when nothing had to change
     */
    public static BlockPos applyDefaultSpawn(MvWorld world) {
        if (world == null) {
            return null;
        }
        ServerLevel level = levelOf(world);
        if (level == null) {
            return null;
        }
        if (world.hasSpawn() && world.spawnY != null && world.spawnY > 0.0D) {
            // /mv setspawn was used on this world - never override an operator's choice
            return null;
        }
        try {
            BlockPos target = surfaceSpawn(world, level);
            world.setSpawn(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 0.0F, 0.0F);
            world.save(folderOf(world));
            OneKeyToSetAServer.LOGGER.info("[MV] world {} spawn point -> {} (surface of 0/0; "
                            + "/mv setspawn picks another spot, /mv spawn teleports there)",
                    world.folder, target);
            return target;
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.warn("[MV] could not set the spawn point of {}: {}", world.folder,
                    t.toString());
            return null;
        }
    }

    /** Records a spawn point for every online managed world; returns how many were written. */
    public static int applyDefaultSpawns() {
        int fixed = 0;
        for (MvWorld world : WORLDS.values()) {
            if (applyDefaultSpawn(world) != null) {
                fixed++;
            }
        }
        if (fixed > 0) {
            OneKeyToSetAServer.LOGGER.info("[MV] spawn points: {} world(s) set to the surface", fixed);
        }
        return fixed;
    }

    /**
     * Where a player should end up in this world: the operator's spawn when one was set, otherwise the surface
     * of 0/0. Used by the spawn guard that rescues players who land inside the rock.
     */
    public static double[] spawnOf(MvWorld world) {
        if (world != null && world.hasSpawn()) {
            return new double[]{world.spawnX, world.spawnY, world.spawnZ};
        }
        return new double[]{0.5D, -1.0D, 0.5D};
    }

    public static boolean unload(MvWorld world) {
        MinecraftServer server = currentServer();
        if (server == null) {
            return false;
        }
        ServerLevel level = levelOf(world);
        if (level == null) {
            return false;
        }
        if (!level.players().isEmpty()) {
            OneKeyToSetAServer.LOGGER.warn("[MV] refusing to unload {}: {} player(s) inside",
                    world.folder, level.players().size());
            return false;
        }
        try {
            level.save(null, true, false);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not save {} before unload", world.folder, t);
        }
        server.forgeGetWorldMap().remove(dimensionKey(world));
        server.markWorldsDirty();
        ManagedWorlds.unregister(world.dimensionPath);
        FakePlayerFactory.unloadLevel(level);
        try {
            level.close();
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.warn("[MV] closing {} reported an error: {}", world.folder, t.toString());
        }
        OneKeyToSetAServer.LOGGER.info("[MV] unloaded world {}", world.folder);
        return true;
    }

    // ------------------------------------------------------------------ create / delete / reset

    public static MvWorld create(String folder, String environment, long seed, String generator) {
        try {
            Path dir = gameDir.resolve(folder);
            // "create or adopt": a folder may already be there because an import, a manual copy, or a delete that
            // could not remove a locked file left it behind. Adopting it is what /mv load does anyway.
            boolean existing = Files.exists(dir);
            Files.createDirectories(dir.resolve("region"));
            MvWorld world = new MvWorld();
            world.folder = folder;
            world.dimensionPath = sanitize(folder);
            world.environment = environment == null || environment.isBlank() ? "NORMAL" : environment.toUpperCase(Locale.ROOT);
            world.generator = generator == null || generator.isBlank() ? "DEFAULT" : generator.toUpperCase(Locale.ROOT);
            world.seed = seed;
            world.save(dir);
            WORLDS.put(world.dimensionPath, world);
            if (existing) {
                OneKeyToSetAServer.LOGGER.info("[MV] adopted existing folder {} as a managed world", folder);
            }
            loadRuntime(world);
            return world;
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not create world {}", folder, t);
            return null;
        }
    }

    public static boolean delete(MvWorld world) {
        unload(world);
        Path dir = folderOf(world);
        com.oktsas.onekeytosetaserver.inventory.PlayerInventoryStore.mailOut(dir, currentServer());
        try {
            deleteRecursively(dir);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not delete world folder {}", world.folder, t);
            return false;
        }
        WORLDS.remove(world.dimensionPath);
        ManagedWorlds.unregister(world.dimensionPath);
        com.oktsas.onekeytosetaserver.domain.DomainManager
                .clearWorld(dimensionKey(world).location().toString());
        OneKeyToSetAServer.LOGGER.info("[MV] deleted world {}", world.folder);
        return true;
    }

    /**
     * Wipes a world's content and brings it back online.  Always removes chunk data; the
     * {@code autoResetClearData} flag additionally removes the per-dimension {@code data/} folder
     * (map data, raids, and other mods' per-dimension state).
     */
    public static boolean reset(MvWorld world) {
        Path dir = folderOf(world);
        unload(world);
        // domains belong to the terrain that is about to disappear
        com.oktsas.onekeytosetaserver.domain.DomainManager
                .clearWorld(dimensionKey(world).location().toString());
        // stored player items are mailed back to their owners instead of vanishing with the terrain
        com.oktsas.onekeytosetaserver.inventory.PlayerInventoryStore.mailOut(dir, currentServer());
        try {
            deleteRecursively(dir.resolve("region"));
            deleteRecursively(dir.resolve("entities"));
            deleteRecursively(dir.resolve("poi"));
            if (world.autoResetClearData) {
                deleteRecursively(dir.resolve("data"));
            }
            Files.createDirectories(dir.resolve("region"));
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] reset of {} failed while deleting content", world.folder, t);
            return false;
        }
        if (world.autoResetNewSeed) {
            world.seed = new Random().nextLong();
            world.save(dir);
        }
        boolean ok = loadRuntime(world);
        OneKeyToSetAServer.LOGGER.info("[MV] reset {} -> {}", world.folder, ok ? "OK" : "FAILED");
        return ok;
    }

    public static void saveAll() {
        for (MvWorld world : WORLDS.values()) {
            world.save(folderOf(world));
        }
    }

    // ------------------------------------------------------------------ helpers

    public static JsonObject dimensionJsonTree(MvWorld world) {
        JsonObject root = new JsonObject();
        root.addProperty("type", dimensionTypeId(world.environment));

        JsonObject generator = new JsonObject();
        switch (world.generator == null ? "DEFAULT" : world.generator) {
            case "FLAT" -> {
                generator.addProperty("type", "minecraft:flat");
                JsonObject settings = new JsonObject();
                JsonArray layers = new JsonArray();
                layers.add(layer("minecraft:bedrock", 1));
                layers.add(layer("minecraft:dirt", 3));
                layers.add(layer("minecraft:grass_block", 1));
                settings.add("layers", layers);
                settings.addProperty("biome", "minecraft:plains");
                settings.add("structure_overrides", new JsonArray());
                generator.add("settings", settings);
            }
            case "VOID" -> {
                generator.addProperty("type", "minecraft:flat");
                JsonObject settings = new JsonObject();
                settings.add("layers", new JsonArray());
                settings.addProperty("biome", "minecraft:plains");
                settings.add("structure_overrides", new JsonArray());
                generator.add("settings", settings);
            }
            case "LARGE_BIOMES" -> {
                generator.addProperty("type", "minecraft:noise");
                generator.addProperty("settings", "minecraft:overworld");
                generator.add("biome_source", multiNoise("minecraft:large_biomes"));
                generator.addProperty("seed", world.seed);
            }
            case "AMPLIFIED" -> {
                generator.addProperty("type", "minecraft:noise");
                generator.addProperty("settings", "minecraft:amplified");
                generator.add("biome_source", multiNoise("minecraft:overworld"));
                generator.addProperty("seed", world.seed);
            }
            default -> {
                generator.addProperty("type", "minecraft:noise");
                generator.addProperty("settings", "minecraft:overworld");
                generator.add("biome_source", multiNoise("minecraft:overworld"));
                generator.addProperty("seed", world.seed);
            }
        }
        root.add("generator", generator);
        return root;
    }

    private static JsonObject layer(String block, int height) {
        JsonObject layer = new JsonObject();
        layer.addProperty("block", block);
        layer.addProperty("height", height);
        return layer;
    }

    private static JsonObject multiNoise(String preset) {
        JsonObject biomeSource = new JsonObject();
        biomeSource.addProperty("type", "minecraft:multi_noise");
        biomeSource.addProperty("preset", preset);
        return biomeSource;
    }

    public static String dimensionTypeId(String environment) {
        if (environment == null) {
            return "minecraft:overworld";
        }
        return switch (environment.toUpperCase(Locale.ROOT)) {
            case "NETHER" -> "minecraft:the_nether";
            case "THE_END", "END" -> "minecraft:the_end";
            case "NORMAL", "OVERWORLD" -> "minecraft:overworld";
            default -> {
                // A typo hand-edited into world.json (the test server had "NROMAL") must not break anything,
                // but the owner should hear about it instead of silently getting an overworld.
                OneKeyToSetAServer.LOGGER.warn("[MV] unknown environment '{}', using NORMAL - fix it with "
                        + "/mv modify <world> environment NORMAL", environment);
                yield "minecraft:overworld";
            }
        };
    }

    public static String sanitize(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replace('\\', '/').replace('/', '_');
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' ? c : '_');
        }
        String out = sb.toString();
        if (out.isEmpty() || !(out.charAt(0) >= 'a' && out.charAt(0) <= 'z')) {
            out = "w_" + out;
        }
        return out;
    }

    private static void writePackMcmeta(Path datapackRoot) throws IOException {
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 15);
        pack.addProperty("description", "OKTSAS generated dimensions");
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        Files.createDirectories(datapackRoot);
        try (Writer w = Files.newBufferedWriter(datapackRoot.resolve("pack.mcmeta"), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, w);
        }
    }

    private static String readLevelName(Path gameDir) {
        Path props = gameDir.resolve("server.properties");
        if (Files.isRegularFile(props)) {
            try (InputStream in = Files.newInputStream(props)) {
                Properties p = new Properties();
                p.load(in);
                String name = p.getProperty("level-name");
                if (name != null && !name.isBlank()) {
                    return name.trim();
                }
            } catch (IOException e) {
                OneKeyToSetAServer.LOGGER.warn("[MV] could not read server.properties, using 'world'", e);
            }
        }
        return "world";
    }

    private static List<Path> findWorldFolders(Path gameDir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(gameDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                if (name.equals(mainLevelName) || IGNORED.contains(name) || name.startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(dir.resolve("region")) || Files.isRegularFile(dir.resolve("level.dat"))) {
                    result.add(dir);
                }
            }
        }
        result.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return result;
    }

    private static long readSeed(Path world) {
        Path levelDat = world.resolve("level.dat");
        if (Files.isRegularFile(levelDat)) {
            try (InputStream in = Files.newInputStream(levelDat)) {
                CompoundTag root = NbtIo.readCompressed(in);
                CompoundTag data = root.getCompound("Data");
                if (data.contains("WorldGenSettings", CompoundTag.TAG_COMPOUND)) {
                    CompoundTag gen = data.getCompound("WorldGenSettings");
                    if (gen.contains("seed", CompoundTag.TAG_LONG)) {
                        return gen.getLong("seed");
                    }
                }
            } catch (Throwable t) {
                OneKeyToSetAServer.LOGGER.warn("[MV] could not read seed from {}: {}", levelDat, t.toString());
            }
        }
        return 0L;
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static MinecraftServer currentServer() {
        return OneKeyToSetAServer.server();
    }
}
