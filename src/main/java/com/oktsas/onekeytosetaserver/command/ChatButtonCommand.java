package com.oktsas.onekeytosetaserver.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.chatbutton.ChatButtonNetwork;
import com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore;
import com.oktsas.onekeytosetaserver.teleport.TeleportQueue;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /chatbutton} (op 4) - the command buttons the e33chat addon draws at the bottom of the chat sidebar.
 *
 * <p>Two ways to edit them: the chat commands below, or a chest GUI ({@code /chatbutton gui}) where each
 * button is one slot. In the GUI the name and the command are typed into the chat bar, exactly like the menu
 * editor does it. The default pair is 菜单 ({@code /menu}) and 商店 ({@code /shop}).</p>
 */
public final class ChatButtonCommand {

    private static final int FIRST = 0;
    private static final int LAST = 44;
    private static final int SLOT_NEW = 45;
    private static final int SLOT_RENAME = 46;
    private static final int SLOT_RECMD = 47;
    private static final int SLOT_REICON = 48;
    private static final int SLOT_CLOSE = 49;
    private static final int SLOT_SAVE = 53;

    private static final Component TITLE = Component.literal("§8聊天按钮编辑");

    /** index the player had selected, so the GUI can come back to it after a chat prompt. */
    private static final Map<UUID, Integer> SELECTED = new LinkedHashMap<>();
    /** who is currently typing a value into chat, and which field. */
    private static final Map<UUID, String> PENDING = new LinkedHashMap<>();

    public ChatButtonCommand() {
    }

    // ------------------------------------------------------------------ command tree

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chatbutton")
                .requires(source -> source.hasPermission(4))
                .executes(ChatButtonCommand::list)
                .then(Commands.literal("list").executes(ChatButtonCommand::list))
                .then(Commands.literal("gui").executes(ChatButtonCommand::gui))
                .then(Commands.literal("save").executes(ChatButtonCommand::save))
                .then(Commands.literal("reload").executes(ChatButtonCommand::reload))
                .then(Commands.literal("add")
                        .executes(ctx -> add(ctx, "新按钮"))
                        .then(Commands.argument("显示名", StringArgumentType.greedyString())
                                .executes(ctx -> add(ctx, StringArgumentType.getString(ctx, "显示名")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("编号", IntegerArgumentType.integer(1, 45))
                                .executes(ChatButtonCommand::remove)))
                .then(Commands.literal("name")
                        .then(Commands.argument("编号", IntegerArgumentType.integer(1, 45))
                                .then(Commands.argument("新名字", StringArgumentType.greedyString())
                                        .executes(ChatButtonCommand::name))))
                .then(Commands.literal("cmd")
                        .then(Commands.argument("编号", IntegerArgumentType.integer(1, 45))
                                .then(Commands.argument("指令", StringArgumentType.greedyString())
                                        .executes(ChatButtonCommand::cmd))))
                .then(Commands.literal("icon")
                        .then(Commands.argument("编号", IntegerArgumentType.integer(1, 45))
                                .executes(ChatButtonCommand::icon)))
                .then(Commands.literal("cooldown")
                        .then(Commands.argument("编号", IntegerArgumentType.integer(1, 45))
                                .then(Commands.argument("秒", IntegerArgumentType.integer(0, 600))
                                        .executes(ChatButtonCommand::cooldown)))));
    }

    // ------------------------------------------------------------------ commands

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<ChatButtonStore.Button> buttons = ChatButtonStore.all();
        StringBuilder sb = new StringBuilder("§e§l聊天按钮§r (" + buttons.size() + " 个，显示在 e33chat 侧边栏底部)");
        for (int i = 0; i < buttons.size(); i++) {
            ChatButtonStore.Button button = buttons.get(i);
            sb.append("\n §6").append(i + 1).append(". §f").append(button.name)
                    .append(" §7图标 §f").append(button.icon)
                    .append(" §7指令 §f/").append(String.join(" §7+ §f/", button.commands))
                    .append(" §7冷却 ").append(button.cooldownSeconds).append("s");
        }
        sb.append("\n§7编辑: §f/chatbutton gui §7或用 add/remove/name/cmd/icon/cooldown");
        sb.append("\n§7改完记得 §f/chatbutton save §7（会推送给装了补丁的客户端）");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return buttons.size();
    }

    private static int add(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer player = ctx.getSource().getPlayer();
        String found = "minecraft:paper";
        if (player != null && !player.getMainHandItem().isEmpty()) {
            var id = ForgeRegistries.ITEMS.getKey(player.getMainHandItem().getItem());
            if (id != null) {
                found = id.toString();
            }
        }
        final String icon = found;
        ChatButtonStore.add(ChatButtonStore.Button.of(name.trim(), icon, "menu"));
        ctx.getSource().sendSuccess(() -> Component.literal("§a已添加按钮 §f" + name.trim()
                + "§a（图标 " + icon + "，默认指令 menu，用 /chatbutton cmd <编号> <指令> 改）"), true);
        ChatButtonNetwork.sendAll(ctx.getSource().getServer());
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        int index = IntegerArgumentType.getInteger(ctx, "编号") - 1;
        if (!ChatButtonStore.remove(index)) {
            ctx.getSource().sendFailure(Component.literal("§c编号不对，用 /chatbutton list 看"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§e已删除按钮 " + (index + 1)), true);
        ChatButtonNetwork.sendAll(ctx.getSource().getServer());
        return 1;
    }

    private static int name(CommandContext<CommandSourceStack> ctx) {
        ChatButtonStore.Button button = button(ctx);
        if (button == null) {
            return 0;
        }
        button.name = StringArgumentType.getString(ctx, "新名字").trim();
        ChatButtonStore.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a名字已改为 §f" + button.name), true);
        ChatButtonNetwork.sendAll(ctx.getSource().getServer());
        return 1;
    }

    private static int cmd(CommandContext<CommandSourceStack> ctx) {
        ChatButtonStore.Button button = button(ctx);
        if (button == null) {
            return 0;
        }
        String command = StringArgumentType.getString(ctx, "指令").trim();
        button.commands.clear();
        for (String part : command.split("[;|]")) {
            String cleaned = part.trim();
            if (!cleaned.isEmpty()) {
                button.commands.add(cleaned.startsWith("/") ? cleaned.substring(1) : cleaned);
            }
        }
        ChatButtonStore.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a按钮 §f" + button.name + "§a 的指令已设为 §f/"
                + String.join("§a, §f/", button.commands)), true);
        ChatButtonNetwork.sendAll(ctx.getSource().getServer());
        return 1;
    }

    private static int icon(CommandContext<CommandSourceStack> ctx) {
        ChatButtonStore.Button button = button(ctx);
        if (button == null) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null || player.getMainHandItem().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§c手上得拿着要当图标的物品"));
            return 0;
        }
        var id = ForgeRegistries.ITEMS.getKey(player.getMainHandItem().getItem());
        button.icon = id == null ? "minecraft:paper" : id.toString();
        ChatButtonStore.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a图标已换成 §f" + button.icon), true);
        ChatButtonNetwork.sendAll(ctx.getSource().getServer());
        return 1;
    }

    private static int cooldown(CommandContext<CommandSourceStack> ctx) {
        ChatButtonStore.Button button = button(ctx);
        if (button == null) {
            return 0;
        }
        button.cooldownSeconds = IntegerArgumentType.getInteger(ctx, "秒");
        ChatButtonStore.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a冷却已设为 " + button.cooldownSeconds + " 秒"), true);
        ChatButtonNetwork.sendAll(ctx.getSource().getServer());
        return 1;
    }

    private static int save(CommandContext<CommandSourceStack> ctx) {
        ChatButtonStore.save();
        ChatButtonNetwork.sendAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("§a已保存 multiverse/chatbuttons.json 并推送给客户端"), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ChatButtonStore.loadAll(FMLPaths.GAMEDIR.get());
        ChatButtonNetwork.sendAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("§a已重新读取，现在有 "
                + ChatButtonStore.size() + " 个按钮"), true);
        return 1;
    }

    private static ChatButtonStore.Button button(CommandContext<CommandSourceStack> ctx) {
        int index = IntegerArgumentType.getInteger(ctx, "编号") - 1;
        ChatButtonStore.Button button = ChatButtonStore.at(index);
        if (button == null) {
            ctx.getSource().sendFailure(Component.literal("§c编号不对，用 /chatbutton list 看"));
        }
        return button;
    }

    // ------------------------------------------------------------------ gui

    private static int gui(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这个命令只能由玩家执行"));
            return 0;
        }
        open(player);
        return 1;
    }

    private static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new EditorMenu(id, inventory, player), TITLE));
    }

    /** The chest editor: one slot per button plus a bottom row of actions. */
    public static final class EditorMenu extends AbstractContainerMenu {

        /** Container rows only; the player slots the client expects come from {@link MenuSlots}. */
        static final int DISPLAY_SLOTS = 54;
        private final ServerPlayer player;
        private final SimpleContainer container = new SimpleContainer(54);
        private final List<Runnable> actions = new ArrayList<>();

        EditorMenu(int id, Inventory inventory, ServerPlayer player) {
            super(MenuType.GENERIC_9x6, id);
            this.player = player;
            for (int slot = 0; slot < 54; slot++) {
                addSlot(new DisplaySlot(container, slot, 8 + (slot % 9) * 18, 18 + (slot / 9) * 18));
            }
            if (com.oktsas.onekeytosetaserver.menu.MenuSlots.showPlayerInventory()) {
                for (int i = 0; i < com.oktsas.onekeytosetaserver.menu.MenuSlots.PLAYER_SLOTS; i++) {
                    addSlot(com.oktsas.onekeytosetaserver.menu.MenuSlots.playerInventorySlot(inventory, i, 6));
                }
            }
            rebuild();
        }

        public int selected() {
            return SELECTED.getOrDefault(player.getUUID(), -1);
        }

        /** Test hook: how many button slots show an icon. */
        public int filled() {
            int count = 0;
            for (int i = FIRST; i <= LAST; i++) {
                if (!container.getItem(i).isEmpty()) {
                    count++;
                }
            }
            return count;
        }

        private void rebuild() {
            for (int i = 0; i < 54; i++) {
                container.setItem(i, ItemStack.EMPTY);
            }
            actions.clear();
            while (actions.size() < 54) {
                actions.add(null);
            }
            List<ChatButtonStore.Button> buttons = ChatButtonStore.all();
            for (int i = 0; i < Math.min(buttons.size(), LAST + 1); i++) {
                ChatButtonStore.Button button = buttons.get(i);
                int index = i;
                boolean active = selected() == i;
                ItemStack stack = stackOf(button.icon);
                stack.setHoverName(Component.literal((active ? "§a▶ " : "§f") + (i + 1) + ". " + button.name));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.literal("§7指令: §f/" + String.join("§7, §f/", button.commands)));
                lore.add(Component.literal("§7图标: §f" + button.icon));
                lore.add(Component.literal("§7冷却: §f" + button.cooldownSeconds + " 秒"));
                lore.add(Component.literal(active ? "§a已选中：用下面一排按钮改它" : "§e点击选中"));
                if (active) {
                    net.minecraft.nbt.CompoundTag tag = stack.getOrCreateTag();
                    tag.put("Enchantments", new ListTag());
                    stack.getOrCreateTagElement("display").put("Lore", loreTag(lore));
                } else {
                    stack.getOrCreateTagElement("display").put("Lore", loreTag(lore));
                }
                set(i, stack, () -> SELECTED.put(player.getUUID(), index));
            }
            set(SLOT_NEW, icon(Items.EMERALD, "§a新增按钮（用手上的物品当图标）",
                            "§7点一下就把手上物品做成新按钮",
                            "§7然后点它选中，再改名字/指令"),
                    this::newButton);
            set(SLOT_RENAME, icon(Items.NAME_TAG, "§e改名字（在聊天栏输入）",
                            "§7选中一个按钮后点这里",
                            "§7然后在聊天栏打上新名字"),
                    () -> prompt("name"));
            set(SLOT_RECMD, icon(Items.COMMAND_BLOCK, "§b改指令（在聊天栏输入）",
                            "§7选中一个按钮后点这里",
                            "§7多条指令用 §f; §7隔开"),
                    () -> prompt("command"));
            set(SLOT_REICON, icon(Items.ITEM_FRAME, "§d换图标（用手上的物品）",
                            "§7选中一个按钮后点这里"),
                    this::reIcon);
            set(SLOT_SAVE, icon(Items.WRITABLE_BOOK, "§a保存并推送给客户端",
                            "§7写入 multiverse/chatbuttons.json",
                            "§7装了补丁的玩家会立刻看到新按钮"),
                    this::save);
            set(SLOT_CLOSE, icon(Items.BARRIER, "§c关闭", "§7直接关掉，记得先保存"), this::close);
        }

        private void set(int slot, ItemStack icon, Runnable action) {
            container.setItem(slot, icon);
            actions.set(slot, action);
        }

        private void newButton() {
            String icon = "minecraft:paper";
            if (!player.getMainHandItem().isEmpty()) {
                var id = ForgeRegistries.ITEMS.getKey(player.getMainHandItem().getItem());
                if (id != null) {
                    icon = id.toString();
                }
            }
            ChatButtonStore.add(ChatButtonStore.Button.of("新按钮", icon, "menu"));
            SELECTED.put(player.getUUID(), ChatButtonStore.size() - 1);
            rebuild();
        }

        private void reIcon() {
            if (selected() < 0) {
                player.sendSystemMessage(Component.literal("§c先点一个按钮选中它"));
                return;
            }
            if (player.getMainHandItem().isEmpty()) {
                player.sendSystemMessage(Component.literal("§c手上得拿着要当图标的物品"));
                return;
            }
            var id = ForgeRegistries.ITEMS.getKey(player.getMainHandItem().getItem());
            ChatButtonStore.Button button = ChatButtonStore.at(selected());
            if (button != null && id != null) {
                button.icon = id.toString();
                ChatButtonStore.save();
            }
            rebuild();
        }

        private void save() {
            ChatButtonStore.save();
            ChatButtonNetwork.sendAll(player.getServer());
            player.sendSystemMessage(Component.literal("§a已保存 " + ChatButtonStore.size()
                    + " 个按钮并推送给客户端"));
            rebuild();
        }

        private void prompt(String field) {
            if (selected() < 0) {
                player.sendSystemMessage(Component.literal("§c先点一个按钮选中它"));
                return;
            }
            PENDING.put(player.getUUID(), field);
            player.closeContainer();
            player.sendSystemMessage(Component.literal("§e请在聊天栏输入新的"
                    + ("name".equals(field) ? "名字" : "指令（多条用 ; 隔开）")
                    + "，输入 §fcancel §e取消；输完会自动回到编辑器"));
        }

        private void close() {
            player.closeContainer();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            // Never a real move: display rows are labels, so the client's prediction is undone by resync.
            return com.oktsas.onekeytosetaserver.menu.MenuSlots.noQuickMove();
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
            if (com.oktsas.onekeytosetaserver.menu.MenuSlots.allowVanillaClick(slotId, DISPLAY_SLOTS)) {
                super.clicked(slotId, button, clickType, clicker);
                com.oktsas.onekeytosetaserver.menu.MenuSlots.resync(this, clicker);
                return;
            }
            Runnable action = slotId >= 0 && slotId < actions.size() ? actions.get(slotId) : null;
            if (action != null) {
                action.run();
                if (slotId >= FIRST && slotId <= LAST) {
                    rebuild();
                }
            }
            com.oktsas.onekeytosetaserver.menu.MenuSlots.resync(this, clicker);
        }
    }

    /** Exposed for the headless self-test. */
    public static EditorMenu createEditor(int id, Inventory inventory, ServerPlayer player) {
        return new EditorMenu(id, inventory, player);
    }

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

    // ------------------------------------------------------------------ chat prompt

    /** The name / command typed into the chat bar comes back here, then the editor reopens. */
    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        String field = PENDING.remove(player.getUUID());
        if (field == null) {
            return;
        }
        event.setCanceled(true);
        String text = event.getRawText() == null ? "" : event.getRawText().trim();
        if (text.isEmpty() || text.equalsIgnoreCase("cancel") || text.equals("取消")) {
            player.sendSystemMessage(Component.literal("§e已取消"));
        } else {
            ChatButtonStore.Button button = ChatButtonStore.at(SELECTED.getOrDefault(player.getUUID(), -1));
            if (button == null) {
                player.sendSystemMessage(Component.literal("§c按钮已经不在了"));
            } else if ("name".equals(field)) {
                button.name = text;
                ChatButtonStore.save();
                player.sendSystemMessage(Component.literal("§a名字已改为 §f" + text));
            } else {
                button.commands.clear();
                for (String part : text.split("[;|]")) {
                    String cleaned = part.trim();
                    if (!cleaned.isEmpty()) {
                        button.commands.add(cleaned.startsWith("/") ? cleaned.substring(1) : cleaned);
                    }
                }
                ChatButtonStore.save();
                player.sendSystemMessage(Component.literal("§a指令已设为 §f/"
                        + String.join("§a, §f/", button.commands)));
            }
            ChatButtonNetwork.sendAll(player.getServer());
        }
        // reopening a container from inside the chat event is not safe: do it on the next server tick
        TeleportQueue.submit(() -> {
            if (!player.hasDisconnected()) {
                open(player);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static ItemStack stackOf(String itemId) {
        try {
            var id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            var item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            if (item != null && item != Items.AIR) {
                return new ItemStack(item);
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return new ItemStack(Items.PAPER);
    }

    private static ItemStack icon(net.minecraft.world.item.Item item, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.setHoverName(Component.literal(name));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(Component.literal(line));
        }
        stack.getOrCreateTagElement("display").put("Lore", loreTag(lines));
        return stack;
    }

    private static ListTag loreTag(List<Component> lines) {
        ListTag tag = new ListTag();
        for (Component line : lines) {
            tag.add(StringTag.valueOf(Component.Serializer.toJson(line)));
        }
        return tag;
    }
}
