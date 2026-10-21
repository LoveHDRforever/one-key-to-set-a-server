package com.oktsas.onekeytosetaserver.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;

import java.util.function.Function;

/**
 * The actual cross-dimension carrier.
 *
 * <p>Vanilla's {@code Entity#changeDimension(ServerLevel)} refuses the move when neither the source nor the
 * destination dimension is the nether or the end (its {@code findDimensionEntryPoint} returns null), which is
 * exactly the shape of every managed world here: overworld-like -> overworld-like.  Forge's ITeleporter entry
 * point exists for this case, so all transfers go through this class and we also control the landing spot.</p>
 */
public final class MvITeleporter implements ITeleporter {
    private final Vec3 requested;

    public MvITeleporter(Vec3 requested) {
        this.requested = requested;
    }

    @Override
    public PortalInfo getPortalInfo(Entity entity, ServerLevel destWorld,
                                    Function<ServerLevel, PortalInfo> portalInfoModifier) {
        BlockPos safe = MvTeleporter.findSafeSpot(destWorld,
                Mth.floor(requested.x), Mth.floor(requested.z), Mth.floor(requested.y));
        return new PortalInfo(new Vec3(safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D),
                Vec3.ZERO, entity.getYRot(), entity.getXRot());
    }

    @Override
    public Entity placeEntity(Entity entity, ServerLevel currentWorld, ServerLevel destWorld, float yaw,
                              Function<Boolean, Entity> repositionEntity) {
        return repositionEntity.apply(false);
    }

    @Override
    public boolean isVanilla() {
        return false;
    }

    @Override
    public boolean playTeleportSound(net.minecraft.server.level.ServerPlayer player, ServerLevel sourceWorld,
                                     ServerLevel destWorld) {
        return false;
    }
}
