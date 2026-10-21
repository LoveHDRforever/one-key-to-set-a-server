package com.oktsas.onekeytosetaserver.mixin;

import com.oktsas.onekeytosetaserver.ManagedWorlds;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

/**
 * Phase 0 Spike B: give every managed dimension its own folder.
 *
 * <p>In 1.20.1 {@code DimensionType#getStorageFolder} is the single place that decides where a
 * dimension's {@code region/}, {@code entities/}, {@code poi/} and {@code data/} live.  The main
 * world path is passed in, so a managed world simply resolves to a sibling folder of the main world
 * folder.  Vanilla dimensions (namespace {@code minecraft}) are left untouched.</p>
 */
@Mixin(DimensionType.class)
public class DimensionTypeMixin {

    @Inject(method = "getStorageFolder", at = @At("HEAD"), cancellable = true)
    private static void oktsas$ownStorageFolder(ResourceKey<Level> dimension, Path levelPath,
                                                CallbackInfoReturnable<Path> cir) {
        try {
            String folder = ManagedWorlds.folderFor(dimension);
            if (folder == null || levelPath == null) {
                return;
            }
            Path parent = levelPath.getParent();
            if (parent == null) {
                return;
            }
            Path target = parent.resolve(folder);
            OneKeyToSetAServer.LOGGER.info("[Phase0] storage redirect {} -> {}", dimension.location(), target);
            cir.setReturnValue(target);
        } catch (Throwable t) {
            // Never break world loading because of this redirect.
            OneKeyToSetAServer.LOGGER.error("[Phase0] storage redirect failed for {}", dimension, t);
        }
    }
}
