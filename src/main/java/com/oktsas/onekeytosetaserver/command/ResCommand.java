package com.oktsas.onekeytosetaserver.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.oktsas.onekeytosetaserver.domain.Domain;
import com.oktsas.onekeytosetaserver.domain.DomainManager;
import com.oktsas.onekeytosetaserver.economy.MoneyService;
import com.oktsas.onekeytosetaserver.teleport.MvTeleporter;
import com.oktsas.onekeytosetaserver.teleport.TeleportService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * {@code /res} - the claim command surface, modelled on the Residence plugin that the Bukkit server ran
 * before this mod existed (same sub-command names, same Chinese wording where it makes sense).
 *
 * <p>Everything is {@code op 0} except {@code /res admin ...}, which is op 4, and dismantling somebody
 * else's claim, which is one op 4 command plus the {@code allowPlayerDeleteOthers} config switch.</p>
 */
public final class ResCommand {

    private ResCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("res")
                .executes(ResCommand::help)

                .then(Commands.literal("wand").executes(ResCommand::wand))

                .then(Commands.literal("create")
                        .then(Commands.argument("名字", StringArgumentType.greedyString()).executes(ResCommand::create)))

                .then(Commands.literal("remove")
                        .then(Commands.argument("名字", StringArgumentType.greedyString()).executes(ResCommand::remove)))

                .then(Commands.literal("rename")
                        .then(Commands.argument("名字", StringArgumentType.string())
                                .then(Commands.argument("新名字", StringArgumentType.greedyString())
                                        .executes(ResCommand::rename))))

                .then(Commands.literal("tp")
                        .then(Commands.argument("名字", StringArgumentType.greedyString()).executes(ResCommand::tp)))

                .then(Commands.literal("list").executes(ResCommand::list))

                .then(Commands.literal("info")
                        .executes(ResCommand::infoHere)
                        .then(Commands.argument("名字", StringArgumentType.greedyString()).executes(ResCommand::info)))

                .then(Commands.literal("select")
                        .executes(ResCommand::select)
                        .then(Commands.literal("clear").executes(ResCommand::selectClear)))

                .then(Commands.literal("height")
                        .then(Commands.literal("up")
                                .then(Commands.argument("格数", IntegerArgumentType.integer(1, 384))
                                        .executes(ctx -> height(ctx, true))))
                        .then(Commands.literal("down")
                                .then(Commands.argument("格数", IntegerArgumentType.integer(1, 384))
                                        .executes(ctx -> height(ctx, false)))))

                .then(Commands.literal("set")
                        // the short form works on the claim the player is standing in, so a Chinese name
                        // never has to be quoted
                        .then(Commands.argument("标志", StringArgumentType.string())
                                .then(Commands.argument("on|off", StringArgumentType.greedyString())
                                        .executes(ctx -> set(ctx, true))))
                        .then(Commands.argument("名字", StringArgumentType.string())
                                .then(Commands.argument("标志", StringArgumentType.string())
                                        .then(Commands.argument("on|off", StringArgumentType.greedyString())
                                                .executes(ctx -> set(ctx, false))))))

                .then(Commands.literal("pset")
                        .then(Commands.argument("玩家", StringArgumentType.string())
                                .then(Commands.argument("标志", StringArgumentType.string())
                                        .then(Commands.argument("on|off|remove", StringArgumentType.greedyString())
                                                .executes(ctx -> pset(ctx, true)))))
                        .then(Commands.argument("名字", StringArgumentType.string())
                                .then(Commands.argument("玩家", StringArgumentType.string())
                                        .then(Commands.argument("标志", StringArgumentType.string())
                                                .then(Commands.argument("on|off|remove",
                                                                StringArgumentType.greedyString())
                                                        .executes(ctx -> pset(ctx, false)))))))

                .then(Commands.literal("trust")
                        .then(Commands.argument("名字", StringArgumentType.string())
                                .then(Commands.argument("玩家", StringArgumentType.string())
                                        .executes(ctx -> trust(ctx, true)))))

                .then(Commands.literal("untrust")
                        .then(Commands.argument("名字", StringArgumentType.string())
                                .then(Commands.argument("玩家", StringArgumentType.string())
                                        .executes(ctx -> trust(ctx, false)))))

                .then(Commands.literal("transfer")
                        .then(Commands.argument("名字", StringArgumentType.string())
                                .then(Commands.argument("玩家", StringArgumentType.string())
                                        .executes(ResCommand::transfer))))

                .then(Commands.literal("give")
                        .then(Commands.argument("名字", StringArgumentType.string())
                                .then(Commands.argument("玩家", StringArgumentType.string())
                                        .executes(ResCommand::transfer))))

                .then(Commands.literal("limits").executes(ResCommand::limits))
                .then(Commands.literal("flags").executes(ResCommand::flags))

                .then(Commands.literal("admin")
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.literal("list").executes(ResCommand::adminList))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("名字", StringArgumentType.greedyString())
                                        .executes(ResCommand::adminDelete)))
                        .then(Commands.literal("price")
                                .then(Commands.argument("单价", com.mojang.brigadier.arguments.DoubleArgumentType
                                        .doubleArg(0.0D))
                                        .executes(ResCommand::adminPrice)))
                        .then(Commands.literal("reload").executes(ResCommand::adminReload)))
        );
    }

    // ------------------------------------------------------------------ help / wand

    private static int help(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder("§e§l领地（/res）指令");
        sb.append("\n§7/res wand §f领取领地法杖（左键=点1，右键=点2，不能耕地）");
        sb.append("\n§7/res create <名字> §f用选区建立领地（按体积收费）");
        sb.append("\n§7/res remove <名字> §f删除自己的领地");
        sb.append("\n§7/res rename <名字> <新名> §f改名　§7/res transfer <名字> <玩家> §f转让");
        sb.append("\n§7/res tp <名字> §f传送到自己的领地（3 秒，跳跃打断）");
        sb.append("\n§7/res list §f我的领地　§7/res info [名字] §f查看详情");
        sb.append("\n§7/res select §f查看选区与费用　§7/res select clear §f清空选区");
        sb.append("\n§7/res height up|down <格数> §f调整选区高度");
        sb.append("\n§7/res set <名字> <标志> <on|off> §f领地保护开关");
        sb.append("\n§7/res pset <名字> <玩家> <标志> <on|off|remove> §f给某人单独开口子");
        sb.append("\n§7/res trust|untrust <名字> <玩家> §f添加/移除成员（成员不受保护限制）");
        sb.append("\n§7/res limits §f查看价格与上限　§7/res flags §f查看 7 个保护标志");
        sb.append("\n§7/res admin list|delete|price|reload §f(OP4) 管理全部领地（reload = 重新读盘，文件坏了不会清空）");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int wand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        var stack = DomainManager.wand();
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a已给你一根领地法杖：§f左键点方块=第一个点，右键点方块=第二个点§a，然后 "
                        + "§f/res create <名字>§a（这把锄头不能耕地）"), false);
        return 1;
    }

    // ------------------------------------------------------------------ crud

    private static int create(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        DomainManager.Result result = DomainManager.create(player, StringArgumentType.getString(ctx, "名字"));
        if (!result.ok()) {
            ctx.getSource().sendFailure(Component.literal(result.error()
                    + (result.volume() > 0 ? "§7（本次选区 " + result.volume() + " 格，费用 "
                    + MoneyService.format(result.cost()) + "）" : "")));
            return 0;
        }
        Domain domain = result.domain();
        double balance = MoneyService.get(player.getServer(), player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("§a你创建了领地 §6" + domain.name + "§a! 体积 §f"
                + domain.sizeX() + "×" + domain.sizeY() + "×" + domain.sizeZ() + " = " + result.volume()
                + " 格§a，花费 §6" + MoneyService.format(result.cost()) + "§a，余额 §f"
                + MoneyService.format(balance)), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        Domain domain = DomainManager.resolve(player, StringArgumentType.getString(ctx, "名字"));
        if (domain == null) {
            ctx.getSource().sendFailure(Component.literal("§c无效的领地。"));
            return 0;
        }
        if (!DomainManager.mayManage(player, domain)) {
            ctx.getSource().sendFailure(Component.literal("§c你不是该领地的所有者。"));
            return 0;
        }
        String name = domain.name;
        if (!DomainManager.delete(domain)) {
            // the store is locked: dropping the claim in memory would only come back on the next restart
            ctx.getSource().sendFailure(Component.literal("§c删除被拒绝：领地存档当前不可写入（res.json 读取失败"
                    + "或含无法解析的条目）。\n§7请管理员修复后执行 §f/res admin reload §7再删除。"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§e已将领地 §6" + name + "§e删除。"), true);
        return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        Domain domain = DomainManager.resolve(player, StringArgumentType.getString(ctx, "名字"));
        if (domain == null) {
            ctx.getSource().sendFailure(Component.literal("§c无效的领地。"));
            return 0;
        }
        if (!DomainManager.mayManage(player, domain)) {
            ctx.getSource().sendFailure(Component.literal("§c你不是该领地的所有者。"));
            return 0;
        }
        String fresh = StringArgumentType.getString(ctx, "新名字").trim();
        if (fresh.isEmpty() || fresh.length() > DomainManager.MAX_NAME_LENGTH) {
            ctx.getSource().sendFailure(Component.literal("§c领地名字最多 " + DomainManager.MAX_NAME_LENGTH
                    + " 个字，且不能为空"));
            return 0;
        }
        if (DomainManager.byName(domain.dimension, fresh) != null) {
            ctx.getSource().sendFailure(Component.literal("§c此区域名已存在。"));
            return 0;
        }
        String old = domain.name;
        domain.name = fresh;
        DomainManager.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§e已将领地 §6" + old + " §e重命名为 §6"
                + fresh + "§e。" + DomainManager.writeWarning()), true);
        return 1;
    }

    private static int transfer(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        Domain domain = DomainManager.resolve(player, StringArgumentType.getString(ctx, "名字"));
        if (domain == null) {
            ctx.getSource().sendFailure(Component.literal("§c无效的领地。"));
            return 0;
        }
        if (!DomainManager.mayManage(player, domain)) {
            ctx.getSource().sendFailure(Component.literal("§c你不是该领地的所有者。"));
            return 0;
        }
        ServerPlayer target = ctx.getSource().getServer().getPlayerList()
                .getPlayerByName(StringArgumentType.getString(ctx, "玩家"));
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        if (DomainManager.ownedAll(domain.dimension, target.getUUID()).size()
                >= Math.max(1, DomainManager.config().maxClaimsPerPlayer)
                && !target.hasPermissions(DomainManager.config().opLevel)) {
            ctx.getSource().sendFailure(Component.literal("§c对方在这个世界已经有领地了（数量已满）"));
            return 0;
        }
        String former = domain.ownerName;
        domain.owner = target.getUUID().toString();
        domain.ownerName = target.getGameProfile().getName();
        domain.members.put(domain.owner, domain.ownerName);
        DomainManager.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§e已将 §6" + domain.name + " §e的所有者由 §6"
                + former + " §e更改为 §6" + domain.ownerName + "§e。" + DomainManager.writeWarning()), true);
        target.sendSystemMessage(Component.literal("§e你获得了一个领地: §f" + domain.name));
        return 1;
    }

    // ------------------------------------------------------------------ lookups

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        String dimension = DomainManager.dimensionOf(player.level());
        List<Domain> mine = DomainManager.ownedAll(dimension, player.getUUID());
        if (mine.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e你在这个世界还没有领地，用 §6/res wand §e开始圈地"), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder("§e§l我的领地§r §7（" + dimension + "）");
        int index = 1;
        for (Domain domain : mine) {
            sb.append("\n §a").append(index++).append(". §6").append(domain.name)
                    .append(" §e- §6世界§e: §6").append(domain.world)
                    .append(" §7体积 ").append(domain.sizeX()).append("×").append(domain.sizeY())
                    .append("×").append(domain.sizeZ()).append(" = ").append(domain.volume()).append(" 格");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return mine.size();
    }

    private static int infoHere(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        Domain domain = DomainManager.at(player.level(), player.blockPosition());
        if (domain == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7你当前不在任何领地里"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(DomainManager.describe(domain)), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        Domain domain = DomainManager.resolve(player,
                StringArgumentType.getString(ctx, "名字"));
        if (domain == null) {
            ctx.getSource().sendFailure(Component.literal("§c无效的领地。"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(DomainManager.describe(domain)), false);
        return 1;
    }

    private static int select(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        DomainManager.Selection selection = DomainManager.selectionOf(player.getUUID());
        if (selection == null || selection.first() == null) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e还没有选区：/res wand 拿法杖，§f左键§e点第一个点，§f右键§e点第二个点"), false);
            return 0;
        }
        String body = "§e第一个点: §6" + show(selection.first())
                + (selection.second() == null ? "\n§7（还没点第二个点）"
                : "\n§e第二个点: §6" + show(selection.second()))
                + "\n§e选区总大小: §6" + DomainManager.preview(selection);
        ctx.getSource().sendSuccess(() -> Component.literal(body), false);
        return 1;
    }

    private static int selectClear(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        DomainManager.clearSelection(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("§e已清空选区"), false);
        return 1;
    }

    private static int height(CommandContext<CommandSourceStack> ctx, boolean up) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        int amount = IntegerArgumentType.getInteger(ctx, "格数");
        String message = DomainManager.adjustHeight(player, up, amount);
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    // ------------------------------------------------------------------ flags

    private static int set(CommandContext<CommandSourceStack> ctx, boolean here) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        Domain domain = here ? DomainManager.at(player.level(), player.blockPosition())
                : DomainManager.resolve(player, StringArgumentType.getString(ctx, "名字"));
        if (domain == null) {
            ctx.getSource().sendFailure(Component.literal(here
                    ? "§c你不在任何领地里，请写上领地名字"
                    : "§c无效的领地。"));
            return 0;
        }
        if (!DomainManager.mayManage(player, domain)) {
            ctx.getSource().sendFailure(Component.literal("§c你不是该领地的所有者。"));
            return 0;
        }
        Domain.Flag flag = Domain.Flag.byId(StringArgumentType.getString(ctx, "标志"));
        if (flag == null) {
            ctx.getSource().sendFailure(Component.literal("§c无效的权限。可用标志见 /res flags"));
            return 0;
        }
        Boolean value = DomainManager.parseToggle(StringArgumentType.getString(ctx, "on|off"));
        if (value == null) {
            ctx.getSource().sendFailure(Component.literal("§c无效的权限状态, 必须为 §6on§c(t) 或 §6off§c(f)"));
            return 0;
        }
        domain.setDeny(flag, value);
        DomainManager.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§e已将领地 §6" + domain.name + " §e的 §6"
                + flag.title + " §e权限设置为 §6" + (value ? "on（禁止外人）" : "off（允许外人）") + "§e。"
                + DomainManager.writeWarning()), true);
        return 1;
    }

    private static int pset(CommandContext<CommandSourceStack> ctx, boolean here) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        Domain domain = here ? DomainManager.at(player.level(), player.blockPosition())
                : DomainManager.resolve(player, StringArgumentType.getString(ctx, "名字"));
        if (domain == null) {
            ctx.getSource().sendFailure(Component.literal(here
                    ? "§c你不在任何领地里，请写上领地名字"
                    : "§c无效的领地。"));
            return 0;
        }
        if (!DomainManager.mayManage(player, domain)) {
            ctx.getSource().sendFailure(Component.literal("§c你不是该领地的所有者。"));
            return 0;
        }
        Domain.Flag flag = Domain.Flag.byId(StringArgumentType.getString(ctx, "标志"));
        if (flag == null) {
            ctx.getSource().sendFailure(Component.literal("§c无效的权限。可用标志见 /res flags"));
            return 0;
        }
        String targetName = StringArgumentType.getString(ctx, "玩家");
        ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayerByName(targetName);
        String raw = StringArgumentType.getString(ctx, "on|off|remove");
        Boolean value = DomainManager.parseToggle(raw);
        if (value == null && !"remove".equalsIgnoreCase(raw) && !"清除".equals(raw)) {
            ctx.getSource().sendFailure(Component.literal(
                    "§c无效的权限状态, 必须为 §6on§c / §6off§c / §6remove"));
            return 0;
        }
        java.util.UUID uuid = online != null ? online.getUUID() : offlineUuid(domain, targetName);
        if (uuid == null) {
            ctx.getSource().sendFailure(Component.literal("§c没有这个玩家（需要在线的玩家，或本领地成员）"));
            return 0;
        }
        String resolvedName = online != null ? online.getGameProfile().getName()
                : domain.members.getOrDefault(uuid.toString(), targetName);
        domain.setOverride(uuid, flag, value);
        DomainManager.save();
        if (value == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("§e已清除玩家 §6" + resolvedName
                    + " §e在领地 §6" + domain.name + " §e的 §6" + flag.title + " §e单独设置。"
                    + DomainManager.writeWarning()), true);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal("§e已将玩家 §6" + resolvedName
                    + " §e在领地 §6" + domain.name + " §e的 §6" + flag.title + " §e权限设置为 §6"
                    + (value ? "on（禁止他）" : "off（允许他）") + "§e。" + DomainManager.writeWarning()), true);
        }
        return 1;
    }

    /** Finds a member uuid by name so offline members can be configured too. */
    private static java.util.UUID offlineUuid(Domain domain, String name) {
        for (var entry : domain.members.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                try {
                    return java.util.UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static int trust(CommandContext<CommandSourceStack> ctx, boolean add) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        Domain domain = DomainManager.resolve(player, StringArgumentType.getString(ctx, "名字"));
        if (domain == null) {
            ctx.getSource().sendFailure(Component.literal("§c无效的领地。"));
            return 0;
        }
        if (!DomainManager.mayManage(player, domain)) {
            ctx.getSource().sendFailure(Component.literal("§c你不是该领地的所有者。"));
            return 0;
        }
        ServerPlayer target = ctx.getSource().getServer().getPlayerList()
                .getPlayerByName(StringArgumentType.getString(ctx, "玩家"));
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("§c玩家不在线。"));
            return 0;
        }
        if (add) {
            if (domain.isOwner(target.getUUID())) {
                ctx.getSource().sendFailure(Component.literal("§c主人本来就是成员。"));
                return 0;
            }
            domain.members.put(target.getUUID().toString(), target.getGameProfile().getName());
        } else {
            if (domain.isOwner(target.getUUID())) {
                ctx.getSource().sendFailure(Component.literal("§c不能把主人移出领地。"));
                return 0;
            }
            domain.members.remove(target.getUUID().toString());
        }
        DomainManager.save();
        String who = target.getGameProfile().getName();
        ctx.getSource().sendSuccess(() -> Component.literal("§e已将领地 §6" + domain.name + " §e的成员 §6"
                + who + " §e" + (add ? "添加" : "移除") + "。" + DomainManager.writeWarning()), true);
        target.sendSystemMessage(Component.literal(add
                ? "§e你被加入了领地 §f" + domain.name
                : "§e你被移出了领地 §f" + domain.name));
        return 1;
    }

    private static int flags(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder("§e§l领地保护标志（7 个，on = 禁止外人，off = 允许外人）");
        for (Domain.Flag flag : Domain.Flag.values()) {
            sb.append("\n §6").append(flag.id).append(" §7= §f").append(flag.title);
        }
        sb.append("\n§7用法: §f/res set <领地> <标志> <on|off>");
        sb.append("\n§7单人开口子: §f/res pset <领地> <玩家> <标志> <on|off|remove>");
        sb.append("\n§7说明: 领地主人和 §f/res trust §7的成员永远不受限制；op4 无视一切保护。");
        sb.append("\n§7默认全部为 on（禁止外人）；设为 off 就允许所有外人做这件事。");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    // ------------------------------------------------------------------ limits

    private static int limits(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        int owned = 0;
        boolean op = false;
        if (player != null) {
            owned = DomainManager.ownedAll(DomainManager.dimensionOf(player.level()), player.getUUID()).size();
            op = player.hasPermissions(DomainManager.config().opLevel);
        }
        int finalOwned = owned;
        boolean finalOp = op;
        var config = DomainManager.config();
        StringBuilder sb = new StringBuilder("§e§l领地限制");
        sb.append("\n §7- §e领地每方块价格:§3 ").append(MoneyService.format(config.pricePerBlock));
        sb.append("\n §7- §e最大领地数量:§3 ")
                .append(config.maxClaimsPerPlayer <= 0 ? "不限" : String.valueOf(config.maxClaimsPerPlayer));
        sb.append("\n §7- §e允许创建领地:§3 是");
        sb.append("\n §7- §e你拥有的领地数量:§3 ").append(finalOwned);
        sb.append("\n §7- §e领地管理员(op").append(config.opLevel).append("):§3 ").append(finalOp ? "是" : "否");
        sb.append("\n §7- §e普通玩家能否拆别人的领地:§3 ")
                .append(config.allowPlayerDeleteOthers ? "能" : "不能（只有 op4）");
        sb.append("\n §7- §e体积上限:§3 不限");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    // ------------------------------------------------------------------ admin

    private static int adminList(CommandContext<CommandSourceStack> ctx) {
        List<Domain> domains = DomainManager.all();
        StringBuilder sb = new StringBuilder("§e§l全服领地§r (" + domains.size() + " 个)");
        for (Domain domain : domains) {
            sb.append("\n §8[id ").append(domain.id).append("] §7主人 §f").append(domain.ownerName)
                    .append(" §7世界 §f").append(domain.world)
                    .append(" §7名字 §6").append(domain.name)
                    .append(" §7体积 ").append(domain.volume()).append(" 格");
        }
        if (domains.isEmpty()) {
            sb.append("\n §7（还没有领地）");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return domains.size();
    }

    private static int adminDelete(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "名字");
        Domain domain = DomainManager.byId(key);
        if (domain == null) {
            domain = DomainManager.byName(null, key);
        }
        if (domain == null) {
            for (Domain candidate : DomainManager.all()) {
                if (candidate.name.equalsIgnoreCase(key)) {
                    domain = candidate;
                    break;
                }
            }
        }
        if (domain == null) {
            ctx.getSource().sendFailure(Component.literal("§c找不到该领地（可用 /res admin list 查 id）"));
            return 0;
        }
        String name = domain.name;
        String owner = domain.ownerName;
        if (!DomainManager.delete(domain)) {
            ctx.getSource().sendFailure(Component.literal("§c拆除被拒绝：领地存档当前不可写入（res.json 读取失败"
                    + "或含无法解析的条目）。\n§7请先修复文件并执行 §f/res admin reload§7。"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§c已拆除领地 §6" + name + "§c（主人 " + owner + "）"),
                true);
        return 1;
    }

    private static int adminPrice(CommandContext<CommandSourceStack> ctx) {
        double price = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "单价");
        DomainManager.setPricePerBlock(price);
        ctx.getSource().sendSuccess(() -> Component.literal("§a领地单价已设为 §6"
                + MoneyService.format(DomainManager.config().pricePerBlock)
                + " §a元/格（1 格 = 1 个方块，长×宽×高）"), true);
        return 1;
    }

    /**
     * op4: re-reads {@code multiverse/res.json} for real. A file that cannot be read (or a claim store that
     * turns out to be empty because of a typo) leaves the live claims alone instead of wiping them.
     */
    private static int adminReload(CommandContext<CommandSourceStack> ctx) {
        DomainManager.ReloadResult result = DomainManager.reload();
        if (!result.ok()) {
            ctx.getSource().sendFailure(Component.literal("§c重新读取失败：" + result.error()
                    + "§7（内存里的 " + result.previous() + " 个领地保持不变，res.json 未被覆盖；"
                    + "在这之前 mod 也不会再写这个文件）"));
            return 0;
        }
        String tail = result.skipped() > 0
                ? "§e，跳过 " + result.skipped() + " 条不完整的数据（§7这些记录只在文件里，已锁定写入，"
                + "修好它们再 /res admin reload§e）"
                : "";
        if (result.skipped() > 0) {
            // yellow, not green: the reload worked, but the store is now write-locked on purpose
            ctx.getSource().sendSuccess(() -> Component.literal("§e已重新读取 multiverse/res.json：§f"
                    + result.loaded() + " §e个领地（之前 §f" + result.previous() + " §e个）" + tail), true);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a已重新读取 multiverse/res.json：§f"
                + result.loaded() + " §a个领地（之前 §f" + result.previous() + " §a个）"), true);
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    private static String show(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Landing spot for {@code /res tp}: the middle of the claim, on top of the terrain. */
    public static void teleportToClaim(ServerPlayer player, Domain domain, boolean withDelay) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ResourceLocation key = ResourceLocation.tryParse(domain.dimension);
        ServerLevel level = key == null ? null : server.getLevel(net.minecraft.resources.ResourceKey
                .create(net.minecraft.core.registries.Registries.DIMENSION, key));
        if (level == null && key != null) {
            // runtime injected worlds are also reachable through the world list
            for (com.oktsas.onekeytosetaserver.world.MvWorld world
                    : com.oktsas.onekeytosetaserver.world.MvWorldManager.all()) {
                if (com.oktsas.onekeytosetaserver.world.MvWorldManager.dimensionKey(world).location()
                        .equals(key)) {
                    level = com.oktsas.onekeytosetaserver.world.MvWorldManager.levelOf(world);
                    break;
                }
            }
        }
        if (level == null) {
            player.sendSystemMessage(Component.literal("§c领地的世界未加载"));
            return;
        }
        ServerLevel target = level;
        int centerX = (domain.minX + domain.maxX) / 2;
        int centerZ = (domain.minZ + domain.maxZ) / 2;
        BlockPos spot = MvTeleporter.findSafeSpot(target, centerX, centerZ,
                Math.min(domain.maxY + 1, target.getMaxBuildHeight() - 1));
        Runnable action = () -> {
            MvTeleporter.teleportPlayer(player, target, spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D,
                    player.getYRot(), player.getXRot());
            player.sendSystemMessage(Component.literal("§e已传送到领地附近。"));
        };
        if (withDelay) {
            TeleportService.request(player, 3, "领地 " + domain.name, true, true, action);
        } else {
            action.run();
        }
    }

    private static int tp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Domain domain = DomainManager.resolve(player, StringArgumentType.getString(ctx, "名字"));
        if (domain == null) {
            ctx.getSource().sendFailure(Component.literal("§c无效的领地。"));
            return 0;
        }
        if (!DomainManager.mayManage(player, domain) && !domain.isMember(player.getUUID())) {
            ctx.getSource().sendFailure(Component.literal("§c你不是该领地的所有者或成员。"));
            return 0;
        }
        teleportToClaim(player, domain, true);
        return 1;
    }
}
