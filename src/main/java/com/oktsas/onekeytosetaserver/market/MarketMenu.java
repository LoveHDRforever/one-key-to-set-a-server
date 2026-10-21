package com.oktsas.onekeytosetaserver.market;

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
import java.util.UUID;

/**
 * Free market screen: a paginated list of listings.
 *
 * <p>Clicking someone else's icon buys it out; clicking your own listing (the "我的挂单" page) takes it
 * back off the market. The toolbar can cycle a category, switch to your own listings and page through
 * the result. Free-text search is a command ({@code /market search <词>}) because a chest screen cannot
 * ask for typed input.</p>
 */
public final class MarketMenu {
    private static final int PER_PAGE = 45;

    private MarketMenu() {
    }

    /** Opens the market with no filter. */
    public static void open(ServerPlayer player) {
        open(player, "", "", false);
    }

    public static void open(ServerPlayer player, String category, String search, boolean mineOnly) {
        MarketMenuInner menu = new MarketMenuInner(0, player.getInventory(), player, category, search, mineOnly);
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new MarketMenuInner(id, inventory, player, category, search, mineOnly),
                Component.literal(menu.title())));
    }

    public static MarketMenuInner create(int id, Inventory inventory, ServerPlayer player) {
        return new MarketMenuInner(id, inventory, player, "", "", false);
    }

    public static MarketMenuInner create(int id, Inventory inventory, ServerPlayer player, String category,
                                         String search, boolean mineOnly) {
        return new MarketMenuInner(id, inventory, player, category, search, mineOnly);
    }

    /** Named with a suffix because the outer class already owns "Menu" semantics in this package. */
    public static final class MarketMenuInner extends AbstractContainerMenu {
        /** Container rows only; the player slots the client expects come from {@link MenuSlots}. */
        static final int DISPLAY_SLOTS = 54;
        private final ServerPlayer player;
        private final SimpleContainer container = new SimpleContainer(54);
        private final List<Runnable> actions = new ArrayList<>();
        private int page;
        private String category;
        private String search;
        private boolean mineOnly;

        MarketMenuInner(int id, Inventory inventory, ServerPlayer player, String category, String search,
                        boolean mineOnly) {
            super(MenuType.GENERIC_9x6, id);
            this.player = player;
            this.category = category == null ? "" : category;
            this.search = search == null ? "" : search;
            this.mineOnly = mineOnly;
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

        public int page() {
            return page;
        }

        public String category() {
            return category;
        }

        public boolean mineOnly() {
            return mineOnly;
        }

        public String title() {
            StringBuilder sb = new StringBuilder("§8自由市场");
            if (mineOnly) {
                sb.append(" §7[我的挂单]");
            }
            if (!category.isBlank()) {
                sb.append(" §7[").append(category).append("]");
            }
            if (!search.isBlank()) {
                sb.append(" §7[搜索:").append(search).append("]");
            }
            return sb.toString();
        }

        public List<MarketManager.Listing> current() {
            return MarketManager.filtered(category, search, mineOnly ? player.getUUID() : (UUID) null);
        }

        /** How many listings the current filter matches (used by the headless test). */
        public int count() {
            return current().size();
        }

        private void rebuild() {
            for (int i = 0; i < 54; i++) {
                container.setItem(i, ItemStack.EMPTY);
            }
            actions.clear();
            while (actions.size() < 54) {
                actions.add(null);
            }
            List<MarketManager.Listing> listings = current();
            int from = page * PER_PAGE;
            for (int i = 0; i < PER_PAGE && from + i < listings.size(); i++) {
                MarketManager.Listing listing = listings.get(from + i);
                ItemStack icon = MarketManager.itemOf(listing);
                if (icon.isEmpty()) {
                    continue;
                }
                boolean own = listing.seller.equals(player.getUUID().toString());
                // The count is re-applied from the decoded stack, so the name is the client's own translation
                // ("钻石 ×3") instead of the English text stored in listing.label.
                icon.setHoverName(ItemNames.withCount(icon));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.literal("§8" + MarketManager.idOf(icon) + "  ×" + icon.getCount()));
                lore.add(Component.literal("§7分类: §f" + MarketManager.categoryOf(icon)));
                lore.add(Component.literal("§7卖家: §f" + listing.sellerName));
                lore.add(Component.literal("§7价格: §a" + MoneyService.format(listing.price)));
                lore.add(Component.literal("§7时长: §f" + MarketManager.durationLabel(listing.hours)
                        + " §7费率: §f" + MarketManager.feePercent(listing.hours) + "%"
                        + (listing.hours <= 0 ? " §7(不过期)" : "")));
                lore.add(Component.literal("§7编号: §f" + listing.id));
                lore.add(Component.literal(own ? "§e这是你的挂单，点击撤回" : "§a点击买断"));
                icon.getOrCreateTagElement("display").put("Lore", loreTag(lore));
                set(i, icon, () -> {
                    if (own) {
                        MarketManager.cancel(player, listing.id);
                    } else {
                        MarketManager.buy(player, listing.id);
                    }
                });
            }
            set(45, icon(Items.ARROW, "§d上一页", "§7第 " + (page + 1) + " 页"),
                    () -> page = Math.max(0, page - 1));
            String nextCategory = nextCategory();
            set(46, icon(Items.HOPPER, "§e分类: §f" + (category.isBlank() ? "全部" : category),
                            "§7点击切换分类", "§7下一个: §f" + (nextCategory.isBlank() ? "全部" : nextCategory)),
                    () -> {
                        category = nextCategory;
                        page = 0;
                    });
            set(47, icon(mineOnly ? Items.CHEST : Items.ENDER_CHEST,
                            "§e我的挂单: " + (mineOnly ? "§a开" : "§7关"),
                            "§7显示我在市场上的 " + MarketManager.countOf(player.getUUID()) + " 件商品",
                            "§7在这里点自己的商品即可撤回"),
                    () -> {
                        mineOnly = !mineOnly;
                        page = 0;
                    });
            set(48, icon(Items.PAPER, "§f搜索: §7" + (search.isBlank() ? "（无）" : search),
                            "§7聊天栏输入 §f/market search <关键词> §7搜索",
                            "§7输入 §f/market search - §7清除"),
                    () -> {
                        search = "";
                        page = 0;
                    });
            set(49, icon(Items.BARRIER, "§c关闭", "§7点击关闭市场"), player::closeContainer);
            set(50, icon(Items.BOOK, "§f当前筛选",
                            "§7分类: §f" + (category.isBlank() ? "全部" : category),
                            "§7搜索: §f" + (search.isBlank() ? "（无）" : search),
                            "§7我的挂单: §f" + (mineOnly ? "是" : "否"),
                            "§7共 §f" + listings.size() + " §7件，第 §f" + (page + 1) + " §7页"), null);
            set(53, icon(Items.ARROW, "§b下一页", "§7第 " + (page + 1) + " 页"),
                    () -> page = Math.min(Math.max(0, (listings.size() - 1) / PER_PAGE), page + 1));
            try {
                broadcastChanges();
            } catch (Throwable ignored) {
                // closed menu or headless test
            }
        }

        /** 全部 -> 方块 -> 工具 -> 武器 -> 防具 -> 食物 -> 材料 -> 全部. */
        private String nextCategory() {
            if (category.isBlank()) {
                return MarketManager.CATEGORIES[0];
            }
            for (int i = 0; i < MarketManager.CATEGORIES.length; i++) {
                if (MarketManager.CATEGORIES[i].equals(category)) {
                    return i + 1 < MarketManager.CATEGORIES.length ? MarketManager.CATEGORIES[i + 1] : "";
                }
            }
            return "";
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
