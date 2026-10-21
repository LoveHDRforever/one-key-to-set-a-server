package com.oktsas.onekeytosetaserver.market;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.economy.MoneyService;
import com.oktsas.onekeytosetaserver.trade.TradeBlacklist;
import com.oktsas.onekeytosetaserver.util.ItemNames;
import com.oktsas.onekeytosetaserver.world.MvWorld;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The free market (auction house, buy-out only) plus the mailbox it depends on.
 *
 * <p>Rules that were asked for: one global market, fixed prices, no bidding; the seller picks a duration and
 * pays for it with a management fee taken from the sale (longer listing, higher fee, capped at 10%); at most
 * {@link MarketConfig#maxListings} listings per player; an expired listing goes to the seller's mailbox.</p>
 *
 * <p>{@code hours <= 0} means a <b>permanent</b> listing: it never expires and never lands in the mailbox.
 * That is allowed when {@code config.json} has {@code allowPermanent} (default true) and is charged the flat
 * {@link MarketConfig#permanentFeeRate} (default 10%, i.e. the old longest tier).</p>
 *
 * <p>Storage: {@code <server root>/multiverse/market/listings.json}, {@code mailbox.json} and
 * {@code config.json}, items as SNBT so the files stay readable.</p>
 */
public final class MarketManager {

    /**
     * duration in hours -> management fee rate taken from the sale price.
     * {@code hours <= 0} is a permanent listing and uses {@link MarketConfig#permanentFeeRate}.
     */
    public static double feeRate(int hours) {
        if (hours <= 0) {
            return config.permanentFeeRate;
        }
        if (hours <= 1) {
            return 0.01D;
        }
        if (hours <= 6) {
            return 0.02D;
        }
        if (hours <= 24) {
            return 0.03D;
        }
        if (hours <= 72) {
            return 0.05D;
        }
        if (hours <= 168) {
            return 0.08D;
        }
        return 0.10D;
    }

    public static final class Listing {
        public String id = "";
        public String seller = "";
        public String sellerName = "";
        public String item = "";
        public double price;
        public long createdAt;
        public int hours = 24;
        public String label = "";
    }

    private static final List<Listing> LISTINGS = new ArrayList<>();
    private static final Map<String, List<String>> MAILBOX = new LinkedHashMap<>();
    /** Item ids the operators banned from the market (level 3/4 manage it). */
    private static final java.util.Set<String> BLACKLIST = new java.util.LinkedHashSet<>();
    /** Returned by {@link #blacklistAdd} when the id belongs to a permanently banned family. */
    public static final int BLACKLIST_PERMANENT = -2;
    private static Path dir;
    private static Path configFile;
    private static MarketConfig config = new MarketConfig();
    /**
     * Set when a market file exists but could not be parsed (or parsed to nothing at all). In that state the
     * file is never overwritten - it is the only copy of that data - and the matching action is refused so
     * that memory and disk cannot drift apart (a listing that only exists in memory would either vanish or
     * be sold twice).
     */
    private static boolean listingsUnreadable;
    private static boolean mailboxUnreadable;
    private static boolean blacklistUnreadable;

    private MarketManager() {
    }

    // ------------------------------------------------------------------ config

    public static MarketConfig config() {
        return config;
    }

    /** Listings one player may have up at once (clamped to at least 1). */
    public static int maxListings() {
        return Math.max(1, config.maxListings);
    }

    /** {@code 永久} for a listing that never expires, otherwise {@code Nh}. */
    public static String durationLabel(int hours) {
        return hours <= 0 ? "永久" : hours + "h";
    }

    /** Fee rate as whole percent, for display. */
    public static int feePercent(int hours) {
        return (int) Math.round(feeRate(hours) * 100.0D);
    }

    /**
     * Reads {@code market/config.json}. A missing file keeps the defaults (the caller writes it back), a
     * broken one keeps whatever values are already in memory instead of wiping the settings.
     *
     * @return true when the file was read
     */
    public static boolean readConfig() {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return false;
        }
        MarketConfig loaded;
        try {
            loaded = new Gson().fromJson(Files.readString(configFile,
                    java.nio.charset.StandardCharsets.UTF_8), MarketConfig.class);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Market] could not read {} (keeping the current values)",
                    configFile, t);
            return false;
        }
        if (loaded == null) {
            return false;
        }
        config = loaded;
        clampConfig();
        return true;
    }

    private static void clampConfig() {
        if (config.maxHours < 1) {
            config.maxHours = 1;
        }
        // the command argument cannot parse past this, so the setting may not promise more
        if (config.maxHours > 1_000_000) {
            config.maxHours = 1_000_000;
        }
        if (config.permanentFeeRate < 0.0D) {
            config.permanentFeeRate = 0.0D;
        }
        if (config.permanentFeeRate > 1.0D) {
            config.permanentFeeRate = 1.0D;
        }
        if (config.maxListings < 1) {
            config.maxListings = 1;
        }
        if (!config.allowPermanent && config.defaultHours <= 0) {
            config.defaultHours = 24;
        }
        if (config.defaultHours < 0) {
            config.defaultHours = 0;
        }
        if (config.defaultHours > config.maxHours) {
            config.defaultHours = config.maxHours;
        }
    }

    public static void saveConfig() {
        if (configFile == null) {
            return;
        }
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, new GsonBuilder().setPrettyPrinting().create().toJson(config),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Market] could not write {}", configFile, t);
        }
    }

    // ------------------------------------------------------------------ storage

    public static void loadAll(Path serverRoot) {
        dir = serverRoot.resolve(MvWorld.CONFIG_DIR).resolve("market");
        configFile = dir.resolve("config.json");
        config = new MarketConfig();
        boolean configExisted = Files.isRegularFile(configFile);
        boolean configRead = readConfig();
        LISTINGS.clear();
        MAILBOX.clear();
        listingsUnreadable = false;
        mailboxUnreadable = false;
        blacklistUnreadable = false;

        Path listingsFile = dir.resolve("listings.json");
        if (Files.isRegularFile(listingsFile)) {
            try {
                List<Listing> loaded = new Gson().fromJson(Files.readString(listingsFile),
                        new TypeToken<List<Listing>>() {
                        }.getType());
                if (loaded == null) {
                    listingsUnreadable = true;
                    OneKeyToSetAServer.LOGGER.error("[Market] {} holds no JSON value at all (empty or "
                            + "truncated?). It is left as it is and no listing is loaded.", listingsFile);
                } else {
                    LISTINGS.addAll(loaded);
                }
            } catch (Throwable t) {
                listingsUnreadable = true;
                OneKeyToSetAServer.LOGGER.error("[Market] could not read {} - it will NOT be overwritten. "
                        + "Fix (or restore) the file and restart.", listingsFile, t);
            }
        }
        Path mailboxFile = dir.resolve("mailbox.json");
        if (Files.isRegularFile(mailboxFile)) {
            try {
                Map<String, List<String>> loaded = new Gson().fromJson(Files.readString(mailboxFile),
                        new TypeToken<Map<String, List<String>>>() {
                        }.getType());
                if (loaded == null) {
                    mailboxUnreadable = true;
                    OneKeyToSetAServer.LOGGER.error("[Market] {} holds no JSON value at all (empty or "
                            + "truncated?). It is left as it is.", mailboxFile);
                } else {
                    MAILBOX.putAll(loaded);
                }
            } catch (Throwable t) {
                mailboxUnreadable = true;
                OneKeyToSetAServer.LOGGER.error("[Market] could not read {} - it will NOT be overwritten",
                        mailboxFile, t);
            }
        }
        Path blacklistFile = dir.resolve("blacklist.json");
        if (Files.isRegularFile(blacklistFile)) {
            try {
                java.util.Set<String> loaded = new Gson().fromJson(Files.readString(blacklistFile),
                        new TypeToken<java.util.LinkedHashSet<String>>() {
                        }.getType());
                if (loaded == null) {
                    blacklistUnreadable = true;
                    OneKeyToSetAServer.LOGGER.error("[Market] {} holds no JSON value at all (empty or "
                            + "truncated?). It is left as it is.", blacklistFile);
                } else {
                    BLACKLIST.clear();
                    for (String id : loaded) {
                        if (id != null && !id.isBlank()) {
                            BLACKLIST.add(id.toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                }
            } catch (Throwable t) {
                blacklistUnreadable = true;
                OneKeyToSetAServer.LOGGER.error("[Market] could not read {} - it will NOT be overwritten",
                        blacklistFile, t);
            }
        }
        if (!configExisted || configRead) {
            saveConfig();       // materialise / normalise config.json so the operator has something to edit
        } else {
            OneKeyToSetAServer.LOGGER.error("[Market] {} could not be read; keeping the built-in defaults and "
                    + "leaving the file untouched", configFile);
        }
        sweepBannedListings();
        OneKeyToSetAServer.LOGGER.info("[Market] {} listing(s), {} mailbox entr(ies), {} blacklisted item(s); "
                        + "permanent={} (fee {}%), maxHours={}, defaultHours={}, maxListings={}",
                LISTINGS.size(), MAILBOX.size(), BLACKLIST.size(), config.allowPermanent,
                feePercent(0), config.maxHours, config.defaultHours, maxListings());
        if (!listingsWritable() || mailboxUnreadable || blacklistUnreadable) {
            OneKeyToSetAServer.LOGGER.error("[Market] market data is incomplete (listings={} mailbox={} "
                    + "blacklist={}) - the affected files stay untouched until they are fixed and the server "
                    + "is restarted", listingsUnreadable, mailboxUnreadable, blacklistUnreadable);
        }
    }

    /** True while a listing may be added or removed; false when listings.json could not be read. */
    public static boolean listingsWritable() {
        return !listingsUnreadable;
    }

    /**
     * True when this stack must not be traded at all: the permanent family ban (armour, weapons, tools, ore
     * blocks, spawn eggs) or the operator blacklist.
     */
    public static boolean isBanned(ItemStack stack) {
        return TradeBlacklist.isBanned(stack) || isBlacklisted(stack);
    }

    /** Why this stack cannot be traded, in player facing Chinese, or "" when it may. */
    public static String banReason(ItemStack stack) {
        String permanent = TradeBlacklist.reasonFor(stack);
        if (!permanent.isEmpty()) {
            return permanent;
        }
        return isBlacklisted(stack) ? "§c这个物品被禁止在市场上交易" : "";
    }

    /**
     * Takes down listings the <b>permanent</b> ban or the operator blacklist covers and mails the goods back to
     * the seller. Runs once per start, after the files were read, so an item banned by an upgrade cannot stay
     * on sale forever.
     */
    private static void sweepBannedListings() {
        List<Listing> hit = new ArrayList<>();
        for (Listing listing : LISTINGS) {
            if (isBanned(decode(listing.item))) {
                hit.add(listing);
            }
        }
        if (hit.isEmpty()) {
            return;
        }
        for (Listing listing : hit) {
            LISTINGS.remove(listing);
            ItemStack stack = decode(listing.item);
            if (!stack.isEmpty()) {
                MAILBOX.computeIfAbsent(listing.seller, key -> new ArrayList<>()).add(encode(stack));
            }
        }
        if (listingsWritable() && !mailboxUnreadable) {
            saveAll();
        }
        OneKeyToSetAServer.LOGGER.info("[Market] took down {} listing(s) of banned items and mailed them back "
                + "to their sellers", hit.size());
    }

    public static void saveAll() {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            writeMarketFile("listings.json", listingsUnreadable, gson.toJson(LISTINGS));
            writeMarketFile("mailbox.json", mailboxUnreadable, gson.toJson(MAILBOX));
            writeMarketFile("blacklist.json", blacklistUnreadable,
                    gson.toJson(new java.util.ArrayList<>(BLACKLIST)));
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Market] could not save market data", t);
        }
    }

    /**
     * Writes one market file. A file that could not be read is never overwritten: the copy on disk is the
     * only one that still holds the data, so it stays as it is until the operator fixes it.
     */
    private static void writeMarketFile(String name, boolean locked, String json) {
        Path target = dir.resolve(name);
        if (locked) {
            OneKeyToSetAServer.LOGGER.error("[Market] refusing to write {}: it still holds data that could not "
                    + "be read. Restore it and restart the server.", target);
            return;
        }
        try {
            Files.writeString(target, json);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Market] could not write {}", target, t);
        }
    }

    public static List<Listing> listings() {
        return new ArrayList<>(LISTINGS);
    }

    /** The categories the market screen cycles through; derived from the item, nothing to configure. */
    public static final String[] CATEGORIES = {"方块", "工具", "武器", "防具", "食物", "材料"};

    /** Coarse category of a listing, guessed from the item type so operators do not have to tag items. */
    public static String categoryOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "材料";
        }
        net.minecraft.world.item.Item item = stack.getItem();
        if (item.getFoodProperties() != null) {
            return "食物";
        }
        if (item instanceof net.minecraft.world.item.ArmorItem) {
            return "防具";
        }
        if (item instanceof net.minecraft.world.item.SwordItem
                || item instanceof net.minecraft.world.item.BowItem
                || item instanceof net.minecraft.world.item.CrossbowItem
                || item instanceof net.minecraft.world.item.TridentItem) {
            return "武器";
        }
        if (item instanceof net.minecraft.world.item.DiggerItem
                || item instanceof net.minecraft.world.item.ShearsItem
                || item instanceof net.minecraft.world.item.FishingRodItem) {
            return "工具";
        }
        if (item instanceof net.minecraft.world.item.BlockItem) {
            return "方块";
        }
        return "材料";
    }

    /**
     * The listings a screen should show: optional category, optional free-text search (item name, label,
     * seller or category) and optional "only this seller". Blacklisted items are never shown.
     */
    public static List<Listing> filtered(String category, String search, UUID seller) {
        List<Listing> result = new ArrayList<>();
        String needle = search == null ? "" : search.trim().toLowerCase(java.util.Locale.ROOT);
        for (Listing listing : LISTINGS) {
            if (seller != null && !listing.seller.equals(seller.toString())) {
                continue;
            }
            ItemStack stack = decode(listing.item);
            if (isBanned(stack)) {
                continue;
            }
            String itemCategory = categoryOf(stack);
            if (category != null && !category.isBlank() && !category.equals(itemCategory)) {
                continue;
            }
            if (!needle.isEmpty()) {
                String haystack = (listing.label + " " + listing.sellerName + " " + itemCategory + " "
                        + stack.getHoverName().getString() + " " + listing.id).toLowerCase(java.util.Locale.ROOT);
                if (!haystack.contains(needle)) {
                    continue;
                }
            }
            result.add(listing);
        }
        return result;
    }

    /** Takes a listing off the market and hands the goods back to its seller (撤单). */
    public static boolean cancel(ServerPlayer player, String listingId) {
        Listing listing = find(listingId);
        if (listing == null) {
            player.sendSystemMessage(Component.literal("§c找不到该商品"));
            return false;
        }
        if (!listing.seller.equals(player.getUUID().toString())) {
            player.sendSystemMessage(Component.literal("§c只能撤回自己上架的商品"));
            return false;
        }
        LISTINGS.remove(listing);
        ItemStack stack = decode(listing.item);
        if (!stack.isEmpty() && !player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        saveAll();
        player.sendSystemMessage(Component.literal("§a已撤回 §f").append(displayOf(listing))
                .append(Component.literal("§a，物品已退回")));
        return true;
    }

    public static int countOf(UUID seller) {
        int count = 0;
        for (Listing listing : LISTINGS) {
            if (listing.seller.equals(seller.toString())) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ blacklist

    public static java.util.Set<String> blacklist() {
        return new java.util.LinkedHashSet<>(BLACKLIST);
    }

    public static boolean isBlacklisted(ItemStack stack) {
        return !stack.isEmpty() && isBlacklisted(idOf(stack));
    }

    /** Registry id of the item behind a stack, e.g. {@code minecraft:diamond}. */
    public static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static boolean isBlacklisted(String itemId) {
        return itemId != null && BLACKLIST.contains(itemId.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Bans an item from the market. Listings that are already up are taken down and mailed back to their
     * sellers, so nobody keeps selling a banned item.
     *
     * @return the number of listings that were removed
     */
    public static int blacklistAdd(net.minecraft.server.MinecraftServer server, String itemId) {
        String key = itemId.toLowerCase(java.util.Locale.ROOT);
        if (TradeBlacklist.isBannedId(key)) {
            return BLACKLIST_PERMANENT;
        }
        if (!BLACKLIST.add(key)) {
            return 0;
        }
        int removed = 0;
        List<Listing> hit = new ArrayList<>();
        for (Listing listing : LISTINGS) {
            if (idOf(decode(listing.item)).equalsIgnoreCase(key)) {
                hit.add(listing);
            }
        }
        for (Listing listing : hit) {
            LISTINGS.remove(listing);
            removed++;
            ItemStack stack = decode(listing.item);
            if (server != null && !stack.isEmpty()) {
                sendMail(server, UUID.fromString(listing.seller), stack);
            }
        }
        saveAll();
        return removed;
    }

    public static boolean blacklistRemove(String itemId) {
        boolean removed = BLACKLIST.remove(itemId == null ? "" : itemId.toLowerCase(java.util.Locale.ROOT));
        if (removed) {
            saveAll();
        }
        return removed;
    }

    /** Wipes the market; the headless self-test uses it so repeated dev runs start from a known state. */
    public static void clearAllForTest() {
        LISTINGS.clear();
        saveAll();
    }

    public static Listing find(String id) {        if (id == null) {
            return null;
        }
        for (Listing listing : LISTINGS) {
            if (listing.id.equalsIgnoreCase(id)) {
                return listing;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ sell / buy

    public static boolean sell(ServerPlayer player, ItemStack stack, double price, int hours) {
        if (stack.isEmpty() || price <= 0.0D || hours < 0) {
            return false;
        }
        if (!listingsWritable()) {
            player.sendSystemMessage(Component.literal("§c市场数据文件读取失败，暂时无法上架"
                    + "（管理员需要先修复 multiverse/market/listings.json 并重启服务器）"));
            return false;
        }
        if (hours == 0 && !config.allowPermanent) {
            player.sendSystemMessage(Component.literal("§c本服不允许永久挂单，请写 1~" + config.maxHours
                    + " 小时（multiverse/market/config.json 的 allowPermanent）"));
            return false;
        }
        if (hours > config.maxHours) {
            player.sendSystemMessage(Component.literal("§c挂单时长最长 " + config.maxHours
                    + " 小时（0 = 永久）"));
            return false;
        }
        if (isBanned(stack)) {
            player.sendSystemMessage(Component.literal(banReason(stack)));
            return false;
        }
        if (countOf(player.getUUID()) >= maxListings()) {
            player.sendSystemMessage(Component.literal("§c上架位已满（最多 " + maxListings() + " 个）"));
            return false;
        }
        Listing listing = createForTest(player.getUUID(), player.getGameProfile().getName(), stack, price, hours);
        player.sendSystemMessage(Component.literal("§a已上架 §f").append(displayOf(listing))
                .append(Component.literal(" §a价格 " + MoneyService.format(price)
                        + "，时长 " + durationLabel(hours) + "，管理费 " + feePercent(hours) + "%"
                        + (hours <= 0 ? "（永久挂单：不会到期，也不会退回邮箱）" : "")
                        + "（编号 " + listing.id + "）")));
        return true;
    }

    /** Buy-out: buyer pays, seller receives the price minus the duration fee. */
    public static boolean buy(ServerPlayer player, String listingId) {
        Listing listing = find(listingId);
        if (listing == null) {
            player.sendSystemMessage(Component.literal("§c找不到该商品"));
            return false;
        }
        if (listing.seller.equals(player.getUUID().toString())) {
            player.sendSystemMessage(Component.literal("§c不能购买自己的商品"));
            return false;
        }
        if (isBanned(decode(listing.item))) {
            player.sendSystemMessage(Component.literal("§c这个物品已被禁止交易"));
            return false;
        }
        if (!listingsWritable()) {
            // the listing could not be removed from listings.json, so the sale would come back on a restart
            player.sendSystemMessage(Component.literal("§c市场数据文件读取失败，暂时无法购买"
                    + "（管理员需要先修复 multiverse/market/listings.json 并重启服务器）"));
            return false;
        }
        if (player.getServer() == null) {
            return false;
        }
        if (!MoneyService.withdraw(player.getServer(), player.getUUID(), listing.price)) {
            player.sendSystemMessage(Component.literal("§c余额不足，需要 " + MoneyService.format(listing.price)));
            return false;
        }
        double fee = listing.price * feeRate(listing.hours);
        double payout = listing.price - fee;
        MoneyService.deposit(player.getServer(), UUID.fromString(listing.seller), payout);
        ItemStack sold = decode(listing.item);
        if (!player.getInventory().add(sold)) {
            player.drop(sold, false);
        }
        LISTINGS.remove(listing);
        saveAll();
        player.sendSystemMessage(Component.literal("§a已买下 §f").append(displayOf(listing))
                .append(Component.literal("§a，花费 " + MoneyService.format(listing.price))));
        ServerPlayer seller = player.getServer().getPlayerList().getPlayer(UUID.fromString(listing.seller));
        if (seller != null) {
            seller.sendSystemMessage(Component.literal("§a你的 §f").append(displayOf(listing))
                    .append(Component.literal(" §a已售出，到账 " + MoneyService.format(payout)
                            + "（管理费 " + MoneyService.format(fee) + "）")));
        }
        return true;
    }

    // ------------------------------------------------------------------ expiry + mailbox

    /** Anything past its duration moves to the seller's mailbox. Called from the server tick. */
    public static void tick(MinecraftServer server, long now) {
        List<Listing> expired = new ArrayList<>();
        for (Listing listing : LISTINGS) {
            if (listing.hours <= 0) {
                continue;       // permanent listing: it never expires
            }
            if (now - listing.createdAt >= (long) listing.hours * 3_600_000L) {
                expired.add(listing);
            }
        }
        if (expired.isEmpty()) {
            return;
        }
        for (Listing listing : expired) {
            LISTINGS.remove(listing);
            MAILBOX.computeIfAbsent(listing.seller, key -> new ArrayList<>()).add(listing.item);
            ServerPlayer seller = server.getPlayerList().getPlayer(UUID.fromString(listing.seller));
            if (seller != null) {
                seller.sendSystemMessage(Component.literal("§e你的 §f").append(displayOf(listing))
                        .append(Component.literal(" §e到期未售出，已退回邮箱，使用 §f/mail §e领取")));
            }
        }
        saveAll();
    }

    public static int mailboxSize(UUID player) {
        List<String> pending = MAILBOX.get(player.toString());
        return pending == null ? 0 : pending.size();
    }

    /** Hands everything in the player's mailbox over (or drops it when the inventory is full). */
    public static int deliverMail(ServerPlayer player) {
        List<String> pending = MAILBOX.get(player.getUUID().toString());
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (String encoded : new ArrayList<>(pending)) {
            ItemStack stack = decode(encoded);
            if (stack.isEmpty()) {
                // Never throw mail away because it could not be decoded: keep it and shout about it.
                OneKeyToSetAServer.LOGGER.error("[Market] mail entry for {} could not be decoded, keeping it: {}",
                        player.getGameProfile().getName(), encoded);
                continue;
            }
            // Inventory#add consumes the passed stack, so snapshot the amount first.
            int amount = stack.getCount();
            if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                player.drop(stack, false);
            }
            pending.remove(encoded);
            delivered += amount - stack.getCount();
        }
        if (pending.isEmpty()) {
            MAILBOX.remove(player.getUUID().toString());
        }
        saveAll();
        return delivered;
    }

    /** Operator mail: any item to any player, no money involved. */
    public static void sendMail(MinecraftServer server, UUID target, ItemStack stack) {
        MAILBOX.computeIfAbsent(target.toString(), key -> new ArrayList<>()).add(encode(stack));
        saveAll();
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        if (online != null) {
            online.sendSystemMessage(Component.literal("§e你收到了一封新邮件，使用 §f/mail §e领取"));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The stack behind a listing, for menus and messages. */
    public static ItemStack itemOf(Listing listing) {
        return listing == null ? ItemStack.EMPTY : decode(listing.item);
    }

    /**
     * What a player should read for a listing: the translated item name plus the count. The client translates
     * it, so a Chinese client reads "钻石 ×3" while an English one reads "Diamond ×3" - no language files are
     * needed server side. {@code listing.label} stays the plain text form because it feeds {@code /market
     * search}.
     */
    public static Component displayOf(Listing listing) {
        if (listing == null) {
            return Component.literal("（已失效）");
        }
        return ItemNames.displayOr(itemOf(listing), listing.label);
    }

    /** Builds and registers a listing; also used directly by the headless tests. */
    public static Listing createForTest(UUID seller, String name, ItemStack stack, double price, int hours) {
        Listing listing = new Listing();
        listing.id = UUID.randomUUID().toString().substring(0, 8);
        listing.seller = seller.toString();
        listing.sellerName = name;
        listing.item = encode(stack);
        listing.price = price;
        listing.createdAt = System.currentTimeMillis();
        listing.hours = hours;
        listing.label = stack.getHoverName().getString() + " x" + stack.getCount();
        LISTINGS.add(listing);
        saveAll();
        return listing;
    }

    private static String encode(ItemStack stack) {
        return stack.save(new CompoundTag()).toString();
    }

    private static ItemStack decode(String snbt) {
        try {
            return ItemStack.of(TagParser.parseTag(snbt));
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Market] could not decode item {}", snbt, t);
            return ItemStack.EMPTY;
        }
    }
}
