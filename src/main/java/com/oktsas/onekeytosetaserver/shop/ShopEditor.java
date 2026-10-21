package com.oktsas.onekeytosetaserver.shop;

import com.oktsas.onekeytosetaserver.economy.MoneyService;
import com.oktsas.onekeytosetaserver.menu.MenuSlots;
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
 * In-game price editor for the official shop (permission level 3 and up).
 *
 * <p>Prices are stepped with buttons instead of typed text: a chest screen has no input field and this
 * keeps the whole flow server side and verifiable without a client. The held item is the subject - you can
 * put a brand new item into the shop, move it to another category, set both the buy price and the recycle
 * price, or take it out again.</p>
 */
public final class ShopEditor {

    public static void open(ServerPlayer player) {
        if (!player.hasPermissions(3)) {
            player.sendSystemMessage(Component.literal("§c编辑商店需要 OP 等级 3（世界管理员）"));
            return;
        }
        if (player.getMainHandItem().isEmpty()) {
            player.sendSystemMessage(Component.literal("§c请手持要编辑的商品，再输入 /shop edit"));
            return;
        }
        String banned = com.oktsas.onekeytosetaserver.trade.TradeBlacklist
                .reasonFor(player.getMainHandItem());
        if (!banned.isEmpty()) {
            player.sendSystemMessage(Component.literal(banned + "§7（这一类不能上架，价格设了也不会出现在商店里）"));
            return;
        }
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new EditorMenu(id, inventory, player), Component.literal("§8商店价格编辑")));
    }

    /** Exposed for headless tests. */
    public static EditorMenu create(int id, Inventory inventory, ServerPlayer player) {
        return new EditorMenu(id, inventory, player);
    }

    public static final class EditorMenu extends AbstractContainerMenu {
        /** Container rows only; the player slots the client expects come from {@link MenuSlots}. */
        static final int DISPLAY_SLOTS = 54;
        private final ServerPlayer player;
        private final SimpleContainer container = new SimpleContainer(54);
        private final List<Runnable> actions = new ArrayList<>();
        private final ItemStack subject;
        private final String itemId;
        /** NBT of the held stack (SNBT), or null for a plain item - this is what makes the entry NBT aware. */
        private final String nbt;
        private double buyPrice;
        private double recyclePrice;
        private int categoryIndex;

        EditorMenu(int id, Inventory inventory, ServerPlayer player) {
            super(MenuType.GENERIC_9x6, id);
            this.player = player;
            this.subject = player.getMainHandItem().copy();
            this.subject.setCount(1);
            this.itemId = ShopManager.idOf(this.subject);
            this.nbt = ShopManager.snbtOf(this.subject);
            this.buyPrice = Math.max(0.0D, ShopManager.buyPriceOf(itemId, nbt));
            this.recyclePrice = Math.max(0.0D, ShopManager.recycleUnitPrice(this.subject));
            ShopManager.Category owner = ShopManager.categoryOfItem(itemId);
            List<ShopManager.Category> categories = ShopManager.categories();
            this.categoryIndex = owner == null ? 0 : Math.max(0, categories.indexOf(owner));
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

        public double buyPrice() {
            return buyPrice;
        }

        public double recyclePrice() {
            return recyclePrice;
        }

        public String itemId() {
            return itemId;
        }

        /** The held stack's SNBT, or null for a plain item; exposed for the headless test. */
        public String nbt() {
            return nbt;
        }

        public boolean hasNbt() {
            return nbt != null && !nbt.isBlank();
        }

        public String categoryId() {
            List<ShopManager.Category> categories = ShopManager.categories();
            return categories.isEmpty() ? "" : categories.get(Math.min(categoryIndex, categories.size() - 1)).id;
        }

        /** Steps one of the two prices; exposed so the headless test can drive it without slot guessing. */
        public void step(boolean buy, double delta) {
            if (buy) {
                buyPrice = ShopManager.round(Math.max(0.0D, buyPrice + delta));
            } else {
                recyclePrice = ShopManager.round(Math.max(0.0D, recyclePrice + delta));
            }
        }

        public void cycleCategory(int direction) {
            List<ShopManager.Category> categories = ShopManager.categories();
            if (categories.isEmpty()) {
                return;
            }
            categoryIndex = Math.floorMod(categoryIndex + direction, categories.size());
        }

        /** Writes the current values to {@code multiverse/shop/*.json}. */
        public void save() {
            String category = categoryId();
            if (!category.isBlank()) {
                ShopManager.putInCategory(category, itemId, nbt, buyPrice);
            } else {
                ShopManager.setBuyPrice(itemId, nbt, buyPrice);
            }
            if (recyclePrice > 0.0D && !ShopManager.setRecyclePrice(itemId, nbt, recyclePrice)) {
                player.sendSystemMessage(Component.literal("§c回收价不能 ≥ 买价（"
                        + MoneyService.format(buyPrice) + "），那会变成刷钱漏洞，回收价未被保存"));
                return;
            }
            String summary = ShopManager.describeNbt(subject);
            player.sendSystemMessage(Component.literal("§a已保存 §f" + itemId
                    + (summary.isEmpty() ? " §7(普通物品)" : " §d[" + summary + "]")
                    + " §a买价 §f" + MoneyService.format(buyPrice)
                    + " §a回收价 §f" + MoneyService.format(recyclePrice)
                    + " §a分类 §f" + category));
        }

        private void rebuild() {
            for (int i = 0; i < 54; i++) {
                container.setItem(i, ItemStack.EMPTY);
            }
            actions.clear();
            while (actions.size() < 54) {
                actions.add(null);
            }
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("§7物品: §f" + itemId));
            String summary = ShopManager.describeNbt(subject);
            lore.add(Component.literal(summary.isEmpty()
                    ? "§7NBT: §8无（普通物品，价格对任何同名物品生效）"
                    : "§dNBT: " + summary));
            lore.add(Component.literal("§7买价: §a" + MoneyService.format(buyPrice)));
            lore.add(Component.literal("§7回收价: §a" + MoneyService.format(recyclePrice)));
            lore.add(Component.literal("§7分类: §f" + categoryId()));
            if (hasNbt()) {
                lore.add(Component.literal("§8这条只对带同样 NBT 的物品生效"));
            }
            ItemStack preview = subject.copy();
            preview.setHoverName(Component.literal("§f" + subject.getHoverName().getString()));
            preview.getOrCreateTagElement("display").put("Lore", loreTag(lore));
            set(4, preview, null);

            set(9, icon(Items.RED_STAINED_GLASS_PANE, "§c买价 -100", "§7左键 -100"),
                    () -> step(true, -100.0D));
            set(10, icon(Items.RED_STAINED_GLASS_PANE, "§c买价 -10"), () -> step(true, -10.0D));
            set(11, icon(Items.RED_STAINED_GLASS_PANE, "§c买价 -1"), () -> step(true, -1.0D));
            set(12, icon(Items.RED_STAINED_GLASS_PANE, "§c买价 -0.1"), () -> step(true, -0.1D));
            set(14, icon(Items.LIME_STAINED_GLASS_PANE, "§a买价 +0.1"), () -> step(true, 0.1D));
            set(15, icon(Items.LIME_STAINED_GLASS_PANE, "§a买价 +1"), () -> step(true, 1.0D));
            set(16, icon(Items.LIME_STAINED_GLASS_PANE, "§a买价 +10"), () -> step(true, 10.0D));
            set(17, icon(Items.LIME_STAINED_GLASS_PANE, "§a买价 +100"), () -> step(true, 100.0D));

            set(18, icon(Items.RED_DYE, "§c回收价 -100"), () -> step(false, -100.0D));
            set(19, icon(Items.RED_DYE, "§c回收价 -10"), () -> step(false, -10.0D));
            set(20, icon(Items.RED_DYE, "§c回收价 -1"), () -> step(false, -1.0D));
            set(21, icon(Items.RED_DYE, "§c回收价 -0.1"), () -> step(false, -0.1D));
            set(23, icon(Items.LIME_DYE, "§a回收价 +0.1"), () -> step(false, 0.1D));
            set(24, icon(Items.LIME_DYE, "§a回收价 +1"), () -> step(false, 1.0D));
            set(25, icon(Items.LIME_DYE, "§a回收价 +10"), () -> step(false, 10.0D));
            set(26, icon(Items.LIME_DYE, "§a回收价 +100"), () -> step(false, 100.0D));

            set(27, icon(Items.ARROW, "§e◀ 上一个分类"),
                    () -> cycleCategory(-1));
            List<ShopManager.Category> categories = ShopManager.categories();
            String title = categories.isEmpty() ? "（没有分类）"
                    : categories.get(Math.min(categoryIndex, categories.size() - 1)).title
                    + " §8(" + categoryId() + ")";
            set(28, icon(Items.CHEST, "§e分类: §f" + title,
                            "§7商店里现有的分类:", "§8" + String.join(", ", categoryIds())),
                    null);
            set(29, icon(Items.ARROW, "§e下一个分类 ▶"),
                    () -> cycleCategory(1));

            set(31, icon(Items.EMERALD_BLOCK, "§a保存",
                            "§7买价 §f" + MoneyService.format(buyPrice),
                            "§7回收价 §f" + MoneyService.format(recyclePrice),
                            "§7写入 multiverse/shop/*.json"),
                    this::save);
            set(33, icon(Items.LAVA_BUCKET, "§c从商店下架这一条",
                            "§7只移除这一条（同样的 NBT 才算同一条）",
                            "§7同一 id 的其他 NBT 版本不受影响",
                            "§7回收价保留"),
                    () -> {
                        boolean removed = ShopManager.removeEntry(itemId, nbt);
                        player.sendSystemMessage(Component.literal(removed
                                ? "§c已下架 " + itemId + (hasNbt() ? " §7(NBT 条目)" : "")
                                : "§e商店里本来就没有这一条"));
                    });
            set(49, icon(Items.BARRIER, "§7关闭", "§7未点保存的修改不会写入"),
                    player::closeContainer);
            try {
                broadcastChanges();
            } catch (Throwable ignored) {
                // closed menu or headless test
            }
        }

        private static List<String> categoryIds() {
            List<String> ids = new ArrayList<>();
            for (ShopManager.Category category : ShopManager.categories()) {
                ids.add(category.id);
            }
            return ids;
        }

        private void set(int slot, ItemStack icon, Runnable action) {
            container.setItem(slot, icon);
            if (actions.size() > slot) {
                actions.set(slot, action);
            }
        }

        private static ItemStack icon(net.minecraft.world.item.Item item, String name, String... lore) {
            ItemStack stack = new ItemStack(item);
            stack.setHoverName(Component.literal(name));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Component.literal(line));
            }
            if (!lines.isEmpty()) {
                stack.getOrCreateTagElement("display").put("Lore", loreTag(lines));
            }
            return stack;
        }

        private static net.minecraft.nbt.ListTag loreTag(List<Component> lines) {
            net.minecraft.nbt.ListTag tag = new net.minecraft.nbt.ListTag();
            for (Component line : lines) {
                tag.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(line)));
            }
            return tag;
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
}
