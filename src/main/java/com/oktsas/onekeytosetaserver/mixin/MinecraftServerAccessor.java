package com.oktsas.onekeytosetaserver.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Executor;

/**
 * Phase 0 Spike C: vanilla keeps these two fields private/protected, and both are required to build a
 * {@code ServerLevel} outside of the vanilla initial-world loop.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess oktsas$storageSource();

    @Accessor("executor")
    Executor oktsas$executor();
}
