package com.oktsas.onekeytosetaserver.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.oktsas.onekeytosetaserver.economy.MoneyService;
import com.oktsas.onekeytosetaserver.market.MarketManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /market} (sell / buy / list) and {@code /mail}. */
public final class MarketCommand {

    private MarketCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("market")
                .executes(ctx -> {
                    if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
                        com.oktsas.onekeytosetaserver.market.MarketMenu.open(player);
                        return 1;
                    }
                    return list(ctx);
                })
                .then(Commands.literal("gui")
                        .executes(ctx -> {
                            if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
                                com.oktsas.onekeytosetaserver.market.MarketMenu.open(player);
                                return 1;
                            }
                            return 0;
                        }))
                .then(Commands.literal("list").executes(MarketCommand::list))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(MarketCommand::cancel)))
                .then(Commands.literal("search")
                        .executes(ctx -> gui(ctx, "", "", false))
                        .then(Commands.argument("keyword", StringArgumentType.greedyString())
                                .executes(ctx -> gui(ctx, "",
                                        StringArgumentType.getString(ctx, "keyword").trim(), false))))
                .then(Commands.literal("cat")
                        .executes(ctx -> gui(ctx, "", "", false))
                        .then(Commands.argument("category", StringArgumentType.word())
                                .executes(ctx -> gui(ctx,
                                        StringArgumentType.getString(ctx, "category").trim(), "", false))))
                .then(Commands.literal("mine").executes(ctx -> gui(ctx, "", "", true)))
                .then(Commands.literal("blacklist")
                        .executes(MarketCommand::blacklistList)
                        .then(Commands.literal("list").executes(MarketCommand::blacklistList))
                        .then(Commands.literal("add")
                                .requires(source -> source.hasPermission(3))
                                .executes(ctx -> blacklist(ctx, true)))
                        .then(Commands.literal("remove")
                                .requires(source -> source.hasPermission(3))
                                .executes(ctx -> blacklist(ctx, false)))
                        .then(Commands.literal("addid")
                                .requires(source -> source.hasPermission(3))
                                .then(Commands.argument("item", StringArgumentType.word())
                                        .executes(ctx -> blacklistId(ctx, true))))
                        .then(Commands.literal("removeid")
                                .requires(source -> source.hasPermission(3))
                                .then(Commands.argument("item", StringArgumentType.word())
                                        .executes(ctx -> blacklistId(ctx, false)))))
                .then(Commands.literal("sell")
                        .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01D))
                                // 省略时长 = config.json 的 defaultHours；写 0 = 永久（allowPermanent）
                                .executes(ctx -> sell(ctx, MarketManager.config().defaultHours))
                                .then(Commands.argument("hours", IntegerArgumentType.integer(0, 1_000_000))
                                        .executes(ctx -> sell(ctx, IntegerArgumentType.getInteger(ctx, "hours"))))))
                .then(Commands.literal("buy")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(MarketCommand::buy)))
                .then(Commands.literal("config")
                        .requires(source -> source.hasPermission(3))
                        .executes(MarketCommand::showConfig)
                        .then(Commands.literal("reload")
                                .requires(source -> source.hasPermission(4))
                                .executes(MarketCommand::reloadConfig))));

        dispatcher.register(Commands.literal("mail")
                .executes(MarketCommand::mail)
                .then(Commands.literal("send")
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(MarketCommand::sendMail))));
    }

    // ------------------------------------------------------------------ blacklist (OP 3)

    private static int blacklistList(CommandContext<CommandSourceStack> ctx) {
        java.util.Set<String> banned = MarketManager.blacklist();
        StringBuilder sb = new StringBuilder("§e§l市场黑名单§r (" + banned.size() + " 项)");
        for (String id : banned) {
            sb.append("\n §7- §f").append(id);
        }
        if (banned.isEmpty()) {
            sb.append("\n §7（空）");
        }
        sb.append("\n§7OP3: 手持物品 /market blacklist add|remove　也可 /market blacklist addid <物品id>");
        sb.append("\n§c永久禁交易§7（写死在代码里，黑白名单都改不动）: §f盔甲 / 武器 / 工具 / 原矿方块 / 刷怪蛋");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int blacklist(CommandContext<CommandSourceStack> ctx, boolean add)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (player.getMainHandItem().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("请手持要操作的东西"));
            return 0;
        }
        return applyBlacklist(ctx, MarketManager.idOf(player.getMainHandItem()), add);
    }

    private static int blacklistId(CommandContext<CommandSourceStack> ctx, boolean add) {
        String raw = StringArgumentType.getString(ctx, "item").toLowerCase(java.util.Locale.ROOT);
        return applyBlacklist(ctx, raw.contains(":") ? raw : "minecraft:" + raw, add);
    }

    private static int applyBlacklist(CommandContext<CommandSourceStack> ctx, String id, boolean add) {
        if (add) {
            int taken = MarketManager.blacklistAdd(ctx.getSource().getServer(), id);
            if (taken == MarketManager.BLACKLIST_PERMANENT) {
                ctx.getSource().sendFailure(Component.literal("§c"
                        + com.oktsas.onekeytosetaserver.trade.TradeBlacklist.message(
                                com.oktsas.onekeytosetaserver.trade.TradeBlacklist.familyOfId(id))
                        + "§7（这类物品是写死在代码里的永久禁交易，黑白名单都改不动它）"));
                return 0;
            }
            ctx.getSource().sendSuccess(() -> Component.literal("§c已禁止市场交易 " + id
                    + (taken > 0 ? "，并撤下 " + taken + " 个在售商品（已邮件退回卖家）" : "")), true);
            return 1;
        }
        if (!MarketManager.blacklistRemove(id)) {
            ctx.getSource().sendFailure(Component.literal(id + " 本来就不在黑名单里"
                    + (com.oktsas.onekeytosetaserver.trade.TradeBlacklist.isBannedId(id)
                            ? "（永久禁交易不靠黑名单，改不了的）" : "")));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a已解除禁止: " + id), true);
        return 1;
    }

    /** Opens the market screen with a filter, or prints the filtered text list for the console. */
    private static int gui(CommandContext<CommandSourceStack> ctx, String category, String search, boolean mineOnly) {
        String keyword = "-".equals(search) ? "" : search;
        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
            com.oktsas.onekeytosetaserver.market.MarketMenu.open(player, category, keyword, mineOnly);
            return 1;
        }
        java.util.List<MarketManager.Listing> found = MarketManager.filtered(category, keyword, null);
        net.minecraft.network.chat.MutableComponent out = Component.literal("§e市场筛选结果（" + found.size() + " 件）"
                + (category.isBlank() ? "" : " 分类=" + category)
                + (keyword.isBlank() ? "" : " 搜索=" + keyword));
        for (MarketManager.Listing listing : found) {
            out.append(Component.literal("\n §7[" + listing.id + "] §f"))
                    .append(MarketManager.displayOf(listing))
                    .append(Component.literal(" §a" + MoneyService.format(listing.price)
                            + " §7卖家 " + listing.sellerName));
        }
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        return MarketManager.cancel(player, StringArgumentType.getString(ctx, "id")) ? 1 : 0;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.network.chat.MutableComponent out =
                Component.literal("§e§l全服自由市场§r（共 " + MarketManager.listings().size() + " 件）");
        for (MarketManager.Listing listing : MarketManager.listings()) {
            out.append(Component.literal("\n §7[" + listing.id + "] §f"))
                    .append(MarketManager.displayOf(listing))
                    .append(Component.literal(" §a" + MoneyService.format(listing.price)
                            + " §7卖家 " + listing.sellerName
                            + " §7时长 " + MarketManager.durationLabel(listing.hours) + " 费率 "
                            + MarketManager.feePercent(listing.hours) + "%"));
        }
        out.append(Component.literal(
                "\n§7上架: /market sell <价格> [小时]（手持物品；0 = 永久，省略 = "
                        + MarketManager.durationLabel(MarketManager.config().defaultHours) + "）"
                        + "\n§7购买: /market buy <编号>　撤单: /market cancel <编号>"
                        + "\n§7筛选: /market cat <方块|工具|武器|防具|食物|材料>　/market search <关键词>　/market mine（我的挂单）"));
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    /** op3: what the market knobs currently are and where to change them. */
    private static int showConfig(CommandContext<CommandSourceStack> ctx) {
        com.oktsas.onekeytosetaserver.market.MarketConfig cfg = MarketManager.config();
        StringBuilder sb = new StringBuilder("§e§l自由市场设置§r §7(multiverse/market/config.json)");
        sb.append("\n §7- §e允许永久挂单:§3 ").append(cfg.allowPermanent ? "是" : "否")
                .append("§7（/market sell <价格> 0）");
        sb.append("\n §7- §e永久挂单费率:§3 ").append((int) Math.round(cfg.permanentFeeRate * 100)).append("%");
        sb.append("\n §7- §e最长挂单时长:§3 ").append(cfg.maxHours).append(" 小时");
        sb.append("\n §7- §e省略时长时的默认值:§3 ")
                .append(cfg.defaultHours <= 0 ? "永久" : cfg.defaultHours + " 小时");
        sb.append("\n §7- §e每人挂单上限:§3 ").append(MarketManager.maxListings()).append(" 个");
        sb.append("\n §7- §e当前在售:§3 ").append(MarketManager.listings().size()).append(" 件");
        sb.append("\n§7有限时长的费率阶梯固定为 1/2/3/5/8/10%（按小时数）。");
        sb.append("\n§7改完文件后执行 §f/market config reload §7（op4）即刻生效，无需重启。");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /** op4: re-reads market/config.json (a broken file keeps the current values). */
    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        if (MarketManager.readConfig()) {
            com.oktsas.onekeytosetaserver.market.MarketConfig cfg = MarketManager.config();
            ctx.getSource().sendSuccess(() -> Component.literal("§a已重新读取 multiverse/market/config.json"
                    + "§7：永久=" + (cfg.allowPermanent ? "开" : "关")
                    + "，永久费率=" + (int) Math.round(cfg.permanentFeeRate * 100) + "%"
                    + "，最长=" + cfg.maxHours + "h，默认="
                    + (cfg.defaultHours <= 0 ? "永久" : cfg.defaultHours + "h")
                    + "，每人上限=" + MarketManager.maxListings()), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("§c读不到 multiverse/market/config.json"
                + "（文件不存在或格式有错，当前设置保持不变）"));
        return 0;
    }

    private static int sell(CommandContext<CommandSourceStack> ctx, int hours)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        double price = DoubleArgumentType.getDouble(ctx, "price");
        com.oktsas.onekeytosetaserver.market.MarketConfig cfg = MarketManager.config();
        if (hours > cfg.maxHours) {
            ctx.getSource().sendFailure(Component.literal("§c挂单时长最长 " + cfg.maxHours
                    + " 小时（0 = 永久，可改 multiverse/market/config.json）"));
            return 0;
        }
        if (hours <= 0 && !cfg.allowPermanent) {
            ctx.getSource().sendFailure(Component.literal("§c本服不允许永久挂单，请写 1~" + cfg.maxHours
                    + " 小时（multiverse/market/config.json 的 allowPermanent）"));
            return 0;
        }
        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("请手持要上架的物品"));
            return 0;
        }
        net.minecraft.world.item.ItemStack copy = held.copyAndClear();
        if (!MarketManager.sell(player, copy, price, hours)) {
            player.getInventory().add(copy);
            return 0;
        }
        return 1;
    }

    private static int buy(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        return MarketManager.buy(player, StringArgumentType.getString(ctx, "id")) ? 1 : 0;
    }

    private static int mail(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int size = MarketManager.mailboxSize(player.getUUID());
        int delivered = MarketManager.deliverMail(player);
        if (size == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7邮箱是空的"), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a已从邮箱领取 " + delivered + " 个物品"), false);
        return 1;
    }

    /** Operator mail: the held item is sent to any online player. */
    private static int sendMail(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerPlayer target = ctx.getSource().getServer().getPlayerList()
                .getPlayerByName(StringArgumentType.getString(ctx, "player"));
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("玩家不在线"));
            return 0;
        }
        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("请手持要邮寄的物品"));
            return 0;
        }
        MarketManager.sendMail(ctx.getSource().getServer(), target.getUUID(), held.copy());
        ctx.getSource().sendSuccess(() -> Component.literal("§a已把 " + held.getCount() + " 个物品邮寄给 "
                + target.getGameProfile().getName()), true);
        return 1;
    }
}
