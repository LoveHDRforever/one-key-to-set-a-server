package com.oktsas.onekeytosetaserver.essentials;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.economy.MoneyService;
import com.oktsas.onekeytosetaserver.teleport.MvTeleporter;
import com.oktsas.onekeytosetaserver.teleport.TeleportService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The EssentialsX style commands the owner asked for (A tier plus B tier plus {@code /mute}).
 *
 * <p>Every root command is registered through {@link #root}, which refuses to register a name that already
 * exists on the server - that is the "注意冲突项" requirement: a duplicate Brigadier root would throw at
 * startup, and this way a clash with another mod only costs us that one command and a log line.</p>
 */
public final class EssentialsCommands {

    /** Cooldown bookkeeping, in memory only. */
    private static final Map<String, Long> COOLDOWN = new LinkedHashMap<>();
    /** Open teleport requests, keyed by the target player. */
    private static final Map<UUID, Tpa> REQUESTS = new LinkedHashMap<>();
    /** Who each player last whispered to, for {@code /r}. */
    private static final Map<UUID, UUID> REPLY_TO = new LinkedHashMap<>();
    /** Players with {@code /socialspy} on. */
    private static final java.util.Set<UUID> SOCIAL_SPY = new java.util.HashSet<>();

    private EssentialsCommands() {
    }

    private record Tpa(UUID from, String fromName, UUID to, boolean here, long expiresAt) {
    }

    // ------------------------------------------------------------------ registration

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        root(dispatcher, Commands.literal("spawn").executes(EssentialsCommands::spawn));
        root(dispatcher, Commands.literal("setspawn").requires(admin()).executes(EssentialsCommands::setSpawn));

        root(dispatcher, Commands.literal("warp")
                .executes(EssentialsCommands::warpList)
                .then(Commands.argument("名字", StringArgumentType.greedyString()).executes(EssentialsCommands::warp)));
        root(dispatcher, Commands.literal("warps").executes(EssentialsCommands::warpList));
        root(dispatcher, Commands.literal("setwarp").requires(admin())
                .then(Commands.argument("名字", StringArgumentType.greedyString()).executes(EssentialsCommands::setWarp)));
        root(dispatcher, Commands.literal("delwarp").requires(admin())
                .then(Commands.argument("名字", StringArgumentType.greedyString()).executes(EssentialsCommands::delWarp)));

        root(dispatcher, Commands.literal("home")
                .executes(ctx -> home(ctx, null))
                .then(Commands.argument("名字", StringArgumentType.greedyString())
                        .executes(ctx -> home(ctx, StringArgumentType.getString(ctx, "名字")))));
        root(dispatcher, Commands.literal("sethome")
                .executes(ctx -> setHome(ctx, null))
                .then(Commands.argument("名字", StringArgumentType.greedyString())
                        .executes(ctx -> setHome(ctx, StringArgumentType.getString(ctx, "名字")))));
        root(dispatcher, Commands.literal("delhome")
                .then(Commands.argument("名字", StringArgumentType.greedyString()).executes(EssentialsCommands::delHome)));
        root(dispatcher, Commands.literal("homes").executes(EssentialsCommands::homes));

        root(dispatcher, Commands.literal("tpa")
                .then(Commands.argument("玩家", StringArgumentType.word()).executes(ctx -> tpa(ctx, false))));
        root(dispatcher, Commands.literal("tpahere")
                .then(Commands.argument("玩家", StringArgumentType.word()).executes(ctx -> tpa(ctx, true))));
        root(dispatcher, Commands.literal("tpaccept")
                .executes(ctx -> tpAccept(ctx, null))
                .then(Commands.argument("玩家", StringArgumentType.word())
                        .executes(ctx -> tpAccept(ctx, StringArgumentType.getString(ctx, "玩家")))));
        root(dispatcher, Commands.literal("tpdeny")
                .executes(ctx -> tpDeny(ctx, null))
                .then(Commands.argument("玩家", StringArgumentType.word())
                        .executes(ctx -> tpDeny(ctx, StringArgumentType.getString(ctx, "玩家")))));
        root(dispatcher, Commands.literal("tpcancel").executes(EssentialsCommands::tpCancel));
        root(dispatcher, Commands.literal("tptoggle").executes(EssentialsCommands::tpToggle));
        root(dispatcher, Commands.literal("back").executes(EssentialsCommands::back));

        root(dispatcher, Commands.literal("tppos").requires(admin())
                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                        .executes(EssentialsCommands::tppos)))));
        root(dispatcher, Commands.literal("tphere").requires(admin())
                .then(Commands.argument("玩家", StringArgumentType.word()).executes(EssentialsCommands::tphere)));
        root(dispatcher, Commands.literal("tpall").requires(admin()).executes(EssentialsCommands::tpall));

        root(dispatcher, Commands.literal("near")
                .executes(ctx -> near(ctx, 200))
                .then(Commands.argument("半径", IntegerArgumentType.integer(1, 10000))
                        .executes(ctx -> near(ctx, IntegerArgumentType.getInteger(ctx, "半径")))));
        root(dispatcher, Commands.literal("who").executes(EssentialsCommands::who));
        root(dispatcher, Commands.literal("seen")
                .then(Commands.argument("玩家", StringArgumentType.word()).executes(EssentialsCommands::seen)));
        root(dispatcher, Commands.literal("afk")
                .executes(ctx -> afk(ctx, null))
                .then(Commands.argument("原因", StringArgumentType.greedyString())
                        .executes(ctx -> afk(ctx, StringArgumentType.getString(ctx, "原因")))));
        root(dispatcher, Commands.literal("r")
                .then(Commands.argument("消息", StringArgumentType.greedyString())
                        .executes(EssentialsCommands::reply)));

        root(dispatcher, Commands.literal("ping").executes(ctx -> ping(ctx, null))
                .then(Commands.argument("玩家", StringArgumentType.word())
                        .executes(ctx -> ping(ctx, StringArgumentType.getString(ctx, "玩家")))));
        root(dispatcher, Commands.literal("getpos").executes(EssentialsCommands::getpos));
        root(dispatcher, Commands.literal("compass").executes(EssentialsCommands::compass));
        root(dispatcher, Commands.literal("depth").executes(EssentialsCommands::depth));
        root(dispatcher, Commands.literal("suicide").executes(EssentialsCommands::suicide));

        // ---- B tier -----------------------------------------------------------------
        root(dispatcher, Commands.literal("god").requires(admin())
                .executes(ctx -> god(ctx, null))
                .then(Commands.argument("玩家", StringArgumentType.word())
                        .executes(ctx -> god(ctx, StringArgumentType.getString(ctx, "玩家")))));
        root(dispatcher, Commands.literal("fly").requires(admin())
                .executes(ctx -> fly(ctx, null))
                .then(Commands.argument("玩家", StringArgumentType.word())
                        .executes(ctx -> fly(ctx, StringArgumentType.getString(ctx, "玩家")))));
        root(dispatcher, Commands.literal("heal")
                .executes(ctx -> heal(ctx, null))
                .then(Commands.argument("玩家", StringArgumentType.word()).requires(admin())
                        .executes(ctx -> heal(ctx, StringArgumentType.getString(ctx, "玩家")))));
        root(dispatcher, Commands.literal("feed")
                .executes(ctx -> feed(ctx, null))
                .then(Commands.argument("玩家", StringArgumentType.word()).requires(admin())
                        .executes(ctx -> feed(ctx, StringArgumentType.getString(ctx, "玩家")))));
        root(dispatcher, Commands.literal("repair")
                .executes(ctx -> repair(ctx, false))
                .then(Commands.literal("all").executes(ctx -> repair(ctx, true))));
        root(dispatcher, Commands.literal("hat").executes(EssentialsCommands::hat));
        root(dispatcher, Commands.literal("more").requires(admin()).executes(EssentialsCommands::more));
        root(dispatcher, Commands.literal("workbench").executes(ctx -> openMenu(ctx, MenuKind.WORKBENCH)));
        root(dispatcher, Commands.literal("anvil").executes(ctx -> openMenu(ctx, MenuKind.ANVIL)));
        root(dispatcher, Commands.literal("grindstone").executes(ctx -> openMenu(ctx, MenuKind.GRINDSTONE)));
        root(dispatcher, Commands.literal("loom").executes(ctx -> openMenu(ctx, MenuKind.LOOM)));
        root(dispatcher, Commands.literal("smithingtable").executes(ctx -> openMenu(ctx, MenuKind.SMITHING)));
        root(dispatcher, Commands.literal("stonecutter").executes(ctx -> openMenu(ctx, MenuKind.STONECUTTER)));
        root(dispatcher, Commands.literal("cartographytable").executes(ctx -> openMenu(ctx, MenuKind.CARTOGRAPHY)));
        root(dispatcher, Commands.literal("enderchest").executes(EssentialsCommands::enderChest));
        root(dispatcher, Commands.literal("ec").executes(EssentialsCommands::enderChest));
        root(dispatcher, Commands.literal("invsee").requires(admin())
                .then(Commands.argument("玩家", StringArgumentType.word()).executes(EssentialsCommands::invsee)));
        root(dispatcher, Commands.literal("speed").requires(admin())
                .then(Commands.argument("速度", IntegerArgumentType.integer(0, 10))
                        .executes(ctx -> speed(ctx, null))
                        .then(Commands.argument("玩家", StringArgumentType.word())
                                .executes(ctx -> speed(ctx, StringArgumentType.getString(ctx, "玩家"))))));
        root(dispatcher, Commands.literal("vanish").requires(admin()).executes(EssentialsCommands::vanish));
        root(dispatcher, Commands.literal("broadcast").requires(admin())
                .then(Commands.argument("消息", StringArgumentType.greedyString())
                        .executes(EssentialsCommands::broadcast)));
        root(dispatcher, Commands.literal("socialspy").requires(admin()).executes(EssentialsCommands::socialSpy));
        root(dispatcher, Commands.literal("ignore")
                .then(Commands.argument("玩家", StringArgumentType.word()).executes(EssentialsCommands::ignore)));
        root(dispatcher, Commands.literal("rtp").executes(EssentialsCommands::rtp));
        root(dispatcher, Commands.literal("top").executes(EssentialsCommands::top));
        root(dispatcher, Commands.literal("jump").executes(EssentialsCommands::jump));
        root(dispatcher, Commands.literal("clearinventory")
                .executes(ctx -> clearInventory(ctx, null))
                .then(Commands.argument("玩家", StringArgumentType.word()).requires(admin())
                        .executes(ctx -> clearInventory(ctx, StringArgumentType.getString(ctx, "玩家")))));
        root(dispatcher, Commands.literal("skull").requires(admin())
                .executes(ctx -> skull(ctx, null))
                .then(Commands.argument("玩家", StringArgumentType.word())
                        .executes(ctx -> skull(ctx, StringArgumentType.getString(ctx, "玩家")))));
        root(dispatcher, Commands.literal("firework").requires(admin()).executes(EssentialsCommands::firework));
        root(dispatcher, Commands.literal("lightning").requires(admin())
                .executes(ctx -> lightning(ctx, null))
                .then(Commands.argument("玩家", StringArgumentType.word())
                        .executes(ctx -> lightning(ctx, StringArgumentType.getString(ctx, "玩家")))));
        root(dispatcher, Commands.literal("exp").requires(admin())
                .then(Commands.argument("数量", IntegerArgumentType.integer(-100000, 100000))
                        .executes(ctx -> exp(ctx, null))
                        .then(Commands.argument("玩家", StringArgumentType.word())
                                .executes(ctx -> exp(ctx, StringArgumentType.getString(ctx, "玩家"))))));
        root(dispatcher, Commands.literal("nick").requires(source -> source.hasPermission(4))
                .executes(EssentialsCommands::nickClear)
                .then(Commands.argument("昵称", StringArgumentType.greedyString())
                        .executes(EssentialsCommands::nick)));
        root(dispatcher, Commands.literal("powertool").requires(admin())
                .executes(ctx -> powertool(ctx, null))
                .then(Commands.argument("指令", StringArgumentType.greedyString())
                        .executes(ctx -> powertool(ctx, StringArgumentType.getString(ctx, "指令")))));

        // ---- mute -------------------------------------------------------------------
        root(dispatcher, Commands.literal("mute").requires(admin())
                .then(Commands.argument("玩家", StringArgumentType.word())
                        .executes(ctx -> mute(ctx, "perm", ""))
                        .then(Commands.argument("时长", StringArgumentType.word())
                                .executes(ctx -> mute(ctx, StringArgumentType.getString(ctx, "时长"), ""))
                                .then(Commands.argument("原因", StringArgumentType.greedyString())
                                        .executes(ctx -> mute(ctx,
                                                StringArgumentType.getString(ctx, "时长"),
                                                StringArgumentType.getString(ctx, "原因")))))));
        root(dispatcher, Commands.literal("unmute").requires(admin())
                .then(Commands.argument("玩家", StringArgumentType.word()).executes(EssentialsCommands::unmute)));
        root(dispatcher, Commands.literal("mutelist").requires(admin()).executes(EssentialsCommands::muteList));

        // ---- config -----------------------------------------------------------------
        root(dispatcher, Commands.literal("ess")
                .executes(EssentialsCommands::essHelp)
                .then(Commands.literal("help").executes(EssentialsCommands::essHelp))
                .then(Commands.literal("fees").requires(admin())
                        .then(Commands.argument("on|off", StringArgumentType.word())
                                .executes(EssentialsCommands::essFees)))
                .then(Commands.literal("fee").requires(admin())
                        .then(Commands.argument("项目", StringArgumentType.word())
                                .then(Commands.argument("价格", DoubleArgumentType.doubleArg(0.0D))
                                        .executes(EssentialsCommands::essFee))))
                .then(Commands.literal("delay").requires(admin())
                        .then(Commands.argument("秒", IntegerArgumentType.integer(0, 60))
                                .executes(EssentialsCommands::essDelay)))
                .then(Commands.literal("homes").requires(admin())
                        .then(Commands.argument("数量", IntegerArgumentType.integer(1, 100))
                                .executes(EssentialsCommands::essHomes)))
                .then(Commands.literal("main").requires(source -> source.hasPermission(4))
                        .then(Commands.argument("世界", StringArgumentType.greedyString())
                                .executes(EssentialsCommands::essMain)))
                .then(Commands.literal("reload").requires(admin()).executes(EssentialsCommands::essReload))
                .then(Commands.literal("save").requires(admin()).executes(EssentialsCommands::essSave)));
    }

    /** Registers a root command unless the name is taken (another mod or vanilla). */
    private static void root(CommandDispatcher<CommandSourceStack> dispatcher,
                             com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> literal) {
        String name = literal.getLiteral();
        boolean taken = dispatcher.getRoot().getChildren().stream()
                .anyMatch(node -> name.equalsIgnoreCase(node.getName()));
        if (taken) {
            OneKeyToSetAServer.LOGGER.warn("[Ess] /{} is already registered on this server, skipping ours", name);
            return;
        }
        dispatcher.register(literal);
    }

    private static java.util.function.Predicate<CommandSourceStack> admin() {
        return source -> source.hasPermission(EssentialsData.config().adminLevel);
    }

    // ------------------------------------------------------------------ helpers

    private static ServerPlayer self(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
        }
        return player;
    }

    private static ServerPlayer target(CommandContext<CommandSourceStack> ctx, String name) {
        if (name == null) {
            return ctx.getSource().getPlayer();
        }
        return ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
    }

    /**
     * Queues a delayed teleport and charges the configured fee when it actually fires, so a cancelled
     * teleport costs nothing.
     */
    private static void travelTo(ServerPlayer player, String label, String feeKey, ServerLevel level,
                                 double x, double y, double z, float yaw, float pitch) {
        EssentialsData.Config config = EssentialsData.config();
        TeleportService.request(player, config.teleportDelaySeconds, label,
                config.interruptOnJump, config.interruptOnHurt, () -> {
                    if (!charge(player, EssentialsData.fee(feeKey))) {
                        return;
                    }
                    MvTeleporter.teleportPlayer(player, level, x, y, z, yaw, pitch);
                    player.sendSystemMessage(Component.literal("§e已传送到 §f" + label));
                });
    }

    private static boolean charge(ServerPlayer player, double fee) {
        if (fee <= 0.0D || !EssentialsData.config().teleportFeesEnabled) {
            return true;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return true;
        }
        if (MoneyService.get(server, player.getUUID()) < fee) {
            player.sendSystemMessage(Component.literal("§c你没有足够的金钱。（需要 "
                    + MoneyService.format(fee) + "）"));
            return false;
        }
        return MoneyService.withdraw(server, player.getUUID(), fee);
    }

    private static boolean onCooldown(ServerPlayer player, String key, int seconds) {
        if (seconds <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        String mapKey = player.getUUID() + "|" + key;
        Long until = COOLDOWN.get(mapKey);
        if (until != null && now < until) {
            player.sendSystemMessage(Component.literal("§e传送太频繁, 请等待 §6"
                    + ((until - now) / 1000L + 1L) + " §e秒后重试。"));
            return true;
        }
        COOLDOWN.put(mapKey, now + seconds * 1000L);
        return false;
    }

    /** Records where the player stands now so {@code /back} can return there. */
    private static void rememberBack(ServerPlayer player, String label) {
        EssentialsData.pushBack(player.getUUID(), EssentialsData.Point.of(player, label));
    }

    private static Component clickable(String text, String command, String hover) {
        return Component.literal(text).withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    // ------------------------------------------------------------------ travel

    private static int spawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (onCooldown(player, "spawn", 3)) {
            return 0;
        }
        ServerLevel level = EssentialsData.mainLevel(ctx.getSource().getServer());
        if (level == null) {
            ctx.getSource().sendFailure(Component.literal("§c主城世界未加载"));
            return 0;
        }
        EssentialsData.Point stored = EssentialsData.spawn();
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        if (stored != null && stored.world.equals(level.dimension().location().toString())) {
            x = stored.x;
            y = stored.y;
            z = stored.z;
            yaw = stored.yaw;
            pitch = stored.pitch;
        } else {
            BlockPos shared = level.getSharedSpawnPos();
            x = shared.getX() + 0.5D;
            y = shared.getY();
            z = shared.getZ() + 0.5D;
            yaw = level.getSharedSpawnAngle();
            pitch = 0.0F;
        }
        rememberBack(player, "传送前的位置");
        travelTo(player, "主城出生点", "spawn", level, x, y, z, yaw, pitch);
        return 1;
    }

    private static int setSpawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        EssentialsData.setSpawn(EssentialsData.Point.of(player, "主城出生点"));
        ctx.getSource().sendSuccess(() -> Component.literal("§a已把主城出生点设在这里"), true);
        return 1;
    }

    private static int warpList(CommandContext<CommandSourceStack> ctx) {
        Map<String, EssentialsData.Point> warps = EssentialsData.warps();
        if (warps.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§e还没有地标，管理员可用 /setwarp <名字> 添加"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§e§l地标§r §7（点击传送，共 " + warps.size() + " 个）"), false);
        for (Map.Entry<String, EssentialsData.Point> entry : warps.entrySet()) {
            EssentialsData.Point point = entry.getValue();
            String name = entry.getKey();
            String world = point.world;
            ctx.getSource().sendSuccess(() -> Component.literal(" §7- ")
                    .append(clickable("§6" + name, "/warp " + name, "§7点击传送到 " + name))
                    .append(Component.literal(" §7" + world)), false);
        }
        return warps.size();
    }

    private static int warp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "名字");
        EssentialsData.Point point = EssentialsData.warp(name);
        if (point == null) {
            ctx.getSource().sendFailure(Component.literal("§c没有这个地标"));
            return 0;
        }
        if (onCooldown(player, "warp", EssentialsData.config().warpCooldownSeconds)) {
            return 0;
        }
        ServerLevel level = EssentialsData.levelOf(ctx.getSource().getServer(), point);
        if (level == null) {
            ctx.getSource().sendFailure(Component.literal("§c地标所在的世界未加载"));
            return 0;
        }
        rememberBack(player, "传送前的位置");
        travelTo(player, "地标 " + name, "warp", level, point.x, point.y, point.z, point.yaw, point.pitch);
        return 1;
    }

    private static int setWarp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "名字");
        EssentialsData.Point existing = EssentialsData.warp(name);
        EssentialsData.setWarp(name, EssentialsData.Point.of(player, name));
        ctx.getSource().sendSuccess(() -> Component.literal("§a地标 " + name
                + (existing == null ? " 已创建" : " 已更新")), true);
        return 1;
    }

    private static int delWarp(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "名字");
        if (!EssentialsData.removeWarp(name)) {
            ctx.getSource().sendFailure(Component.literal("§c没有这个地标"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§e已删除地标 " + name), true);
        return 1;
    }

    private static final String DEFAULT_HOME = "home";

    private static int home(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        EssentialsData.PlayerData data = EssentialsData.of(player.getUUID());
        String key = name == null || name.isBlank() ? DEFAULT_HOME : name.toLowerCase(Locale.ROOT);
        EssentialsData.Point point = data.homes.get(key);
        if (point == null) {
            ctx.getSource().sendFailure(Component.literal(data.homes.isEmpty()
                    ? "§c你还没有家，用 /sethome 设置一个"
                    : "§c没有叫 " + key + " 的家，用 /homes 看看"));
            return 0;
        }
        if (onCooldown(player, "home", EssentialsData.config().homeCooldownSeconds)) {
            return 0;
        }
        ServerLevel level = EssentialsData.levelOf(ctx.getSource().getServer(), point);
        if (level == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个家所在的世界已经没了（世界被重置或删除），无法传送"));
            return 0;
        }
        rememberBack(player, "传送前的位置");
        travelTo(player, "家 " + key, "home", level, point.x, point.y, point.z, point.yaw, point.pitch);
        return 1;
    }

    private static int setHome(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        EssentialsData.PlayerData data = EssentialsData.of(player.getUUID());
        String key = name == null || name.isBlank() ? DEFAULT_HOME : name.trim();
        if (key.length() > 24) {
            ctx.getSource().sendFailure(Component.literal("§c家的名字最多 24 个字"));
            return 0;
        }
        boolean exists = data.homes.containsKey(key.toLowerCase(Locale.ROOT));
        int limit = Math.max(1, EssentialsData.config().maxHomes);
        if (!exists && data.homes.size() >= limit) {
            ctx.getSource().sendFailure(Component.literal("§c你的家已经满了（上限 " + limit + " 个）"));
            return 0;
        }
        data.homes.put(key.toLowerCase(Locale.ROOT), EssentialsData.Point.of(player, key));
        EssentialsData.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a已把家 §6" + key + "§a设在这里（"
                + data.homes.size() + "/" + limit + "）"), true);
        return 1;
    }

    private static int delHome(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        EssentialsData.PlayerData data = EssentialsData.of(player.getUUID());
        String key = StringArgumentType.getString(ctx, "名字").toLowerCase(Locale.ROOT);
        if (data.homes.remove(key) == null) {
            ctx.getSource().sendFailure(Component.literal("§c没有这个家"));
            return 0;
        }
        EssentialsData.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§e已删除家 " + key), true);
        return 1;
    }

    private static int homes(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        EssentialsData.PlayerData data = EssentialsData.of(player.getUUID());
        if (data.homes.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§e你还没有家，用 /sethome [名字] 设置"), false);
            return 0;
        }
        int limit = Math.max(1, EssentialsData.config().maxHomes);
        ctx.getSource().sendSuccess(() -> Component.literal("§e§l我的家§r §7(" + data.homes.size() + "/" + limit + "，点击传送)"), false);
        for (Map.Entry<String, EssentialsData.Point> entry : data.homes.entrySet()) {
            EssentialsData.Point point = entry.getValue();
            boolean alive = EssentialsData.levelOf(ctx.getSource().getServer(), point) != null;
            String name = entry.getKey();
            ctx.getSource().sendSuccess(() -> Component.literal(" §7- ")
                    .append(alive
                            ? clickable("§6" + name, "/home " + name, "§7点击传送")
                            : Component.literal("§c" + name + " §8(失效)"))
                    .append(Component.literal(" §8" + point.world)), false);
        }
        return data.homes.size();
    }

    private static int back(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        EssentialsData.Point point = EssentialsData.popBack(player.getUUID());
        if (point == null) {
            ctx.getSource().sendFailure(Component.literal("§c没有可以回去的地方"));
            return 0;
        }
        ServerLevel level = EssentialsData.levelOf(ctx.getSource().getServer(), point);
        if (level == null) {
            ctx.getSource().sendFailure(Component.literal("§c那个世界未加载"));
            return 0;
        }
        travelTo(player, point.label == null || point.label.isBlank() ? "上一个位置" : point.label,
                "back", level, point.x, point.y, point.z, point.yaw, point.pitch);
        return 1;
    }

    private static int tppos(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        double x = DoubleArgumentType.getDouble(ctx, "x");
        double y = DoubleArgumentType.getDouble(ctx, "y");
        double z = DoubleArgumentType.getDouble(ctx, "z");
        ServerLevel level = player.serverLevel();
        rememberBack(player, "传送前的位置");
        travelTo(player, "坐标 " + (int) x + " " + (int) y + " " + (int) z, "tppos", level, x, y, z,
                player.getYRot(), player.getXRot());
        return 1;
    }

    private static int tphere(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer target = target(ctx, StringArgumentType.getString(ctx, "玩家"));
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        ServerPlayer self = ctx.getSource().getPlayer();
        if (self == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        rememberBack(target, "传送前的位置");
        MvTeleporter.teleportPlayer(target, self.serverLevel(), self.getX(), self.getY(), self.getZ(),
                self.getYRot(), self.getXRot());
        target.sendSystemMessage(Component.literal("§e你被传送到 §f" + self.getGameProfile().getName()));
        ctx.getSource().sendSuccess(() -> Component.literal("§a已把 " + target.getGameProfile().getName()
                + " 传到身边"), true);
        return 1;
    }

    private static int tpall(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        int moved = 0;
        for (ServerPlayer other : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            if (other == self) {
                continue;
            }
            rememberBack(other, "传送前的位置");
            MvTeleporter.teleportPlayer(other, self.serverLevel(), self.getX(), self.getY(), self.getZ(),
                    self.getYRot(), self.getXRot());
            other.sendSystemMessage(Component.literal("§e你被传送到 §f" + self.getGameProfile().getName()));
            moved++;
        }
        int count = moved;
        ctx.getSource().sendSuccess(() -> Component.literal("§a已把 " + count + " 个玩家传到身边"), true);
        return moved;
    }

    // ------------------------------------------------------------------ tpa

    private static int tpa(CommandContext<CommandSourceStack> ctx, boolean here) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerPlayer target = target(ctx, StringArgumentType.getString(ctx, "玩家"));
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        if (target == player) {
            ctx.getSource().sendFailure(Component.literal("§c不能请求传送到自己那里"));
            return 0;
        }
        EssentialsData.PlayerData data = EssentialsData.of(target.getUUID());
        if (data.tptoggle) {
            ctx.getSource().sendFailure(Component.literal("§c对方关闭了传送请求（/tptoggle）"));
            return 0;
        }
        if (onCooldown(player, "tpa", EssentialsData.config().tpaCooldownSeconds)) {
            return 0;
        }
        long expires = System.currentTimeMillis() + Math.max(10, EssentialsData.config().tpaTimeoutSeconds) * 1000L;
        REQUESTS.put(target.getUUID(), new Tpa(player.getUUID(), player.getGameProfile().getName(),
                target.getUUID(), here, expires));
        String who = player.getGameProfile().getName();
        target.sendSystemMessage(Component.literal("§e" + who + " 请求" + (here ? "把你传送到他那里" : "传送到你这里")
                + "。§a/tpaccept §7同意 §c/tpdeny §7拒绝"));
        target.sendSystemMessage(Component.literal("§7（" + EssentialsData.config().tpaTimeoutSeconds + " 秒内有效）")
                .append(Component.literal(" §a[同意]").withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaccept " + who))))
                .append(Component.literal(" §c[拒绝]").withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpdeny " + who)))));
        ctx.getSource().sendSuccess(() -> Component.literal("§a已向 " + target.getGameProfile().getName()
                + " 发送传送请求"), false);
        return 1;
    }

    private static int tpAccept(CommandContext<CommandSourceStack> ctx, String fromName) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Tpa request = takeRequest(player, fromName);
        if (request == null) {
            ctx.getSource().sendFailure(Component.literal("§c没有待处理的传送请求"));
            return 0;
        }
        ServerPlayer from = ctx.getSource().getServer().getPlayerList().getPlayer(request.from());
        if (from == null) {
            ctx.getSource().sendFailure(Component.literal("§c对方已经不在线了"));
            return 0;
        }
        double fee = EssentialsData.fee(request.here() ? "tpahere" : "tpa");
        if (!charge(from, fee)) {
            player.sendSystemMessage(Component.literal("§c对方的钱不够，传送取消"));
            return 0;
        }
        ServerPlayer mover = request.here() ? player : from;
        ServerPlayer anchor = request.here() ? from : player;
        rememberBack(mover, "传送前的位置");
        MvTeleporter.teleportPlayer(mover, anchor.serverLevel(), anchor.getX(), anchor.getY(), anchor.getZ(),
                anchor.getYRot(), anchor.getXRot());
        mover.sendSystemMessage(Component.literal("§e已传送到 §f" + anchor.getGameProfile().getName()));
        anchor.sendSystemMessage(Component.literal("§e" + mover.getGameProfile().getName() + " 已传送过来"));
        return 1;
    }

    private static int tpDeny(CommandContext<CommandSourceStack> ctx, String fromName) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        Tpa request = takeRequest(player, fromName);
        if (request == null) {
            ctx.getSource().sendFailure(Component.literal("§c没有待处理的传送请求"));
            return 0;
        }
        ServerPlayer from = ctx.getSource().getServer().getPlayerList().getPlayer(request.from());
        if (from != null) {
            from.sendSystemMessage(Component.literal("§c" + player.getGameProfile().getName() + " 拒绝了你的传送请求"));
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§e已拒绝传送请求"), false);
        return 1;
    }

    private static int tpCancel(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        boolean removed = REQUESTS.entrySet().removeIf(entry -> entry.getValue().from().equals(player.getUUID()));
        if (!removed) {
            ctx.getSource().sendFailure(Component.literal("§c你没有发出中的传送请求"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§e已取消你的传送请求"), false);
        return 1;
    }

    private static int tpToggle(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        EssentialsData.PlayerData data = EssentialsData.of(player.getUUID());
        data.tptoggle = !data.tptoggle;
        EssentialsData.save();
        ctx.getSource().sendSuccess(() -> Component.literal(data.tptoggle
                ? "§e已关闭接收传送请求（/tptoggle）"
                : "§a已开启接收传送请求"), false);
        return 1;
    }

    private static Tpa takeRequest(ServerPlayer player, String fromName) {
        Tpa request = REQUESTS.get(player.getUUID());
        if (request == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        boolean expired = request.expiresAt() < now;
        if (expired) {
            REQUESTS.remove(player.getUUID());
            return null;
        }
        if (fromName != null && !request.fromName().equalsIgnoreCase(fromName)) {
            return null;
        }
        REQUESTS.remove(player.getUUID());
        return request;
    }

    /** Called once per second from the tick handler: forgets timed out requests. */
    public static void tick() {
        long now = System.currentTimeMillis();
        REQUESTS.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    // ------------------------------------------------------------------ info

    private static int near(CommandContext<CommandSourceStack> ctx, int radius) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<ServerPlayer> found = new ArrayList<>();
        for (ServerPlayer other : player.serverLevel().players()) {
            if (other != player && other.distanceTo(player) <= radius) {
                found.add(other);
            }
        }
        found.sort(java.util.Comparator.comparingDouble(other -> other.distanceTo(player)));
        StringBuilder sb = new StringBuilder("§e§l附近的玩家§r §7（" + radius + " 格内，共 " + found.size() + " 人）");
        for (ServerPlayer other : found) {
            sb.append("\n §7- §f").append(other.getGameProfile().getName())
                    .append(" §7").append((int) other.distanceTo(player)).append(" 格")
                    .append(other.isInvisible() ? " §8(隐身)" : "");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return found.size();
    }

    private static int who(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        StringBuilder sb = new StringBuilder("§e§l在线玩家§r §7（" + players.size() + " 人）");
        for (ServerPlayer player : players) {
            EssentialsData.PlayerData data = EssentialsData.of(player.getUUID());
            sb.append("\n §7- §f").append(player.getGameProfile().getName())
                    .append(data.nick.isBlank() ? "" : " §7(" + data.nick + ")")
                    .append(" §8").append(player.level().dimension().location())
                    .append(" §7").append(player.latency).append("ms")
                    .append(data.afk ? " §e[AFK]" : "")
                    .append(player.isInvisible() ? " §8[隐身]" : "");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return players.size();
    }

    private static int seen(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "玩家");
        ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (online != null) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a" + online.getGameProfile().getName()
                    + " §7正在线（" + online.level().dimension().location() + "）"), false);
            return 1;
        }
        String last = EssentialsData.lastSeen(name);
        if (last == null) {
            ctx.getSource().sendFailure(Component.literal("§c没有关于 " + name + " 的记录"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§e" + name + " §7不在线，上次在线：§f" + last), false);
        return 1;
    }

    private static int afk(CommandContext<CommandSourceStack> ctx, String reason) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        EssentialsData.PlayerData data = EssentialsData.of(player.getUUID());
        data.afk = !data.afk;
        EssentialsData.save();
        String suffix = reason == null || reason.isBlank() ? "" : "（" + reason + "）";
        String who = player.getGameProfile().getName();
        if (data.afk) {
            ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("§7" + who + " 现在离开" + suffix), false);
        } else {
            ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("§7" + who + " 回来了"), false);
        }
        return 1;
    }

    private static int reply(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID partner = REPLY_TO.get(player.getUUID());
        if (partner == null) {
            ctx.getSource().sendFailure(Component.literal("§c还没有人跟你私聊过"));
            return 0;
        }
        ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayer(partner);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c对方不在线"));
            return 0;
        }
        String message = StringArgumentType.getString(ctx, "消息");
        reply(player, target, message, ctx.getSource().getServer());
        return 1;
    }

    /** Shared by {@code /r} and the whisper interceptor. */
    public static void reply(ServerPlayer from, ServerPlayer to, String message, MinecraftServer server) {
        REPLY_TO.put(from.getUUID(), to.getUUID());
        REPLY_TO.put(to.getUUID(), from.getUUID());
        String fromName = from.getGameProfile().getName();
        String toName = to.getGameProfile().getName();
        to.sendSystemMessage(Component.literal("§7" + fromName + " §7悄悄地对你说: §f" + message));
        from.sendSystemMessage(Component.literal("§7你对 " + toName + " 说: §f" + message));
        for (UUID spy : SOCIAL_SPY) {
            if (spy.equals(from.getUUID()) || spy.equals(to.getUUID())) {
                continue;
            }
            ServerPlayer watcher = server.getPlayerList().getPlayer(spy);
            if (watcher != null) {
                watcher.sendSystemMessage(Component.literal("§8[社交间谍] " + fromName + " -> " + toName + ": " + message));
            }
        }
    }

    public static Map<UUID, UUID> replyBook() {
        return REPLY_TO;
    }

    public static java.util.Set<UUID> socialSpies() {
        return SOCIAL_SPY;
    }

    private static int ping(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer target = target(ctx, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§e" + target.getGameProfile().getName()
                + " §7的延迟: §f" + target.latency + " ms"), false);
        return 1;
    }

    private static int getpos(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ctx.getSource().sendSuccess(() -> Component.literal("§e你的位置: §f"
                + String.format(Locale.ROOT, "%.1f %.1f %.1f", player.getX(), player.getY(), player.getZ())
                + " §7(" + player.level().dimension().location() + ")"), false);
        return 1;
    }

    private static int compass(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ctx.getSource().sendSuccess(() -> Component.literal("§e你面向 §f"
                + player.getDirection().getName() + " §7(yaw " + (int) player.getYRot() + ")"), false);
        return 1;
    }

    private static int depth(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, player.blockPosition().getX(),
                player.blockPosition().getZ());
        int depth = surface - player.blockPosition().getY();
        ctx.getSource().sendSuccess(() -> Component.literal("§e你在地表"
                + (depth > 0 ? "以下 §f" + depth + " §e格" : "以上 §f" + (-depth) + " §e格")
                + "§7（地表高度 " + surface + "）"), false);
        return 1;
    }

    private static int suicide(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        player.sendSystemMessage(Component.literal("§c再见。"));
        player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE);
        return 1;
    }

    // ------------------------------------------------------------------ B tier

    private static int god(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer target = target(ctx, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        EssentialsData.PlayerData data = EssentialsData.of(target.getUUID());
        data.god = !data.god;
        target.getAbilities().invulnerable = data.god;
        target.onUpdateAbilities();
        EssentialsData.save();
        boolean on = data.god;
        ctx.getSource().sendSuccess(() -> Component.literal("§e" + target.getGameProfile().getName()
                + " 的无敌模式: " + (on ? "§a开" : "§c关")), true);
        return 1;
    }

    private static int fly(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer target = target(ctx, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        EssentialsData.PlayerData data = EssentialsData.of(target.getUUID());
        data.fly = !data.fly;
        target.getAbilities().mayfly = data.fly;
        if (!data.fly) {
            target.getAbilities().flying = false;
        }
        target.onUpdateAbilities();
        EssentialsData.save();
        boolean on = data.fly;
        ctx.getSource().sendSuccess(() -> Component.literal("§e" + target.getGameProfile().getName()
                + " 的飞行: " + (on ? "§a开" : "§c关")), true);
        return 1;
    }

    private static int heal(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer target = target(ctx, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        target.setHealth(target.getMaxHealth());
        target.getFoodData().setFoodLevel(20);
        ctx.getSource().sendSuccess(() -> Component.literal("§a已治疗 " + target.getGameProfile().getName()), true);
        return 1;
    }

    private static int feed(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer target = target(ctx, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        target.getFoodData().setFoodLevel(20);
        target.getFoodData().setSaturation(20.0F);
        ctx.getSource().sendSuccess(() -> Component.literal("§a已喂饱 " + target.getGameProfile().getName()), true);
        return 1;
    }

    private static int repair(CommandContext<CommandSourceStack> ctx, boolean all) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int repaired = 0;
        if (all) {
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                repaired += repairStack(inventory.getItem(slot));
            }
        } else {
            repaired = repairStack(player.getMainHandItem());
        }
        int count = repaired;
        ctx.getSource().sendSuccess(() -> Component.literal(count > 0
                ? "§a已修复 " + count + " 件物品"
                : "§e没有需要修复的物品"), false);
        return repaired;
    }

    private static int repairStack(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem() || stack.getDamageValue() == 0) {
            return 0;
        }
        stack.setDamageValue(0);
        return 1;
    }

    private static int hat(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§c手上得拿着东西"));
            return 0;
        }
        ItemStack previous = player.getInventory().armor.get(3).copy();
        player.getInventory().armor.set(3, hand.copy());
        player.getInventory().setItem(player.getInventory().selected, previous);
        ctx.getSource().sendSuccess(() -> Component.literal("§a已把它戴在头上"), false);
        return 1;
    }

    private static int more(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§c手上得拿着东西"));
            return 0;
        }
        hand.setCount(hand.getMaxStackSize());
        ctx.getSource().sendSuccess(() -> Component.literal("§a已补满手上的物品"), false);
        return 1;
    }

    private enum MenuKind { WORKBENCH, ANVIL, GRINDSTONE, LOOM, SMITHING, STONECUTTER, CARTOGRAPHY }

    private static int openMenu(CommandContext<CommandSourceStack> ctx, MenuKind kind) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ContainerLevelAccess access = ContainerLevelAccess.create(player.level(), player.blockPosition());
        Component title = switch (kind) {
            case WORKBENCH -> Component.literal("§8工作台");
            case ANVIL -> Component.literal("§8铁砧");
            case GRINDSTONE -> Component.literal("§8砂轮");
            case LOOM -> Component.literal("§8织布机");
            case SMITHING -> Component.literal("§8锻造台");
            case STONECUTTER -> Component.literal("§8切石机");
            case CARTOGRAPHY -> Component.literal("§8制图台");
        };
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> switch (kind) {
            case WORKBENCH -> new net.minecraft.world.inventory.CraftingMenu(id, inventory, access);
            case ANVIL -> new net.minecraft.world.inventory.AnvilMenu(id, inventory, access);
            case GRINDSTONE -> new net.minecraft.world.inventory.GrindstoneMenu(id, inventory, access);
            case LOOM -> new net.minecraft.world.inventory.LoomMenu(id, inventory, access);
            case SMITHING -> new net.minecraft.world.inventory.SmithingMenu(id, inventory, access);
            case STONECUTTER -> new net.minecraft.world.inventory.StonecutterMenu(id, inventory, access);
            case CARTOGRAPHY -> new net.minecraft.world.inventory.CartographyTableMenu(id, inventory, access);
        }, title));
        return 1;
    }

    private static int enderChest(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new net.minecraft.world.inventory.ChestMenu(
                MenuType.GENERIC_9x3, id, inventory, p.getEnderChestInventory(), 3),
                Component.literal("§8末影箱")));
        return 1;
    }

    /** A chest view onto somebody else's inventory; edits are written straight into their inventory. */
    public static final class InvSeeMenu extends AbstractContainerMenu {
        InvSeeMenu(int id, Inventory viewer, Inventory target) {
            super(MenuType.GENERIC_9x5, id);
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 9; column++) {
                    int index = column + row * 9;
                    addSlot(new net.minecraft.world.inventory.Slot(target, index,
                            8 + column * 18, 18 + row * 18));
                }
            }
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 9; column++) {
                    addSlot(new net.minecraft.world.inventory.Slot(viewer, column + row * 9 + 9,
                            8 + column * 18, 103 + row * 18));
                }
            }
            for (int column = 0; column < 9; column++) {
                addSlot(new net.minecraft.world.inventory.Slot(viewer, column, 8 + column * 18, 161));
            }
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return true;
        }

        @Override
        public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int index) {
            return ItemStack.EMPTY;
        }
    }

    private static int invsee(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer viewer = ctx.getSource().getPlayerOrException();
        ServerPlayer target = target(ctx, StringArgumentType.getString(ctx, "玩家"));
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        viewer.openMenu(new SimpleMenuProvider((id, inventory, p) -> new InvSeeMenu(id, inventory,
                target.getInventory()), Component.literal("§8" + target.getGameProfile().getName() + " 的背包")));
        ctx.getSource().sendSuccess(() -> Component.literal("§7看的是对方当前世界的背包（看世界独立背包规则）"), false);
        return 1;
    }

    private static int speed(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer target = target(ctx, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        int level = IntegerArgumentType.getInteger(ctx, "速度");
        float value = level <= 0 ? 0.05F : Math.min(1.0F, level / 10.0F);
        boolean flying = target.getAbilities().flying;
        if (flying) {
            target.getAbilities().setFlyingSpeed(value);
        } else {
            target.getAbilities().setWalkingSpeed(value);
        }
        target.onUpdateAbilities();
        ctx.getSource().sendSuccess(() -> Component.literal("§a已把 " + target.getGameProfile().getName()
                + " 的" + (flying ? "飞行" : "行走") + "速度设为 " + level), true);
        return 1;
    }

    private static int vanish(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        EssentialsData.PlayerData data = EssentialsData.of(player.getUUID());
        data.vanish = !data.vanish;
        player.setInvisible(data.vanish);
        player.setInvulnerable(data.vanish);
        MinecraftServer server = ctx.getSource().getServer();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == player) {
                continue;
            }
            if (data.vanish) {
                other.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
            } else {
                other.connection.send(new ClientboundPlayerInfoUpdatePacket(
                        EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), List.of(player)));
            }
        }
        EssentialsData.save();
        boolean on = data.vanish;
        ctx.getSource().sendSuccess(() -> Component.literal(on ? "§e你已隐身" : "§a你已现身"), false);
        return 1;
    }

    private static int broadcast(CommandContext<CommandSourceStack> ctx) {
        String message = StringArgumentType.getString(ctx, "消息");
        ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("§6[广播] §f" + message).withStyle(ChatFormatting.BOLD), false);
        return 1;
    }

    private static int socialSpy(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        if (SOCIAL_SPY.remove(player.getUUID())) {
            ctx.getSource().sendSuccess(() -> Component.literal("§e已关闭社交间谍"), false);
        } else {
            SOCIAL_SPY.add(player.getUUID());
            ctx.getSource().sendSuccess(() -> Component.literal("§a已开启社交间谍（能看到所有私聊）"), false);
        }
        return 1;
    }

    private static int ignore(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "玩家");
        EssentialsData.PlayerData data = EssentialsData.of(player.getUUID());
        String key = name.toLowerCase(Locale.ROOT);
        boolean nowIgnored;
        if (data.ignore.contains(key)) {
            data.ignore.remove(key);
            nowIgnored = false;
        } else {
            data.ignore.add(key);
            nowIgnored = true;
        }
        EssentialsData.save();
        ctx.getSource().sendSuccess(() -> Component.literal(nowIgnored
                ? "§e已屏蔽 " + name + " 的聊天"
                : "§a已取消屏蔽 " + name), false);
        return 1;
    }

    private static int rtp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (onCooldown(player, "rtp", EssentialsData.config().rtpCooldownSeconds)) {
            return 0;
        }
        EssentialsData.Config config = EssentialsData.config();
        ServerLevel level = player.serverLevel();
        BlockPos spawn = level.getSharedSpawnPos();
        java.util.Random random = new java.util.Random();
        int min = Math.max(16, config.rtpMinRadius);
        int max = Math.max(min + 16, config.rtpMaxRadius);
        for (int attempt = 0; attempt < Math.max(1, config.rtpAttempts); attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = min + random.nextInt(max - min);
            int x = spawn.getX() + (int) (Math.cos(angle) * distance);
            int z = spawn.getZ() + (int) (Math.sin(angle) * distance);
            if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z))) {
                continue;   // do not force-load the world just for a dice roll
            }
            BlockPos spot = MvTeleporter.findSafeSpot(level, x, z, -1);
            if (spot.getY() <= level.getMinBuildHeight() + 1) {
                continue;
            }
            rememberBack(player, "传送前的位置");
            travelTo(player, "随机地点 " + spot.getX() + " " + spot.getZ(), "rtp", level,
                    spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, player.getYRot(), player.getXRot());
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("§c附近找不到合适的地点，请稍后再试"));
        return 0;
    }

    private static int top(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        rememberBack(player, "传送前的位置");
        travelTo(player, "地表", "spawn", level, pos.getX() + 0.5D, y, pos.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        return 1;
    }

    private static int jump(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        HitResult hit = player.pick(30.0D, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            ctx.getSource().sendFailure(Component.literal("§c请对着要跳上去的方块"));
            return 0;
        }
        BlockPos landing = blockHit.getBlockPos().relative(blockHit.getDirection());
        ServerLevel level = player.serverLevel();
        BlockState state = level.getBlockState(landing);
        if (!state.getCollisionShape(level, landing).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§c那里站不下"));
            return 0;
        }
        rememberBack(player, "传送前的位置");
        travelTo(player, "跳跃目标", "spawn", level, landing.getX() + 0.5D, landing.getY(),
                landing.getZ() + 0.5D, player.getYRot(), player.getXRot());
        return 1;
    }

    private static int clearInventory(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer target = target(ctx, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        target.getInventory().clearContent();
        ctx.getSource().sendSuccess(() -> Component.literal("§e已清空 " + target.getGameProfile().getName()
                + " 当前世界的背包"), true);
        return 1;
    }

    private static int skull(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer self = ctx.getSource().getPlayer();
        String who = name == null
                ? (self == null ? null : self.getGameProfile().getName())
                : name;
        if (who == null) {
            ctx.getSource().sendFailure(Component.literal("§c请指定玩家名或用玩家身份执行"));
            return 0;
        }
        if (self == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        net.minecraft.nbt.CompoundTag owner = new net.minecraft.nbt.CompoundTag();
        owner.putString("Name", who);
        head.getOrCreateTag().put("SkullOwner", owner);
        head.setHoverName(Component.literal("§f" + who + " 的头"));
        if (!self.getInventory().add(head)) {
            self.drop(head, false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a已给你 " + who + " 的头"), false);
        return 1;
    }

    private static int firework(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        net.minecraft.world.entity.projectile.FireworkRocketEntity rocket =
                new net.minecraft.world.entity.projectile.FireworkRocketEntity(level,
                        player.getX(), player.getY() + 1.0D, player.getZ(), new ItemStack(Items.FIREWORK_ROCKET));
        level.addFreshEntity(rocket);
        ctx.getSource().sendSuccess(() -> Component.literal("§a已放出一个烟花"), false);
        return 1;
    }

    private static int lightning(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer target = target(ctx, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        ServerLevel level = target.serverLevel();
        net.minecraft.world.entity.LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return 0;
        }
        bolt.moveTo(target.getX(), target.getY(), target.getZ());
        level.addFreshEntity(bolt);
        ctx.getSource().sendSuccess(() -> Component.literal("§e已劈 " + target.getGameProfile().getName()), true);
        return 1;
    }

    private static int exp(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer target = target(ctx, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        int amount = IntegerArgumentType.getInteger(ctx, "数量");
        target.giveExperiencePoints(amount);
        ctx.getSource().sendSuccess(() -> Component.literal("§a已给 " + target.getGameProfile().getName()
                + " " + amount + " 点经验"), true);
        return 1;
    }

    private static int nick(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String nick = StringArgumentType.getString(ctx, "昵称").trim();
        if (nick.length() > 24) {
            ctx.getSource().sendFailure(Component.literal("§c昵称最多 24 个字"));
            return 0;
        }
        EssentialsData.PlayerData data = EssentialsData.of(player.getUUID());
        data.nick = nick;
        EssentialsData.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a你的聊天前缀现在是 §f[" + nick + "]"), true);
        return 1;
    }

    private static int nickClear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        EssentialsData.of(player.getUUID()).nick = "";
        EssentialsData.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§e已清除聊天前缀"), true);
        return 1;
    }

    private static int powertool(CommandContext<CommandSourceStack> ctx, String command) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (command == null) {
            EssentialsData.setPowertool(player.getUUID(), null, null);
            ctx.getSource().sendSuccess(() -> Component.literal("§e已清除手上物品的指令绑定"), false);
            return 1;
        }
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§c手上得拿着要绑定的物品"));
            return 0;
        }
        String itemId = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(hand.getItem()));
        EssentialsData.setPowertool(player.getUUID(), itemId, command);
        ctx.getSource().sendSuccess(() -> Component.literal("§a已把 §f/" + command
                + " §a绑定到 §f" + itemId + "§a（右键触发）"), false);
        return 1;
    }

    // ------------------------------------------------------------------ mute

    /** Parses {@code 10s / 5m / 2h / 3d / perm}; a bare number means minutes. */
    public static long parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return Long.MAX_VALUE;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.equals("perm") || text.equals("forever") || text.equals("永久") || text.equals("-1")) {
            return Long.MAX_VALUE;
        }
        long multiplier = 60_000L;
        if (text.endsWith("s") || text.endsWith("秒")) {
            multiplier = 1000L;
            text = text.substring(0, text.length() - (text.endsWith("s") ? 1 : 1));
        } else if (text.endsWith("m") || text.endsWith("分")) {
            multiplier = 60_000L;
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("h") || text.endsWith("时")) {
            multiplier = 3_600_000L;
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("d") || text.endsWith("天")) {
            multiplier = 86_400_000L;
            text = text.substring(0, text.length() - 1);
        }
        try {
            long amount = Long.parseLong(text.trim());
            if (amount <= 0L) {
                return Long.MAX_VALUE;
            }
            return System.currentTimeMillis() + amount * multiplier;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static int mute(CommandContext<CommandSourceStack> ctx, String duration, String reason) {
        String name = StringArgumentType.getString(ctx, "玩家");
        ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线（禁言只对在线的玩家生效）"));
            return 0;
        }
        long until = parseDuration(duration);
        if (until < 0L) {
            ctx.getSource().sendFailure(Component.literal("§c时长格式不对，用 10s/5m/2h/3d，或 perm 表示永久"));
            return 0;
        }
        EssentialsData.mute(target.getUUID(), until, reason);
        if (!target.getGameProfile().getName().isBlank()) {
            EssentialsData.of(target.getUUID()).lastName = target.getGameProfile().getName();
            EssentialsData.save();
        }
        String remaining = EssentialsData.muteRemaining(target.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("§e已禁言 §f" + target.getGameProfile().getName()
                + "§e（" + remaining + "）" + (reason.isBlank() ? "" : " 原因: " + reason)), true);
        target.sendSystemMessage(Component.literal("§c你被禁言了（" + remaining + "）"
                + (reason.isBlank() ? "" : " 原因: " + reason)));
        return 1;
    }

    private static int unmute(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "玩家");
        ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
        UUID id = target != null ? target.getUUID() : EssentialsData.findByName(name);
        if (id == null) {
            ctx.getSource().sendFailure(Component.literal("§c找不到这个玩家"));
            return 0;
        }
        EssentialsData.unmute(id);
        ctx.getSource().sendSuccess(() -> Component.literal("§a已解除 " + name + " 的禁言"), true);
        if (target != null) {
            target.sendSystemMessage(Component.literal("§a你已被解除禁言"));
        }
        return 1;
    }

    private static int muteList(CommandContext<CommandSourceStack> ctx) {
        Map<String, String> muted = EssentialsData.mutedPlayers();
        if (muted.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7没有正在被禁言的玩家"), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder("§e§l禁言名单§r (" + muted.size() + " 人)");
        for (Map.Entry<String, String> entry : muted.entrySet()) {
            sb.append("\n §7- §f").append(entry.getKey()).append(" §7").append(entry.getValue());
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return muted.size();
    }

    // ------------------------------------------------------------------ config commands

    private static int essHelp(CommandContext<CommandSourceStack> ctx) {
        EssentialsData.Config config = EssentialsData.config();
        StringBuilder sb = new StringBuilder("§e§l常用指令（Essentials 风格）");
        sb.append("\n§7传送: §f/spawn /back /home [名] /sethome [名] /delhome /homes /warp /warps /tpa /tpahere /tpaccept /tpdeny /tpcancel /tptoggle /rtp /top /jump");
        sb.append("\n§7信息: §f/near /who /seen /afk /r /ping /getpos /compass /depth /suicide");
        sb.append("\n§7实用: §f/workbench /anvil /grindstone /loom /smithingtable /stonecutter /cartographytable /enderchest /ec /hat /repair [all] /heal /feed /skull /firework /clearinventory /ignore");
        sb.append("\n§7管理(op").append(config.adminLevel).append("): §f/fly /god /more /speed /vanish /broadcast /socialspy /invsee /lightning /exp /powertool /setwarp /delwarp /tppos /tphere /tpall /mute /unmute /mutelist");
        sb.append("\n§7配置: §f/ess fees <on|off> /ess fee <项目> <价格> /ess delay <秒> /ess homes <数量> /ess main <世界文件夹> /ess reload");
        sb.append("\n§7当前: 传送延迟 §f").append(config.teleportDelaySeconds).append(" 秒")
                .append("§7, 收费 §f").append(config.teleportFeesEnabled ? "开" : "关")
                .append("§7, 家上限 §f").append(config.maxHomes)
                .append("§7, 主城 §f").append(config.mainWorld)
                .append("§7, 跳跃打断 §f").append(config.interruptOnJump ? "开" : "关")
                .append("§7, 受伤打断 §f").append(config.interruptOnHurt ? "开" : "关");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int essFees(CommandContext<CommandSourceStack> ctx) {
        Boolean value = com.oktsas.onekeytosetaserver.domain.DomainManager
                .parseToggle(StringArgumentType.getString(ctx, "on|off"));
        if (value == null) {
            ctx.getSource().sendFailure(Component.literal("§c值必须是 on 或 off"));
            return 0;
        }
        EssentialsData.config().teleportFeesEnabled = value;
        EssentialsData.save();
        ctx.getSource().sendSuccess(() -> Component.literal(value
                ? "§a传送收费已开启（价格用 /ess fee 调整）"
                : "§a传送收费已关闭（全部免费）"), true);
        return 1;
    }

    private static int essFee(CommandContext<CommandSourceStack> ctx) {
        String what = StringArgumentType.getString(ctx, "项目").toLowerCase(Locale.ROOT);
        double price = DoubleArgumentType.getDouble(ctx, "价格");
        EssentialsData.Config config = EssentialsData.config();
        switch (what) {
            case "tpa" -> config.feeTpa = price;
            case "tpahere" -> config.feeTpahere = price;
            case "home" -> config.feeHome = price;
            case "warp" -> config.feeWarp = price;
            case "back" -> config.feeBack = price;
            case "spawn" -> config.feeSpawn = price;
            case "rtp" -> config.feeRtp = price;
            case "tppos" -> config.feeTppos = price;
            default -> {
                ctx.getSource().sendFailure(Component.literal(
                        "§c项目只能是 tpa / tpahere / home / warp / back / spawn / rtp / tppos"));
                return 0;
            }
        }
        EssentialsData.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a已把 §f" + what + " §a的传送费用设为 §f"
                + MoneyService.format(price) + " §a（收费总开关: "
                + (config.teleportFeesEnabled ? "开" : "关") + "）"), true);
        return 1;
    }

    private static int essDelay(CommandContext<CommandSourceStack> ctx) {
        int seconds = IntegerArgumentType.getInteger(ctx, "秒");
        EssentialsData.config().teleportDelaySeconds = seconds;
        EssentialsData.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a传送延迟已设为 " + seconds + " 秒"
                + (seconds == 0 ? "（瞬间传送）" : "")), true);
        return 1;
    }

    private static int essHomes(CommandContext<CommandSourceStack> ctx) {
        int amount = IntegerArgumentType.getInteger(ctx, "数量");
        EssentialsData.config().maxHomes = amount;
        EssentialsData.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a每人的家上限已设为 " + amount), true);
        return 1;
    }

    private static int essMain(CommandContext<CommandSourceStack> ctx) {
        String world = StringArgumentType.getString(ctx, "世界");
        EssentialsData.config().mainWorld = world;
        EssentialsData.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a主城世界文件夹已设为 " + world
                + "（/spawn 会去这里）"), true);
        return 1;
    }

    private static int essReload(CommandContext<CommandSourceStack> ctx) {
        EssentialsData.loadAll(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
        ctx.getSource().sendSuccess(() -> Component.literal("§a已重新读取 multiverse/essentials.json"), true);
        return 1;
    }

    private static int essSave(CommandContext<CommandSourceStack> ctx) {
        EssentialsData.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a已保存 multiverse/essentials.json"), true);
        return 1;
    }
}
