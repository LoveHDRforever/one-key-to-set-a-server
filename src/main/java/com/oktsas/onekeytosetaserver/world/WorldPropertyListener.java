package com.oktsas.onekeytosetaserver.world;

import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Applies per-world rules: game mode, flight, hunger, auto heal, PvP and access limits.
 *
 * <p>Everything here was picked because Forge already exposes the needed hook, no mixin required.</p>
 */
public final class WorldPropertyListener {

    private static WorldRuleConfig config = new WorldRuleConfig();
    private static java.nio.file.Path configFile;

    public static WorldRuleConfig config() {
        return config;
    }

    /** Reads {@code multiverse/world-rules.json} (and writes the defaults on first run). */
    public static void loadConfig(java.nio.file.Path gameDir) {
        configFile = gameDir.resolve(MvWorld.CONFIG_DIR).resolve("world-rules.json");
        try {
            if (java.nio.file.Files.isRegularFile(configFile)) {
                WorldRuleConfig loaded = new com.google.gson.Gson().fromJson(
                        java.nio.file.Files.readString(configFile, java.nio.charset.StandardCharsets.UTF_8),
                        WorldRuleConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            }
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not read {}", configFile, t);
        }
        saveConfig();
    }

    public static void saveConfig() {
        if (configFile == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(configFile.getParent());
            java.nio.file.Files.writeString(configFile, new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .create().toJson(config), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not write {}", configFile, t);
        }
    }

    /** True when this player is an admin who is exempt from that rule. */
    private static boolean exempt(ServerPlayer player, java.util.function.Predicate<WorldRuleConfig> rule) {
        return player != null && player.hasPermissions(config.exemptOpLevel) && rule.test(config);
    }

    public static MvWorld worldOf(Level level) {
        if (level == null) {
            return null;
        }
        ResourceLocation id = level.dimension().location();
        if (!OneKeyToSetAServer.MODID.equals(id.getNamespace())) {
            return null;
        }
        return MvWorldManager.byName(id.getPath());
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        apply(event.getEntity());
    }

    @SubscribeEvent
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        apply(event.getEntity());
        if (event.getEntity() instanceof ServerPlayer player) {
            rescueBuried(player, "登录");
        }
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        apply(event.getEntity());
        applyRespawnWorld(event);
        if (event.getEntity() instanceof ServerPlayer player && !event.isEndConquered()) {
            rescueBuried(player, "重生");
        }
    }

    /**
     * Puts a player who ended up <b>inside</b> a managed world's blocks back on that world's spawn point.
     *
     * <p>This is the other half of the spawn fix: a managed world has no {@code level.dat} of its own, so the
     * server derives its level data from the <b>main</b> world ({@code DerivedLevelData.setSpawn} is an empty
     * method). Dying inside a managed world therefore respawned the player at the main world's spawn
     * coordinates - which on the owner's server is 0,0,0, i.e. inside the rock ("玩家默认出生点是 0 0 0 导致
     * 玩家在地底下"). Since the spawn point cannot be stored per world in the level data, the mod stores it in
     * {@code world.json} and enforces it here.</p>
     *
     * <p>Only players who are actually buried (or in lava, or below the world) are moved, so a bed respawn, a
     * deliberate underground base or a normal login is never touched.</p>
     */
    public static boolean rescueBuried(ServerPlayer player, String reason) {
        MvWorld world = worldOf(player.level());
        if (world == null || !isBuried(player)) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        double[] spawn = MvWorldManager.spawnOf(world);
        OneKeyToSetAServer.LOGGER.info("[MV] {} of {} landed inside blocks at {} ({}), moving to the spawn point "
                        + "of {}", reason, player.getGameProfile().getName(), pos, level.dimension().location(),
                world.folder);
        com.oktsas.onekeytosetaserver.teleport.MvTeleporter.teleportPlayer(player, level, spawn[0], spawn[1],
                spawn[2], player.getYRot(), player.getXRot());
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§e你落在地底了，已把你送回 " + world.displayName() + " 的出生点"));
        return true;
    }

    /** True when this player stands inside blocks, in lava, or below the world. Public for the self test. */
    public static boolean isBuried(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        boolean lava = feet.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
                || head.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
        return feet.blocksMotion() || head.blocksMotion() || lava
                || pos.getY() <= level.getMinBuildHeight() + 1;
    }

    /** Public for the self test: the same rescue the respawn/login hooks run, returning whether it acted. */
    public static boolean rescue(ServerPlayer player, String reason) {
        return rescueBuried(player, reason);
    }

    /**
     * Multiverse's {@code respawnWorld} / {@code bedRespawn}: after dying in a world that redirects respawns,
     * the player is moved to the target world's spawn. {@code bedRespawn} keeps a bed/anchor respawn where it
     * is, which the client already handled, so the redirect is skipped in that case.
     */
    private void applyRespawnWorld(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isEndConquered()) {
            return;
        }
        if (player.getServer() == null) {
            return;
        }
        MvWorld world = worldOf(player.level());
        if (world == null || world.respawnWorld == null || world.respawnWorld.isBlank()) {
            return;
        }
        boolean usedBed = player.getRespawnPosition() != null;
        if (usedBed && world.bedRespawn) {
            return;
        }
        MvWorld target = MvWorldManager.byName(world.respawnWorld);
        net.minecraft.server.level.ServerLevel level = target == null ? null : MvWorldManager.levelOf(target);
        if (level == null) {
            OneKeyToSetAServer.LOGGER.warn("[MV] respawnWorld {} of {} is not loaded",
                    world.respawnWorld, world.folder);
            return;
        }
        double x = target.spawnX != null ? target.spawnX : 0.5D;
        double y = target.spawnY != null ? target.spawnY : -1.0D;
        double z = target.spawnZ != null ? target.spawnZ : 0.5D;
        com.oktsas.onekeytosetaserver.teleport.MvTeleporter.teleportPlayer(player, level, x, y, z,
                player.getYRot(), player.getXRot());
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§e你在 " + world.displayName() + " 死亡，已回到 " + target.displayName()));
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player.tickCount % 20 == 0) {
            apply(event.player);
        }
    }

    /** Nobody may hurt another player in a world with pvp disabled - unless the attacker is an exempt admin. */
    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        Entity source = event.getSource().getEntity();
        if (!(source instanceof ServerPlayer attacker) || attacker == victim) {
            return;
        }
        MvWorld world = worldOf(victim.level());
        if (world == null || world.pvp) {
            return;
        }
        if (exempt(attacker, rule -> rule.exemptPvp) || exempt(victim, rule -> rule.exemptPvp)) {
            return;
        }
        event.setCanceled(true);
    }

    /** Applies every per-world rule to a player; also called directly by the headless test. */
    public void apply(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        MvWorld world = worldOf(serverPlayer.level());
        if (world == null) {
            return;
        }
        try {
            GameType wanted = GameType.byName(world.gameMode, GameType.SURVIVAL);
            if (serverPlayer.gameMode.getGameModeForPlayer() != wanted
                    && !exempt(serverPlayer, rule -> rule.exemptGameMode)) {
                serverPlayer.setGameMode(wanted);
            }
            // flight always follows the mode the player is actually in, so an exempt admin keeps theirs
            GameType current = serverPlayer.gameMode.getGameModeForPlayer();
            boolean mayFly = world.allowFlight || current == GameType.CREATIVE || current == GameType.SPECTATOR;
            if (serverPlayer.getAbilities().mayfly != mayFly
                    && !exempt(serverPlayer, rule -> rule.exemptFlight)) {
                serverPlayer.getAbilities().mayfly = mayFly;
                if (!mayFly) {
                    serverPlayer.getAbilities().flying = false;
                }
                serverPlayer.onUpdateAbilities();
            }
            if (!world.hunger && !exempt(serverPlayer, rule -> rule.exemptHunger)) {
                serverPlayer.getFoodData().setFoodLevel(20);
                serverPlayer.getFoodData().setSaturation(20.0F);
            }
            if (world.autoHeal && serverPlayer.getHealth() < serverPlayer.getMaxHealth()
                    && !exempt(serverPlayer, rule -> rule.exemptAutoHeal)) {
                serverPlayer.heal(1.0F);
            }
            ServerLevel level = serverPlayer.serverLevel();
            if (world.borderSize > 0.0D && Math.abs(level.getWorldBorder().getSize() - world.borderSize) > 1.0D) {
                level.getWorldBorder().setSize(world.borderSize);
            }
            applyScale(serverPlayer, world);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not apply world rules to {}", serverPlayer.getGameProfile().getName(), t);
        }
    }

    /** Fixed id so the per-world scale modifier can be refreshed instead of stacking up. */
    public static final java.util.UUID SCALE_MODIFIER =
            java.util.UUID.fromString("6f2a1c8e-0f4b-4c9a-9d3e-7b1c5a2d8e40");

    /**
     * Multiverse's per-world {@code scale}: everything except the base speed is multiplied, so a scale of 2
     * means "walk twice as fast in this world" and 0.5 means "half speed".
     */
    private void applyScale(ServerPlayer player, MvWorld world) {
        net.minecraft.world.entity.ai.attributes.AttributeInstance speed =
                player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        double wanted = Math.max(0.0D, world.scale) - 1.0D;
        net.minecraft.world.entity.ai.attributes.AttributeModifier existing = speed.getModifier(SCALE_MODIFIER);
        if (existing != null && Math.abs(existing.getAmount() - wanted) < 1.0E-6) {
            return;
        }
        if (existing != null) {
            speed.removeModifier(SCALE_MODIFIER);
        }
        if (Math.abs(wanted) > 1.0E-6) {
            speed.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    SCALE_MODIFIER, "oktsas_world_scale", wanted,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    /** Access limit helper used by the travel hook in the command/teleport layer. */
    public static boolean canEnter(ServerPlayer player, MvWorld world) {
        if (world.worldBlacklist.contains(player.getGameProfile().getName())) {
            player.sendSystemMessage(Component.literal("你被禁止进入世界 " + world.displayName()));
            return false;
        }
        if (world.playerLimit > 0) {
            ServerLevel level = MvWorldManager.levelOf(world);
            if (level != null && level.players().size() >= world.playerLimit) {
                player.sendSystemMessage(Component.literal("世界 " + world.displayName() + " 已满员"));
                return false;
            }
        }
        return true;
    }
}
