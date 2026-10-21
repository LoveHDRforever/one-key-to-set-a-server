package com.oktsas.onekeytosetaserver.inventory;

import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.world.MvWorld;
import com.oktsas.onekeytosetaserver.world.MvWorldManager;
import com.oktsas.onekeytosetaserver.world.WorldPropertyListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-world player inventories (Multiverse-Inventories equivalent, optional per world).
 *
 * <p>Isolated worlds keep their own pool of items in {@code <world folder>/multiverse/inventories/<uuid>.nbt};
 * worlds without the option share one "global" pool in {@code <server root>/multiverse/global_inventories/}.
 * Modelled as pools, moving between two worlds simply saves the current pool, clears the player and loads the
 * destination pool - which also means the round trip shared -> isolated -> shared gives the original items back.</p>
 *
 * <p>The stored data covers the main inventory, the armor slots and both hands, plus the Curios accessory
 * slots when Curios is installed (they share the same snapshot; {@link CuriosBridge} is only loaded in that
 * case).</p>
 *
 * <h2>Rules this class follows, after the 0.2.3 accessory loss</h2>
 *
 * <ol>
 *   <li><b>Taking a snapshot never takes anything away.</b> {@link #save(ServerPlayer, Path)} only records;
 *       emptying the player is done by {@link #clear(ServerPlayer)} and only by callers that have the
 *       destination in hand. 0.2.4 let the snapshot call Curios' {@code saveInventory(true)}, so merely
 *       logging in for the first time into a managed world stripped the accessories - and the next snapshot
 *       then wrote that empty state over the stored one.</li>
 *   <li><b>Nothing is cleared before the source has been read and parsed.</b> Both the login path and the
 *       world-change path read the target file into memory first; an unreadable file means the player keeps
 *       whatever they are wearing and the file stays untouched for the operator.</li>
 *   <li><b>Every stack is accounted for.</b> Whatever cannot be put back (slot type gone, slot occupied,
 *       index past the end) is mailed to the player instead of being dropped, and the mismatch is logged at
 *       ERROR level.</li>
 *   <li><b>The file is replaced atomically, with one previous generation kept</b> as
 *       {@code <uuid>.nbt.bak}.</li>
 * </ol>
 */
public final class PlayerInventoryStore {

    public static final String GLOBAL_DIR = "global_inventories";

    /** Where the pool directory of an isolated world lives, relative to the world folder. */
    private static final String POOL_DIR = "inventories";
    /** Compound keys inside a pool file. */
    private static final String KEY_INVENTORY = "inventory";
    private static final String KEY_CURIOS = "curios";
    private static final String SUFFIX = ".nbt";
    private static final String BACKUP_SUFFIX = ".bak";

    private PlayerInventoryStore() {
    }

    /** The pool a player's items belong to while being in this level. */
    public static Path poolFor(ServerLevel level) {
        MvWorld world = WorldPropertyListener.worldOf(level);
        if (world != null && world.separateInventory) {
            return MvWorldManager.folderOf(world).resolve(MvWorld.CONFIG_DIR).resolve(POOL_DIR);
        }
        return MvWorldManager.gameDir().resolve(MvWorld.CONFIG_DIR).resolve(GLOBAL_DIR);
    }

    public static Path snapshotFile(Path pool, UUID playerId) {
        return pool.resolve(playerId + SUFFIX);
    }

    /**
     * Records the player's inventory in a pool. The player keeps everything they wear - this is a copy.
     *
     * @return true when the file was written
     */
    public static boolean save(ServerPlayer player, Path pool) {
        try {
            String name = player.getGameProfile().getName();
            Path file = snapshotFile(pool, player.getUUID());
            CompoundTag root = new CompoundTag();
            root.put(KEY_INVENTORY, player.getInventory().save(new ListTag()));
            int accessories = 0;
            ListTag curios = curiosLoaded() ? CuriosBridge.snapshot(player) : null;
            if (curios != null && !curios.isEmpty()) {
                root.put(KEY_CURIOS, curios);
                accessories = countCurioStacks(curios);
            } else {
                // Nothing readable to write: either Curios is not installed on this server or this player has
                // no accessory capability at all. Writing the snapshot now would drop the accessories the
                // stored file still holds, so carry that section over untouched.
                ListTag kept = storedCurios(file);
                if (kept != null) {
                    root.put(KEY_CURIOS, kept);
                    accessories = countCurioStacks(kept);
                    OneKeyToSetAServer.LOGGER.warn("[MV] no accessory slots readable for {} - carrying the {} "
                            + "accessory item(s) of {} over instead of dropping them", name, accessories, file);
                }
            }
            boolean written = write(file, root);
            if (written) {
                OneKeyToSetAServer.LOGGER.info("[MV] stored inventory of {} in {} ({} accessory item(s))",
                        name, file, accessories);
            }
            return written;
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not save inventory of {} to {}",
                    player.getGameProfile().getName(), pool, t);
            return false;
        }
    }

    /** The accessory section an existing pool file holds, or null. Silent on purpose - the caller reports. */
    private static ListTag storedCurios(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            CompoundTag old = NbtIo.readCompressed(in);
            if (old == null) {
                return null;
            }
            ListTag kept = old.getList(KEY_CURIOS, CompoundTag.TAG_COMPOUND);
            return kept.isEmpty() ? null : kept;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when Curios is present; keeps the bridge class from ever being resolved on a plain server. */
    public static boolean curiosLoaded() {
        try {
            return net.minecraftforge.fml.ModList.get().isLoaded("curios");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Applies the stored pool to the player, but only after the file has been read completely - a broken file
     * leaves the player and the file alone.
     *
     * @return true when a snapshot existed and was applied; false means "first visit, inventory stays empty"
     */
    public static boolean load(ServerPlayer player, Path pool) {
        Path file = snapshotFile(pool, player.getUUID());
        if (!Files.isRegularFile(file)) {
            return false;
        }
        CompoundTag root = read(file, player.getGameProfile().getName());
        if (root == null) {
            return false;
        }
        apply(player, root, file);
        return true;
    }

    /** Clears the inventory and the accessory slots; the caller is responsible for what happens to them. */
    public static void clear(ServerPlayer player) {
        player.getInventory().clearContent();
        if (curiosLoaded()) {
            CuriosBridge.clear(player);
        }
        // let the client drop whatever it still shows as held / crafted
        try {
            player.inventoryMenu.broadcastChanges();
        } catch (Throwable ignored) {
            // nothing to sync (fake players, headless tests)
        }
        try {
            player.containerMenu.broadcastChanges();
        } catch (Throwable ignored) {
            // ditto
        }
    }

    /** Moves a player between two inventory pools. No-op when both levels share the same pool. */
    public static void swap(ServerPlayer player, ServerLevel from, ServerLevel to) {
        if (from == null || to == null) {
            return;
        }
        try {
            Path fromPool = poolFor(from);
            Path toPool = poolFor(to);
            if (fromPool.equals(toPool)) {
                return;
            }
            String name = player.getGameProfile().getName();
            Path target = snapshotFile(toPool, player.getUUID());
            // read the destination before touching the player: if it cannot be read, the current items stay
            // where they are instead of being emptied into a pool whose content is unknown
            CompoundTag destination = Files.isRegularFile(target) ? read(target, name) : null;
            save(player, fromPool);
            if (Files.isRegularFile(target) && destination == null) {
                OneKeyToSetAServer.LOGGER.error("[MV] destination pool {} is unreadable - {} keeps the "
                        + "inventory of {} for now; fix the file and let them change world again",
                        target, name, from.dimension().location());
                player.sendSystemMessage(Component.literal(
                        "§c这个世界保存的背包读取失败，管理员需要先修好文件；你的物品暂时留在身上"));
                return;
            }
            clear(player);
            if (destination != null) {
                apply(player, destination, target);
            }
            OneKeyToSetAServer.LOGGER.info("[MV] inventory swap for {}: {} -> {} ({})",
                    name, from.dimension().location(), to.dimension().location(),
                    destination != null ? "restored" : "empty (first visit)");
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] inventory swap failed for {}",
                    player.getGameProfile().getName(), t);
        }
    }

    /**
     * On login the stored pool is authoritative (it is written on every world change and logout); the current
     * inventory is only adopted when no snapshot exists yet, which is what a first run looks like.
     *
     * <p>Adopting a snapshot means "record what the player arrived with" and nothing else - the player keeps
     * their items, exactly like the vanilla inventory always did. Emptying them is reserved for
     * {@link #swap(ServerPlayer, ServerLevel, ServerLevel)}, where the destination is known to be good.</p>
     */
    public static void adoptOrLoad(ServerPlayer player) {
        try {
            ServerLevel level = player.serverLevel();
            Path pool = poolFor(level);
            Path file = snapshotFile(pool, player.getUUID());
            if (!Files.isRegularFile(file)) {
                save(player, pool);
                return;
            }
            String name = player.getGameProfile().getName();
            CompoundTag root = read(file, name);
            if (root == null) {
                OneKeyToSetAServer.LOGGER.error("[MV] stored inventory {} of {} could not be read - keeping the "
                        + "inventory they logged in with and leaving the file untouched", file, name);
                player.sendSystemMessage(Component.literal(
                        "§c你在这个世界保存的背包读取失败，已保留你登录时的物品；请联系管理员处理"));
                return;
            }
            clear(player);
            apply(player, root, file);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] inventory adopt failed for {}",
                    player.getGameProfile().getName(), t);
        }
    }

    // ------------------------------------------------------------------ reading / applying

    /** Reads a pool file completely; {@code null} means "do not touch anything" (missing or broken). */
    private static CompoundTag read(Path file, String playerName) {
        try (InputStream in = Files.newInputStream(file)) {
            CompoundTag root = NbtIo.readCompressed(in);
            if (root == null || !root.contains(KEY_INVENTORY, CompoundTag.TAG_LIST)) {
                OneKeyToSetAServer.LOGGER.error("[MV] {} is not a pool snapshot (no inventory list) - leaving it "
                        + "and the inventory of {} alone", file, playerName);
                return null;
            }
            return root;
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not read {} for {} - it is left untouched, the player "
                    + "keeps the inventory they have", file, playerName, t);
            return null;
        }
    }

    /** Puts a parsed snapshot on the player and reports anything that did not fit. */
    private static void apply(ServerPlayer player, CompoundTag root, Path file) {
        String name = player.getGameProfile().getName();
        Inventory inventory = player.getInventory();
        ListTag stored = root.getList(KEY_INVENTORY, CompoundTag.TAG_COMPOUND);
        inventory.clearContent();
        inventory.load(stored);
        int items = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).isEmpty()) {
                items++;
            }
        }
        int accessories = 0;
        int restoredAccessories = 0;
        if (curiosLoaded()) {
            ListTag curios = root.getList(KEY_CURIOS, CompoundTag.TAG_COMPOUND);
            accessories = CuriosBridge.countItems(curios);
            List<ItemStack> leftOver = CuriosBridge.apply(player, curios);
            restoredAccessories = CuriosBridge.items(player).size();
            if (!leftOver.isEmpty()) {
                // never delete: hand them to the player through the mailbox and say so loudly
                OneKeyToSetAServer.LOGGER.error("[MV] {} of {} accessory stack(s) of {} from {} could not be "
                                + "equipped ({} placed) - mailing the rest instead",
                        leftOver.size(), accessories, name, file, restoredAccessories);
                MinecraftServer server = player.getServer();
                if (server != null) {
                    for (ItemStack stack : leftOver) {
                        com.oktsas.onekeytosetaserver.market.MarketManager.sendMail(server, player.getUUID(), stack);
                    }
                    player.sendSystemMessage(Component.literal("§e有 " + leftOver.size()
                            + " 件饰品栏物品无法装备，已放入你的邮箱（输入 /mail 领取）"));
                }
            }
            if (accessories != restoredAccessories) {
                OneKeyToSetAServer.LOGGER.error("[MV] accessory round trip mismatch for {} from {}: stored {} "
                        + "stack(s), player now has {}", name, file, accessories, restoredAccessories);
            }
            CuriosBridge.sync(player);
        }
        OneKeyToSetAServer.LOGGER.info("[MV] {} <- {}: {} inventory stack(s), {} accessory item(s)",
                name, file, items, restoredAccessories);
        try {
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
        } catch (Throwable ignored) {
            // headless / fake players
        }
    }

    /** Writes a snapshot: temp file first, one previous generation kept as {@code .bak}. */
    private static boolean write(Path file, CompoundTag root) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(tmp)) {
                NbtIo.writeCompressed(root, out);
            }
            if (Files.isRegularFile(file)) {
                try {
                    Files.copy(file, file.resolveSibling(file.getFileName() + BACKUP_SUFFIX),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable t) {
                    OneKeyToSetAServer.LOGGER.warn("[MV] could not keep a backup of {}", file, t);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not write {}", file, t);
            try {
                Files.deleteIfExists(tmp);
            } catch (Throwable ignored) {
                // nothing else to do
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ self test seams

    /** Slot identifiers and their size, for the headless round-trip test. Empty without Curios. */
    public static Map<String, Integer> accessorySlots(ServerPlayer player) {
        if (!curiosLoaded()) {
            return new LinkedHashMap<>();
        }
        return CuriosBridge.slots(player);
    }

    /** The non-empty accessory stacks the player currently wears. Empty without Curios. */
    public static List<ItemStack> accessoriesOf(ServerPlayer player) {
        if (!curiosLoaded()) {
            return new ArrayList<>();
        }
        return CuriosBridge.items(player);
    }

    /** Puts a stack into one accessory slot; false when Curios or that slot does not exist. */
    public static boolean setAccessory(ServerPlayer player, String identifier, int index, ItemStack stack) {
        return curiosLoaded() && CuriosBridge.set(player, identifier, index, stack);
    }

    // ------------------------------------------------------------------ world reset / repair

    /**
     * World delete / reset: instead of parking the stored items in an archive folder, hand every item back to
     * its owner through the mailbox - that is the behaviour the world owner asked for.
     *
     * @return number of mailed item stacks
     */
    public static int mailOut(Path worldFolder, MinecraftServer server) {
        Path pool = worldFolder.resolve(MvWorld.CONFIG_DIR).resolve(POOL_DIR);
        int mailed = mailPool(pool, server, true);
        if (mailed > 0) {
            OneKeyToSetAServer.LOGGER.info("[MV] mailed {} item stack(s) from {} back to their owners",
                    mailed, worldFolder.getFileName());
        }
        return mailed;
    }

    /**
     * Recovery path for a pool file that could not be loaded (operator tool): every item in the pools of one
     * player is mailed to them and the files are renamed to {@code .recovered}, so nothing can be handed out
     * twice.
     *
     * @param server running server, for the mailbox
     * @return number of mailed item stacks
     */
    public static int recover(UUID owner, MinecraftServer server) {
        int mailed = 0;
        for (Path pool : pools()) {
            Path file = snapshotFile(pool, owner);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            CompoundTag root = read(file, owner.toString());
            if (root == null) {
                continue;
            }
            mailed += mailContents(root, owner, server);
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".recovered"),
                        StandardCopyOption.REPLACE_EXISTING);
                OneKeyToSetAServer.LOGGER.info("[MV] recovered {} -> {}", file, file.getFileName() + ".recovered");
            } catch (Throwable t) {
                OneKeyToSetAServer.LOGGER.error("[MV] could not rename {} after recovery", file, t);
            }
        }
        return mailed;
    }

    /** Every pool directory of this server: the shared one plus one per managed world. */
    public static List<Path> pools() {
        List<Path> pools = new ArrayList<>();
        if (MvWorldManager.gameDir() != null) {
            pools.add(MvWorldManager.gameDir().resolve(MvWorld.CONFIG_DIR).resolve(GLOBAL_DIR));
        }
        for (MvWorld world : MvWorldManager.all()) {
            pools.add(MvWorldManager.folderOf(world).resolve(MvWorld.CONFIG_DIR).resolve(POOL_DIR));
        }
        return pools;
    }

    /**
     * Operator report: what every pool file of this player holds, without changing anything. Used by
     * {@code /mv inventory inspect} before a recovery run.
     */
    public static String describePools(UUID owner) {
        StringBuilder sb = new StringBuilder("§e§l" + owner + " 的背包池文件");
        int found = 0;
        for (Path pool : pools()) {
            Path file = snapshotFile(pool, owner);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            found++;
            long size = 0L;
            try {
                size = Files.size(file);
            } catch (Throwable ignored) {
                // only cosmetic
            }
            CompoundTag root = read(file, owner.toString());
            if (root == null) {
                sb.append("\n §7- §f").append(pool)
                        .append(" §c读取失败（文件保留原样；这种情况 recover 也读不了它，请从 .bak 或整服备份恢复）");
                continue;
            }
            int items = countStacks(root.getList(KEY_INVENTORY, CompoundTag.TAG_COMPOUND));
            int curios = countCurioStacks(root.getList(KEY_CURIOS, CompoundTag.TAG_COMPOUND));
            sb.append("\n §7- §f").append(pool)
                    .append(" §7背包 §f").append(items).append(" §7件 / 饰品 §f").append(curios)
                    .append(" §7件 §8(").append(size).append(" B)");
        }
        if (found == 0) {
            sb.append("\n §7没有找到这个玩家的任何池文件");
        }
        return sb.toString();
    }

    private static int countStacks(ListTag items) {
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            if (!ItemStack.of(items.getCompound(i)).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static int countCurioStacks(ListTag curios) {
        int count = 0;
        for (int i = 0; i < curios.size(); i++) {
            CompoundTag entry = curios.getCompound(i);
            count += countStacks(entry.getList(CuriosBridge.KEY_STACKS, CompoundTag.TAG_COMPOUND));
            count += countStacks(entry.getList(CuriosBridge.KEY_COSMETICS, CompoundTag.TAG_COMPOUND));
        }
        return count;
    }

    /** Mails everything of every pool file in one directory; optionally deletes the files afterwards. */
    private static int mailPool(Path pool, MinecraftServer server, boolean delete) {
        if (!Files.isDirectory(pool) || server == null) {
            return 0;
        }
        int mailed = 0;
        try (var stream = Files.list(pool)) {
            for (Path file : stream.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(SUFFIX)) {
                    continue;       // leaves .bak and .tmp alone
                }
                UUID owner;
                try {
                    owner = UUID.fromString(name.substring(0, name.length() - SUFFIX.length()));
                } catch (Throwable t) {
                    continue;
                }
                CompoundTag root;
                try (InputStream in = Files.newInputStream(file)) {
                    root = NbtIo.readCompressed(in);
                }
                if (root == null) {
                    continue;       // unreadable: leave it for the operator instead of deleting it
                }
                mailed += mailContents(root, owner, server);
                if (delete) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not mail out inventories of {}", pool, t);
        }
        return mailed;
    }

    /** Both halves of a snapshot into the mailbox. */
    private static int mailContents(CompoundTag root, UUID owner, MinecraftServer server) {
        int mailed = 0;
        ListTag items = root.getList(KEY_INVENTORY, CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = ItemStack.of(items.getCompound(i));
            if (!stack.isEmpty()) {
                com.oktsas.onekeytosetaserver.market.MarketManager.sendMail(server, owner, stack);
                mailed++;
            }
        }
        // accessory slots are stored in Curios' own {{Size, Items}} layout; read it generically so this class
        // never has to touch the Curios API
        return mailed + mailCurios(root.getList(KEY_CURIOS, CompoundTag.TAG_COMPOUND), owner, server);
    }

    /** Reads Curios' saved accessory tag without linking against the Curios API. */
    private static int mailCurios(ListTag curios, UUID owner, MinecraftServer server) {
        int mailed = 0;
        for (int i = 0; i < curios.size(); i++) {
            CompoundTag entry = curios.getCompound(i);
            mailed += mailItemList(entry.getList(CuriosBridge.KEY_STACKS, CompoundTag.TAG_COMPOUND), owner, server);
            mailed += mailItemList(entry.getList(CuriosBridge.KEY_COSMETICS, CompoundTag.TAG_COMPOUND),
                    owner, server);
        }
        return mailed;
    }

    private static int mailItemList(ListTag list, UUID owner, MinecraftServer server) {
        int mailed = 0;
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.of(list.getCompound(i));
            if (!stack.isEmpty()) {
                com.oktsas.onekeytosetaserver.market.MarketManager.sendMail(server, owner, stack);
                mailed++;
            }
        }
        return mailed;
    }

    /** Moves a world's inventory pool out of the way instead of deleting it (legacy safety net). */
    public static void archive(Path worldFolder, Path archiveRoot) {
        Path pool = worldFolder.resolve(MvWorld.CONFIG_DIR).resolve(POOL_DIR);
        if (!Files.isDirectory(pool)) {
            return;
        }
        try {
            Files.createDirectories(archiveRoot);
            Path target = archiveRoot.resolve(worldFolder.getFileName() + "-" + System.currentTimeMillis());
            Files.createDirectories(target);
            try (var stream = Files.list(pool)) {
                for (Path file : stream.toList()) {
                    Files.move(file, target.resolve(file.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            OneKeyToSetAServer.LOGGER.info("[MV] archived {} inventories to {}", worldFolder.getFileName(), target);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[MV] could not archive inventories of {}", worldFolder, t);
        }
    }
}
