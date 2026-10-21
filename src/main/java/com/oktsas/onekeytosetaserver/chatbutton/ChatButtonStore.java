package com.oktsas.onekeytosetaserver.chatbutton;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.world.MvWorld;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The command buttons shown in the e33chat sidebar by the client addon
 * ({@code oktsas_chatbridge-*.jar}).
 *
 * <p>Stored in {@code <server root>/multiverse/chatbuttons.json} next to every other data file. The button
 * list is pushed to clients that have the addon, so an operator edits it once and everybody sees the same
 * buttons. The sidebar buttons used to point at command NPCs when this mod was first written; that module is
 * gone, so the buttons are pure command shortcuts now.</p>
 */
public final class ChatButtonStore {

    /** One button. Public fields keep it Gson friendly and identical to the addon's model. */
    public static final class Button {
        public String name = "";
        public String icon = "minecraft:paper";
        public List<String> commands = new ArrayList<>();
        public int cooldownSeconds = 1;

        public static Button of(String name, String icon, String command) {
            Button button = new Button();
            button.name = name == null ? "" : name;
            button.icon = icon == null || icon.isBlank() ? "minecraft:paper" : icon;
            button.commands.add(command == null || command.isBlank() ? "menu" : command);
            return button;
        }

        public String iconOf(ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return "minecraft:paper";
            }
            net.minecraft.resources.ResourceLocation id =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            return id == null ? "minecraft:paper" : id.toString();
        }
    }

    private static final List<Button> BUTTONS = new ArrayList<>();
    /** The editor screen has 45 button slots + 9 tool slots; anything past 45 is unreachable, so cap there. */
    public static final int MAX_BUTTONS = 45;
    private static Path file;

    private ChatButtonStore() {
    }

    public static List<Button> all() {
        return new ArrayList<>(BUTTONS);
    }

    public static int size() {
        return BUTTONS.size();
    }

    public static Button at(int index) {
        return index >= 0 && index < BUTTONS.size() ? BUTTONS.get(index) : null;
    }

    public static void add(Button button) {
        if (BUTTONS.size() >= MAX_BUTTONS) {
            OneKeyToSetAServer.LOGGER.warn("[ChatButton] the sidebar already has {} buttons, refusing to add more",
                    MAX_BUTTONS);
            return;
        }
        BUTTONS.add(button);
        save();
    }

    public static boolean remove(int index) {
        if (index < 0 || index >= BUTTONS.size()) {
            return false;
        }
        BUTTONS.remove(index);
        save();
        return true;
    }

    public static void loadAll(Path gameDir) {
        file = gameDir.resolve(MvWorld.CONFIG_DIR).resolve("chatbuttons.json");
        BUTTONS.clear();
        try {
            if (Files.isRegularFile(file)) {
                List<Button> loaded = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        new TypeToken<List<Button>>() {
                        }.getType());
                if (loaded != null) {
                    BUTTONS.addAll(loaded);
                }
            }
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[ChatButton] could not read {}", file, t);
        }
        if (BUTTONS.isEmpty()) {
            defaults();
            save();
        } else if (migrateOldDefaults() || trimOverflow()) {
            save();
        }
        OneKeyToSetAServer.LOGGER.info("[ChatButton] {} button(s) loaded", BUTTONS.size());
    }

    /** Drops anything past {@link #MAX_BUTTONS}, which the editor could never show or remove again. */
    private static boolean trimOverflow() {
        if (BUTTONS.size() <= MAX_BUTTONS) {
            return false;
        }
        int dropped = BUTTONS.size() - MAX_BUTTONS;
        BUTTONS.subList(MAX_BUTTONS, BUTTONS.size()).clear();
        OneKeyToSetAServer.LOGGER.warn("[ChatButton] dropped {} button(s) past the {} button limit", dropped,
                MAX_BUTTONS);
        return true;
    }

    /**
     * The two buttons the sidebar starts with. The owner asked for the second one to open the shop
     * (<code>/shop</code>) instead of repeating the menu, which the first button already does.
     */
    private static void defaults() {
        BUTTONS.add(Button.of("菜单", "minecraft:chest", "menu"));
        BUTTONS.add(Button.of("商店", "minecraft:emerald", "shop"));
    }

    /**
     * Upgrades a config that still holds the very first default pair (菜单→menu and 多元世界→mv menu) to the
     * new pair. Only that exact pair is touched, so an operator who edited the buttons keeps their own list.
     *
     * @return true when something changed and the file should be written back
     */
    private static boolean migrateOldDefaults() {
        if (BUTTONS.size() != 2) {
            return false;
        }
        Button first = BUTTONS.get(0);
        Button second = BUTTONS.get(1);
        boolean oldPair = first.commands.size() == 1 && "menu".equals(first.commands.get(0))
                && second.commands.size() == 1 && "mv menu".equals(second.commands.get(0));
        if (!oldPair) {
            return false;
        }
        BUTTONS.clear();
        defaults();
        OneKeyToSetAServer.LOGGER.info("[ChatButton] upgraded the old 菜单/多元世界 buttons to 菜单/商店");
        return true;
    }

    public static void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[ChatButton] could not write {}", file, t);
        }
    }

    /** The exact JSON the client addon parses. */
    public static String toJson() {
        return new GsonBuilder().setPrettyPrinting().create().toJson(BUTTONS);
    }
}
