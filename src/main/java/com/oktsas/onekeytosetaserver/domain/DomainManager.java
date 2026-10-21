package com.oktsas.onekeytosetaserver.domain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.economy.MoneyService;
import com.oktsas.onekeytosetaserver.world.MvWorld;
import com.oktsas.onekeytosetaserver.world.WorldPropertyListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Player claims (领地): selection, storage and the permission question the event listeners ask.
 *
 * <p>Since 0.2.0 the command surface is {@code /res} (Residence style) and a claim is a real cuboid that is
 * paid for by volume: {@code length x width x height x pricePerBlock}. A normal player gets
 * {@link DomainConfig#maxClaimsPerPlayer} claim(s) per world, operators (level 4) are unlimited, ignore every
 * claim and may dismantle any of them.</p>
 *
 * <p>Data lives in {@code <server root>/multiverse/res.json}; the old {@code domains.json} of the
 * {@code /domain} era is migrated automatically. Claims are dropped when their world is reset or deleted.</p>
 */
public final class DomainManager {

    /** Tag that marks the golden hoe handed out by {@code /res wand}. */
    public static final String WAND_TAG = "oktsas_domain_wand";

    /** Claim names may not be longer than this. */
    public static final int MAX_NAME_LENGTH = 24;

    private static final List<Domain> DOMAINS = new ArrayList<>();
    private static final Map<UUID, Selection> SELECTIONS = new LinkedHashMap<>();
    private static final Map<UUID, String> LAST_DOMAIN = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST_OUTLINE = new LinkedHashMap<>();
    private static DomainConfig config = new DomainConfig();
    private static Path file;
    private static Path configFile;
    /**
     * True while the store on disk may hold claims that are <b>not</b> in memory (file unreadable, empty at
     * the byte level, or containing entries that had to be skipped). Writing is refused in that state: the
     * file is the only copy of that data, so overwriting it would destroy it. Cleared by a successful
     * {@link #reload()} or a restart that reads the file cleanly.
     */
    private static boolean storeIncomplete;

    /** The two corners a player has marked; both carry a Y so the claim is a cuboid. */
    public record Selection(String dimension, BlockPos first, BlockPos second) {
        public boolean complete() {
            return first != null && second != null;
        }
    }

    private DomainManager() {
    }

    public static DomainConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ storage

    /**
     * Reads {@code multiverse/res-config.json}. A missing file keeps the defaults, a broken one keeps the
     * values that are already in memory instead of resetting the price to zero behind the operator's back.
     *
     * @return true when the file was read
     */
    public static boolean readConfig() {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return false;
        }
        DomainConfig loaded;
        try {
            loaded = new Gson().fromJson(Files.readString(configFile, StandardCharsets.UTF_8),
                    DomainConfig.class);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Res] could not read {} (keeping the current values)",
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
        if (config.pricePerBlock < 0.0D) {
            config.pricePerBlock = 0.0D;
        }
        if (config.maxClaimsPerPlayer < 0) {
            config.maxClaimsPerPlayer = 0;
        }
    }

    public static void loadAll(Path gameDir) {
        Path dir = gameDir.resolve("multiverse");
        file = dir.resolve("res.json");
        configFile = dir.resolve("res-config.json");
        boolean configExisted = Files.isRegularFile(configFile);
        boolean configRead = readConfig();
        clampConfig();

        DOMAINS.clear();
        Path legacy = dir.resolve("domains.json");
        Path source = Files.isRegularFile(file) ? file
                : (Files.isRegularFile(legacy) ? legacy : null);
        storeIncomplete = false;
        if (source != null) {
            List<Domain> parsed = new ArrayList<>();
            int skipped = 0;
            try {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                List<Domain> loaded = new GsonBuilder().create().fromJson(text,
                        new TypeToken<ArrayList<Domain>>() {
                        }.getType());
                if (loaded == null) {
                    // "", whitespace and the literal "null" all parse to null without throwing - that is a
                    // truncated (or hand-emptied) file, not an empty store, so never treat it as one
                    storeIncomplete = true;
                    OneKeyToSetAServer.LOGGER.error("[Res] {} holds no JSON value at all (empty or truncated?). "
                            + "It is left untouched and no claim is loaded; restore it and run "
                            + "/res admin reload.", source);
                } else {
                    for (Domain domain : loaded) {
                        if (usable(domain)) {
                            parsed.add(domain);
                        } else {
                            skipped++;
                        }
                    }
                }
            } catch (Throwable t) {
                storeIncomplete = true;
                OneKeyToSetAServer.LOGGER.error("[Res] could not read {} - it will NOT be overwritten. "
                        + "Fix (or restore) the file, then use /res admin reload.", source, t);
            }
            DOMAINS.addAll(parsed);
            if (skipped > 0) {
                storeIncomplete = true;
                OneKeyToSetAServer.LOGGER.error("[Res] {} entry(ies) in {} are incomplete (id / dimension / "
                        + "owner missing) and are not in memory. The file stays locked until they are fixed, so "
                        + "they cannot be lost.", skipped, source);
            }
        }
        if (source != null && source.equals(legacy)) {
            save();
            OneKeyToSetAServer.LOGGER.info("[Res] migrated domains.json -> res.json");
        }
        if (!configExisted || configRead) {
            saveConfig();       // materialise / normalise the file the operator edits
        } else {
            OneKeyToSetAServer.LOGGER.error("[Res] {} could not be read; keeping the current defaults and "
                    + "leaving the file untouched", configFile);
        }
        OneKeyToSetAServer.LOGGER.info("[Res] {} claim(s) loaded, price {} per block, max {} per player{}",
                DOMAINS.size(), config.pricePerBlock, config.maxClaimsPerPlayer,
                storeIncomplete ? " [WARNING: store incomplete, writes blocked]" : "");
    }

    /** True when the claim store may be written; false while the file on disk holds data memory does not. */
    public static boolean writable() {
        return !storeIncomplete;
    }

    /**
     * Suffix for a success message whose change could not be stored. Empty in the normal case; while the store
     * is locked the change lives in memory only, and the player has to be told instead of being lied to.
     */
    public static String writeWarning() {
        return storeIncomplete
                ? " §c（注意：领地存档被锁定，这次改动只在内存中，重启后会还原；"
                + "请管理员修复 multiverse/res.json 后执行 /res admin reload）"
                : "";
    }

    /** Writes the claim store. Refused (and logged) while {@link #writable()} is false. */
    public static boolean save() {
        if (file == null) {
            return false;
        }
        if (storeIncomplete) {
            OneKeyToSetAServer.LOGGER.error("[Res] refusing to write {}: it still holds claims that are not in "
                    + "memory. Fix (or restore) the file, then run /res admin reload.", file);
            return false;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(DOMAINS),
                    StandardCharsets.UTF_8);
            return true;
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Res] could not write {}", file, t);
            return false;
        }
    }

    /**
     * Outcome of {@link #reload()}; {@code skipped} counts entries the file had but that could not be used.
     *
     * @param ok       true when the file was read and memory now holds what the file says
     * @param previous claims in memory before the reload
     * @param loaded   claims now in memory
     * @param skipped  entries dropped because they were incomplete (id / dimension / owner missing)
     * @param error    why the read failed, or null
     */
    public record ReloadResult(boolean ok, int previous, int loaded, int skipped, String error) {
    }

    /**
     * Re-reads {@code multiverse/res.json} (and {@code res-config.json}) into memory - the real thing behind
     * {@code /res admin reload}.
     *
     * <p>Safety rules, because a claim store that silently empties itself costs players their land:</p>
     * <ul>
     *   <li>a missing, empty or unreadable file leaves the current claims <b>untouched</b> and reports the
     *       error;</li>
     *   <li>a file whose entries are all usable replaces memory and is written back in the canonical format;</li>
     *   <li>a file with incomplete entries loads what it can but stays <b>locked against writes</b> until the
     *       operator has fixed it - otherwise the next save would drop those entries for good;</li>
     *   <li>the config is only replaced when it parses.</li>
     * </ul>
     */
    public static ReloadResult reload() {
        return reloadFrom(file);
    }

    /** Test seam: reload from an explicit path; never writes back unless it is the live store. */
    public static ReloadResult reloadFrom(Path source) {
        boolean live = file != null && file.equals(source);
        if (source == null || !Files.isRegularFile(source)) {
            if (live) {
                storeIncomplete = true;     // the operator deleted it by accident: do not write an empty store
            }
            return new ReloadResult(false, DOMAINS.size(), DOMAINS.size(), 0,
                    "文件不存在（误删就从备份恢复；想清空请停服重启，届时才会重建空库）");
        }
        List<Domain> parsed = new ArrayList<>();
        int skipped = 0;
        try {
            List<Domain> loaded = new GsonBuilder().create().fromJson(
                    Files.readString(source, StandardCharsets.UTF_8),
                    new TypeToken<ArrayList<Domain>>() {
                    }.getType());
            if (loaded == null) {
                // empty / whitespace / "null": nothing to load, and definitely not a reason to wipe memory
                if (live) {
                    storeIncomplete = true;
                }
                OneKeyToSetAServer.LOGGER.error("[Res] reload of {} found no JSON value (empty or truncated?) "
                        + "- keeping {} claim(s)", source, DOMAINS.size());
                return new ReloadResult(false, DOMAINS.size(), DOMAINS.size(), 0,
                        "文件里没有任何内容（空文件或被截断），已保留当前领地");
            }
            for (Domain domain : loaded) {
                if (usable(domain)) {
                    parsed.add(domain);
                } else {
                    skipped++;
                }
            }
        } catch (Throwable t) {
            if (live) {
                storeIncomplete = true;
            }
            OneKeyToSetAServer.LOGGER.error("[Res] reload of {} failed (keeping {} claim(s))",
                    source, DOMAINS.size(), t);
            return new ReloadResult(false, DOMAINS.size(), DOMAINS.size(), 0, shortError(t));
        }
        int previous = DOMAINS.size();
        DOMAINS.clear();
        DOMAINS.addAll(parsed);
        LAST_DOMAIN.clear();
        if (live) {
            readConfig();       // price/limits may have been hand-edited too
            // Skipped entries were in the file but are not in memory. Keep the store locked so the next save
            // cannot drop them, and say what has to happen next.
            storeIncomplete = skipped > 0;
            if (skipped == 0) {
                save();         // normalise
            } else {
                OneKeyToSetAServer.LOGGER.error("[Res] {} entr(ies) in {} are incomplete and were NOT loaded. "
                        + "Writes stay blocked so the file keeps them; fix those entries and reload again.",
                        skipped, source);
            }
        }
        OneKeyToSetAServer.LOGGER.info("[Res] reload {}: {} -> {} claim(s), skipped {}",
                source.getFileName(), previous, DOMAINS.size(), skipped);
        return new ReloadResult(true, previous, DOMAINS.size(), skipped, null);
    }

    /** The same completeness test {@link #loadAll(Path)} applies, minus the null-pointer traps. */
    private static boolean usable(Domain domain) {
        return domain != null && domain.id != null && !domain.id.isBlank()
                && domain.dimension != null && !domain.dimension.isBlank()
                && domain.owner != null && !domain.owner.isBlank()
                && domain.name != null;
    }

    private static String shortError(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            message = t.getClass().getSimpleName();
        }
        return message.length() > 80 ? message.substring(0, 80) + "…" : message;
    }

    public static void saveConfig() {
        if (configFile == null) {
            return;
        }
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, new GsonBuilder().setPrettyPrinting().create().toJson(config),
                    StandardCharsets.UTF_8);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Res] could not write {}", configFile, t);
        }
    }

    public static void setPricePerBlock(double price) {
        config.pricePerBlock = Math.max(0.0D, round(price));
        saveConfig();
    }

    public static List<Domain> all() {
        return new ArrayList<>(DOMAINS);
    }

    public static Domain byId(String id) {
        for (Domain domain : DOMAINS) {
            if (domain.id.equalsIgnoreCase(id)) {
                return domain;
            }
        }
        return null;
    }

    public static Domain byName(String dimension, String name) {
        for (Domain domain : DOMAINS) {
            if (domain.inWorld(dimension) && domain.name.equalsIgnoreCase(name)) {
                return domain;
            }
        }
        return null;
    }

    /** Resolves a claim by name in the player's own world first, then anywhere (for operators). */
    public static Domain resolve(ServerPlayer player, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String dimension = dimensionOf(player.level());
        Domain local = byName(dimension, name);
        if (local != null) {
            return local;
        }
        Domain global = null;
        for (Domain domain : DOMAINS) {
            if (domain.name.equalsIgnoreCase(name)) {
                if (global != null) {
                    return null;      // ambiguous across worlds
                }
                global = domain;
            }
        }
        return global;
    }

    public static Domain ownedBy(String dimension, UUID owner) {
        for (Domain domain : DOMAINS) {
            if (domain.inWorld(dimension) && domain.isOwner(owner)) {
                return domain;
            }
        }
        return null;
    }

    public static List<Domain> ownedAll(String dimension, UUID owner) {
        List<Domain> result = new ArrayList<>();
        for (Domain domain : DOMAINS) {
            if (domain.inWorld(dimension) && domain.isOwner(owner)) {
                result.add(domain);
            }
        }
        return result;
    }

    /** The claim containing a position, or null. */
    public static Domain at(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        String dimension = level.dimension().location().toString();
        for (Domain domain : DOMAINS) {
            if (domain.inWorld(dimension) && domain.contains(pos.getX(), pos.getY(), pos.getZ())) {
                return domain;
            }
        }
        return null;
    }

    public static List<Domain> inWorld(String dimension) {
        List<Domain> result = new ArrayList<>();
        for (Domain domain : DOMAINS) {
            if (domain.inWorld(dimension)) {
                result.add(domain);
            }
        }
        return result;
    }

    /** Claims dropped from the store; also forgets who was standing inside them. */
    public static int clearWorld(String dimension) {
        List<Domain> removed = new ArrayList<>();
        for (Domain domain : DOMAINS) {
            if (domain.inWorld(dimension)) {
                removed.add(domain);
            }
        }
        DOMAINS.removeAll(removed);
        LAST_DOMAIN.clear();
        SELECTIONS.entrySet().removeIf(e -> dimension.equals(e.getValue().dimension()));
        if (!removed.isEmpty()) {
            if (!save()) {
                OneKeyToSetAServer.LOGGER.warn("[Res] dropped {} claim(s) of {} in memory only - the store is "
                        + "locked, so they are still in {}", removed.size(), dimension, file);
            }
            OneKeyToSetAServer.LOGGER.info("[Res] cleared {} claim(s) of {}", removed.size(), dimension);
        }
        return removed.size();
    }

    public static boolean delete(Domain domain) {
        if (storeIncomplete) {
            OneKeyToSetAServer.LOGGER.error("[Res] refusing to drop claim '{}': the store is locked ({}), so the "
                    + "removal would come back with the next restart", domain == null ? "?" : domain.name, file);
            return false;
        }
        boolean removed = DOMAINS.remove(domain);
        if (removed) {
            save();
        }
        return removed;
    }

    // ------------------------------------------------------------------ selection

    public static ItemStack wand() {
        ItemStack stack = new ItemStack(Items.GOLDEN_HOE);
        stack.setHoverName(Component.literal("§b领地法杖"));
        stack.getOrCreateTag().putBoolean(WAND_TAG, true);
        net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
        for (String line : new String[]{"§7左键点方块 = 第一个点", "§7右键点方块 = 第二个点",
                "§7然后 /res create <名字>（按体积收费）"}) {
            lore.add(net.minecraft.nbt.StringTag.valueOf(
                    Component.Serializer.toJson(Component.literal(line))));
        }
        stack.getOrCreateTagElement("display").put("Lore", lore);
        return stack;
    }

    public static boolean isWand(ItemStack stack) {
        return stack != null && stack.is(Items.GOLDEN_HOE) && stack.hasTag()
                && stack.getTag().getBoolean(WAND_TAG);
    }

    public static Selection selectionOf(UUID player) {
        return SELECTIONS.get(player);
    }

    /** Seam for the headless self-test: a fake player has no usable crosshair to look at. */
    public static void setSelectionForTest(UUID player, String dimension, BlockPos first, BlockPos second) {
        SELECTIONS.put(player, new Selection(dimension, first, second));
    }

    public static void clearSelection(UUID player) {
        SELECTIONS.remove(player);
        LAST_OUTLINE.remove(player);
    }

    /** Marks a corner at the block the player is looking at (falling back to their feet). */
    public static BlockPos targetedBlock(Player player) {
        HitResult hit = player.pick(100.0D, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return player.blockPosition().below();
    }

    public static Selection mark(ServerPlayer player, boolean second) {
        BlockPos pos = targetedBlock(player);
        String dimension = player.level().dimension().location().toString();
        Selection previous = SELECTIONS.get(player.getUUID());
        Selection updated = second
                ? new Selection(dimension, previous != null && dimension.equals(previous.dimension())
                        ? previous.first() : null, pos)
                : new Selection(dimension, pos, previous != null && dimension.equals(previous.dimension())
                        ? previous.second() : null);
        SELECTIONS.put(player.getUUID(), updated);
        return updated;
    }

    /**
     * Grows or shrinks the marked selection vertically without clicking again.
     *
     * @return a player facing message describing the new box (or why it could not be changed)
     */
    public static String adjustHeight(ServerPlayer player, boolean up, int amount) {
        Selection selection = SELECTIONS.get(player.getUUID());
        if (selection == null || !selection.complete()) {
            return "§c执行此命令前需要先选中两个选取点!（/res wand 拿法杖，左键=点1，右键=点2）";
        }
        BlockPos first = selection.first();
        BlockPos second = selection.second();
        int minX = Math.min(first.getX(), second.getX());
        int maxX = Math.max(first.getX(), second.getX());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxZ = Math.max(first.getZ(), second.getZ());
        int minY = Math.min(first.getY(), second.getY());
        int maxY = Math.max(first.getY(), second.getY());
        int bottom = player.level().getMinBuildHeight();
        int top = player.level().getMaxBuildHeight() - 1;
        int step = Math.max(1, amount);
        if (up) {
            maxY = Math.min(top, maxY + step);
        } else {
            minY = Math.max(bottom, minY - step);
        }
        SELECTIONS.put(player.getUUID(), new Selection(selection.dimension(),
                new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)));
        return "§a领地高度现在是 §fY " + minY + " ~ " + maxY + "§a，选区 " + preview(selectionOf(player.getUUID()));
    }

    // ------------------------------------------------------------------ creation

    /** Why a claim could not be created, plus what it would cost. */
    public record Result(Domain domain, String error, double cost, long volume) {
        public boolean ok() {
            return domain != null;
        }

        static Result fail(String error) {
            return new Result(null, error, 0.0D, 0L);
        }
    }

    /** Volume and price of a marked selection, e.g. {@code 64×1×64 = 4096 格，费用 6144.00 元}. */
    public static String preview(Selection selection) {
        if (selection == null) {
            return "还没有选区";
        }
        int x = 1;
        int y = 1;
        int z = 1;
        if (selection.first() != null && selection.second() != null) {
            x = Math.abs(selection.first().getX() - selection.second().getX()) + 1;
            y = Math.abs(selection.first().getY() - selection.second().getY()) + 1;
            z = Math.abs(selection.first().getZ() - selection.second().getZ()) + 1;
        }
        long volume = (long) x * y * z;
        return x + "×" + y + "×" + z + " = " + volume + " 格，费用 " + MoneyService.format(costOf(volume));
    }

    public static double costOf(long volume) {
        return round(volume * config.pricePerBlock);
    }

    public static Result create(ServerPlayer player, String rawName) {
        String dimension = player.level().dimension().location().toString();
        Selection selection = SELECTIONS.get(player.getUUID());
        if (selection == null || !selection.complete()) {
            return Result.fail("§c执行此命令前需要先选中两个选取点!（/res wand 拿法杖，左键=点1，右键=点2）");
        }
        if (!selection.dimension().equals(dimension)) {
            return Result.fail("§c选区在别的世界，请回到那个世界或重新标记");
        }
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            return Result.fail("§c请输入领地名字");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return Result.fail("§c领地名字最多 " + MAX_NAME_LENGTH + " 个字");
        }
        if (name.chars().anyMatch(Character::isWhitespace)) {
            return Result.fail("§c领地名字里不能有空格");
        }
        if (byName(dimension, name) != null) {
            return Result.fail("§c此区域名已存在。");
        }
        boolean op = player.hasPermissions(config.opLevel);
        if (!op && config.maxClaimsPerPlayer > 0
                && ownedAll(dimension, player.getUUID()).size() >= config.maxClaimsPerPlayer) {
            return Result.fail("§c你拥有的领地数量达到了上限（这个世界 " + config.maxClaimsPerPlayer + " 个）。");
        }

        BlockPos first = selection.first();
        BlockPos second = selection.second();
        Domain candidate = new Domain();
        candidate.owner = player.getUUID().toString();
        candidate.ownerName = player.getGameProfile().getName();
        candidate.dimension = dimension;
        candidate.world = worldFolderOf(player.level());
        candidate.name = name;
        candidate.minX = Math.min(first.getX(), second.getX());
        candidate.maxX = Math.max(first.getX(), second.getX());
        candidate.minZ = Math.min(first.getZ(), second.getZ());
        candidate.maxZ = Math.max(first.getZ(), second.getZ());
        candidate.minY = Math.min(first.getY(), second.getY());
        candidate.maxY = Math.max(first.getY(), second.getY());
        candidate.members.put(candidate.owner, candidate.ownerName);

        for (Domain other : DOMAINS) {
            if (!other.inWorld(dimension)) {
                continue;
            }
            if (other.overlaps(candidate) && !(op && other.isOwner(player.getUUID()))) {
                return Result.fail("§c区域与领地 §6" + other.name + " §c冲突。");
            }
        }

        long volume = candidate.volume();
        double cost = costOf(volume);
        // Money is only taken when the claim can actually be stored: while res.json is locked (unreadable or
        // holding entries that could not be parsed) a save is refused, and charging for a claim that vanishes
        // with the next restart is exactly what 0.2.4 did.
        if (!writable()) {
            return new Result(null, "§c领地存档当前不可写入（res.json 读取失败或含无法解析的条目），"
                    + "已取消创建，§a没有扣费§c。请管理员修复后执行 §f/res admin reload", cost, volume);
        }
        var server = player.getServer();
        if (server != null && cost > 0.0D) {
            double balance = MoneyService.get(server, player.getUUID());
            if (balance < cost) {
                return new Result(null, "§c你没有足够的金钱。（需要 " + MoneyService.format(cost)
                        + "，你有 " + MoneyService.format(balance) + "）", cost, volume);
            }
            if (!MoneyService.withdraw(server, player.getUUID(), cost)) {
                return new Result(null, "§c扣费失败，领地未创建", cost, volume);
            }
        }

        DOMAINS.add(candidate);
        save();
        SELECTIONS.remove(player.getUUID());
        LAST_OUTLINE.remove(player.getUUID());
        return new Result(candidate, null, cost, volume);
    }

    /** The managing world folder of a level, or the dimension path as a fallback. */
    public static String worldFolderOf(Level level) {
        MvWorld world = WorldPropertyListener.worldOf(level);
        if (world != null) {
            return world.folder;
        }
        return level.dimension().location().getPath();
    }

    // ------------------------------------------------------------------ permission

    /** Someone with the configured op level ignores every claim. */
    public static boolean bypasses(Player player) {
        return opTestBypass || (player != null && player.hasPermissions(config.opLevel));
    }

    /** Seam for the headless self-test, which has no real operator to log in with. */
    private static boolean opTestBypass = false;

    public static void setOpTestBypass(boolean value) {
        opTestBypass = value;
    }

    /** Op level that may run {@code /res admin}; a separate, higher bar than "may manage a claim". */
    public static boolean mayUseAdmin(Player player) {
        return (player != null && player.hasPermissions(4)) || opAdminTestBypass;
    }

    private static boolean opAdminTestBypass = false;

    public static void setOpAdminTestBypass(boolean value) {
        opAdminTestBypass = value;
    }

    /**
     * The single question every listener asks: may this actor do {@code flag} at {@code pos}?
     * A null actor means "not a player" (contraption, explosion, dispenser) and is denied unless the
     * claim allows it - that is what keeps Create drills and TNT out of a claim.
     */
    public static boolean allowed(Player player, Level level, BlockPos pos, Domain.Flag flag) {
        Domain domain = at(level, pos);
        if (domain == null) {
            return true;
        }
        if (player == null) {
            return !domain.denies(flag);
        }
        if (bypasses(player)) {
            return true;
        }
        if (domain.isMember(player.getUUID())) {
            return true;
        }
        return !domain.deniesFor(player.getUUID(), flag);
    }

    /** Who may manage a claim: the owner, or any operator. */
    public static boolean mayManage(Player player, Domain domain) {
        return player != null && domain != null && (bypasses(player) || domain.isOwner(player.getUUID()));
    }

    /** Who may delete a claim that is not theirs: op 4 always, everybody else only when configured. */
    public static boolean mayDeleteOthers(Player player) {
        return mayUseAdmin(player) || (config.allowPlayerDeleteOthers && player != null);
    }

    // ------------------------------------------------------------------ messages

    /** Sends the "entering/leaving a claim" notice; called from the tick handler. */
    public static void announceEntry(ServerPlayer player) {
        Domain current = at(player.level(), player.blockPosition());
        String previous = LAST_DOMAIN.get(player.getUUID());
        String now = current == null ? null : current.id;
        if (java.util.Objects.equals(previous, now)) {
            return;
        }
        if (now == null) {
            LAST_DOMAIN.remove(player.getUUID());
            if (previous != null) {
                Domain left = byId(previous);
                if (left != null) {
                    player.displayClientMessage(Component.literal("§7你正在离开 §f" + left.name + "§7 领地"), true);
                }
            }
            return;
        }
        LAST_DOMAIN.put(player.getUUID(), now);
        player.displayClientMessage(Component.literal("§e你正在进入 §f" + current.ownerName + "§e 的领地 §7("
                + current.name + "§7)"), true);
    }

    /**
     * Outlines the current selection with the "villager trade" green particles. Only the 12 edges are drawn
     * and anything further away than {@link DomainConfig#outlineRange} is skipped, so a huge selection stays
     * cheap. With a single corner marked only that block is highlighted.
     */
    public static void outline(ServerPlayer player, long now) {
        Selection selection = SELECTIONS.get(player.getUUID());
        if (selection == null || selection.first() == null) {
            return;
        }
        Long last = LAST_OUTLINE.get(player.getUUID());
        int interval = Math.max(100, config.outlineIntervalMs);
        if (last != null && now - last < interval) {
            return;
        }
        LAST_OUTLINE.put(player.getUUID(), now);
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!selection.dimension().equals(dimensionOf(level))) {
            return;
        }
        BlockPos first = selection.first();
        BlockPos second = selection.second() == null ? first : selection.second();
        double x1 = Math.min(first.getX(), second.getX());
        double x2 = Math.max(first.getX(), second.getX()) + 1.0D;
        double y1 = Math.min(first.getY(), second.getY());
        double y2 = Math.max(first.getY(), second.getY()) + 1.0D;
        double z1 = Math.min(first.getZ(), second.getZ());
        double z2 = Math.max(first.getZ(), second.getZ()) + 1.0D;

        double perimeter = 4.0D * ((x2 - x1) + (z2 - z1)) + 4.0D * (y2 - y1);
        double step = Math.max(1.0D, perimeter / 320.0D);
        int[] budget = {640};
        double[][] corners = {
                {x1, y1, z1}, {x2, y1, z1}, {x1, y1, z2}, {x2, y1, z2},
                {x1, y2, z1}, {x2, y2, z1}, {x1, y2, z2}, {x2, y2, z2}};
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] edge : edges) {
            double[] a = corners[edge[0]];
            double[] b = corners[edge[1]];
            drawEdge(level, player, a, b, step, budget);
            if (budget[0] <= 0) {
                break;
            }
        }
    }

    private static void drawEdge(ServerLevel level, ServerPlayer player, double[] a, double[] b,
                                 double step, int[] budget) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double dz = b[2] - a[2];
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) Math.ceil(length / step);
        double range = Math.max(8, config.outlineRange);
        for (int i = 0; i <= steps && budget[0] > 0; i++) {
            double t = steps == 0 ? 0.0D : (double) i / (double) steps;
            double x = a[0] + dx * t;
            double y = a[1] + dy * t;
            double z = a[2] + dz * t;
            if (player.distanceToSqr(x, y, z) > range * range) {
                continue;
            }
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            budget[0]--;
        }
    }

    /** Short multi-line description used by {@code /res info}. */
    public static String describe(Domain domain) {
        return "§e§l领地 " + domain.name + "§r"
                + "\n §7主人: §f" + domain.ownerName
                + "\n §7世界: §f" + domain.world + " §8(" + domain.dimension + ")"
                + "\n §7范围: §fX " + domain.minX + "~" + domain.maxX
                + " §7Y §f" + domain.minY + "~" + domain.maxY
                + " §7Z §f" + domain.minZ + "~" + domain.maxZ
                + "\n §7体积: §f" + domain.sizeX() + "×" + domain.sizeY() + "×" + domain.sizeZ()
                + " §7= §f" + domain.volume() + " 格 §7(费用 " + MoneyService.format(costOf(domain.volume())) + ")"
                + "\n §7保护: " + flagsLine(domain)
                + "\n §7成员: §f" + String.join(", ", domain.members.values());
    }

    /** One line with the seven flags of a claim, as shown by {@code /res info}. */
    public static String flagsLine(Domain domain) {
        StringBuilder sb = new StringBuilder();
        for (Domain.Flag flag : Domain.Flag.values()) {
            if (sb.length() > 0) {
                sb.append(" §8| ");
            }
            sb.append("§7").append(flag.title).append("=").append(on(domain.denies(flag)));
        }
        return sb.toString();
    }

    private static String on(boolean value) {
        return value ? "§c禁止" : "§a允许";
    }

    /** {@code on}/{@code off} as players type it. */
    public static Boolean parseToggle(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "t", "yes", "y", "开", "禁止" -> Boolean.TRUE;
            case "off", "false", "f", "no", "n", "关", "允许" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Drops cached per-player state; used when the server stops. */
    public static void clearCache() {
        SELECTIONS.clear();
        LAST_DOMAIN.clear();
        LAST_OUTLINE.clear();
    }

    // ------------------------------------------------------------------ helpers

    public static String dimensionOf(Level level) {
        return level == null ? "" : level.dimension().location().toString();
    }

    public static ServerLevel levelOf(ServerPlayer player) {
        return player.serverLevel();
    }

    public static Domain parseFlagTarget(String dimension, String name) {
        return byName(dimension, name);
    }

    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Keeps the money maths in the same two decimal shape the economy uses. */
    public static double round(double value) {
        return java.math.BigDecimal.valueOf(Math.max(0.0D, value))
                .setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
