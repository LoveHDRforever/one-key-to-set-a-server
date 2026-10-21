package com.oktsas.onekeytosetaserver.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.oktsas.onekeytosetaserver.economy.MoneyService;
import com.oktsas.onekeytosetaserver.shop.ShopManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** {@code /money}, {@code /shop} and {@code /sellall}. */
public final class EconomyCommand {

    private EconomyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("money")
                .executes(ctx -> balance(ctx, ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("pay")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01D))
                                        .executes(EconomyCommand::pay))))
                .then(Commands.literal("give")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01D))
                                        .executes(ctx -> adjust(ctx, "give")))))
                .then(Commands.literal("take")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01D))
                                        .executes(ctx -> adjust(ctx, "take")))))
                .then(Commands.literal("set")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0D))
                                        .executes(ctx -> adjust(ctx, "set"))))));

        dispatcher.register(Commands.literal("shop")
                .executes(ctx -> {
                    if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
                        ShopManager.saveAll();
                        com.oktsas.onekeytosetaserver.shop.ShopMenus.openShop(player);
                        return 1;
                    }
                    return shopInfo(ctx);
                })
                .then(Commands.literal("list").executes(EconomyCommand::shopInfo))
                .then(Commands.literal("nbt")
                        .requires(source -> source.hasPermission(3))
                        .executes(EconomyCommand::shopNbt))
                .then(Commands.literal("edit")
                        .requires(source -> source.hasPermission(3))
                        .executes(EconomyCommand::shopEdit))
                .then(Commands.literal("price")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.argument("price",
                                        com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0D))
                                .executes(EconomyCommand::shopSetBuyPrice)))
                .then(Commands.literal("recycle")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.literal("remove").executes(EconomyCommand::shopRemoveRecycle))
                        .then(Commands.argument("price",
                                        com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0D))
                                .executes(EconomyCommand::shopSetRecyclePrice)))
                .then(Commands.literal("buy")
                        .then(Commands.argument("item", StringArgumentType.word())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 2304))
                                        .executes(EconomyCommand::buy)))));

        dispatcher.register(Commands.literal("trash")
                .executes(ctx -> {
                    if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
                        com.oktsas.onekeytosetaserver.shop.ShopMenus.openTrash(player);
                        return 1;
                    }
                    ctx.getSource().sendFailure(Component.literal("该命令只能由玩家使用"));
                    return 0;
                })
                .then(Commands.literal("confirm").executes(ctx -> {
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                        ctx.getSource().sendFailure(Component.literal("该命令只能由玩家使用"));
                        return 0;
                    }
                    boolean on = com.oktsas.onekeytosetaserver.shop.ShopMenus.toggleTrashConfirm(player.getUUID());
                    ctx.getSource().sendSuccess(() -> Component.literal(on
                            ? "§a垃圾桶已切换为「确认模式」：关闭界面不会销毁，要点红色的「立即销毁」按钮才销毁"
                            : "§e垃圾桶已切换为「关闭即销毁」模式"), false);
                    return 1;
                })));

        dispatcher.register(Commands.literal("sell")
                .executes(ctx -> {
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                        ctx.getSource().sendFailure(Component.literal("该命令只能由玩家使用"));
                        return 0;
                    }
                    com.oktsas.onekeytosetaserver.shop.ShopMenus.openSell(player);
                    return 1;
                }));

        dispatcher.register(Commands.literal("sellall").executes(EconomyCommand::sellAll));
    }

    private static int balance(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        double balance = MoneyService.get(player.getServer(), player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("§b余额: §f" + MoneyService.format(balance)), false);
        return 1;
    }

    private static int pay(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer from = ctx.getSource().getPlayerOrException();
        ServerPlayer to = ctx.getSource().getServer().getPlayerList()
                .getPlayerByName(StringArgumentType.getString(ctx, "player"));
        if (to == null) {
            ctx.getSource().sendFailure(Component.literal("玩家不在线"));
            return 0;
        }
        double amount = DoubleArgumentType.getDouble(ctx, "amount");
        if (!MoneyService.transfer(from.getServer(), from.getUUID(), to.getUUID(), amount)) {
            ctx.getSource().sendFailure(Component.literal("§c余额不足"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a已向 " + to.getGameProfile().getName()
                + " 转账 " + MoneyService.format(amount)), false);
        to.sendSystemMessage(Component.literal("§a收到来自 " + from.getGameProfile().getName()
                + " 的 " + MoneyService.format(amount)));
        return 1;
    }

    private static int adjust(CommandContext<CommandSourceStack> ctx, String mode) {
        ServerPlayer target = ctx.getSource().getServer().getPlayerList()
                .getPlayerByName(StringArgumentType.getString(ctx, "player"));
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("玩家不在线（离线账户暂不支持）"));
            return 0;
        }
        double amount = DoubleArgumentType.getDouble(ctx, "amount");
        switch (mode) {
            case "give" -> MoneyService.deposit(target.getServer(), target.getUUID(), amount);
            case "take" -> MoneyService.withdraw(target.getServer(), target.getUUID(), amount);
            default -> MoneyService.set(target.getServer(), target.getUUID(), amount);
        }
        double now = MoneyService.get(target.getServer(), target.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + target.getGameProfile().getName()
                + " 的余额: " + MoneyService.format(now)), true);
        return 1;
    }

    // ------------------------------------------------------------------ price editing (OP 3)

    /** Opens the chest-screen price editor for the held item. */
    private static int shopEdit(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        com.oktsas.onekeytosetaserver.shop.ShopEditor.open(player);
        return 1;
    }

    /** {@code /shop nbt} - the held stack's SNBT, so an operator can copy it into the JSON files. */
    private static int shopNbt(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("该命令只能由玩家使用（请手持物品）"));
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("请手持要查看的物品"));
            return 0;
        }
        String id = ShopManager.idOf(held);
        String nbt = ShopManager.snbtOf(held);
        String summary = ShopManager.describeNbt(held);
        ctx.getSource().sendSuccess(() -> Component.literal("§e" + id
                + (summary.isEmpty() ? " §7(普通物品，没有 NBT)" : "§r\n§d" + summary)
                + (nbt == null ? "" : "\n§8" + nbt)), false);
        return 1;
    }

    /** {@code /shop price <买价>} - the held item keeps its category, new items go to the "服主新增" one. */
    private static int shopSetBuyPrice(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("该命令只能由玩家使用（请手持物品）"));
            return 0;
        }
        if (player.getMainHandItem().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("请手持要改价的物品"));
            return 0;
        }
        String banned = com.oktsas.onekeytosetaserver.trade.TradeBlacklist.reasonFor(player.getMainHandItem());
        if (!banned.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(banned + "§7（这一类不能上架）"));
            return 0;
        }
        String id = ShopManager.idOf(player.getMainHandItem());
        String nbt = ShopManager.snbtOf(player.getMainHandItem());
        double price = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "price");
        boolean updated = ShopManager.setBuyPrice(id, nbt, price);
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + id + (nbt == null ? "" : " §7(NBT 条目)")
                + " 的买价已" + (updated ? "更新" : "新增")
                + "为 " + com.oktsas.onekeytosetaserver.economy.MoneyService.format(price)
                + (updated ? "" : "（分类: " + ShopManager.customCategory().title + "）")), true);
        return 1;
    }

    /** {@code /shop recycle <回收价>} - adds or updates one row of the recycle table (NBT included). */
    private static int shopSetRecyclePrice(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("该命令只能由玩家使用（请手持物品）"));
            return 0;
        }
        if (player.getMainHandItem().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("请手持要设置回收价的物品"));
            return 0;
        }
        String banned = com.oktsas.onekeytosetaserver.trade.TradeBlacklist.reasonFor(player.getMainHandItem());
        if (!banned.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(banned + "§7（这一类不回收）"));
            return 0;
        }
        String id = ShopManager.idOf(player.getMainHandItem());
        String nbt = ShopManager.snbtOf(player.getMainHandItem());
        double price = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "price");
        if (!ShopManager.setRecyclePrice(id, nbt, price)) {
            ctx.getSource().sendFailure(Component.literal("§c回收价不能 ≥ 买价（当前买价 "
                    + com.oktsas.onekeytosetaserver.economy.MoneyService.format(ShopManager.buyPriceOf(id, nbt))
                    + "），那会变成刷钱漏洞，已拒绝"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + id + (nbt == null ? "" : " §7(NBT 条目)")
                + " 的回收价已设为 "
                + com.oktsas.onekeytosetaserver.economy.MoneyService.format(price)), true);
        return 1;
    }

    private static int shopRemoveRecycle(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null || player.getMainHandItem().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("请由玩家手持物品执行"));
            return 0;
        }
        String id = ShopManager.idOf(player.getMainHandItem());
        String nbt = ShopManager.snbtOf(player.getMainHandItem());
        boolean removed = nbt == null
                ? ShopManager.removeRecyclePrice(id)
                : ShopManager.removeRecyclePrice(id, nbt);
        if (!removed) {
            ctx.getSource().sendFailure(Component.literal(nbt == null
                    ? id + " 本来就不在回收表里"
                    : id + " 没有这条 NBT 的回收价（回收表里只有通配价，手持普通物品再执行一次可删）"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§c已把 " + id
                + (nbt == null ? "" : " §7(NBT 条目)") + " 从回收表移除"), true);
        return 1;
    }

    private static int shopInfo(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder("§e§l服务器商店§r（可买可卖，共 "
                + ShopManager.categories().size() + " 个分类 / " + ShopManager.itemCount() + " 件商品）");
        for (ShopManager.Category category : ShopManager.categories()) {
            sb.append("\n §7- §f").append(category.id).append(" §8(").append(category.title).append(") §7")
                    .append(category.items.size()).append(" 件");
        }
        sb.append("\n§7购买/出售: 在商店里点商品选数量，然后点「确认购买」或「确认出售」");
        sb.append("\n§7售卖区: §f/sell §7打开箱子，把想卖的物品放进去，点「确认出售」两次即结算，"
                + "不可卖的原样退回");
        sb.append("\n§7回收价表: ").append(ShopManager.recycleTable().size())
                .append(" 条通配价 + ").append(ShopManager.recycleEntries().size()
                        - ShopManager.recycleTable().size())
                .append(" 条 NBT 价（/shop edit 图形化改价，/shop nbt 看手上物品的 NBT）");
        sb.append("\n§e入口: §f/shop §7商店、§f/sell §7售卖区、§f/market §7自由市场、§f/trash §7垃圾桶、"
                + "§f/sellall §7一键卖背包（也可用 §f/menu §7菜单）");
        sb.append("\n§c提醒: §7商店方块（官方商店/售卖区/自由市场/垃圾桶）已删除，"
                + "升级前放在世界里的那些方块会因为方块不存在而变成空气");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int buy(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String item = StringArgumentType.getString(ctx, "item");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        return ShopManager.buy(player, item, amount) ? 1 : 0;
    }

    private static int sellAll(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ShopManager.sellAll(ctx.getSource().getPlayerOrException());
        return 1;
    }
}
