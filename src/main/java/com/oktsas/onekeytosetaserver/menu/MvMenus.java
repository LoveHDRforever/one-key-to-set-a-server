package com.oktsas.onekeytosetaserver.menu;

import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.teleport.MvTeleporter;
import com.oktsas.onekeytosetaserver.world.MvWorld;
import com.oktsas.onekeytosetaserver.world.MvWorldManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Chest-GUI menu framework.
 *
 * <p>Uses vanilla's {@code GENERIC_9xN} menu types, so plain clients see a normal chest screen - the mod
 * does not have to be installed on the client for menus to work. Slots are display-only copies and clicks are
 * handled server side, which keeps the usual "menu dupe" class of bugs out.</p>
 *
 * <p>Content comes from {@link MenuManager} (operator editable) except for the dynamic
 * {@link #WORLDS} list, which is generated from the loaded worlds. The same class renders the operator
 * editor: layout mode shows every slot, button mode shows the options of one slot.</p>
 */
public final class MvMenus {

    public static final String MAIN = MenuManager.MAIN;
    public static final String WORLDS = MenuManager.WORLDS;

    private MvMenus() {
    }

    public enum Action {
        NONE,
        COMMAND_PLAYER,
        COMMAND_CONSOLE,
        OPEN_MENU,
        CLOSE,
        /** Operator-editor operation; never persisted in menus.json. */
        EDITOR
    }

    public record Entry(ItemStack icon, Action action, String argument) {
    }

    public static void open(ServerPlayer player, String menuId) {
        MenuManager.MenuDef def = MenuManager.get(menuId);
        String id = def != null ? def.id : menuId;
        player.openMenu(new SimpleMenuProvider(
                (windowId, inventory, p) -> new MvMenu(windowId, inventory, player, id, false, -1),
                title(id)));
    }

    /** Opens the operator editor for a menu in layout mode. */
    public static void openEditor(ServerPlayer player, String menuId, int editSlot) {
        MenuManager.MenuDef def = MenuManager.get(menuId);
        String id = def != null ? def.id : menuId;
        player.openMenu(new SimpleMenuProvider(
                (windowId, inventory, p) -> new MvMenu(windowId, inventory, player, id, true, editSlot),
                Component.literal("§8[编辑] " + title(id).getString())));
    }

    private static Component title(String menuId) {
        MenuManager.MenuDef def = MenuManager.get(menuId);
        if (def != null) {
            return Component.literal(def.title);
        }
        return Component.literal(WORLDS.equals(menuId) ? "§8世界列表" : "§8世界菜单");
    }

    /** Exposed for headless tests. */
    public static MvMenu create(int id, Inventory inventory, ServerPlayer player, String menuId) {
        return new MvMenu(id, inventory, player, menuId, false, -1);
    }

    /** Exposed for headless tests: {@code editSlot < 0} means layout mode. */
    public static MvMenu createEdit(int id, Inventory inventory, ServerPlayer player, String menuId, int editSlot) {
        return new MvMenu(id, inventory, player, menuId, true, editSlot);
    }

    public static final class MvMenu extends AbstractContainerMenu {
        private final String menuId;
        private final boolean editor;
        private final int slotCount;
        private final SimpleContainer container;
        private final Entry[] entries = new Entry[54];
        private int editSlot;
        private final ServerPlayer viewer;

        MvMenu(int id, Inventory inventory, ServerPlayer player, String menuId, boolean editor, int editSlot) {
            super(menuType(editor ? 6
                    : WORLDS.equals(menuId) ? 6
                    : Math.max(1, Math.min(MenuManager.MAX_ROWS,
                            MenuManager.get(menuId) == null ? 6 : MenuManager.get(menuId).rows))), id);
            this.menuId = MenuManager.isDynamic(menuId) ? WORLDS : menuId;
            this.editor = editor;
            this.editSlot = editSlot;
            this.viewer = player;
            this.slotCount = editor ? 54 : (WORLDS.equals(this.menuId) ? 54
                    : Math.max(1, Math.min(MenuManager.MAX_ROWS,
                            MenuManager.get(this.menuId) == null ? 1 : MenuManager.get(this.menuId).rows)) * 9);
            this.container = new SimpleContainer(slotCount);
            build();
            for (int slot = 0; slot < slotCount; slot++) {
                addSlot(new DisplaySlot(container, slot, 8 + (slot % 9) * 18, 18 + (slot / 9) * 18));
            }
            // The client always shows a vanilla chest menu (container rows + 36 inventory + 9 hotbar), so the
            // server has to register the same layout - otherwise a shift click desyncs into a ghost item.
            if (MenuSlots.showPlayerInventory()) {
                for (int i = 0; i < MenuSlots.PLAYER_SLOTS; i++) {
                    addSlot(MenuSlots.playerInventorySlot(inventory, i, Math.max(1, slotCount / 9)));
                }
            }
        }

        private static MenuType<?> menuType(int rows) {
            return switch (Math.max(1, Math.min(6, rows))) {
                case 1 -> MenuType.GENERIC_9x1;
                case 2 -> MenuType.GENERIC_9x2;
                case 3 -> MenuType.GENERIC_9x3;
                case 4 -> MenuType.GENERIC_9x4;
                case 5 -> MenuType.GENERIC_9x5;
                default -> MenuType.GENERIC_9x6;
            };
        }

        public String menuId() {
            return menuId;
        }

        public boolean isEditor() {
            return editor;
        }

        public int editSlot() {
            return editSlot;
        }

        public void setEditSlot(int slot) {
            this.editSlot = slot;
        }

        public int slotCount() {
            return slotCount;
        }

        /** "layout" or "button:<slot>" - handy for assertions in the headless test. */
        public String screen() {
            if (!editor) {
                return "view";
            }
            return editSlot < 0 ? "layout" : "button:" + editSlot;
        }

        /** Re-renders the whole screen after a change. */
        public void refresh() {
            for (int slot = 0; slot < slotCount; slot++) {
                container.setItem(slot, ItemStack.EMPTY);
                entries[slot] = null;
            }
            build();
        }

        private void build() {
            if (editor) {
                buildEditor(container, viewer);
            } else if (WORLDS.equals(menuId)) {
                fillWorlds(container, viewer);
            } else if (!fillConfigured(container, viewer)) {
                fillMain(container, viewer);
            }
        }

        // -------------------------------------------------------------- viewing

        /** Renders the operator configured menu; false when the definition is missing. */
        private boolean fillConfigured(SimpleContainer container, ServerPlayer player) {
            MenuManager.MenuDef def = MenuManager.get(menuId);
            if (def == null) {
                return false;
            }
            for (int slot = 0; slot < def.slotCount() && slot < slotCount; slot++) {
                MenuManager.Button button = def.at(slot);
                if (button == null) {
                    continue;
                }
                put(container, slot, MenuManager.toDisplay(button), new Entry(null, button.action(), button.argument()));
            }
            return true;
        }

        private void fillWorlds(SimpleContainer container, ServerPlayer player) {
            int slot = 0;
            for (MvWorld world : MvWorldManager.all()) {
                if (slot >= 45) {
                    break;
                }
                if (world.hidden && !player.hasPermissions(3)) {
                    continue;
                }
                boolean online = MvWorldManager.levelOf(world) != null;
                ItemStack icon = icon(online ? Items.GRASS_BLOCK : Items.DEAD_BUSH,
                        "§f" + world.displayName(),
                        online ? "§7点击传送到这个世界" : "§c该世界当前未加载",
                        "§8维度: " + OneKeyToSetAServer.MODID + ":" + world.dimensionPath,
                        "§8环境: " + world.environment + "  生成器: " + world.generator);
                put(container, slot, icon, new Entry(null, online ? Action.COMMAND_PLAYER : Action.NONE,
                        "mv tp " + world.folder));
                slot++;
            }
            put(container, 49, icon(Items.BARRIER, "§c关闭", "§7点击关闭菜单"), new Entry(null, Action.CLOSE, ""));
        }

        private void fillMain(SimpleContainer container, ServerPlayer player) {
            put(container, 22, icon(Items.PAPER, "§e我的信息",
                            "§7所在世界: " + player.level().dimension().location()),
                    new Entry(null, Action.NONE, ""));
            put(container, 31, icon(Items.BARRIER, "§c关闭", "§7点击关闭菜单"),
                    new Entry(null, Action.CLOSE, ""));
        }

        // -------------------------------------------------------------- editing

        private void buildEditor(SimpleContainer container, ServerPlayer player) {
            MenuManager.MenuDef def = MenuManager.get(menuId);
            if (def == null || !def.editable()) {
                put(container, 22, icon(Items.BARRIER, "§c该菜单不可编辑", "§7内置动态菜单由服务器生成"),
                        new Entry(null, Action.CLOSE, ""));
                return;
            }
            if (editSlot < 0) {
                for (int slot = 0; slot < MenuManager.MAX_ROWS * 9; slot++) {
                    if (slot >= def.slotCount()) {
                        put(container, slot, icon(Items.GRAY_STAINED_GLASS_PANE, "§8菜单范围之外",
                                        "§7当前行数: " + def.rows + "（点击扩展到这一格）"),
                                new Entry(null, Action.EDITOR, "grow:" + slot));
                        continue;
                    }
                    MenuManager.Button button = def.at(slot);
                    if (button == null) {
                        put(container, slot, icon(Items.LIGHT_GRAY_STAINED_GLASS_PANE, "§7空位 #" + slot,
                                        "§7点击在这里添加按钮"),
                                new Entry(null, Action.EDITOR, "slot:" + slot));
                    } else {
                        put(container, slot, decorate(MenuManager.toDisplay(button), slot, button),
                                new Entry(null, Action.EDITOR, "slot:" + slot));
                    }
                }
                put(container, 45, icon(Items.LIME_DYE, "§a保存菜单", "§7写入 multiverse/menus.json"),
                        new Entry(null, Action.EDITOR, "save"));
                put(container, 46, icon(Items.RED_DYE, "§c放弃修改", "§7从磁盘重新读取"),
                        new Entry(null, Action.EDITOR, "reload"));
                put(container, 47, icon(Items.NAME_TAG, "§e修改标题", "§7当前: " + def.title, "§7点击后在聊天栏输入新标题"),
                        new Entry(null, Action.EDITOR, "title"));
                put(container, 48, icon(Items.OAK_SIGN, "§e修改行数", "§7当前: " + def.rows + " 行",
                                "§7点击循环 1 → 2 → 3 → 4 → 5"),
                        new Entry(null, Action.EDITOR, "rows"));
                put(container, 49, icon(Items.ENDER_EYE, "§b预览", "§7以玩家视角打开这个菜单"),
                        new Entry(null, Action.EDITOR, "preview"));
                put(container, 50, icon(Items.TNT, "§c清空全部按钮", "§7点击两次生效"),
                        new Entry(null, Action.EDITOR, "clear"));
                put(container, 51, icon(Items.BOOK, "§f帮助", "§7点格子 → 编辑该格按钮",
                                "§7行数上限 " + MenuManager.MAX_ROWS + "（第 6 行留给工具栏）"),
                        new Entry(null, Action.EDITOR, "help"));
                if (!MenuManager.isBuiltin(menuId)) {
                    put(container, 52, icon(Items.LAVA_BUCKET, "§c删除这个菜单", "§7点击两次生效"),
                            new Entry(null, Action.EDITOR, "delete"));
                }
                put(container, 53, icon(Items.BARRIER, "§7关闭编辑器", "§7未保存的修改请先点保存"),
                        new Entry(null, Action.EDITOR, "close"));
                return;
            }

            MenuManager.Button button = def.at(editSlot);
            boolean exists = button != null;
            put(container, 4, exists ? decorate(MenuManager.toDisplay(button), editSlot, button)
                            : icon(Items.LIGHT_GRAY_STAINED_GLASS_PANE, "§7空位 #" + editSlot, "§7点下方按钮开始设置"),
                    new Entry(null, Action.NONE, ""));
            put(container, 45, icon(Items.DIAMOND, "§a图标 = 手持物品",
                            "§7当前手持: " + player.getMainHandItem().getHoverName().getString()),
                    new Entry(null, Action.EDITOR, "icon"));
            put(container, 46, icon(Items.PAPER, "§7图标 = 纸（默认）"),
                    new Entry(null, Action.EDITOR, "icon_default"));
            put(container, 47, icon(Items.NAME_TAG, "§e名称",
                            "§7当前: " + (exists ? button.name : "（空）"),
                            "§7点击后在聊天栏输入；输入 - 表示留空"),
                    new Entry(null, Action.EDITOR, "name"));
            put(container, 48, icon(Items.WRITABLE_BOOK, "§e说明文字",
                            "§7当前 " + (exists ? button.lore.size() : 0) + " 行",
                            "§7聊天栏输入，用 | 分隔多行；输入 - 清空"),
                    new Entry(null, Action.EDITOR, "lore"));
            put(container, 49, icon(Items.COMPARATOR, "§e动作",
                            "§7当前: " + (exists ? button.action() : Action.NONE),
                            "§7点击循环切换：不变 / 玩家执行指令 / 控制台执行指令 / 打开菜单 / 关闭 / 无"),
                    new Entry(null, Action.EDITOR, "action"));
            put(container, 50, icon(Items.COMMAND_BLOCK, "§e参数",
                            "§7当前: " + (exists ? button.argument : "（空）"),
                            "§7动作为「玩家执行指令」时填指令（不带 /）",
                            "§7动作为「打开菜单」时填菜单 id"),
                    new Entry(null, Action.EDITOR, "argument"));
            put(container, 51, icon(Items.BARRIER, "§c删除这个按钮"),
                    new Entry(null, Action.EDITOR, "remove"));
            put(container, 52, icon(Items.ARROW, "§7返回布局"),
                    new Entry(null, Action.EDITOR, "back"));
            put(container, 53, icon(Items.LIME_DYE, "§a保存菜单"),
                    new Entry(null, Action.EDITOR, "save"));
        }

        /** Layout markers must survive the icon pipeline, so they are re-applied after the display stack. */
        private ItemStack decorate(ItemStack stack, int slot, MenuManager.Button button) {
            List<Component> extra = new ArrayList<>();
            extra.add(Component.literal(""));
            extra.add(Component.literal("§8槽位 " + slot + "  动作 " + button.action()));
            if (!button.argument.isBlank()) {
                extra.add(Component.literal("§8参数 " + button.argument));
            }
            net.minecraft.nbt.ListTag tag = stack.getOrCreateTagElement("display")
                    .getList("Lore", net.minecraft.nbt.Tag.TAG_STRING);
            for (Component line : extra) {
                tag.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(line)));
            }
            stack.getOrCreateTagElement("display").put("Lore", tag);
            return stack;
        }

        // -------------------------------------------------------------- plumbing

        public static ItemStack icon(Item item, String name, String... lore) {
            ItemStack stack = new ItemStack(item);
            stack.setHoverName(Component.literal(name));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Component.literal(line));
            }
            if (!lines.isEmpty()) {
                net.minecraft.nbt.ListTag tag = new net.minecraft.nbt.ListTag();
                for (Component line : lines) {
                    tag.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(line)));
                }
                stack.getOrCreateTagElement("display").put("Lore", tag);
            }
            return stack;
        }

        private void put(SimpleContainer container, int slot, ItemStack icon, Entry entry) {
            if (slot < 0 || slot >= slotCount) {
                return;
            }
            container.setItem(slot, icon);
            entries[slot] = new Entry(icon, entry.action(), entry.argument());
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        /** How long a "click again to confirm" stays armed. */
        private static final long CONFIRM_WINDOW_MS = 3000L;

        private int confirmSlot = -1;
        private long confirmDeadline;

        /** True while this menu waits for the second click that executes a dangerous button. */
        public boolean awaitingConfirm() {
            return confirmSlot >= 0 && System.currentTimeMillis() <= confirmDeadline;
        }

        /**
         * Buttons whose command wipes a whole inventory ask for a second click inside three seconds, so a
         * misclick on "一键出售背包" can never sell everything by accident.
         */
        private static boolean needsConfirm(Entry entry) {
            if (entry == null || entry.action() != Action.COMMAND_PLAYER) {
                return false;
            }
            String argument = entry.argument() == null ? "" : entry.argument().trim().toLowerCase(java.util.Locale.ROOT);
            return argument.equals("sellall") || argument.startsWith("sellall ");
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            // Never a real move: display rows are labels, so the client's prediction is undone by resync.
            return MenuSlots.noQuickMove();
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (MenuSlots.allowVanillaClick(slotId, slotCount)) {
                super.clicked(slotId, button, clickType, player);
                MenuSlots.resync(this, player);
                return;
            }
            if (slotId < 0 || slotId >= entries.length) {
                MenuSlots.resync(this, player);
                return;
            }
            Entry entry = entries[slotId];
            if (entry == null || !(player instanceof ServerPlayer serverPlayer)) {
                MenuSlots.resync(this, player);
                return;
            }
            if (needsConfirm(entry)) {
                long now = System.currentTimeMillis();
                if (confirmSlot != slotId || now > confirmDeadline) {
                    confirmSlot = slotId;
                    confirmDeadline = now + CONFIRM_WINDOW_MS;
                    serverPlayer.sendSystemMessage(Component.literal(
                            "§e再点一次「" + stripColour(entry.icon() == null ? ""
                                    : entry.icon().getHoverName().getString())
                                    + "§e」确认（3 秒内有效），这次不会卖任何东西"));
                    MenuSlots.resync(this, serverPlayer);
                    return;
                }
                confirmSlot = -1;
            }
            switch (entry.action()) {
                case COMMAND_PLAYER -> {
                    serverPlayer.closeContainer();
                    serverPlayer.getServer().getCommands()
                            .performPrefixedCommand(serverPlayer.createCommandSourceStack(), entry.argument());
                }
                case COMMAND_CONSOLE -> serverPlayer.getServer().getCommands()
                        .performPrefixedCommand(serverPlayer.getServer().createCommandSourceStack(), entry.argument());
                case OPEN_MENU -> open(serverPlayer, entry.argument());
                case CLOSE -> serverPlayer.closeContainer();
                case EDITOR -> MenuEditor.click(serverPlayer, this, entry.argument());
                case NONE -> {
                    // read-only decoration
                }
            }
            MenuSlots.resync(this, serverPlayer);
        }

        @Override
        public void removed(Player player) {
            confirmSlot = -1;
            super.removed(player);
        }

        private static String stripColour(String text) {
            return text == null ? "" : text.replaceAll("§.", "");
        }
    }

    /** Slots hold display copies only: nothing can be taken out or put in. */
    private static final class DisplaySlot extends Slot {
        DisplaySlot(net.minecraft.world.Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }
}
