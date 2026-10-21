package com.oktsas.chatbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The button list of the addon, plus the tiny amount of state needed to click one (a per button cooldown).
 *
 * <p>The JSON shape is shared with the server side of onekeytosetaserver, so a server can push its own list
 * over the {@code onekeytosetaserver:chatbuttons} channel and the addon simply replaces what it has.</p>
 */
public final class ChatButtons {

    /** One button. Public fields so Gson can round-trip it (the same shape is used server side). */
    public static final class Button {
        public String name = "";
        public String icon = "minecraft:paper";
        public List<String> commands = new ArrayList<>();
        public int cooldownSeconds = 1;
        /** Session only: epoch millis until which this button ignores clicks. */
        public transient long cooldownUntil;

        public ItemStack stack() {
            try {
                ResourceLocation id = ResourceLocation.tryParse(icon);
                Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
                if (item != null && item != Items.AIR) {
                    return new ItemStack(item);
                }
            } catch (Throwable ignored) {
                // fall through to the default icon
            }
            return new ItemStack(Items.PAPER);
        }

        public String commandLine() {
            return commands == null || commands.isEmpty() ? "" : commands.get(0);
        }
    }

    private static final List<Button> BUTTONS = new ArrayList<>();
    private static Path file;

    private ChatButtons() {
    }

    public static List<Button> buttons() {
        return BUTTONS;
    }

    // ------------------------------------------------------------------ local config

    /** Reads {@code config/oktsas-chatbuttons.json}, writing the two default buttons on first run. */
    public static void load() {
        try {
            file = FMLPaths.CONFIGDIR.get().resolve("oktsas-chatbuttons.json");
            if (Files.isRegularFile(file)) {
                List<Button> loaded = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        new TypeToken<List<Button>>() {
                        }.getType());
                if (loaded != null && !loaded.isEmpty()) {
                    BUTTONS.clear();
                    BUTTONS.addAll(loaded);
                    ChatBridge.LOGGER.info("[ChatBridge] {} button(s) loaded from {}", BUTTONS.size(), file);
                    return;
                }
            }
            defaults();
            save();
        } catch (Throwable t) {
            ChatBridge.LOGGER.error("[ChatBridge] could not read the button config", t);
            if (BUTTONS.isEmpty()) {
                defaults();
            }
        }
    }

    /** The two starting buttons: the menu, and the shop (the server usually pushes its own list anyway). */
    private static void defaults() {
        BUTTONS.clear();
        BUTTONS.add(button("菜单", "minecraft:chest", "menu"));
        BUTTONS.add(button("商店", "minecraft:emerald", "shop"));
    }

    private static Button button(String name, String icon, String command) {
        Button entry = new Button();
        entry.name = name;
        entry.icon = icon;
        entry.commands.add(command);
        entry.cooldownSeconds = 1;
        return entry;
    }

    public static void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            ChatBridge.LOGGER.error("[ChatBridge] could not write the button config", t);
        }
    }

    public static String toJson() {
        return new GsonBuilder().setPrettyPrinting().create().toJson(BUTTONS);
    }

    /** Replaces the list with whatever the server sent (a JSON array in the same shape). */
    public static void replace(String json) {
        try {
            List<Button> loaded = new Gson().fromJson(json, new TypeToken<List<Button>>() {
            }.getType());
            BUTTONS.clear();
            if (loaded != null) {
                BUTTONS.addAll(loaded);
            }
            ChatBridge.LOGGER.info("[ChatBridge] {} button(s) received from the server", BUTTONS.size());
        } catch (Throwable t) {
            ChatBridge.LOGGER.error("[ChatBridge] could not parse the server button list", t);
        }
    }

    // ------------------------------------------------------------------ clicking

    /** True when the click was consumed; runs the bound commands silently (no local echo). */
    public static boolean click(Button button) {
        long now = System.currentTimeMillis();
        if (button.cooldownUntil > now) {
            return true;
        }
        button.cooldownUntil = now + Math.max(0, button.cooldownSeconds) * 1000L;
        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null || player.connection == null) {
            return true;
        }
        for (String raw : button.commands) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String command = raw.startsWith("/") ? raw.substring(1) : raw;
            try {
                player.connection.sendCommand(command);
            } catch (Throwable t) {
                ChatBridge.LOGGER.error("[ChatBridge] could not run /{}", command, t);
            }
        }
        return true;
    }

    public static boolean coolingDown(Button button) {
        return button.cooldownUntil > System.currentTimeMillis();
    }
}
