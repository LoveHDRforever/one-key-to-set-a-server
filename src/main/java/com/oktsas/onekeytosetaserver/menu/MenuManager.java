package com.oktsas.onekeytosetaserver.menu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Editable chest-menu definitions.
 *
 * <p>Storage: {@code <server root>/multiverse/menus.json}. Every menu is a grid of at most
 * {@link #MAX_ROWS} rows (45 buttons); the editor needs the sixth row for its toolbar, so layout
 * editing stops at five rows. {@link #WORLDS} is a dynamic built-in that lists the loaded worlds
 * and is therefore not editable.</p>
 */
public final class MenuManager {

    public static final String MAIN = "main";
    public static final String WORLDS = "worlds";
    public static final int MAX_ROWS = 5;
    public static final int EDITOR_TOOLBAR_SLOT = 45;

    /** Menus the mod ships and never lets an operator delete. */
    private static final Set<String> BUILTIN = Set.of(MAIN);

    private static final Map<String, MenuDef> MENUS = new LinkedHashMap<>();
    private static Path file;

    private MenuManager() {
    }

    /** One clickable button: an icon plus what happens when it is clicked. */
    public static final class Button {
        public String icon = "";
        public String name = "";
        public List<String> lore = new ArrayList<>();
        public String action = MvMenus.Action.NONE.name();
        public String argument = "";

        public Button() {
        }

        public Button(ItemStack icon, String name, MvMenus.Action action, String argument, String... lore) {
            this.icon = snbt(icon);
            this.name = name;
            this.action = action.name();
            this.argument = argument == null ? "" : argument;
            for (String line : lore) {
                this.lore.add(line);
            }
        }

        public MvMenus.Action action() {
            try {
                return MvMenus.Action.valueOf(action.toUpperCase(Locale.ROOT));
            } catch (Throwable t) {
                return MvMenus.Action.NONE;
            }
        }

        public String argument() {
            return argument == null ? "" : argument;
        }

        public Button copy() {
            Button other = new Button();
            other.icon = icon;
            other.name = name;
            other.lore = new ArrayList<>(lore);
            other.action = action;
            other.argument = argument;
            return other;
        }
    }

    /** A whole menu: title, row count and a slot -> button map. */
    public static final class MenuDef {
        public String id = "";
        public String title = "§8菜单";
        public int rows = 5;
        public Map<String, Button> buttons = new LinkedHashMap<>();

        public boolean editable() {
            return !WORLDS.equals(id);
        }

        public int slotCount() {
            return rows * 9;
        }

        public Button at(int slot) {
            return buttons.get(String.valueOf(slot));
        }

        public boolean has(int slot) {
            return buttons.containsKey(String.valueOf(slot));
        }

        public void set(int slot, Button button) {
            buttons.put(String.valueOf(slot), button);
        }

        public void remove(int slot) {
            buttons.remove(String.valueOf(slot));
        }

        public int filled() {
            return buttons.size();
        }
    }

    // ------------------------------------------------------------------ storage

    public static void loadAll(Path gameDir) {
        file = gameDir.resolve("multiverse").resolve("menus.json");
        MENUS.clear();
        if (Files.exists(file)) {
            try {
                Map<String, MenuDef> loaded = new Gson().fromJson(
                        Files.readString(file, StandardCharsets.UTF_8),
                        new TypeToken<LinkedHashMap<String, MenuDef>>() {
                        }.getType());
                if (loaded != null) {
                    for (Map.Entry<String, MenuDef> entry : loaded.entrySet()) {
                        String id = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                        MenuDef def = entry.getValue();
                        if (def == null || id.isBlank()) {
                            continue;
                        }
                        def.id = id;
                        clamp(def);
                        MENUS.put(id, def);
                    }
                }
            } catch (Throwable t) {
                OneKeyToSetAServer.LOGGER.error("[Menu] could not read {}", file, t);
            }
        }
        if (!MENUS.containsKey(MAIN)) {
            MENUS.put(MAIN, defaultMain());
        }
        renameShopButton();
        save();
        OneKeyToSetAServer.LOGGER.info("[Menu] {} editable menu(s) loaded", MENUS.size());
    }

    /**
     * The shop is called "服务器商店" now (the owner asked for the title of his reference screenshot), so a menu
     * written by an older version is renamed once instead of showing two different names for the same screen.
     */
    private static void renameShopButton() {
        int renamed = 0;
        for (MenuDef def : MENUS.values()) {
            for (Button button : def.buttons.values()) {
                if (button != null && button.name != null && button.name.contains("官方商店")) {
                    button.name = button.name.replace("官方商店", "服务器商店");
                    renamed++;
                }
            }
        }
        if (renamed > 0) {
            OneKeyToSetAServer.LOGGER.info("[Menu] renamed {} button(s) from 官方商店 to 服务器商店", renamed);
        }
    }

    private static void clamp(MenuDef def) {
        if (def.rows < 1) {
            def.rows = 1;
        }
        if (def.rows > MAX_ROWS) {
            def.rows = MAX_ROWS;
        }
        if (def.title == null || def.title.isBlank()) {
            def.title = "§8菜单";
        }
        if (def.buttons == null) {
            def.buttons = new LinkedHashMap<>();
        }
        def.buttons.entrySet().removeIf(e -> {
            try {
                int slot = Integer.parseInt(e.getKey());
                return slot < 0 || slot >= MAX_ROWS * 9 || e.getValue() == null;
            } catch (NumberFormatException nfe) {
                return true;
            }
        });
    }

    /** The shipped default main menu: the entry point every player sees. */
    private static MenuDef defaultMain() {
        MenuDef def = new MenuDef();
        def.id = MAIN;
        def.title = "§8世界菜单";
        def.rows = 5;
        def.set(19, new Button(new ItemStack(Items.GRASS_BLOCK), "§a世界列表", MvMenus.Action.OPEN_MENU, WORLDS,
                "§7查看并传送到各个世界"));
        def.set(20, new Button(new ItemStack(Items.EMERALD), "§a服务器商店", MvMenus.Action.COMMAND_PLAYER, "shop",
                "§7用钱购买服务器物资"));
        def.set(21, new Button(new ItemStack(Items.CHEST), "§a自由市场", MvMenus.Action.COMMAND_PLAYER, "market",
                "§7玩家之间自由挂单买卖"));
        def.set(22, new Button(new ItemStack(Items.PAPER), "§e我的邮箱", MvMenus.Action.COMMAND_PLAYER, "mail",
                "§7领取系统与玩家寄来的物品"));
        def.set(23, new Button(new ItemStack(Items.CAULDRON), "§e垃圾桶", MvMenus.Action.COMMAND_PLAYER, "trash",
                "§7销毁不需要的物品"));
        def.set(24, new Button(new ItemStack(Items.CHEST), "§6售卖区（卖任意物品）", MvMenus.Action.COMMAND_PLAYER,
                "sell", "§7把想卖的物品放进箱子界面", "§7确认后自动结算，不可卖的原样退回"));
        def.set(31, new Button(new ItemStack(Items.BARRIER), "§c关闭", MvMenus.Action.CLOSE, "",
                "§7点击关闭菜单"));
        return def;
    }

    public static void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(MENUS),
                    StandardCharsets.UTF_8);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Menu] could not write {}", file, t);
        }
    }

    // ------------------------------------------------------------------ access

    public static Collection<MenuDef> all() {
        return new ArrayList<>(MENUS.values());
    }

    public static MenuDef get(String id) {
        return id == null ? null : MENUS.get(id.toLowerCase(Locale.ROOT));
    }

    public static boolean isDynamic(String id) {
        return WORLDS.equalsIgnoreCase(id);
    }

    public static boolean isBuiltin(String id) {
        return id != null && BUILTIN.contains(id.toLowerCase(Locale.ROOT));
    }

    public static MenuDef create(String id, String title, int rows) {
        String key = id == null ? "" : id.toLowerCase(Locale.ROOT).trim();
        if (key.isBlank() || MENUS.containsKey(key)) {
            return null;
        }
        MenuDef def = new MenuDef();
        def.id = key;
        def.title = title == null || title.isBlank() ? "§8菜单" : title;
        def.rows = Math.max(1, Math.min(MAX_ROWS, rows));
        MENUS.put(key, def);
        save();
        return def;
    }

    public static boolean delete(String id) {
        String key = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (BUILTIN.contains(key)) {
            return false;
        }
        boolean removed = MENUS.remove(key) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    /** Discards in-memory edits by reading the file again. */
    public static void reload() {
        if (file != null) {
            loadAll(file.getParent().getParent());
        }
    }

    /** Number of buttons in a menu (used by {@code /menu list}). */
    public static int filled(MenuDef def) {
        return def == null ? 0 : def.filled();
    }

    // ------------------------------------------------------------------ icons

    /** Turns a stored button into the stack the client is shown (name + lore applied). */
    public static ItemStack toDisplay(Button button) {
        ItemStack stack = decode(button == null ? "" : button.icon);
        if (stack.isEmpty()) {
            stack = new ItemStack(Items.PAPER);
        }
        stack.setCount(1);
        if (button != null && button.name != null && !button.name.isBlank()) {
            stack.setHoverName(Component.literal(button.name));
        }
        if (button != null && button.lore != null && !button.lore.isEmpty()) {
            ListTag tag = new ListTag();
            for (String line : button.lore) {
                tag.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(line))));
            }
            stack.getOrCreateTagElement("display").put("Lore", tag);
        }
        return stack;
    }

    public static String snbt(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy.save(new net.minecraft.nbt.CompoundTag()).toString();
    }

    public static ItemStack decode(String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return ItemStack.EMPTY;
        }
        try {
            return ItemStack.of(TagParser.parseTag(snbt));
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Menu] could not decode icon {}", snbt, t);
            return ItemStack.EMPTY;
        }
    }

    /** Convenience for building default buttons in code. */
    public static Button button(Item item, String name, MvMenus.Action action, String argument, String... lore) {
        return new Button(new ItemStack(item), name, action, argument, lore);
    }
}
