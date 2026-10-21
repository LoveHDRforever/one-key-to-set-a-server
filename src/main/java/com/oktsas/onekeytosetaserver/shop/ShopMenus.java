package com.oktsas.onekeytosetaserver.shop;

import com.oktsas.onekeytosetaserver.economy.MoneyService;
import com.oktsas.onekeytosetaserver.menu.MenuSlots;
import com.oktsas.onekeytosetaserver.util.ItemNames;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The shop GUI. Vanilla {@code GENERIC_9x6} chest, so a plain client shows it; the mod does not have to be on
 * the client. Buy-only: the official shop never pays money back, recycling happens in its own screen.
 *
 * <p>The category screen follows the layout the owner asked for (screenshot "服务器商店"): the goods sit in the
 * middle (rows 3 and 4, nine per row = 18 categories a page), the bottom row carries the player's own head with
 * their balance on the left and the red close button on the far right. The item and quantity screens keep the
 * layout they had.</p>
 *
 * <p>Layout of the quantity screen follows the reference plugin this feature was modelled on:
 * 19/20/21 = -32/-16/-1, 23/24/25 = +1/+16/+32, 13 = confirm, 22 = the item being bought with its amount.</p>
 */
public final class ShopMenus {

    private static final int PER_PAGE = 45;
    /** Categories a page of the category screen holds: rows 3 and 4, nine each. */
    public static final int CATEGORIES_PER_PAGE = 18;
    /** First slot of the category grid (row 3, column 1). */
    public static final int CATEGORY_FIRST_SLOT = 18;
    /** Bottom row of the category screen. */
    public static final int INFO_SLOT = 45;
    public static final int SELL_ALL_SLOT = 46;
    public static final int TRASH_SLOT = 47;
    public static final int MARKET_SLOT = 48;
    public static final int PREV_PAGE_SLOT = 49;
    public static final int NEXT_PAGE_SLOT = 51;
    public static final int CLOSE_SLOT = 53;

    private ShopMenus() {
    }

    public static void openShop(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new ShopMenu(id, inventory, player), Component.literal("§8服务器商店")));
    }

    public static void openTrash(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new TrashMenu(id, inventory, player), Component.literal("§8垃圾桶")));
    }

    /** Players who want a confirmation step before the trash destroys anything. */
    private static final java.util.Set<java.util.UUID> TRASH_CONFIRM = new java.util.HashSet<>();

    /** Toggles the "ask before destroying" mode; returns the new state. */
    public static boolean toggleTrashConfirm(java.util.UUID player) {
        if (TRASH_CONFIRM.remove(player)) {
            return false;
        }
        TRASH_CONFIRM.add(player);
        return true;
    }

    public static boolean trashConfirmMode(java.util.UUID player) {
        return player != null && TRASH_CONFIRM.contains(player);
    }

    public static void openSell(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new SellMenu(id, inventory, player), Component.literal("§8一键售卖区")));
    }

    /** Exposed for headless tests. */
    public static SellMenu createSell(int id, Inventory inventory, ServerPlayer player) {
        return new SellMenu(id, inventory, player);
    }

    /**
     * The sell area ("一键售卖区"): put items into the 45 rows, press <b>确认出售</b> and press it again within
     * three seconds - the same confirm gesture the menus use. The shop then buys every stack the recycle table
     * knows, hands every other stack back to the player and pays once. Closing the screen without the second
     * click sells <b>nothing</b> and returns every item, so an accidental Escape cannot lose anything.
     *
     * <p>Shift clicks work in both directions: a shift click in the player inventory moves the stack into the
     * sell area, a shift click in the sell area puts it back.</p>
     */
    public static final class SellMenu extends AbstractContainerMenu {
        /** Container rows only; the player slots the client expects come from {@link MenuSlots}. */
        static final int DISPLAY_SLOTS = 54;
        /** Rows 1-5 (0-44) really take items; only the button row below them is a label. */
        static final int INTAKE_SLOTS = 45;
        /** Bottom row: the live summary; 49 = 确认出售, 53 = 关闭（不卖）. */
        public static final int STATS_SLOT = 45;
        public static final int CONFIRM_SLOT = 49;
        public static final int CLOSE_SLOT = 53;
        /** "确认出售" only fires on a second click inside this window. */
        private static final long CONFIRM_WINDOW_MS = 3000L;

        private final ServerPlayer player;
        private final SimpleContainer container = new SimpleContainer(INTAKE_SLOTS);
        private final SimpleContainer buttons = new SimpleContainer(9);
        private int confirmSlot = -1;
        private long confirmDeadline;
        private boolean settled;

        SellMenu(int id, Inventory inventory, ServerPlayer player) {
            super(MenuType.GENERIC_9x6, id);
            this.player = player;
            for (int slot = 0; slot < INTAKE_SLOTS; slot++) {
                addSlot(new Slot(container, slot, 8 + (slot % 9) * 18, 18 + (slot / 9) * 18));
            }
            for (int slot = INTAKE_SLOTS; slot < DISPLAY_SLOTS; slot++) {
                addSlot(new DisplaySlot(buttons, slot - INTAKE_SLOTS,
                        8 + (slot % 9) * 18, 18 + (slot / 9) * 18));
            }
            if (MenuSlots.showPlayerInventory()) {
                for (int i = 0; i < MenuSlots.PLAYER_SLOTS; i++) {
                    addSlot(MenuSlots.playerInventorySlot(inventory, i, 6));
                }
            }
            rebuild();
        }

        /** {@code {unitsSellable, earned, unitsReturned}} for what is in the box right now. */
        private double[] stats() {
            double earned = 0.0D;
            int sellable = 0;
            int returned = 0;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (ShopManager.isSellable(stack)) {
                    sellable += stack.getCount();
                    earned += ShopManager.recycleUnitPrice(stack) * stack.getCount();
                } else {
                    returned += stack.getCount();
                }
            }
            return new double[]{sellable, earned, returned};
        }

        private void rebuild() {
            double[] stats = stats();
            int sellable = (int) stats[0];
            int returned = (int) stats[2];

            ItemStack hint = new ItemStack(Items.NETHER_STAR);
            hint.setHoverName(Component.literal("§e把要卖的物品放进上面 45 格"));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("§7可卖: §a" + sellable + " §7件，共 §a" + MoneyService.format(stats[1])));
            lore.add(Component.literal(returned > 0
                    ? "§7不可卖: §c" + returned + " §7件（结算时退回背包）"
                    : "§7不可卖的物品会在结算时退回"));
            lore.add(Component.literal("§7点下面的「确认出售」，3 秒内再点一次才结算"));
            hint.getOrCreateTagElement("display").put("Lore", Lore.of(lore));
            buttons.setItem(STATS_SLOT - INTAKE_SLOTS, hint);

            boolean armed = confirmSlot == CONFIRM_SLOT && System.currentTimeMillis() <= confirmDeadline;
            ItemStack confirm = new ItemStack(armed ? Items.GOLD_BLOCK : Items.LIME_DYE);
            confirm.setHoverName(Component.literal(armed ? "§6真的要卖？再点一次！" : "§a确认出售"));
            List<Component> confirmLore = new ArrayList<>();
            confirmLore.add(Component.literal("§7再点一次就结算（3 秒内有效）"));
            confirmLore.add(Component.literal("§7可卖 " + sellable + " 件，共 " + MoneyService.format(stats[1])));
            confirmLore.add(Component.literal(returned > 0
                    ? "§7" + returned + " 件不可卖，会退回背包" : "§7当前没有不可卖的物品"));
            confirm.getOrCreateTagElement("display").put("Lore", Lore.of(confirmLore));
            buttons.setItem(CONFIRM_SLOT - INTAKE_SLOTS, confirm);

            ItemStack close = new ItemStack(Items.BARRIER);
            close.setHoverName(Component.literal("§c关闭（不卖）"));
            close.getOrCreateTagElement("display").put("Lore", Lore.of(List.of(
                    Component.literal("§7关闭界面，物品全部退回背包"))));
            buttons.setItem(CLOSE_SLOT - INTAKE_SLOTS, close);
            try {
                broadcastChanges();
            } catch (Throwable ignored) {
                // closed menu or headless test
            }
        }

        /** Sells everything sellable in the box, returns the rest, pays once. */
        private void settle() {
            if (settled) {
                return;
            }
            settled = true;
            double earned = 0.0D;
            int sold = 0;
            int returned = 0;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (ShopManager.isSellable(stack)) {
                    earned += ShopManager.recycleUnitPrice(stack) * stack.getCount();
                    sold += stack.getCount();
                    container.setItem(i, ItemStack.EMPTY);
                } else {
                    returned += stack.getCount();
                    if (this.player != null && !this.player.getInventory().add(stack)) {
                        this.player.drop(stack, false);
                    }
                    container.setItem(i, ItemStack.EMPTY);
                }
            }
            if (earned > 0.0D && this.player != null && this.player.getServer() != null) {
                MoneyService.deposit(this.player.getServer(), this.player.getUUID(), earned);
            }
            if (this.player != null) {
                this.player.sendSystemMessage(Component.literal("§a已结算 " + sold + " 个物品，获得 "
                        + MoneyService.format(earned)
                        + (returned > 0 ? " §c；" + returned + " 个不可回收物品已退回" : "")));
            }
        }

        /** Gives everything still in the box back to the player (or drops it when the inventory is full). */
        private void refund(String message) {
            int count = 0;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                count += stack.getCount();
                if (this.player != null && !this.player.getInventory().add(stack)) {
                    this.player.drop(stack, false);
                }
                container.setItem(i, ItemStack.EMPTY);
            }
            if (count > 0 && this.player != null) {
                this.player.sendSystemMessage(Component.literal(message));
            }
        }

        private void onConfirm() {
            long now = System.currentTimeMillis();
            if (confirmSlot != CONFIRM_SLOT || now > confirmDeadline) {
                confirmSlot = CONFIRM_SLOT;
                confirmDeadline = now + CONFIRM_WINDOW_MS;
                double[] stats = stats();
                if (this.player != null) {
                    this.player.sendSystemMessage(Component.literal(
                            "§e再点一次「确认出售」确认（3 秒内有效），这次不会结算：可卖 "
                                    + (int) stats[0] + " 件共 " + MoneyService.format(stats[1]) + "，"
                                    + (int) stats[2] + " 件会退回"));
                }
                return;
            }
            confirmSlot = -1;
            settle();
            if (this.player != null) {
                this.player.closeContainer();
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            if (!MenuSlots.showPlayerInventory() || index < 0 || index >= this.slots.size()) {
                return MenuSlots.noQuickMove();
            }
            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) {
                return MenuSlots.noQuickMove();
            }
            ItemStack stack = slot.getItem();
            ItemStack copy = stack.copy();
            if (index < INTAKE_SLOTS) {
                // sell area -> player inventory
                if (!this.moveItemStackTo(stack, DISPLAY_SLOTS, DISPLAY_SLOTS + MenuSlots.PLAYER_SLOTS, true)) {
                    return MenuSlots.noQuickMove();
                }
            } else if (index >= DISPLAY_SLOTS) {
                // player inventory -> sell area
                if (!this.moveItemStackTo(stack, 0, INTAKE_SLOTS, false)) {
                    return MenuSlots.noQuickMove();
                }
            } else {
                // the bottom row is a label
                return MenuSlots.noQuickMove();
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            return copy;
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
            if (slotId == CONFIRM_SLOT) {
                onConfirm();
                if (!settled) {
                    rebuild();
                    MenuSlots.resync(this, clicker);
                }
                return;
            }
            if (slotId == CLOSE_SLOT) {
                if (clicker instanceof ServerPlayer serverPlayer) {
                    serverPlayer.closeContainer();
                }
                return;
            }
            // The 45 rows above are real storage here (you put items in), so the vanilla logic handles them;
            // only the bottom row is a label. Forwarding these to the vanilla logic is what makes "put your
            // item in" work at all.
            boolean storage = slotId >= 0 && slotId < INTAKE_SLOTS;
            if (storage || MenuSlots.allowVanillaClick(slotId, DISPLAY_SLOTS)) {
                super.clicked(slotId, button, clickType, clicker);
            }
            rebuild();
            MenuSlots.resync(this, clicker);
        }

        @Override
        public void removed(Player player) {
            if (!settled) {
                refund("§e没有点「确认出售」，物品已全部退回");
            }
            confirmSlot = -1;
            super.removed(player);
        }
    }

    // ------------------------------------------------------------------ shop

    public static final class ShopMenu extends AbstractContainerMenu {
        private enum Screen { CATEGORIES, ITEMS, QUANTITY }

        /** Container rows only; the player slots the client expects come from {@link MenuSlots}. */
        static final int DISPLAY_SLOTS = 54;
        private final ServerPlayer player;
        private final SimpleContainer container = new SimpleContainer(54);
        private final List<Runnable> actions = new ArrayList<>();

        private Screen screen = Screen.CATEGORIES;
        private int categoryIndex;
        private int page;
        private int itemIndex = -1;
        private int amount = 1;

        ShopMenu(int id, Inventory inventory, ServerPlayer player) {
            super(MenuType.GENERIC_9x6, id);
            this.player = player;
            for (int slot = 0; slot < 54; slot++) {
                addSlot(new DisplaySlot(container, slot, 8 + (slot % 9) * 18, 18 + (slot / 9) * 18));
            }
            if (MenuSlots.showPlayerInventory()) {
                for (int i = 0; i < MenuSlots.PLAYER_SLOTS; i++) {
                    addSlot(MenuSlots.playerInventorySlot(inventory, i, 6));
                }
            }
            rebuild();
        }

        public String screenName() {
            return screen.name();
        }

        public int amount() {
            return amount;
        }

        private void rebuild() {
            for (int i = 0; i < 54; i++) {
                container.setItem(i, ItemStack.EMPTY);
            }
            actions.clear();
            while (actions.size() < 54) {
                actions.add(null);
            }
            switch (screen) {
                case CATEGORIES -> fillCategories();
                case ITEMS -> fillItems();
                case QUANTITY -> fillQuantity();
            }
            // A closed menu (or a fake player in a headless test) must not break the flow.
            try {
                broadcastChanges();
            } catch (Throwable ignored) {
                // nothing to sync
            }
        }

        /**
         * The category screen, in the layout the owner asked for: the categories fill rows 3 and 4 (18 a page),
         * the bottom row carries the player's own head with their balance on the left and the red close button
         * on the far right. "上一页/下一页" only appear when there really is more than one page (there are 11
         * categories, so normally they do not).
         */
        private void fillCategories() {
            List<ShopManager.Category> categories = ShopManager.categories();
            int pages = Math.max(1, (categories.size() + CATEGORIES_PER_PAGE - 1) / CATEGORIES_PER_PAGE);
            page = Math.max(0, Math.min(page, pages - 1));
            int from = page * CATEGORIES_PER_PAGE;
            for (int i = 0; i < CATEGORIES_PER_PAGE; i++) {
                int absolute = from + i;
                if (absolute >= categories.size()) {
                    break;
                }
                ShopManager.Category category = categories.get(absolute);
                int index = absolute;
                double cheapest = category.items.stream().mapToDouble(ShopManager.ShopItem::buyPrice).min().orElse(0);
                ItemStack stack = ShopManager.stackOf(category.iconId(), 1);
                if (stack.isEmpty()) {
                    stack = new ItemStack(Items.CHEST);
                }
                stack.setHoverName(Component.literal("§f" + category.title));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.literal("§7" + category.items.size() + " 件商品"));
                lore.add(Component.literal("§7起价 " + MoneyService.format(cheapest)));
                lore.add(Component.literal("§a点击查看"));
                stack.getOrCreateTagElement("display").put("Lore", Lore.of(lore));
                set(CATEGORY_FIRST_SLOT + i, stack, () -> {
                    categoryIndex = index;
                    page = 0;
                    screen = Screen.ITEMS;
                });
            }
            set(INFO_SLOT, playerHead(), null);
            set(SELL_ALL_SLOT, icon(Items.CHEST, "§6售卖区（卖任意物品）",
                            "§7打开箱子，把想卖的物品放进去",
                            "§7确认后自动结算，不可卖的原样退回"),
                    () -> run("sell"));
            set(TRASH_SLOT, icon(Items.CAULDRON, "§e垃圾桶", "§7打开垃圾桶界面"), () -> run("trash"));
            set(MARKET_SLOT, icon(Items.CHEST, "§a自由市场", "§7打开玩家市场"), () -> run("market"));
            if (pages > 1) {
                if (page > 0) {
                    set(PREV_PAGE_SLOT, icon(Items.ARROW, "§d上一页",
                            "§7第 " + page + " / " + pages + " 页"), () -> page = Math.max(0, page - 1));
                }
                if (page < pages - 1) {
                    set(NEXT_PAGE_SLOT, icon(Items.ARROW, "§b下一页",
                            "§7第 " + (page + 2) + " / " + pages + " 页"), () -> page = Math.min(pages - 1, page + 1));
                }
            }
            set(CLOSE_SLOT, icon(Items.BARRIER, "§c关闭", "§7点击关闭商店"), this::close);
        }

        /** The viewer's own head: name, balance and the world they are standing in. */
        private ItemStack playerHead() {
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            try {
                net.minecraft.nbt.CompoundTag owner = new net.minecraft.nbt.CompoundTag();
                net.minecraft.nbt.NbtUtils.writeGameProfile(owner, player.getGameProfile());
                head.getOrCreateTag().put("SkullOwner", owner);
            } catch (Throwable t) {
                // a head without a profile still renders as the default skin
            }
            head.setHoverName(Component.literal("§f" + player.getGameProfile().getName()));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("§7余额: §f"
                    + MoneyService.format(MoneyService.get(player.getServer(), player.getUUID()))));
            lore.add(Component.literal("§7所在世界: §f" + worldName()));
            head.getOrCreateTagElement("display").put("Lore", Lore.of(lore));
            return head;
        }

        private String worldName() {
            try {
                com.oktsas.onekeytosetaserver.world.MvWorld world =
                        com.oktsas.onekeytosetaserver.world.WorldPropertyListener.worldOf(player.level());
                if (world != null) {
                    return world.displayName();
                }
            } catch (Throwable ignored) {
                // fall through to the dimension id
            }
            return player.level().dimension().location().toString();
        }

        /** Closes the shop and runs one of our commands, the same way a menu button does. */
        private void run(String command) {
            player.closeContainer();
            if (player.getServer() != null) {
                player.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
            }
        }

        private void fillItems() {
            List<ShopManager.Category> categories = ShopManager.categories();
            if (categoryIndex < 0 || categoryIndex >= categories.size()) {
                screen = Screen.CATEGORIES;
                fillCategories();
                return;
            }
            ShopManager.Category category = categories.get(categoryIndex);
            List<ShopManager.ShopItem> items = ShopManager.itemsOf(category, page, PER_PAGE);
            for (int i = 0; i < items.size(); i++) {
                ShopManager.ShopItem item = items.get(i);
                int absolute = page * PER_PAGE + i;
                ItemStack probe = ShopManager.stackOf(item, 1);
                ItemStack icon = probe.isEmpty() ? new ItemStack(Items.BARRIER) : probe.copy();
                if (!probe.isEmpty() && !item.hasNbt()) {
                    icon.setHoverName(ItemNames.of(item.itemId()));
                }
                double listed = ShopManager.recycleUnitPrice(probe);
                List<Component> lore = new ArrayList<>();
                lore.add(ItemNames.idLine(item.itemId()));
                String nbtLine = ShopManager.describeNbt(probe);
                if (!nbtLine.isEmpty()) {
                    lore.add(Component.literal("§d" + nbtLine));
                }
                lore.add(Component.literal("§7买价: §a" + MoneyService.format(item.buyPrice())));
                lore.add(Component.literal(listed > 0.0D
                        ? "§7回收价: §6" + MoneyService.format(listed)
                        : "§7回收价: §8本店不回收"));
                lore.add(Component.literal("§7余额: §f" + MoneyService.format(MoneyService.get(
                        player.getServer(), player.getUUID()))));
                lore.add(Component.literal("§7你有: §f"
                        + ShopManager.inventoryCount(player, probe) + " 个"));
                lore.add(Component.literal("§a点击选数量：既能买，也能卖"));
                icon.getOrCreateTagElement("display").put("Lore", Lore.of(lore));
                set(i, icon, () -> {
                    itemIndex = absolute;
                    amount = 1;
                    screen = Screen.QUANTITY;
                });
            }
            set(45, icon(Items.ARROW, "§d上一页", "§7第 " + (page + 1) + " 页"),
                    () -> page = Math.max(0, page - 1));
            set(48, icon(Items.CHEST, "§e返回分类", "§7回到分类列表"), () -> screen = Screen.CATEGORIES);
            set(49, icon(Items.BARRIER, "§c关闭", "§7点击关闭商店"), this::close);
            set(53, icon(Items.ARROW, "§b下一页", "§7第 " + (page + 1) + " 页"),
                    () -> page = Math.min(Math.max(0, (category.items.size() - 1) / PER_PAGE), page + 1));
        }

        private void fillQuantity() {
            List<ShopManager.Category> categories = ShopManager.categories();
            if (categoryIndex < 0 || categoryIndex >= categories.size()) {
                screen = Screen.CATEGORIES;
                fillCategories();
                return;
            }
            ShopManager.Category category = categories.get(categoryIndex);
            if (itemIndex < 0 || itemIndex >= category.items.size()) {
                screen = Screen.ITEMS;
                fillItems();
                return;
            }
            ShopManager.ShopItem item = category.items.get(itemIndex);
            ItemStack probe = ShopManager.stackOf(item, 1);
            double total = item.buyPrice() * amount;
            boolean affordable = MoneyService.get(player.getServer(), player.getUUID()) >= total;
            double recycle = ShopManager.recycleUnitPrice(probe);
            double sellTotal = recycle * amount;
            int owned = ShopManager.inventoryCount(player, probe);
            String blocker = ShopManager.sellBlocker(probe);
            boolean canSell = blocker.isEmpty() && owned > 0;

            set(19, icon(Items.MAGENTA_STAINED_GLASS_PANE, "§d-32", "§7数量 -32"), () -> amount = Math.max(1, amount - 32));
            set(20, icon(Items.MAGENTA_STAINED_GLASS_PANE, "§d-16", "§7数量 -16"), () -> amount = Math.max(1, amount - 16));
            set(21, icon(Items.MAGENTA_STAINED_GLASS_PANE, "§d-1", "§7数量 -1"), () -> amount = Math.max(1, amount - 1));
            set(23, icon(Items.LIGHT_BLUE_STAINED_GLASS_PANE, "§b+1", "§7数量 +1"), () -> amount = Math.min(2304, amount + 1));
            set(24, icon(Items.LIGHT_BLUE_STAINED_GLASS_PANE, "§b+16", "§7数量 +16"), () -> amount = Math.min(2304, amount + 16));
            set(25, icon(Items.LIGHT_BLUE_STAINED_GLASS_PANE, "§b+32", "§7数量 +32"), () -> amount = Math.min(2304, amount + 32));

            ItemStack display = probe.isEmpty() ? new ItemStack(Items.BARRIER) : probe.copy();
            if (!probe.isEmpty() && !item.hasNbt()) {
                display.setHoverName(ItemNames.of(item.itemId()));
            }
            List<Component> lore = new ArrayList<>();
            lore.add(ItemNames.idLine(item.itemId()));
            String nbtLine = ShopManager.describeNbt(probe);
            if (!nbtLine.isEmpty()) {
                lore.add(Component.literal("§d" + nbtLine));
            }
            lore.add(Component.literal("§7数量: §f" + amount));
            lore.add(Component.literal("§7买价: §a" + MoneyService.format(item.buyPrice())
                    + " §8→ 合计 " + MoneyService.format(total)));
            lore.add(Component.literal(recycle > 0.0D
                    ? "§7回收价: §6" + MoneyService.format(recycle)
                            + " §8→ 合计 " + MoneyService.format(sellTotal)
                    : "§7回收价: §8本店不回收"));
            lore.add(Component.literal("§7你背包里有: §f" + owned + " 个"));
            display.getOrCreateTagElement("display").put("Lore", Lore.of(lore));
            set(22, display, null);

            set(BUY_SLOT, icon(affordable ? Items.PAPER : Items.BARRIER,
                            affordable ? "§a确认购买" : "§c余额不足",
                            "§7总价: " + MoneyService.format(total),
                            "§7点击完成交易"),
                    () -> {
                        if (ShopManager.buy(player, item, amount)) {
                            screen = Screen.ITEMS;
                        }
                    });
            set(SELL_SLOT, icon(canSell ? Items.GOLD_INGOT : Items.BARRIER,
                            canSell ? "§6确认出售" : (blocker.isEmpty() ? "§c你背包里没有这个物品" : blocker),
                            canSell ? "§7卖出 " + amount + " 个可得 " + MoneyService.format(sellTotal)
                                    : "§7先把这个物品放进背包",
                            "§7出售按回收价表结算"),
                    () -> {
                        if (ShopManager.sell(player, item, amount) > 0) {
                            screen = Screen.ITEMS;
                        }
                    });
            set(31, icon(Items.CHEST, "§e返回商品", "§7回到商品列表"), () -> screen = Screen.ITEMS);
            set(49, icon(Items.BARRIER, "§c关闭", "§7点击关闭商店"), this::close);
        }

        /** Slot of the buy button on the quantity screen. */
        public static final int BUY_SLOT = 13;

        /** Slot of the sell button on the quantity screen. */
        public static final int SELL_SLOT = 15;

        /**
         * Test hook: jumps straight to the quantity screen of an item, without walking the category pages.
         *
         * @return false when the item is not in the shop at all
         */
        public boolean openQuantityFor(String itemId) {
            List<ShopManager.Category> categories = ShopManager.categories();
            for (int c = 0; c < categories.size(); c++) {
                List<ShopManager.ShopItem> items = categories.get(c).items;
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).itemId().equalsIgnoreCase(itemId)) {
                        categoryIndex = c;
                        itemIndex = i;
                        amount = 1;
                        screen = Screen.QUANTITY;
                        rebuild();
                        return true;
                    }
                }
            }
            return false;
        }

        private void set(int slot, ItemStack icon, Runnable action) {
            container.setItem(slot, icon);
            if (actions.size() > slot) {
                actions.set(slot, action);
            }
        }

        private ItemStack icon(net.minecraft.world.item.Item item, String name, String... lore) {
            ItemStack stack = new ItemStack(item);
            stack.setHoverName(Component.literal(name));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Component.literal(line));
            }
            if (!lines.isEmpty()) {
                stack.getOrCreateTagElement("display").put("Lore", Lore.of(lines));
            }
            return stack;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            // Never a real move: display rows are labels, so the client's prediction is undone by resync.
            return MenuSlots.noQuickMove();
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (MenuSlots.allowVanillaClick(slotId, DISPLAY_SLOTS)) {
                super.clicked(slotId, button, clickType, player);
                MenuSlots.resync(this, player);
                return;
            }
            Runnable action = slotId >= 0 && slotId < actions.size() ? actions.get(slotId) : null;
            if (action != null) {
                action.run();
                rebuild();
            }
            MenuSlots.resync(this, player);
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
        }

        private void close() {
            player.closeContainer();
        }
    }

    /** Exposed for headless tests. */
    public static ShopMenu createShop(int id, Inventory inventory, ServerPlayer player) {
        return new ShopMenu(id, inventory, player);
    }

    /** Exposed for headless tests. */
    public static TrashMenu createTrash(int id, Inventory inventory) {
        return new TrashMenu(id, inventory, null);
    }

    /** Exposed for headless tests: with a player the confirmation mode is honoured. */
    public static TrashMenu createTrash(int id, Inventory inventory, ServerPlayer player) {
        return new TrashMenu(id, inventory, player);
    }

    // ------------------------------------------------------------------ trash

    /**
     * Items dropped into this chest are destroyed when the screen closes. In "确认模式"
     * ({@code /trash confirm}) nothing is destroyed unless the red button is pressed - the items come back
     * to the player instead. Slot 49 is that button; slots 0-48 and 50-53 take items.
     */
    public static final class TrashMenu extends AbstractContainerMenu {
        private static final int ACTION_SLOT = 49;
        /** Container rows only; the player slots the client expects come from {@link MenuSlots}. */
        static final int DISPLAY_SLOTS = 54;
        private final SimpleContainer container = new SimpleContainer(54);
        private final ServerPlayer player;
        private final boolean confirmMode;
        private boolean confirmed;

        TrashMenu(int id, Inventory inventory, ServerPlayer player) {
            super(MenuType.GENERIC_9x6, id);
            this.player = player;
            this.confirmMode = ShopMenus.trashConfirmMode(player == null ? null : player.getUUID());
            for (int slot = 0; slot < 54; slot++) {
                if (slot == ACTION_SLOT) {
                    addSlot(new DisplaySlot(container, slot, 8 + (slot % 9) * 18, 18 + (slot / 9) * 18));
                } else {
                    addSlot(new Slot(container, slot, 8 + (slot % 9) * 18, 18 + (slot / 9) * 18));
                }
            }
            if (MenuSlots.showPlayerInventory()) {
                for (int i = 0; i < MenuSlots.PLAYER_SLOTS; i++) {
                    addSlot(MenuSlots.playerInventorySlot(inventory, i, 6));
                }
            }
            ItemStack button = new ItemStack(Items.LAVA_BUCKET);
            button.setHoverName(Component.literal("§c立即销毁并关闭"));
            button.getOrCreateTagElement("display").put("Lore", Lore.of(List.of(
                    Component.literal("§7把左边的东西全部销毁"),
                    Component.literal("§7当前模式: " + (confirmMode ? "§e确认模式（不点这个按钮就退回）" : "§7关闭界面即销毁")))));
            container.setItem(ACTION_SLOT, button);
        }

        /** Takes everything out of the chest; {@code keep} hands it back to the player instead of deleting. */
        private int take(boolean keep) {
            int count = 0;
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (i == ACTION_SLOT) {
                    continue;
                }
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                count += stack.getCount();
                if (keep && player != null && !player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
                container.setItem(i, ItemStack.EMPTY);
            }
            return count;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            // Never a real move: display rows are labels, so the client's prediction is undone by resync.
            return MenuSlots.noQuickMove();
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
            if (slotId == ACTION_SLOT) {
                confirmed = true;
                int destroyed = take(false);
                if (player != null) {
                    player.sendSystemMessage(Component.literal("§c已销毁 " + destroyed + " 个物品"));
                    player.closeContainer();
                }
                return;
            }
            // everything except the red button is an ordinary slot you can drop items into
            boolean storage = slotId >= 0 && slotId < DISPLAY_SLOTS && slotId != ACTION_SLOT;
            if (storage || MenuSlots.allowVanillaClick(slotId, DISPLAY_SLOTS)) {
                super.clicked(slotId, button, clickType, clicker);
            }
            MenuSlots.resync(this, clicker);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void removed(Player player) {
            boolean keep = confirmMode && !confirmed;
            int count = take(keep);
            super.removed(player);
            if (count > 0) {
                player.sendSystemMessage(Component.literal(keep
                        ? "§e确认模式：未点「立即销毁」，已退回 " + count + " 个物品"
                        : "§7已销毁 " + count + " 个物品"));
            }
        }
    }

    /** Slots hold display copies only. */
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

    /** Small helper so lore can be built in one place. */
    static final class Lore {
        private Lore() {
        }

        static net.minecraft.nbt.ListTag of(List<Component> lines) {
            net.minecraft.nbt.ListTag tag = new net.minecraft.nbt.ListTag();
            for (Component line : lines) {
                tag.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(line)));
            }
            return tag;
        }
    }
}
