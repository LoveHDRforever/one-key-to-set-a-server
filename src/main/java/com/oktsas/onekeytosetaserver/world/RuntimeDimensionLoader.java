package com.oktsas.onekeytosetaserver.world;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.mixin.MinecraftServerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;

import java.util.List;

/**
 * Brings a managed world online while the server is already running - the mechanism behind
 * {@code /mv load} and {@code /mv create} (validated by Phase 0 Spike C).
 *
 * <p>Steps mirror what vanilla does while building its initial level list: parse the dimension json into a
 * {@link LevelStem}, register it in the (normally frozen) {@code LEVEL_STEM} registry, build the
 * {@link ServerLevel}, then publish it through Forge's live world map.</p>
 */
public final class RuntimeDimensionLoader {

    private static final ChunkProgressListener NOOP_LISTENER = new ChunkProgressListener() {
        @Override
        public void updateSpawnPos(ChunkPos pos) {
        }

        @Override
        public void onStatusChange(ChunkPos pos, ChunkStatus status) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    };

    private RuntimeDimensionLoader() {
    }

    public static boolean load(MinecraftServer server, MvWorld world) {
        try {
            ResourceKey<Level> levelKey = MvWorldManager.dimensionKey(world);
            if (server.getLevel(levelKey) != null) {
                return true;
            }
            RegistryAccess.Frozen access = server.registryAccess();
            @SuppressWarnings("unchecked")
            MappedRegistry<LevelStem> stems = (MappedRegistry<LevelStem>) access.registryOrThrow(Registries.LEVEL_STEM);
            ResourceKey<LevelStem> stemKey = Registries.levelToLevelStem(levelKey);

            if (!stems.containsKey(stemKey)) {
                RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
                LevelStem parsed = LevelStem.CODEC.parse(ops, MvWorldManager.dimensionJsonTree(world))
                        .getOrThrow(false, msg -> OneKeyToSetAServer.LOGGER.error("[MV] LevelStem parse error: {}", msg));
                stems.unfreeze();
                Registry.register(stems, stemKey, parsed);
                stems.freeze();
                OneKeyToSetAServer.LOGGER.info("[MV] registered LevelStem {} at runtime", stemKey.location());
            }
            LevelStem stem = stems.getHolderOrThrow(stemKey).value();

            MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
            ServerLevel overworld = server.overworld();
            long seed = world.seed != 0L ? world.seed : overworld.getSeed();
            ServerLevelData levelData = new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData());
            ServerLevel level = new ServerLevel(server, accessor.oktsas$executor(), accessor.oktsas$storageSource(),
                    levelData, levelKey, stem, NOOP_LISTENER, false, seed, List.of(), false, new RandomSequences(seed));

            server.forgeGetWorldMap().put(levelKey, level);
            server.markWorldsDirty();
            applyWorldRules(level, world);

            // Pre-load the spawn chunk: Phase 0 showed that a transfer into a just created, not yet persisted
            // world can be refused, and warming the destination avoids that.
            level.getChunk(0, 0);
            level.save(null, true, false);

            OneKeyToSetAServer.LOGGER.info("[MV] world {} online (folder {}, seed {})",
                    levelKey.location(), world.folder, seed);
            return true;
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not bring world {} online", world.folder, t);
            return false;
        }
    }

    /** Applies the settings that belong to the level object itself. */
    public static void applyWorldRules(ServerLevel level, MvWorld world) {
        try {
            if (world.hasSpawn()) {
                level.setDefaultSpawnPos(new BlockPos(world.spawnX.intValue(), world.spawnY.intValue(),
                        world.spawnZ.intValue()), world.spawnYaw == null ? 0.0F : world.spawnYaw);
            }
            if (world.borderSize > 0.0D) {
                level.getWorldBorder().setSize(world.borderSize);
            }
            if (world.keepSpawnInMemory) {
                BlockPos spawn = level.getSharedSpawnPos();
                level.setChunkForced(spawn.getX() >> 4, spawn.getZ() >> 4, true);
            }
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.warn("[MV] could not apply rules to {}: {}", world.folder, t.toString());
        }
    }
}
