package com.oktsas.onekeytosetaserver.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.economy.MoneyService;
import com.oktsas.onekeytosetaserver.market.MarketManager;
import com.oktsas.onekeytosetaserver.trade.TradeBlacklist;
import com.oktsas.onekeytosetaserver.world.MvWorld;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The official shop: buy-only categories with per-item prices, plus a <b>separate</b> recycle price table.
 *
 * <p>Two independent price sources on purpose: what the shop charges and what it pays back are edited
 * separately (that is a difference from the Bukkit plugins this is modelled on, where selling reused the shop
 * price). Data lives in {@code <server root>/multiverse/shop/*.json} so operators can also edit it by hand.</p>
 *
 * <h2>Items carry NBT since 0.2.6</h2>
 * <p>A goods entry is {@code {itemId, nbt?, buyPrice}}; when {@code nbt} is present the shop hands out exactly
 * that stack (an enchanted book with its enchantment, a potion with its effect, ...). A recycle entry is
 * {@code {itemId, nbt?, price}} with two deliberate levels:</p>
 * <ul>
 *   <li>{@code nbt} absent ({@code null}) = <b>wildcard</b>: any variant of that item sells for this price.
 *       That is what every pre-0.2.6 entry turned into, so old tables and old player expectations still
 *       work - a plain {@code minecraft:diamond} entry keeps buying every diamond.</li>
 *   <li>{@code nbt} present = <b>exact</b>: only a stack whose visible properties match sells. An exact entry
 *       always wins over the wildcard one for the same id.</li>
 * </ul>
 *
 * <p>Matching ignores {@code Damage} and {@code RepairCost} (durability wear and the anvil penalty), so a
 * slightly used tool is still the same goods; everything else - enchantments, potion, custom name, custom
 * model data, other mods' keys - has to match. There is no "match on key fields only" fuzzy mode on purpose:
 * it is exactly the hole that lets a renamed cheap item pass for an expensive one.</p>
 */
public final class ShopManager {

    /**
     * One goods entry. {@code nbt} is the SNBT of the stack's tag (without id/count), {@code null} for a plain
     * item and for hand written entries that mean "the plain variant".
     */
    public record ShopItem(String itemId, double buyPrice, String nbt) {
        public ShopItem(String itemId, double buyPrice) {
            this(itemId, buyPrice, null);
        }

        public boolean hasNbt() {
            return nbt != null && !nbt.isBlank();
        }
    }

    /**
     * One recycle price. {@code nbt == null} is the wildcard entry ("any variant of this id"); an empty string
     * means "the plain variant only"; anything else must match the stack's NBT.
     */
    public record RecycleEntry(String itemId, String nbt, double price) {
        public RecycleEntry(String itemId, double price) {
            this(itemId, null, price);
        }

        public boolean wildcard() {
            return nbt == null;
        }
    }

    public static final class Category {
        public String id = "";
        public String title = "";
        /** Item shown for this category on the category screen; blank = the category's first item. */
        public String icon = "";
        public List<ShopItem> items = new ArrayList<>();

        /** The stack the category screen shows: the configured icon, else the first item of the category. */
        public String iconId() {
            if (icon != null && !icon.isBlank()) {
                return icon;
            }
            return items == null || items.isEmpty() ? "minecraft:chest" : items.get(0).itemId();
        }
    }

    /**
     * Display order of the categories on the category screen - the order of the reference screenshot the owner
     * sent ("建筑方块 / 农作物 / 怪物掉落 / 染料 / 其他 / 食物 / 装饰 / 红石 / 矿物"), with the categories the
     * reference does not have appended.
     */
    private static final List<String> DISPLAY_ORDER = List.of(
            "blocks", "farming", "mobs", "dyes", "everything_else", "food", "books_potions",
            "decoration", "redstone", "ores", "spawn_eggs", "misc");

    /** Icons for the category screen, read off the owner's screenshot. Anything absent falls back. */
    private static final Map<String, String> DEFAULT_ICONS = Map.ofEntries(
            Map.entry("blocks", "minecraft:grass_block"),
            Map.entry("farming", "minecraft:golden_hoe"),
            Map.entry("mobs", "minecraft:rotten_flesh"),
            Map.entry("dyes", "minecraft:lapis_lazuli"),
            Map.entry("everything_else", "minecraft:copper_ingot"),
            Map.entry("food", "minecraft:bread"),
            Map.entry("books_potions", "minecraft:enchanted_book"),
            Map.entry("decoration", "minecraft:lapis_block"),
            Map.entry("redstone", "minecraft:redstone"),
            Map.entry("ores", "minecraft:iron_ingot"),
            Map.entry("spawn_eggs", "minecraft:creeper_spawn_egg"));

    private static final Map<String, Category> CATEGORIES = new LinkedHashMap<>();
    private static final List<RecycleEntry> RECYCLE = new ArrayList<>();
    private static Path configDir;

    /** Set while {@link #loadAll} converts a pre-0.2.6 {@code {"id": price}} map into the list format. */
    private static boolean recycleWasLegacy;

    private ShopManager() {
    }

    // ------------------------------------------------------------------ storage

    public static void loadAll(Path serverRoot) {
        configDir = serverRoot.resolve(MvWorld.CONFIG_DIR).resolve("shop");
        CATEGORIES.clear();
        RECYCLE.clear();
        recycleWasLegacy = false;
        readCategories(configDir.resolve("categories.json"));
        readRecycle(configDir.resolve("recycle.json"));
        if (CATEGORIES.isEmpty() || RECYCLE.isEmpty()) {
            if (!loadBundled()) {
                defaults();
            }
            saveAll();
        } else if (isToyDefault()) {
            // 0.2.0 shipped a three category toy table; the real one (converted from the owner's
            // EconomyShopGUI files) replaced it, so upgrade in place instead of asking for a file copy.
            CATEGORIES.clear();
            RECYCLE.clear();
            if (!loadBundled()) {
                defaults();
            }
            saveAll();
            OneKeyToSetAServer.LOGGER.info("[Shop] replaced the built-in 3 category default with the imported "
                    + "price table");
        }
        if (recycleWasLegacy) {
            backupOnce("recycle.json");
            saveAll();
            OneKeyToSetAServer.LOGGER.info("[Shop] recycle.json upgraded to the NBT aware list format "
                    + "(a copy of the old file is at recycle.json.bak)");
        }
        upgradeTable();
        applyDisplayDefaults();
        OneKeyToSetAServer.LOGGER.info("[Shop] {} categor(ies), {} item(s), {} recycle price(s) "
                        + "({} with NBT rules)",
                CATEGORIES.size(), itemCount(), RECYCLE.size(), nbtEntryCount());
    }

    private static int nbtEntryCount() {
        int total = 0;
        for (RecycleEntry entry : RECYCLE) {
            if (!entry.wildcard()) {
                total++;
            }
        }
        return total;
    }

    private static void readCategories(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            List<Category> list = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<List<Category>>() {
                    }.getType());
            if (list == null) {
                return;
            }
            for (Category category : list) {
                if (category != null && category.id != null && !category.id.isBlank()) {
                    CATEGORIES.put(category.id, category);
                }
            }
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Shop] could not read {}", file, t);
        }
    }

    /**
     * Reads {@code recycle.json} in either shape: the 0.2.6 list of entries, or the legacy {@code id -> price}
     * map (converted in memory and written back as a list afterwards).
     */
    private static void readRecycle(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            RECYCLE.addAll(parseRecycle(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))));
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Shop] could not read {}", file, t);
        }
    }

    /** Parses either the 0.2.6 entry list or the legacy {@code id -> price} map. */
    private static List<RecycleEntry> parseRecycle(JsonElement root) {
        List<RecycleEntry> result = new ArrayList<>();
        if (root == null || root.isJsonNull()) {
            return result;
        }
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String id = string(object, "itemId");
                if (id.isBlank()) {
                    id = string(object, "id");
                }
                if (id.isBlank()) {
                    continue;
                }
                String nbt = object.has("nbt") && !object.get("nbt").isJsonNull()
                        ? object.get("nbt").getAsString() : null;
                double price = object.has("price") ? object.get("price").getAsDouble() : 0.0D;
                if (price > 0.0D) {
                    result.add(new RecycleEntry(id.toLowerCase(Locale.ROOT), nbt, round(price)));
                }
            }
            return result;
        }
        if (root.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || !entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                try {
                    double price = entry.getValue().getAsDouble();
                    if (price > 0.0D) {
                        result.add(new RecycleEntry(entry.getKey().toLowerCase(Locale.ROOT), null, round(price)));
                    }
                } catch (Throwable ignored) {
                    // a hand edited non numeric value is skipped, not fatal
                }
            }
            recycleWasLegacy = !result.isEmpty();
        }
        return result;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    /**
     * Brings a price table that predates the new category screen in line with it: every category gets its icon
     * and the file is stored in the order the reference screenshot shows. This runs <b>once</b> - as soon as the
     * file carries icons of its own the operator's values win, including the order.
     */
    private static void applyDisplayDefaults() {
        boolean changed = false;
        for (Category category : CATEGORIES.values()) {
            String icon = DEFAULT_ICONS.get(category.id);
            if ((category.icon == null || category.icon.isBlank()) && icon != null) {
                category.icon = icon;
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        List<Category> ordered = new ArrayList<>();
        for (String id : DISPLAY_ORDER) {
            Category category = CATEGORIES.get(id);
            if (category != null) {
                ordered.add(category);
            }
        }
        for (Category category : CATEGORIES.values()) {
            if (!ordered.contains(category)) {
                ordered.add(category);
            }
        }
        CATEGORIES.clear();
        for (Category category : ordered) {
            CATEGORIES.put(category.id, category);
        }
        backupOnce("categories.json");
        saveAll();
        OneKeyToSetAServer.LOGGER.info("[Shop] categories.json: added the category icons and the display order "
                + "of the reference screenshot (a backup is at categories.json.bak)");
    }

    /** Keeps one copy of the file before it is rewritten, so the owner can compare. */
    private static void backupOnce(String name) {
        if (configDir == null) {
            return;
        }
        try {
            Path file = configDir.resolve(name);
            Path backup = configDir.resolve(name + ".bak");
            if (Files.isRegularFile(file) && !Files.exists(backup)) {
                Files.copy(file, backup);
            }
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.warn("[Shop] could not back up {}: {}", name, t.toString());
        }
    }

    /**
     * Reads the price table shipped inside the jar ({@code /shop-defaults/*.json}, converted from the shop
     * files the owner used before this mod existed).
     */
    private static boolean loadBundled() {
        try (java.io.InputStream categories = ShopManager.class
                .getResourceAsStream("/shop-defaults/categories.json");
             java.io.InputStream recycle = ShopManager.class.getResourceAsStream("/shop-defaults/recycle.json")) {
            if (categories == null || recycle == null) {
                return false;
            }
            List<Category> list = new Gson().fromJson(
                    new java.io.InputStreamReader(categories, StandardCharsets.UTF_8),
                    new TypeToken<List<Category>>() {
                    }.getType());
            RECYCLE.addAll(parseRecycle(JsonParser.parseReader(
                    new java.io.InputStreamReader(recycle, StandardCharsets.UTF_8))));
            if (list == null || list.isEmpty()) {
                return false;
            }
            for (Category category : list) {
                if (category != null && category.id != null && !category.id.isBlank()) {
                    CATEGORIES.put(category.id, category);
                }
            }
            return !CATEGORIES.isEmpty();
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Shop] could not read the bundled price table", t);
            return false;
        }
    }

    /**
     * 0.2.6 upgrade of an <b>existing</b> table: the permanently banned families leave the shop, the categories
     * they leave empty are dropped, and the new "附魔与药水" category plus the wildcard recycle prices for
     * enchanted books / potions / tipped arrows are added when the file does not have them yet.
     *
     * <p>Everything else in the operator's file (prices, their own items, their categories) is left alone.</p>
     */
    private static void upgradeTable() {
        boolean dirty = false;
        for (Category category : CATEGORIES.values()) {
            int before = category.items.size();
            category.items.removeIf(item -> TradeBlacklist.isBannedId(item.itemId()));
            if (category.items.size() != before) {
                dirty = true;
            }
        }
        int categoriesBefore = CATEGORIES.size();
        CATEGORIES.entrySet().removeIf(entry -> entry.getValue().items.isEmpty());
        if (CATEGORIES.size() != categoriesBefore) {
            dirty = true;
        }
        int recycleBefore = RECYCLE.size();
        RECYCLE.removeIf(entry -> TradeBlacklist.isBannedId(entry.itemId()));
        if (RECYCLE.size() != recycleBefore) {
            dirty = true;
        }
        if (!CATEGORIES.containsKey("books_potions")) {
            Category bundled = bundledCategory("books_potions");
            if (bundled != null) {
                CATEGORIES.put(bundled.id, bundled);
                dirty = true;
            }
        }
        for (RecycleEntry bundled : bundledRecycle()) {
            if (!BOOK_GOODS.contains(bundled.itemId()) || !bundled.wildcard()) {
                continue;
            }
            boolean present = false;
            for (RecycleEntry entry : RECYCLE) {
                if (entry.wildcard() && entry.itemId().equalsIgnoreCase(bundled.itemId())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                RECYCLE.add(bundled);
                dirty = true;
            }
        }
        if (dirty) {
            backupOnce("categories.json");
            backupOnce("recycle.json");
            saveAll();
            OneKeyToSetAServer.LOGGER.info("[Shop] table upgraded to 0.2.6: banned families removed, "
                    + "books/potions category and wildcard recycle prices added where missing");
        }
    }

    /** The NBT goods ids 0.2.6 sells and recycles. */
    private static final java.util.Set<String> BOOK_GOODS = java.util.Set.of(
            "minecraft:enchanted_book", "minecraft:potion", "minecraft:splash_potion",
            "minecraft:lingering_potion", "minecraft:tipped_arrow");

    private static Category bundledCategory(String id) {
        for (Category category : bundledCategories()) {
            if (category != null && id.equals(category.id) && !category.items.isEmpty()) {
                return category;
            }
        }
        return null;
    }

    private static List<Category> bundledCategories() {
        try (java.io.InputStream stream = ShopManager.class
                .getResourceAsStream("/shop-defaults/categories.json")) {
            if (stream == null) {
                return new ArrayList<>();
            }
            List<Category> list = new Gson().fromJson(
                    new java.io.InputStreamReader(stream, StandardCharsets.UTF_8),
                    new TypeToken<List<Category>>() {
                    }.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Shop] could not read the bundled categories", t);
            return new ArrayList<>();
        }
    }

    private static List<RecycleEntry> bundledRecycle() {
        try (java.io.InputStream stream = ShopManager.class
                .getResourceAsStream("/shop-defaults/recycle.json")) {
            if (stream == null) {
                return new ArrayList<>();
            }
            return parseRecycle(JsonParser.parseReader(
                    new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Shop] could not read the bundled recycle table", t);
            return new ArrayList<>();
        }
    }

    /** True when the stored data is still the three category toy default of 0.2.0. */
    private static boolean isToyDefault() {
        return CATEGORIES.size() == 3 && CATEGORIES.containsKey("blocks") && CATEGORIES.containsKey("food")
                && CATEGORIES.containsKey("tools");
    }

    public static void saveAll() {
        if (configDir == null) {
            return;
        }
        try {
            Files.createDirectories(configDir);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(configDir.resolve("categories.json"),
                    gson.toJson(new ArrayList<>(CATEGORIES.values())), StandardCharsets.UTF_8);
            Files.writeString(configDir.resolve("recycle.json"),
                    gson.toJson(new ArrayList<>(RECYCLE)), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Shop] could not save shop data", t);
        }
    }

    private static void defaults() {
        if (CATEGORIES.isEmpty()) {
            Category blocks = new Category();
            blocks.id = "blocks";
            blocks.title = "方块";
            blocks.items.add(new ShopItem("minecraft:dirt", 2.0D));
            blocks.items.add(new ShopItem("minecraft:stone", 4.0D));
            blocks.items.add(new ShopItem("minecraft:oak_log", 6.0D));
            blocks.items.add(new ShopItem("minecraft:glass", 8.0D));
            blocks.items.add(new ShopItem("minecraft:iron_ingot", 45.0D));
            blocks.items.add(new ShopItem("minecraft:gold_ingot", 120.0D));
            blocks.items.add(new ShopItem("minecraft:diamond", 840.0D));
            blocks.items.add(new ShopItem("minecraft:emerald", 300.0D));
            CATEGORIES.put(blocks.id, blocks);

            Category food = new Category();
            food.id = "food";
            food.title = "食物";
            food.items.add(new ShopItem("minecraft:bread", 12.0D));
            food.items.add(new ShopItem("minecraft:cooked_beef", 20.0D));
            food.items.add(new ShopItem("minecraft:golden_apple", 500.0D));
            CATEGORIES.put(food.id, food);

            Category tools = new Category();
            tools.id = "tools";
            tools.title = "工具";
            tools.items.add(new ShopItem("minecraft:iron_pickaxe", 150.0D));
            tools.items.add(new ShopItem("minecraft:diamond_pickaxe", 2500.0D));
            tools.items.add(new ShopItem("minecraft:shears", 60.0D));
            CATEGORIES.put(tools.id, tools);
        }
        if (RECYCLE.isEmpty()) {
            RECYCLE.add(new RecycleEntry("minecraft:dirt", 0.5D));
            RECYCLE.add(new RecycleEntry("minecraft:stone", 1.0D));
            RECYCLE.add(new RecycleEntry("minecraft:cobblestone", 1.0D));
            RECYCLE.add(new RecycleEntry("minecraft:oak_log", 2.0D));
            RECYCLE.add(new RecycleEntry("minecraft:glass", 2.5D));
            RECYCLE.add(new RecycleEntry("minecraft:iron_ingot", 15.0D));
            RECYCLE.add(new RecycleEntry("minecraft:gold_ingot", 40.0D));
            RECYCLE.add(new RecycleEntry("minecraft:diamond", 210.0D));
            RECYCLE.add(new RecycleEntry("minecraft:emerald", 90.0D));
            RECYCLE.add(new RecycleEntry("minecraft:bread", 4.0D));
            RECYCLE.add(new RecycleEntry("minecraft:cooked_beef", 7.0D));
        }
    }

    // ------------------------------------------------------------------ lookups

    public static List<Category> categories() {
        return new ArrayList<>(CATEGORIES.values());
    }

    public static Category category(String id) {
        return id == null ? null : CATEGORIES.get(id);
    }

    public static int itemCount() {
        int total = 0;
        for (Category category : CATEGORIES.values()) {
            total += category.items.size();
        }
        return total;
    }

    public static List<ShopItem> itemsOf(Category category, int page, int perPage) {
        List<ShopItem> result = new ArrayList<>();
        if (category == null) {
            return result;
        }
        int from = Math.max(0, page * perPage);
        for (int i = from; i < Math.min(category.items.size(), from + perPage); i++) {
            result.add(category.items.get(i));
        }
        return result;
    }

    /** A plain stack of an item id (no NBT). */
    public static ItemStack stackOf(String itemId, int count) {
        Item item = itemFor(itemId);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, Math.max(1, count));
    }

    /** The exact stack a goods entry describes, carrying its NBT when it has one. */
    public static ItemStack stackOf(ShopItem entry, int count) {
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = stackOf(entry.itemId(), count);
        if (!stack.isEmpty() && entry.hasNbt()) {
            CompoundTag tag = parseNbt(entry.nbt());
            if (tag != null && !tag.isEmpty()) {
                stack.setTag(tag);
            }
        }
        return stack;
    }

    public static Item itemFor(String itemId) {
        ResourceLocation id = itemId == null ? null : ResourceLocation.tryParse(itemId.toLowerCase(Locale.ROOT));
        if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) {
            return null;
        }
        return ForgeRegistries.ITEMS.getValue(id);
    }

    public static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    // ------------------------------------------------------------------ NBT helpers

    /** The tag of a stack as SNBT, or {@code null} when it carries none. */
    public static String snbtOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        return tag == null || tag.isEmpty() ? null : tag.toString();
    }

    private static CompoundTag parseNbt(String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return new CompoundTag();
        }
        try {
            return TagParser.parseTag(snbt);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Shop] could not parse NBT '{}'", snbt, t);
            return null;
        }
    }

    /**
     * The comparable form of a tag: a copy without the two fields that change by themselves, so "used a
     * little" still matches. Everything else stays, including other mods' keys.
     */
    private static CompoundTag normalized(CompoundTag tag) {
        CompoundTag copy = tag == null ? new CompoundTag() : tag.copy();
        copy.remove("Damage");
        copy.remove("RepairCost");
        return copy;
    }

    /** True when a stack (already normalised) satisfies a recycle entry's NBT rule. */
    private static boolean nbtMatches(CompoundTag stackTag, String entryNbt) {
        if (entryNbt == null) {
            return true;                      // wildcard
        }
        CompoundTag want = parseNbt(entryNbt);
        return want != null && nbtEquals(normalized(want), stackTag);
    }

    /**
     * Deep, list order aware NBT equality. Written out instead of relying on {@code Tag#equals} because not
     * every tag type overrides it and a silent {@code false} here means "player cannot sell his item".
     */
    private static boolean nbtEquals(Tag a, Tag b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.getId() != b.getId()) {
            return false;
        }
        if (a instanceof CompoundTag ca && b instanceof CompoundTag cb) {
            if (!ca.getAllKeys().equals(cb.getAllKeys())) {
                return false;
            }
            for (String key : ca.getAllKeys()) {
                if (!nbtEquals(ca.get(key), cb.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof CollectionTag<?> la && b instanceof CollectionTag<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!nbtEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.getAsString().equals(b.getAsString());
    }

    /** A short human summary of what makes this stack special, or "" for a plain item. */
    public static String describeNbt(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (stack.hasCustomHoverName()) {
            parts.add("名字「" + stack.getHoverName().getString() + "」");
        }
        Map<net.minecraft.world.item.enchantment.Enchantment, Integer> enchants =
                EnchantmentHelper.getEnchantments(stack);
        if (!enchants.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<net.minecraft.world.item.enchantment.Enchantment, Integer> entry : enchants.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(entry.getKey().getFullname(entry.getValue()).getString());
            }
            parts.add("附魔 " + sb);
        }
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            if (tag.contains("Potion")) {
                parts.add("药水 " + tag.getString("Potion").replace("minecraft:", ""));
            }
            if (tag.contains("CustomModelData")) {
                parts.add("模型 " + tag.getInt("CustomModelData"));
            }
            if (stack.isDamageableItem() && tag.contains("Damage")) {
                parts.add("耐久 " + (stack.getMaxDamage() - tag.getInt("Damage")) + "/" + stack.getMaxDamage());
            }
        }
        return String.join("，", parts);
    }

    // ------------------------------------------------------------------ recycle table

    /** Recycle price for one unit of this stack, or 0 when the shop does not buy that exact variant back. */
    public static double recycleUnitPrice(ItemStack stack) {
        RecycleEntry entry = entryFor(stack);
        return entry == null ? 0.0D : entry.price();
    }

    /** The recycle entry that governs this stack (exact match first, wildcard second), or null. */
    public static RecycleEntry entryFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String id = idOf(stack);
        if (id.isEmpty()) {
            return null;
        }
        CompoundTag stackTag = normalized(stack.getTag());
        RecycleEntry wildcard = null;
        for (RecycleEntry entry : RECYCLE) {
            if (!entry.itemId().equalsIgnoreCase(id)) {
                continue;
            }
            if (entry.wildcard()) {
                if (wildcard == null) {
                    wildcard = entry;
                }
                continue;
            }
            if (nbtMatches(stackTag, entry.nbt())) {
                return entry;
            }
        }
        return wildcard;
    }

    public static boolean isSellable(ItemStack stack) {
        return !stack.isEmpty() && sellBlocker(stack).isEmpty() && recycleUnitPrice(stack) > 0.0D;
    }

    /** Every recycle entry, exact ones included. */
    public static List<RecycleEntry> recycleEntries() {
        return new ArrayList<>(RECYCLE);
    }

    /** The wildcard view of the table ({@code id -> price}), kept for messages and older call sites. */
    public static Map<String, Double> recycleTable() {
        Map<String, Double> map = new LinkedHashMap<>();
        for (RecycleEntry entry : RECYCLE) {
            if (entry.wildcard()) {
                map.put(entry.itemId(), entry.price());
            }
        }
        return map;
    }

    /** The category a shop item belongs to, or null. */
    public static Category categoryOfItem(String itemId) {
        String key = itemId.toLowerCase(Locale.ROOT);
        for (Category category : CATEGORIES.values()) {
            for (ShopItem item : category.items) {
                if (item.itemId().equalsIgnoreCase(key) && !item.hasNbt()) {
                    return category;
                }
            }
        }
        for (Category category : CATEGORIES.values()) {
            for (ShopItem item : category.items) {
                if (item.itemId().equalsIgnoreCase(key)) {
                    return category;
                }
            }
        }
        return null;
    }

    /** The category new items are filed under; created on first use. */
    public static Category customCategory() {
        String id = "custom";
        Category category = CATEGORIES.get(id);
        if (category == null) {
            category = new Category();
            category.id = id;
            category.title = "§e服主新增";
            CATEGORIES.put(id, category);
            saveAll();
        }
        return category;
    }

    /**
     * Sets (or adds) the shop price of a plain item. Existing items keep their category; new ones go to
     * {@link #customCategory()}.
     *
     * @return true when an existing entry was updated, false when a new one was added
     */
    public static boolean setBuyPrice(String itemId, double price) {
        return setBuyPrice(itemId, null, price);
    }

    /** Sets (or adds) the shop price of an item <b>with this exact NBT</b> ({@code null} = the plain item). */
    public static boolean setBuyPrice(String itemId, String nbt, double price) {
        String key = itemId.toLowerCase(Locale.ROOT);
        for (Category category : CATEGORIES.values()) {
            for (int i = 0; i < category.items.size(); i++) {
                ShopItem existing = category.items.get(i);
                if (existing.itemId().equalsIgnoreCase(key) && sameNbt(existing.nbt(), nbt)) {
                    category.items.set(i, new ShopItem(key, round(price), blankToNull(nbt)));
                    saveAll();
                    return true;
                }
            }
        }
        customCategory().items.add(new ShopItem(key, round(price), blankToNull(nbt)));
        saveAll();
        return false;
    }

    /** Takes an item out of every category (shop stops selling every variant of it). */
    public static boolean removeItem(String itemId) {
        String key = itemId.toLowerCase(Locale.ROOT);
        boolean removed = false;
        for (Category category : CATEGORIES.values()) {
            removed |= category.items.removeIf(item -> item.itemId().equalsIgnoreCase(key));
        }
        if (removed) {
            saveAll();
        }
        return removed;
    }

    /** Takes <b>one</b> goods entry (this exact NBT) out of the shop. */
    public static boolean removeEntry(String itemId, String nbt) {
        String key = itemId.toLowerCase(Locale.ROOT);
        boolean removed = false;
        for (Category category : CATEGORIES.values()) {
            removed |= category.items.removeIf(item -> item.itemId().equalsIgnoreCase(key)
                    && sameNbt(item.nbt(), nbt));
        }
        if (removed) {
            saveAll();
        }
        return removed;
    }

    /** Files an item under a specific category at a specific price (used by the in-game price editor). */
    public static boolean putInCategory(String categoryId, String itemId, double price) {
        return putInCategory(categoryId, itemId, null, price);
    }

    /** Files an item <b>with this exact NBT</b> under a specific category. */
    public static boolean putInCategory(String categoryId, String itemId, String nbt, double price) {
        Category target = CATEGORIES.get(categoryId.toLowerCase(Locale.ROOT));
        if (target == null) {
            return false;
        }
        String key = itemId.toLowerCase(Locale.ROOT);
        for (Category category : CATEGORIES.values()) {
            category.items.removeIf(item -> item.itemId().equalsIgnoreCase(key)
                    && sameNbt(item.nbt(), nbt));
        }
        target.items.add(new ShopItem(key, round(price), blankToNull(nbt)));
        saveAll();
        return true;
    }

    /** Drops the wildcard recycle price of an item (it can no longer be sold back by id). */
    public static boolean removeRecyclePrice(String itemId) {
        return removeRecyclePrice(itemId, null);
    }

    /** Drops one recycle entry. {@code nbt == null} removes the wildcard one. */
    public static boolean removeRecyclePrice(String itemId, String nbt) {
        String key = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        boolean removed = RECYCLE.removeIf(entry -> entry.itemId().equalsIgnoreCase(key)
                && sameNbt(entry.nbt(), nbt));
        if (removed) {
            saveAll();
        }
        return removed;
    }

    /** Money is stored with two decimals; keep edited prices in the same shape. */
    public static double round(double value) {
        return java.math.BigDecimal.valueOf(Math.max(0.0D, value))
                .setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /** Wildcard recycle price for one unit of an item id (the pre-0.2.6 meaning), or 0. */
    public static double recyclePriceOf(String itemId) {
        String key = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        for (RecycleEntry entry : RECYCLE) {
            if (entry.wildcard() && entry.itemId().equalsIgnoreCase(key)) {
                return entry.price();
            }
        }
        return 0.0D;
    }

    /** Recycle price of one exact variant ({@code nbt == null} = wildcard), or 0. */
    public static double recyclePriceOf(String itemId, String nbt) {
        String key = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        for (RecycleEntry entry : RECYCLE) {
            if (entry.itemId().equalsIgnoreCase(key) && sameNbt(entry.nbt(), nbt)) {
                return entry.price();
            }
        }
        return 0.0D;
    }

    /**
     * Sets the recycle price of a plain item.
     *
     * <p>A recycle price at or above the shop's own buy price would let anybody mint money by buying the item
     * and selling it straight back, so such edits are refused instead of saved.</p>
     *
     * @return false when the value was refused
     */
    public static boolean setRecyclePrice(String itemId, double price) {
        return setRecyclePrice(itemId, null, price);
    }

    /** Sets the recycle price of one exact variant; {@code nbt == null} is the wildcard entry. */
    public static boolean setRecyclePrice(String itemId, String nbt, double price) {
        String key = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        if (key.isBlank()) {
            // the editor sends an empty id while nothing is selected; that is not an operator mistake worth a
            // warning on every click
            return false;
        }
        double value = round(price);
        double buy = buyPriceOf(key, nbt);
        if (buy > 0.0D && value >= buy) {
            OneKeyToSetAServer.LOGGER.warn("[Shop] refused recycle price {} for {} (buy price is {})",
                    value, key, buy);
            return false;
        }
        String stored = blankToNull(nbt);
        for (int i = 0; i < RECYCLE.size(); i++) {
            RecycleEntry entry = RECYCLE.get(i);
            if (entry.itemId().equalsIgnoreCase(key) && sameNbt(entry.nbt(), stored)) {
                RECYCLE.set(i, new RecycleEntry(entry.itemId(), stored, value));
                saveAll();
                return true;
            }
        }
        RECYCLE.add(new RecycleEntry(key, stored, value));
        saveAll();
        return true;
    }

    /** Buy price of one exact variant; falls back to the wildcard/plain entry of the same id. */
    public static double buyPriceOf(String itemId, String nbt) {
        String key = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        double plain = 0.0D;
        for (Category category : CATEGORIES.values()) {
            for (ShopItem item : category.items) {
                if (!item.itemId().equalsIgnoreCase(key)) {
                    continue;
                }
                if (sameNbt(item.nbt(), nbt)) {
                    return item.buyPrice();
                }
                if (!item.hasNbt()) {
                    plain = item.buyPrice();
                }
            }
        }
        return plain;
    }

    /** Buy price of the plain variant of an id (0 when the shop does not sell it that way). */
    public static double priceOf(String itemId) {
        String wanted = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        for (Category category : CATEGORIES.values()) {
            for (ShopItem item : category.items) {
                if (item.itemId().equalsIgnoreCase(wanted) && !item.hasNbt()) {
                    return item.buyPrice();
                }
            }
        }
        return 0.0D;
    }

    private static boolean sameNbt(String a, String b) {
        return Objects.equals(blankToNull(a), blankToNull(b));
    }

    private static String blankToNull(String nbt) {
        return nbt == null || nbt.isBlank() ? null : nbt;
    }

    // ------------------------------------------------------------------ transactions

    /** Buys {@code amount} units of a shop entry. Buy-only: nothing can be sold through this path. */
    public static boolean buy(ServerPlayer player, String itemId, int amount) {
        return buy(player, new ShopItem(itemId, priceOf(itemId)), amount);
    }

    /** Buys {@code amount} units of one goods entry (its NBT is handed out as it is). */
    public static boolean buy(ServerPlayer player, ShopItem entry, int amount) {
        if (entry == null || amount <= 0 || player.getServer() == null) {
            return false;
        }
        ItemStack single = stackOf(entry, 1);
        if (single.isEmpty()) {
            return false;
        }
        String banned = TradeBlacklist.reasonFor(single);
        if (!banned.isEmpty()) {
            player.sendSystemMessage(Component.literal(banned));
            return false;
        }
        if (MarketManager.isBlacklisted(idOf(single))) {
            player.sendSystemMessage(Component.literal("§c该物品已被自由市场黑名单禁止交易，商店也不出售"));
            return false;
        }
        double unit = entry.buyPrice();
        if (unit <= 0.0D) {
            player.sendSystemMessage(Component.literal("§c本店不出售这个物品（只回收）"));
            return false;
        }
        double total = unit * amount;
        if (!MoneyService.withdraw(player.getServer(), player.getUUID(), total)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c余额不足，需要 " + MoneyService.format(total)));
            return false;
        }
        int left = amount;
        while (left > 0) {
            int batch = Math.min(left, single.getMaxStackSize());
            ItemStack stack = fullCopy(single, batch);
            left -= batch;
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
        player.sendSystemMessage(Component.literal("§a已购买 " + amount + " × ")
                .append(com.oktsas.onekeytosetaserver.util.ItemNames.withId(entry.itemId()))
                .append(Component.literal("§a，花费 " + MoneyService.format(total)
                        + "，余额 " + MoneyService.format(MoneyService.get(player.getServer(), player.getUUID())))));
        return true;
    }

    /** A copy of the template with a new count (tags included). */
    private static ItemStack fullCopy(ItemStack template, int count) {
        ItemStack copy = template.copy();
        copy.setCount(Math.max(1, count));
        return copy;
    }

    /** Main inventory slots - armour and the off hand are deliberately not sellable. */
    public static final int MAIN_SLOTS = 36;

    /**
     * Why this item cannot be sold to the shop, in player facing Chinese, or an empty string when it can.
     * Mirrors the checks the free market applies so both systems refuse the same items.
     */
    public static String sellBlocker(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "§c这不是一个有效的物品";
        }
        return sellBlocker(stackOf(itemId, 1));
    }

    /** Same check for a concrete stack, NBT included. */
    public static String sellBlocker(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "§c这不是一个有效的物品";
        }
        String banned = TradeBlacklist.reasonFor(stack);
        if (!banned.isEmpty()) {
            return banned;
        }
        String id = idOf(stack);
        if (MarketManager.isBlacklisted(id)) {
            return "§c该物品已被自由市场黑名单禁止交易，商店也不回收";
        }
        if (entryFor(stack) == null) {
            return "§c本店不回收该物品（没有回收价）";
        }
        if (recycleUnitPrice(stack) <= 0.0D) {
            return "§c本店不回收该物品（没有回收价）";
        }
        return "";
    }

    /** How many of this exact variant the player is carrying in the main inventory. */
    public static int inventoryCount(ServerPlayer player, String itemId) {
        return inventoryCount(player, stackOf(itemId, 1));
    }

    /** How many stacks identical to the template (id + recycle rule) the player carries. */
    public static int inventoryCount(ServerPlayer player, ItemStack template) {
        if (player == null || template == null || template.isEmpty()) {
            return 0;
        }
        String key = idOf(template);
        RecycleEntry wanted = entryFor(template);
        int total = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Math.min(MAIN_SLOTS, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !idOf(stack).equalsIgnoreCase(key)) {
                continue;
            }
            if (wanted != null && !wanted.equals(entryFor(stack))) {
                continue;
            }
            total += stack.getCount();
        }
        return total;
    }

    /**
     * Sells {@code amount} units the player is carrying at the recycle price.
     *
     * @return the number of units actually sold (0 means the trade was refused and money was untouched)
     */
    public static int sell(ServerPlayer player, String itemId, int amount) {
        return sell(player, stackOf(itemId, 1), amount);
    }

    /** Sells {@code amount} units that match the template (same item, same recycle rule). */
    public static int sell(ServerPlayer player, ShopItem entry, int amount) {
        return sell(player, stackOf(entry, 1), amount);
    }

    /** Sells units matching this stack; only identical variants are taken. */
    public static int sell(ServerPlayer player, ItemStack template, int amount) {
        if (amount <= 0 || player.getServer() == null || template == null || template.isEmpty()) {
            return 0;
        }
        String blocker = sellBlocker(template);
        if (!blocker.isEmpty()) {
            player.sendSystemMessage(Component.literal(blocker));
            return 0;
        }
        RecycleEntry entry = entryFor(template);
        if (entry == null) {
            player.sendSystemMessage(Component.literal("§c本店不回收该物品（没有回收价）"));
            return 0;
        }
        int have = inventoryCount(player, template);
        if (have < amount) {
            player.sendSystemMessage(Component.literal(
                    "§c你只有 " + have + " 个 " + idOf(template) + "，卖不了 " + amount + " 个"));
            return 0;
        }
        String key = idOf(template);
        int left = amount;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Math.min(MAIN_SLOTS, inventory.getContainerSize()) && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !idOf(stack).equalsIgnoreCase(key)
                    || !entry.equals(entryFor(stack))) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            left -= take;
            stack.shrink(take);
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        double earned = entry.price() * amount;
        MoneyService.deposit(player.getServer(), player.getUUID(), earned);
        player.sendSystemMessage(Component.literal("§a已出售 " + amount + " × ")
                .append(com.oktsas.onekeytosetaserver.util.ItemNames.withId(key))
                .append(Component.literal("§a，获得 " + MoneyService.format(earned)
                        + "，余额 " + MoneyService.format(
                                MoneyService.get(player.getServer(), player.getUUID())))));
        return amount;
    }

    /**
     * Sells every sellable stack in the player's <b>main</b> inventory (36 slots) at the recycle prices.
     * Worn armour, the off hand and cosmetic slots are left alone.
     *
     * @return {@code {soldStacks, earned}} - unsellable items are left untouched.
     */
    public static double[] sellAll(ServerPlayer player) {
        double earned = 0.0D;
        int soldStacks = 0;
        if (player == null || player.getServer() == null) {
            return new double[]{0, 0};
        }
        int slots = Math.min(MAIN_SLOTS, player.getInventory().getContainerSize());
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!isSellable(stack)) {
                continue;
            }
            double unit = recycleUnitPrice(stack);
            earned += unit * stack.getCount();
            soldStacks++;
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        if (earned > 0.0D) {
            MoneyService.deposit(player.getServer(), player.getUUID(), earned);
        }
        player.sendSystemMessage(Component.literal(
                "§a已出售 " + soldStacks + " 组物品，获得 " + MoneyService.format(earned)
                        + "（只算背包 36 格，身上的装备不卖）"
                        + "，余额 " + MoneyService.format(MoneyService.get(player.getServer(), player.getUUID()))));
        return new double[]{soldStacks, earned};
    }
}
