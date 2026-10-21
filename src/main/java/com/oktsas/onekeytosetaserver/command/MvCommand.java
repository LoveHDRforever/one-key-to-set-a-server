package com.oktsas.onekeytosetaserver.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.menu.MvMenus;
import com.oktsas.onekeytosetaserver.mixin.MinecraftServerAccessor;
import com.oktsas.onekeytosetaserver.teleport.MvTeleporter;
import com.oktsas.onekeytosetaserver.world.MvWorld;
import com.oktsas.onekeytosetaserver.world.MvWorldManager;
import com.oktsas.onekeytosetaserver.world.RuntimeDimensionLoader;
import com.oktsas.onekeytosetaserver.world.WorldPropertyListener;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * The {@code /mv} command surface.  Management needs permission level 3 ("world administrator"),
 * everyday travel needs nothing.
 */
public final class MvCommand {

    private MvCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Every /mv sub-command is a world-admin command (op 3) except /mv tp and the menu itself.
        java.util.function.Predicate<CommandSourceStack> admin = source -> source.hasPermission(3);
        dispatcher.register(Commands.literal("mv")
                .then(Commands.literal("list").requires(admin).executes(MvCommand::list))
                .then(Commands.literal("info").requires(admin)
                        .then(Commands.argument("world", StringArgumentType.word()).executes(MvCommand::info)))
                .then(Commands.literal("tp")
                        .then(Commands.literal("spawn").executes(MvCommand::spawn))
                        .then(Commands.argument("world", StringArgumentType.word()).executes(MvCommand::tp)))
                .then(Commands.literal("setspawn").requires(admin).executes(MvCommand::setspawn))
                .then(Commands.literal("save").requires(admin).executes(MvCommand::save))
                .then(Commands.literal("menu")
                        .executes(ctx -> com.oktsas.onekeytosetaserver.command.MenuCommand.open(ctx,
                                com.oktsas.onekeytosetaserver.menu.MenuManager.MAIN))
                        .then(Commands.literal("list").requires(admin)
                                .executes(com.oktsas.onekeytosetaserver.command.MenuCommand::list))
                        .then(Commands.literal("reload").requires(admin)
                                .executes(com.oktsas.onekeytosetaserver.command.MenuCommand::reload))
                        .then(Commands.literal("edit")
                                .requires(source -> source.hasPermission(4))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(com.oktsas.onekeytosetaserver.command.MenuCommand::edit)))
                        .then(Commands.literal("create")
                                .requires(source -> source.hasPermission(4))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("title", StringArgumentType.greedyString())
                                                .executes(ctx -> com.oktsas.onekeytosetaserver.command.MenuCommand
                                                        .create(ctx, 5)))))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> com.oktsas.onekeytosetaserver.command.MenuCommand.open(ctx,
                                        StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("inventory")
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.literal("recover")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(MvCommand::inventoryRecover)))
                        .then(Commands.literal("inspect")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(MvCommand::inventoryInspect))))
                .then(Commands.literal("load").requires(admin)
                        .then(Commands.argument("world", StringArgumentType.word()).executes(MvCommand::load)))
                .then(Commands.literal("unload").requires(admin)
                        .then(Commands.argument("world", StringArgumentType.word()).executes(MvCommand::unload)))
                .then(Commands.literal("delete").requires(admin)
                        .then(Commands.argument("world", StringArgumentType.word()).executes(MvCommand::delete)))
                .then(Commands.literal("reset").requires(admin)
                        .then(Commands.argument("world", StringArgumentType.word()).executes(MvCommand::reset)))
                .then(Commands.literal("create").requires(admin)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> create(ctx, "NORMAL", "DEFAULT", 0L))
                                .then(Commands.argument("environment", StringArgumentType.word())
                                        .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "environment"),
                                                "DEFAULT", 0L))
                                        .then(Commands.argument("generator", StringArgumentType.word())
                                                .executes(ctx -> create(ctx,
                                                        StringArgumentType.getString(ctx, "environment"),
                                                        StringArgumentType.getString(ctx, "generator"), 0L))
                                                .then(Commands.argument("seed", LongArgumentType.longArg())
                                                        .executes(ctx -> create(ctx,
                                                                StringArgumentType.getString(ctx, "environment"),
                                                                StringArgumentType.getString(ctx, "generator"),
                                                                LongArgumentType.getLong(ctx, "seed"))))))))
                .then(Commands.literal("modify").requires(admin)
                        .then(Commands.argument("world", StringArgumentType.word())
                                .then(Commands.argument("property", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(MvCommand::modify)))))
                .then(Commands.literal("rules").requires(admin)
                        .executes(MvCommand::rules)
                        .then(Commands.literal("exempt")
                                .then(Commands.argument("rule", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.word())
                                                .executes(MvCommand::rulesExempt))))
                        .then(Commands.literal("level")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 4))
                                        .executes(MvCommand::rulesLevel)))));
    }

    // ------------------------------------------------------------------ menu

    /** {@code /mv rules} - shows the world-admin exemption settings. */
    private static int rules(CommandContext<CommandSourceStack> ctx) {
        if (!requireAdmin(ctx)) {
            return 0;
        }
        var config = WorldPropertyListener.config();
        StringBuilder sb = new StringBuilder("§e§l世界规则豁免（op " + config.exemptOpLevel + " 及以上）");
        sb.append("\n §7- §e强制模式不受影响: §3").append(config.exemptGameMode ? "是" : "否");
        sb.append("\n §7- §e强制飞行不受影响: §3").append(config.exemptFlight ? "是" : "否");
        sb.append("\n §7- §e强制饥饿不受影响: §3").append(config.exemptHunger ? "是" : "否");
        sb.append("\n §7- §e自动回血不受影响: §3").append(config.exemptAutoHeal ? "是" : "否");
        sb.append("\n §7- §e禁 PvP 不受影响: §3").append(config.exemptPvp ? "是" : "否");
        sb.append("\n§7修改: §f/mv rules exempt <gamemode|flight|hunger|autoheal|pvp|all> <on|off>");
        sb.append("\n§7豁免等级: §f/mv rules level <0-4>");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int rulesExempt(CommandContext<CommandSourceStack> ctx) {
        if (!requireAdmin(ctx)) {
            return 0;
        }
        String rule = StringArgumentType.getString(ctx, "rule").toLowerCase(java.util.Locale.ROOT);
        Boolean value = com.oktsas.onekeytosetaserver.domain.DomainManager
                .parseToggle(StringArgumentType.getString(ctx, "value"));
        if (value == null) {
            ctx.getSource().sendFailure(Component.literal("§c值必须是 on 或 off"));
            return 0;
        }
        var config = WorldPropertyListener.config();
        boolean all = rule.equals("all") || rule.equals("全部");
        boolean known = true;
        switch (rule) {
            case "gamemode", "mode", "模式" -> config.exemptGameMode = value;
            case "flight", "fly", "飞行" -> config.exemptFlight = value;
            case "hunger", "饥饿" -> config.exemptHunger = value;
            case "autoheal", "heal", "回血" -> config.exemptAutoHeal = value;
            case "pvp", "对战" -> config.exemptPvp = value;
            default -> known = all;
        }
        if (all) {
            config.exemptGameMode = value;
            config.exemptFlight = value;
            config.exemptHunger = value;
            config.exemptAutoHeal = value;
            config.exemptPvp = value;
        } else if (!known) {
            ctx.getSource().sendFailure(Component.literal(
                    "§c未知的规则，可用: gamemode / flight / hunger / autoheal / pvp / all"));
            return 0;
        }
        WorldPropertyListener.saveConfig();
        ctx.getSource().sendSuccess(() -> Component.literal("§a已把 §f" + rule + " §a的豁免设为 §f"
                + (value ? "on（管理员不受影响）" : "off（管理员也受世界规则约束）")), true);
        return 1;
    }

    private static int rulesLevel(CommandContext<CommandSourceStack> ctx) {
        if (!requireAdmin(ctx)) {
            return 0;
        }
        int level = IntegerArgumentType.getInteger(ctx, "value");
        WorldPropertyListener.config().exemptOpLevel = level;
        WorldPropertyListener.saveConfig();
        ctx.getSource().sendSuccess(() -> Component.literal("§a豁免等级已设为 op " + level), true);
        return 1;
    }

    // ------------------------------------------------------------------ travel

    private static int list(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        StringBuilder sb = new StringBuilder("§e§l世界列表§r (" + MvWorldManager.all().size() + " 个)");
        for (MvWorld world : MvWorldManager.all()) {
            ServerLevel level = MvWorldManager.levelOf(world);
            if (world.hidden && !ctx.getSource().hasPermission(3)) {
                continue;
            }
            sb.append("\n §7- §f").append(world.displayName())
                    .append(" §8[").append(OneKeyToSetAServer.MODID).append(':').append(world.dimensionPath).append("]")
                    .append(level == null ? " §c未加载" : " §a已加载")
                    .append(level == null ? "" : " §7玩家: " + level.players().size());
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        MvWorld world = MvWorldManager.byName(StringArgumentType.getString(ctx, "world"));
        if (world == null) {
            ctx.getSource().sendFailure(Component.literal("找不到世界"));
            return 0;
        }
        ServerLevel level = MvWorldManager.levelOf(world);
        Path path = ((MinecraftServerAccessor) ctx.getSource().getServer()).oktsas$storageSource()
                .getDimensionPath(MvWorldManager.dimensionKey(world));
        String msg = "§e§l世界 " + world.displayName() + "§r"
                + "\n §7维度: §f" + OneKeyToSetAServer.MODID + ":" + world.dimensionPath
                + "\n §7文件夹: §f" + world.folder
                + "\n §7环境/生成器: §f" + world.environment + " / " + world.generator
                + "\n §7种子: §f" + world.seed
                + "\n §7状态: " + (level == null ? "§c未加载" : "§a已加载")
                + "\n §7存档目录: §f" + path
                + "\n §7PvP/游戏模式/飞行/饥饿/自愈: §f" + world.pvp + " / " + world.gameMode + " / "
                + world.allowFlight + " / " + world.hunger + " / " + world.autoHeal
                + "\n §7边界/人数上限/缩放: §f" + world.borderSize + " / " + world.playerLimit + " / " + world.scale
                + "\n §7独立背包: §f" + world.separateInventory
                + "\n §7自动重置: §f" + world.autoReset + "（每 " + world.autoResetHours + " 小时，新种子="
                + world.autoResetNewSeed + "，清数据=" + world.autoResetClearData + "）";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int tp(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MvWorld world = MvWorldManager.byName(StringArgumentType.getString(ctx, "world"));
        if (world == null) {
            ctx.getSource().sendFailure(Component.literal("找不到世界"));
            return 0;
        }
        if (world.hidden && !ctx.getSource().hasPermission(3)) {
            ctx.getSource().sendFailure(Component.literal("§c你没有权限进入该世界"));
            return 0;
        }
        ServerLevel level = MvWorldManager.levelOf(world);
        if (level == null) {
            // Everyone may travel with /mv tp, so the world is loaded on demand instead of asking an admin.
            MvWorldManager.loadRuntime(world);
            level = MvWorldManager.levelOf(world);
        }
        if (level == null) {
            ctx.getSource().sendFailure(Component.literal("§c世界加载失败，请稍后再试或联系管理员"));
            return 0;
        }
        if (!WorldPropertyListener.canEnter(player, world)) {
            return 0;
        }
        double x = world.spawnX != null ? world.spawnX : 0.5D;
        double y = world.spawnY != null ? world.spawnY : -1.0D;
        double z = world.spawnZ != null ? world.spawnZ : 0.5D;
        MvTeleporter.teleportPlayer(player, level, x, y, z,
                world.spawnYaw == null ? player.getYRot() : world.spawnYaw,
                world.spawnPitch == null ? player.getXRot() : world.spawnPitch);
        ctx.getSource().sendSuccess(() -> Component.literal("§a正在传送至 " + world.displayName()), false);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MvWorld world = WorldPropertyListener.worldOf(player.level());
        ServerLevel level = player.serverLevel();
        if (world != null && MvWorldManager.levelOf(world) != null) {
            level = MvWorldManager.levelOf(world);
        }
        // the world's own spawn point when /mv setspawn was used, otherwise the surface of 0/0
        double[] spawn = MvWorldManager.spawnOf(world);
        MvTeleporter.teleportPlayer(player, level, spawn[0], spawn[1], spawn[2],
                player.getYRot(), player.getXRot());
        ctx.getSource().sendSuccess(() -> Component.literal("§a已传送回本世界出生点"), false);
        return 1;
    }

    private static int setspawn(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!requireAdmin(ctx)) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MvWorld world = WorldPropertyListener.worldOf(player.level());
        if (world == null) {
            ctx.getSource().sendFailure(Component.literal("当前世界不受本 mod 管理"));
            return 0;
        }
        world.setSpawn(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        world.save(MvWorldManager.folderOf(world));
        ServerLevel level = player.serverLevel();
        level.setDefaultSpawnPos(player.blockPosition(), player.getYRot());
        ctx.getSource().sendSuccess(() -> Component.literal("§a已将 " + world.displayName() + " 的出生点设在这里"), true);
        return 1;
    }

    // ------------------------------------------------------------------ management

    private static int load(CommandContext<CommandSourceStack> ctx) {
        if (!requireAdmin(ctx)) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "world");
        MvWorld world = MvWorldManager.byName(name);
        if (world == null) {
            Path folder = MvWorldManager.gameDir() == null ? null : MvWorldManager.gameDir().resolve(name);
            if (folder == null || !Files.isDirectory(folder)) {
                ctx.getSource().sendFailure(Component.literal("服务器根目录下没有这个世界文件夹: " + name));
                return 0;
            }
            world = MvWorldManager.create(name, "NORMAL", 0L, "DEFAULT");
            if (world == null) {
                ctx.getSource().sendFailure(Component.literal("加载失败，请看日志"));
                return 0;
            }
        }
        boolean ok = MvWorldManager.loadRuntime(world);
        if (ok) {
            MvWorld finalWorld = world;
            ctx.getSource().sendSuccess(() -> Component.literal("§a已加载世界 " + finalWorld.displayName()), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("加载失败，请看日志"));
        return 0;
    }

    private static int unload(CommandContext<CommandSourceStack> ctx) {
        if (!requireAdmin(ctx)) {
            return 0;
        }
        MvWorld world = MvWorldManager.byName(StringArgumentType.getString(ctx, "world"));
        if (world == null) {
            ctx.getSource().sendFailure(Component.literal("找不到世界"));
            return 0;
        }
        if (MvWorldManager.unload(world)) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a已卸载世界 " + world.displayName()), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("卸载失败（世界内有玩家或未加载）"));
        return 0;
    }

    private static int create(CommandContext<CommandSourceStack> ctx, String environment, String generator, long seed) {
        if (!requireAdmin(ctx)) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        MvWorld world = MvWorldManager.create(name, environment, seed, generator);
        if (world == null) {
            ctx.getSource().sendFailure(Component.literal("创建失败（同名文件夹已存在或见日志）"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a已创建并加载世界 " + world.displayName()
                + "（" + world.environment + " / " + world.generator + "）"), true);
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) {
        if (!requireAdmin(ctx)) {
            return 0;
        }
        MvWorld world = MvWorldManager.byName(StringArgumentType.getString(ctx, "world"));
        if (world == null) {
            ctx.getSource().sendFailure(Component.literal("找不到世界"));
            return 0;
        }
        if (MvWorldManager.delete(world)) {
            ctx.getSource().sendSuccess(() -> Component.literal("§c已删除世界 " + world.displayName()), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("删除失败（可能仍有玩家在里面）"));
        return 0;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        if (!requireAdmin(ctx)) {
            return 0;
        }
        MvWorld world = MvWorldManager.byName(StringArgumentType.getString(ctx, "world"));
        if (world == null) {
            ctx.getSource().sendFailure(Component.literal("找不到世界"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§e正在重置 " + world.displayName() + "…"), true);
        boolean ok = MvWorldManager.reset(world);
        ctx.getSource().sendSuccess(() -> Component.literal(ok ? "§a重置完成" : "§c重置失败，请看日志"), true);
        return ok ? 1 : 0;
    }

    private static int save(CommandContext<CommandSourceStack> ctx) {
        if (!requireAdmin(ctx)) {
            return 0;
        }
        MvWorldManager.saveAll();
        ctx.getSource().sendSuccess(() -> Component.literal("§a已保存所有世界设置"), true);
        return 1;
    }

    private static int modify(CommandContext<CommandSourceStack> ctx) {
        if (!requireAdmin(ctx)) {
            return 0;
        }
        MvWorld world = MvWorldManager.byName(StringArgumentType.getString(ctx, "world"));
        if (world == null) {
            ctx.getSource().sendFailure(Component.literal("找不到世界"));
            return 0;
        }
        String property = StringArgumentType.getString(ctx, "property").toLowerCase(Locale.ROOT);
        String value = StringArgumentType.getString(ctx, "value").trim();
        try {
            switch (property) {
                case "pvp" -> world.pvp = parseBool(value);
                case "gamemode", "gameMode" -> world.gameMode = value.toUpperCase(Locale.ROOT);
                case "allowflight" -> world.allowFlight = parseBool(value);
                case "hunger" -> world.hunger = parseBool(value);
                case "autoheal" -> world.autoHeal = parseBool(value);
                case "border" -> world.borderSize = Double.parseDouble(value);
                case "playerlimit" -> world.playerLimit = Integer.parseInt(value);
                case "alias" -> world.alias = value.equalsIgnoreCase("none") ? "" : value;
                case "hidden" -> world.hidden = parseBool(value);
                case "scale" -> world.scale = Double.parseDouble(value);
                case "respawnworld" -> world.respawnWorld = value.equalsIgnoreCase("none") ? "" : value;
                case "autoload" -> world.autoLoad = parseBool(value);
                case "keepspawninmemory" -> world.keepSpawnInMemory = parseBool(value);
                case "bedrespawn" -> world.bedRespawn = parseBool(value);
                case "separateinventory" -> world.separateInventory = parseBool(value);
                case "autoreset" -> world.autoReset = parseBool(value);
                case "autoresethours" -> world.autoResetHours = Integer.parseInt(value);
                case "newseedonreset" -> world.autoResetNewSeed = parseBool(value);
                case "cleardataonreset" -> world.autoResetClearData = parseBool(value);
                case "blacklist" -> {
                    world.worldBlacklist.clear();
                    if (!value.equalsIgnoreCase("none")) {
                        for (String part : value.split(",")) {
                            if (!part.isBlank()) {
                                world.worldBlacklist.add(part.trim());
                            }
                        }
                    }
                }
                default -> {
                    ctx.getSource().sendFailure(Component.literal("不支持的属性: " + property
                            + "\n可用: pvp, gamemode, allowFlight, hunger, autoHeal, border, playerLimit, alias, hidden,"
                            + " scale, respawnWorld, autoLoad, keepSpawnInMemory, bedRespawn, autoReset, autoResetHours,"
                            + " newSeedOnReset, clearDataOnReset, blacklist"));
                    return 0;
                }
            }
            world.save(MvWorldManager.folderOf(world));
            ServerLevel level = MvWorldManager.levelOf(world);
            if (level != null) {
                RuntimeDimensionLoader.applyWorldRules(level, world);
            }
            ctx.getSource().sendSuccess(() -> Component.literal("§a" + world.displayName() + " 的 " + property
                    + " 已设为 " + value), true);
            return 1;
        } catch (NumberFormatException e) {
            ctx.getSource().sendFailure(Component.literal("数值格式不对: " + value));
            return 0;
        }
    }

    // ------------------------------------------------------------------ inventory repair

    /**
     * {@code /mv inventory recover <player>} (op 4): hands everything stored in that player's inventory pools
     * back through the mailbox and renames the pool files, so items that got stuck in a pool (the 0.2.3
     * accessory loss) can be returned without editing NBT by hand.
     */
    private static int inventoryRecover(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "player");
        UUID owner = resolvePlayer(ctx, name);
        if (owner == null) {
            return 0;
        }
        MinecraftServer server = ctx.getSource().getServer();
        int mailed = com.oktsas.onekeytosetaserver.inventory.PlayerInventoryStore.recover(owner, server);
        if (mailed == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("§e没有可恢复的物品（该玩家没有池文件，"
                    + "或文件已经处理过）"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a已把 §f" + mailed
                + " §a件物品发到 " + name + " 的邮箱（原文件已改名为 .recovered）。"
                + "\n§7玩家输入 §f/mail §7领取，或下次登录自动到账。"), true);
        return mailed;
    }

    /** {@code /mv inventory inspect <player>} (op 4): read-only report of that player's pool files. */
    private static int inventoryInspect(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "player");
        UUID owner = resolvePlayer(ctx, name);
        if (owner == null) {
            return 0;
        }
        String report = com.oktsas.onekeytosetaserver.inventory.PlayerInventoryStore.describePools(owner);
        ctx.getSource().sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    /** Online player first, then the server's name cache (usercache.json), then a raw UUID. */
    private static UUID resolvePlayer(CommandContext<CommandSourceStack> ctx, String name) {
        MinecraftServer server = ctx.getSource().getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.getUUID();
        }
        var cached = server.getProfileCache();
        if (cached != null) {
            java.util.Optional<com.mojang.authlib.GameProfile> profile = cached.get(name);
            if (profile.isPresent() && profile.get().getId() != null) {
                return profile.get().getId();
            }
        }
        try {
            return UUID.fromString(name);
        } catch (Throwable ignored) {
            ctx.getSource().sendFailure(Component.literal("§c找不到玩家 §f" + name
                    + "§c（需要他至少进过一次服务器，或直接给出 UUID）"));
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean parseBool(String value) {
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on")
                || value.equalsIgnoreCase("yes") || value.equals("1");
    }

    private static boolean requireAdmin(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().hasPermission(3)) {
            return true;
        }
        ctx.getSource().sendFailure(Component.literal("需要 OP 等级 3（世界管理员）"));
        return false;
    }
}
