package com.oktsas.onekeytosetaserver.menu;

import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * In-game chest-menu editor. Only permission level 4 may use it (the menu layout is a server-wide
 * setting, deliberately kept above the level-3 "world administrator").
 *
 * <p>Editing happens on the live definition and is written to disk when the operator presses save.
 * Text fields cannot be typed into a chest screen, so they are collected through the chat bar: the
 * player is asked for the value, the next chat line is captured and the editor reopens on the next
 * tick (opening chat closes the container client side).</p>
 */
public final class MenuEditor {

    private static final int REQUIRED_LEVEL = 4;
    private static final long CONFIRM_MILLIS = 15_000L;

    /** Where each operator currently is: which menu, and which slot (-1 = layout). */
    private static final Map<UUID, State> STATES = new LinkedHashMap<>();
    /** Pending chat prompts. */
    private static final Map<UUID, Prompt> PROMPTS = new LinkedHashMap<>();
    /** One-shot confirmations for destructive buttons (clear / delete). */
    private static final Map<UUID, String> CONFIRM = new LinkedHashMap<>();
    /** Editors to reopen once the chat screen is gone. */
    private static final Map<UUID, Long> REOPEN = new LinkedHashMap<>();

    private record State(String menuId, int slot) {
    }

    private record Prompt(Kind kind, String menuId, int slot) {
    }

    public enum Kind {
        TITLE, NAME, LORE, ARGUMENT
    }

    public MenuEditor() {
    }

    // ------------------------------------------------------------------ opening

    public static void openLayout(ServerPlayer player, String menuId) {
        if (!allowed(player)) {
            return;
        }
        String id = resolveId(menuId);
        if (id == null) {
            player.sendSystemMessage(Component.literal("§c找不到菜单: " + menuId));
            return;
        }
        STATES.put(player.getUUID(), new State(id, -1));
        MvMenus.openEditor(player, id, -1);
    }

    public static void openButton(ServerPlayer player, String menuId, int slot) {
        if (!allowed(player)) {
            return;
        }
        String id = resolveId(menuId);
        if (id == null) {
            player.sendSystemMessage(Component.literal("§c找不到菜单: " + menuId));
            return;
        }
        STATES.put(player.getUUID(), new State(id, slot));
        MvMenus.openEditor(player, id, slot);
    }

    private static String resolveId(String menuId) {
        MenuManager.MenuDef def = MenuManager.get(menuId);
        return def == null ? null : def.id;
    }

    private static boolean allowed(ServerPlayer player) {
        if (!permissionCheck || player.hasPermissions(REQUIRED_LEVEL)) {
            return true;
        }
        player.closeContainer();
        player.sendSystemMessage(Component.literal("§c菜单编辑需要 OP 等级 " + REQUIRED_LEVEL));
        return false;
    }

    /** Seam for the headless self-test, which has no real operator to log in with. */
    private static boolean permissionCheck = true;

    public static void setPermissionCheckForTest(boolean enabled) {
        permissionCheck = enabled;
    }

    // ------------------------------------------------------------------ clicks

    /** Handles one editor click. {@code op} comes from the menu entry argument. */
    public static void click(ServerPlayer player, MvMenus.MvMenu menu, String op) {
        if (!allowed(player)) {
            return;
        }
        MenuManager.MenuDef def = MenuManager.get(menu.menuId());
        if (def == null || !def.editable()) {
            player.closeContainer();
            player.sendSystemMessage(Component.literal("§c内置动态菜单不可编辑"));
            return;
        }
        String plain = op == null ? "" : op;
        if (!plain.startsWith("clear") && !plain.startsWith("delete")) {
            CONFIRM.remove(player.getUUID());
        }
        int slot = menu.editSlot();
        if (plain.startsWith("slot:")) {
            openButton(player, def.id, parseInt(plain.substring(5), -1));
            return;
        }
        if (plain.startsWith("grow:")) {
            int target = parseInt(plain.substring(5), -1);
            if (target >= 0 && target < MenuManager.MAX_ROWS * 9) {
                def.rows = Math.min(MenuManager.MAX_ROWS, target / 9 + 1);
                menu.setEditSlot(-1);
                menu.refresh();
                player.sendSystemMessage(Component.literal("§a行数已改为 " + def.rows + "（记得保存）"));
            }
            return;
        }
        switch (plain) {
            case "save" -> {
                MenuManager.save();
                player.sendSystemMessage(Component.literal("§a菜单 " + def.id + " 已保存到 multiverse/menus.json"));
                menu.refresh();
            }
            case "reload" -> {
                MenuManager.reload();
                openLayout(player, def.id);
                player.sendSystemMessage(Component.literal("§e已放弃未保存的修改"));
            }
            case "title" -> prompt(player, Kind.TITLE, def.id, -1);
            case "rows" -> {
                def.rows = def.rows >= MenuManager.MAX_ROWS ? 1 : def.rows + 1;
                menu.setEditSlot(-1);
                menu.refresh();
                player.sendSystemMessage(Component.literal("§a行数已改为 " + def.rows + "（记得保存）"));
            }
            case "preview" -> MvMenus.open(player, def.id);
            case "help" -> player.sendSystemMessage(Component.literal("""
                    §e§l菜单编辑器帮助
                    §7- 点击布局里的格子即可编辑那个位置的按钮
                    §7- 空位格点为添加，超出范围的灰色格子点一下会扩展行数
                    §7- 名称/说明/参数会请你到聊天栏输入，输入 §fcancel §7取消
                    §7- 修改只在点「保存菜单」后写入文件，「放弃修改」会重新读盘
                    §7- 布局最多 5 行，第 6 行是工具栏"""));
            case "close" -> player.closeContainer();
            case "clear" -> {
                if (!confirm(player, "clear")) {
                    return;
                }
                def.buttons.clear();
                menu.setEditSlot(-1);
                menu.refresh();
                player.sendSystemMessage(Component.literal("§c已清空该菜单的全部按钮（记得保存）"));
            }
            case "delete" -> {
                if (MenuManager.isBuiltin(def.id)) {
                    player.sendSystemMessage(Component.literal("§c内置菜单不能删除"));
                    return;
                }
                if (!confirm(player, "delete")) {
                    return;
                }
                String id = def.id;
                STATES.remove(player.getUUID());
                if (MenuManager.delete(id)) {
                    player.closeContainer();
                    player.sendSystemMessage(Component.literal("§c菜单 " + id + " 已删除"));
                }
            }
            // ------------------------------------------------------ button options
            case "icon" -> {
                MenuManager.Button button = ensure(def, slot);
                if (button == null) {
                    return;
                }
                if (player.getMainHandItem().isEmpty()) {
                    player.sendSystemMessage(Component.literal("§c主手没有物品，先拿着想要的图标再点这里"));
                    return;
                }
                button.icon = MenuManager.snbt(player.getMainHandItem());
                menu.refresh();
                player.sendSystemMessage(Component.literal("§a图标已设为手持物品"));
            }
            case "icon_default" -> {
                MenuManager.Button button = ensure(def, slot);
                if (button != null) {
                    button.icon = MenuManager.snbt(new net.minecraft.world.item.ItemStack(
                            net.minecraft.world.item.Items.PAPER));
                    menu.refresh();
                    player.sendSystemMessage(Component.literal("§a图标已重置为纸"));
                }
            }
            case "name" -> prompt(player, Kind.NAME, def.id, slot);
            case "lore" -> prompt(player, Kind.LORE, def.id, slot);
            case "argument" -> prompt(player, Kind.ARGUMENT, def.id, slot);
            case "action" -> {
                MenuManager.Button button = ensure(def, slot);
                if (button == null) {
                    return;
                }
                MvMenus.Action[] values = {MvMenus.Action.NONE, MvMenus.Action.COMMAND_PLAYER,
                        MvMenus.Action.COMMAND_CONSOLE, MvMenus.Action.OPEN_MENU, MvMenus.Action.CLOSE};
                int index = 0;
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == button.action()) {
                        index = i;
                    }
                }
                button.action = values[(index + 1) % values.length].name();
                menu.refresh();
                player.sendSystemMessage(Component.literal("§a动作已改为 " + button.action()));
            }
            case "remove" -> {
                if (def.at(slot) == null) {
                    player.sendSystemMessage(Component.literal("§7这一格本来就是空的"));
                    return;
                }
                def.remove(slot);
                menu.refresh();
                player.sendSystemMessage(Component.literal("§c已删除槽位 " + slot + " 的按钮（记得保存）"));
            }
            case "back" -> {
                menu.setEditSlot(-1);
                menu.refresh();
            }
            default -> player.sendSystemMessage(Component.literal("§c未知的编辑操作: " + plain));
        }
    }

    /** Two-step confirmation for destructive operations. */
    private static boolean confirm(ServerPlayer player, String op) {
        String previous = CONFIRM.get(player.getUUID());
        if (op.equals(previous)) {
            CONFIRM.remove(player.getUUID());
            return true;
        }
        CONFIRM.put(player.getUUID(), op);
        player.sendSystemMessage(Component.literal("§e再点一次同样的按钮确认操作（"
                + CONFIRM_MILLIS / 1000 + " 秒内有效）"));
        return false;
    }

    private static MenuManager.Button ensure(MenuManager.MenuDef def, int slot) {
        if (slot < 0 || slot >= MenuManager.MAX_ROWS * 9) {
            return null;
        }
        MenuManager.Button button = def.at(slot);
        if (button == null) {
            button = new MenuManager.Button(new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.PAPER), "", MvMenus.Action.NONE, "");
            def.set(slot, button);
        }
        if (slot >= def.slotCount()) {
            def.rows = Math.min(MenuManager.MAX_ROWS, slot / 9 + 1);
        }
        return button;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ text input

    private static void prompt(ServerPlayer player, Kind kind, String menuId, int slot) {
        PROMPTS.put(player.getUUID(), new Prompt(kind, menuId, slot));
        String what = switch (kind) {
            case TITLE -> "菜单标题";
            case NAME -> "按钮名称";
            case LORE -> "按钮说明（可用 | 分隔多行，输入 - 清空）";
            case ARGUMENT -> "参数（指令内容或目标菜单 id，输入 - 清空）";
        };
        player.closeContainer();
        player.sendSystemMessage(Component.literal("§e请在聊天栏输入" + what + "，输入 §fcancel §e取消"));
    }

    /** Applies typed text. Also called directly by the headless test. */
    public static boolean applyText(ServerPlayer player, Kind kind, String menuId, int slot, String text) {
        MenuManager.MenuDef def = MenuManager.get(menuId);
        if (def == null) {
            return false;
        }
        switch (kind) {
            case TITLE -> def.title = text;
            case NAME -> {
                MenuManager.Button button = ensure(def, slot);
                if (button == null) {
                    return false;
                }
                button.name = text;
            }
            case LORE -> {
                MenuManager.Button button = ensure(def, slot);
                if (button == null) {
                    return false;
                }
                button.lore = new ArrayList<>();
                for (String line : text.split("\\|")) {
                    button.lore.add(line);
                }
            }
            case ARGUMENT -> {
                MenuManager.Button button = ensure(def, slot);
                if (button == null) {
                    return false;
                }
                button.argument = text;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Test entry point: applies a prompt as if it had been typed in chat. */
    public static boolean submitText(ServerPlayer player, String kind, String text) {
        Prompt prompt = PROMPTS.get(player.getUUID());
        if (prompt == null) {
            return false;
        }
        PROMPTS.remove(player.getUUID());
        boolean ok = applyText(player, prompt.kind(), prompt.menuId(), prompt.slot(), text);
        REOPEN.put(player.getUUID(), System.currentTimeMillis());
        return ok;
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        Prompt prompt = PROMPTS.remove(player.getUUID());
        if (prompt == null) {
            return;
        }
        event.setCanceled(true);
        String text = event.getRawText() == null ? "" : event.getRawText().trim();
        if (text.equalsIgnoreCase("cancel")) {
            player.sendSystemMessage(Component.literal("§7已取消输入"));
        } else {
            String value = text.equals("-") ? "" : text;
            if (applyText(player, prompt.kind(), prompt.menuId(), prompt.slot(), value)) {
                player.sendSystemMessage(Component.literal("§a已更新（记得点保存）"));
            } else {
                player.sendSystemMessage(Component.literal("§c菜单已不存在，修改未生效"));
            }
        }
        REOPEN.put(player.getUUID(), System.currentTimeMillis());
    }

    /** Brings the editor back after a chat prompt; the client closed the container to open chat. */
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || REOPEN.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        long now = System.currentTimeMillis();
        List<UUID> done = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : REOPEN.entrySet()) {
            if (now - entry.getValue() < 50L) {
                continue;
            }
            done.add(entry.getKey());
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            State state = STATES.get(entry.getKey());
            if (player != null && state != null) {
                MvMenus.openEditor(player, state.menuId(), state.slot());
            }
        }
        for (UUID id : done) {
            REOPEN.remove(id);
        }
        if (CONFIRM.size() > 64) {
            CONFIRM.clear();
        }
    }

    /** Clears per-player editor state (players are gone after a stop/start cycle). */
    public static void clear() {
        STATES.clear();
        PROMPTS.clear();
        CONFIRM.clear();
        REOPEN.clear();
    }

    /** Lower-cased kind name, used by the test entry point. */
    public static Kind kindOf(String name) {
        try {
            return Kind.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return Kind.NAME;
        }
    }
}
