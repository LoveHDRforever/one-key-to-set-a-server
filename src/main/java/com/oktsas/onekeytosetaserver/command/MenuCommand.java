package com.oktsas.onekeytosetaserver.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.oktsas.onekeytosetaserver.menu.MenuEditor;
import com.oktsas.onekeytosetaserver.menu.MenuManager;
import com.oktsas.onekeytosetaserver.menu.MvMenus;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code /menu} command surface: everyone may open menus, only permission level 4 may edit them
 * ("menu editing is globally OP 4 only").
 */
public final class MenuCommand {

    private MenuCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("menu")
                .executes(ctx -> open(ctx, MenuManager.MAIN))
                .then(Commands.literal("list").executes(MenuCommand::list))
                .then(Commands.literal("reload").executes(MenuCommand::reload))
                .then(Commands.literal("edit")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(MenuCommand::edit)))
                .then(Commands.literal("create")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("title", StringArgumentType.greedyString())
                                        .executes(ctx -> create(ctx, 5)))))
                .then(Commands.literal("rows")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("rows", IntegerArgumentType.integer(1, 5))
                                        .executes(MenuCommand::rows))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(MenuCommand::delete)))
                .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> open(ctx,
                        StringArgumentType.getString(ctx, "id")))));
    }

    static int open(CommandContext<CommandSourceStack> ctx, String id) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            list(ctx);
            return 1;
        }
        if (MenuManager.get(id) == null && !MenuManager.isDynamic(id)) {
            ctx.getSource().sendFailure(Component.literal("找不到菜单: " + id));
            return 0;
        }
        MvMenus.open(player, id);
        return 1;
    }

    static int edit(CommandContext<CommandSourceStack> ctx) {
        if (!requireOp4(ctx)) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("这个命令需要玩家执行"));
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id");
        if (MenuManager.isDynamic(id)) {
            ctx.getSource().sendFailure(Component.literal("内置动态菜单（" + MenuManager.WORLDS + "）由服务器生成，不可编辑"));
            return 0;
        }
        if (MenuManager.get(id) == null) {
            ctx.getSource().sendFailure(Component.literal("找不到菜单: " + id + "（可用 /menu create 新建）"));
            return 0;
        }
        MenuEditor.openLayout(player, id);
        return 1;
    }

    static int list(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder("§e§l菜单列表§r (" + MenuManager.all().size() + " 个)");
        for (MenuManager.MenuDef def : MenuManager.all()) {
            sb.append("\n §7- §f").append(def.id)
                    .append(" §8[").append(def.rows).append(" 行, ").append(def.filled()).append(" 个按钮]")
                    .append(def.id.equals(MenuManager.MAIN) ? " §a默认入口" : "");
        }
        sb.append("\n §7内置动态: §f").append(MenuManager.WORLDS).append(" §8(世界列表，不可编辑)");
        sb.append("\n §7打开: §f/menu <id>§7　编辑: §f/menu edit <id>");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    static int reload(CommandContext<CommandSourceStack> ctx) {
        if (!requireOp4(ctx)) {
            return 0;
        }
        MenuManager.reload();
        ctx.getSource().sendSuccess(() -> Component.literal("§a已从 multiverse/menus.json 重新读取菜单"), true);
        return 1;
    }

    static int create(CommandContext<CommandSourceStack> ctx, int rows) {
        if (!requireOp4(ctx)) {
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(java.util.Locale.ROOT);
        String title = StringArgumentType.getString(ctx, "title");
        if (MenuManager.isDynamic(id)) {
            ctx.getSource().sendFailure(Component.literal("该 id 是内置动态菜单保留的"));
            return 0;
        }
        MenuManager.MenuDef def = MenuManager.create(id, title, rows);
        if (def == null) {
            ctx.getSource().sendFailure(Component.literal("创建失败：id 为空或已存在"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a已创建菜单 " + id + "（标题: " + title
                + "，行数 " + rows + "）\n§7用 /menu edit " + id + " 打开编辑器"), true);
        return 1;
    }

    static int rows(CommandContext<CommandSourceStack> ctx) {
        if (!requireOp4(ctx)) {
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(java.util.Locale.ROOT);
        MenuManager.MenuDef def = MenuManager.get(id);
        if (def == null) {
            ctx.getSource().sendFailure(Component.literal("找不到菜单: " + id));
            return 0;
        }
        def.rows = IntegerArgumentType.getInteger(ctx, "rows");
        MenuManager.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a菜单 " + id + " 已改为 " + def.rows + " 行"), true);
        return 1;
    }

    static int delete(CommandContext<CommandSourceStack> ctx) {        if (!requireOp4(ctx)) {
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(java.util.Locale.ROOT);
        if (!MenuManager.delete(id)) {
            ctx.getSource().sendFailure(Component.literal("删除失败：菜单不存在或是内置菜单"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§c已删除菜单 " + id), true);
        return 1;
    }

    private static boolean requireOp4(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().hasPermission(4)) {
            return true;
        }
        ctx.getSource().sendFailure(Component.literal("菜单编辑需要 OP 等级 4"));
        return false;
    }
}
