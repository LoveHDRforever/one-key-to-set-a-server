package com.oktsas.onekeytosetaserver.teleport;

import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 0 Spike D, part 2: the safe transfer pipeline.
 *
 * <p>Rules that keep cross-dimension travel from crashing a modded server:</p>
 * <ul>
 *   <li>players are moved with {@code ServerPlayer#teleportTo(ServerLevel, ...)} (handles the respawn packet),</li>
 *   <li>every other entity must pass a policy check - by default vehicles and modded contraptions are refused
 *       (Create contraptions, airships, cars and trains do not survive a dimension change),</li>
 *   <li>all entities are serialisation-checked before the move and the position is restored when it fails,</li>
 *   <li>the actual move always happens through {@link TeleportQueue} at the end of a server tick.</li>
 * </ul>
 */
public final class MvTeleporter {

    /** Mod namespaces whose entities are never transferred between dimensions by default. */
    private static final String[] DENIED_NAMESPACES = {
            "create", "railways", "immersive_aircraft", "automobility", "riautomobility"
    };

    private MvTeleporter() {
    }

    /** Player path - called from commands and (later) portals. */
    public static void teleportPlayer(ServerPlayer player, ServerLevel target, double x, double y, double z,
                                      float yaw, float pitch) {
        if (player == null || target == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        TeleportQueue.submit(() -> {
            try {
                if (player.isPassenger()) {
                    player.stopRiding();
                }
                BlockPos safe = findSafeSpot(target, Mth.floor(x), Mth.floor(z), Mth.floor(y));
                player.teleportTo(target, safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D, yaw, pitch);
                OneKeyToSetAServer.LOGGER.info("[Phase0] teleported player {} to {} at {}",
                        player.getGameProfile().getName(), target.dimension().location(), safe);
            } catch (Throwable t) {
                OneKeyToSetAServer.LOGGER.error("[Phase0] player teleport failed for {}",
                        player.getGameProfile().getName(), t);
            }
        });
    }

    /**
     * Non-player path. Returns whether the entity ended up in the target level. Safe to call from the
     * server thread; the caller keeps ownership of the entity.
     */
    public static boolean transferEntity(Entity entity, ServerLevel target) {
        if (entity == null || target == null || entity.level() == target) {
            return false;
        }
        if (entity instanceof ServerPlayer) {
            return false; // players always go through teleportPlayer
        }
        if (entity.isPassenger() || entity.isVehicle()) {
            OneKeyToSetAServer.LOGGER.warn("[Phase0] refused to transfer {} ({}): vehicle or passenger",
                    entity.getType().builtInRegistryHolder().key().location(), entity.getUUID());
            return false;
        }
        if (!policyAllows(entity)) {
            return false;
        }
        // serialisation pre-check: an entity that cannot save itself must not be moved
        try {
            entity.saveWithoutId(new CompoundTag());
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Phase0] refused to transfer {}: not serialisable",
                    entity.getType().builtInRegistryHolder().key().location(), t);
            return false;
        }

        Vec3 before = entity.position();
        ResourceKey<Level> fromDimension = entity.level().dimension();
        try {
            Entity moved = entity.changeDimension(target, new MvITeleporter(before));
            boolean ok = moved != null && moved.level() == target;
            if (!ok) {
                OneKeyToSetAServer.LOGGER.error("[Phase0] transfer of {} produced no entity in target level, rolling back",
                        entity.getType().builtInRegistryHolder().key().location());
                rollback(entity, fromDimension, before);
                return false;
            }
            OneKeyToSetAServer.LOGGER.info("[Phase0] transferred {} from {} to {} at {}",
                    entity.getType().builtInRegistryHolder().key().location(), fromDimension.location(),
                    target.dimension().location(), moved.position());
            return true;
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Phase0] transfer of {} threw, rolling back",
                    entity.getType().builtInRegistryHolder().key().location(), t);
            rollback(entity, fromDimension, before);
            return false;
        }
    }

    private static boolean policyAllows(Entity entity) {
        String namespace = entity.getType().builtInRegistryHolder().key().location().getNamespace();
        for (String denied : DENIED_NAMESPACES) {
            if (denied.equals(namespace)) {
                OneKeyToSetAServer.LOGGER.warn("[Phase0] refused to transfer {}: namespace '{}' is on the deny list",
                        entity.getType().builtInRegistryHolder().key().location(), namespace);
                return false;
            }
        }
        return true;
    }

    private static void rollback(Entity entity, ResourceKey<Level> fromDimension, Vec3 before) {
        try {
            MinecraftServer server = entity.getServer();
            if (server == null) {
                return;
            }
            ServerLevel from = server.getLevel(fromDimension);
            if (from == null) {
                return;
            }
            if (entity.level() != from) {
                entity.changeDimension(from, new MvITeleporter(before));
            }
            entity.moveTo(before.x, before.y, before.z, entity.getYRot(), entity.getXRot());
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Phase0] rollback failed", t);
        }
    }

    /**
     * Find a spot with solid ground below and two blocks of air.
     *
     * <p>The heightmap is the primary answer - it is what vanilla uses and it counts water, so a column in the
     * ocean gets the water surface. Two traps are handled explicitly, both of which produced a spot below
     * bedrock in testing:</p>
     * <ul>
     *   <li>the chunk has to be in memory first, otherwise {@code Level#getHeight} answers
     *       {@code getMinBuildHeight()} (a world spawn point once ended up at {@code y=-63});</li>
     *   <li>walking the column with block states alone must <b>not</b> skip over water: doing that drilled
     *       through an ocean and stopped in an air pocket at {@code y=-38}.</li>
     * </ul>
     *
     * @param preferredY a height to keep when it is clear and well away from the surface (an operator's base),
     *                   otherwise -1 to use the surface
     */
    public static BlockPos findSafeSpot(ServerLevel level, int x, int z, int preferredY) {
        try {
            level.getChunk(net.minecraft.core.SectionPos.blockToSectionCoord(x),
                    net.minecraft.core.SectionPos.blockToSectionCoord(z));
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.warn("[Phase0] could not load the chunk at {}/{}: {}", x, z, t.toString());
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        boolean haveData = surface > level.getMinBuildHeight() + 2;
        if (preferredY > 0 && (!haveData || Math.abs(preferredY - surface) > 16)) {
            // somebody asked for a specific height (an underground base, a platform): keep it when it is clear
            BlockPos chosen = clearSpot(level, x, z, preferredY);
            if (chosen != null) {
                return chosen;
            }
        }
        if (haveData) {
            return new BlockPos(x, surface, z);
        }
        BlockPos found = scanDown(level, x, z, level.getMaxBuildHeight() - 2);
        // Nothing to stand on (a void world, or a column without any data): never answer with a spot below the
        // build limit, the player would be buried in bedrock.
        return found != null ? found : new BlockPos(x, FALLBACK_Y, z);
    }

    /** {@code y} when that spot and the one above it are both clear (no ground check at all). */
    private static BlockPos clearSpot(ServerLevel level, int x, int z, int y) {
        int top = Math.min(level.getMaxBuildHeight() - 2, y);
        for (int at = top; at > level.getMinBuildHeight() + 1; at--) {
            BlockPos pos = new BlockPos(x, at, z);
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()) {
                return pos;
            }
            if (at <= y - 4) {
                break;
            }
        }
        return null;
    }

    /**
     * First spot from {@code fromY} downwards that has two blocks of air above a block that is not air - solid
     * ground <b>or</b> a liquid surface, so an ocean is not skipped in favour of a cave below it.
     */
    private static BlockPos scanDown(ServerLevel level, int x, int z, int fromY) {
        for (int y = fromY; y > level.getMinBuildHeight() + 1; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState below = level.getBlockState(pos.below());
            if (!below.isAir() && level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.above()).isAir()
                    && !below.is(Blocks.LAVA) && !below.is(Blocks.MAGMA_BLOCK)
                    && !below.is(Blocks.CACTUS) && !below.is(Blocks.FIRE)
                    && !below.is(Blocks.SOUL_FIRE) && !below.is(Blocks.CAMPFIRE)) {
                return pos;
            }
        }
        return null;
    }

    /** Height used when a column has no ground at all (void worlds and similar). */
    private static final int FALLBACK_Y = 64;
}
