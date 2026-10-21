package com.oktsas.onekeytosetaserver.trade;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BrushItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.FoodOnAStickItem;
import net.minecraft.world.item.HorseArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;

/**
 * Item families the server owner permanently banned from <b>every</b> player trade: the official shop and the
 * free market both refuse to sell them and refuse to buy them.
 *
 * <p>"Permanent" is literal: this list is code, not data. {@code market/blacklist.json} cannot lift it and
 * {@code /market blacklist remove} cannot touch it, so an operator cannot accidentally re-open armour trading
 * while editing the market blacklist. The families are recognised by <b>vanilla item class</b> (plus the
 * {@code *_ore} id suffix), which means modded pickaxes and modded chestplates are covered too - the rule is
 * "no gear trading", not "these 158 ids".</p>
 *
 * <p>Deliberately <b>not</b> included, because the owner did not ask for it and it would gut mining: raw ores
 * ({@code minecraft:raw_iron} ...), {@code minecraft:ancient_debris}, ingots, gems, and ammunition
 * ({@code minecraft:arrow}/{@code tipped_arrow}). Only the silk-touch ore <b>blocks</b> are banned.</p>
 */
public final class TradeBlacklist {

    /** The families, with the Chinese label used in player facing messages. */
    public enum Family {
        ARMOR("盔甲"),
        WEAPON("武器"),
        TOOL("工具"),
        ORE_BLOCK("原矿方块"),
        SPAWN_EGG("刷怪蛋");

        private final String label;

        Family(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private TradeBlacklist() {
    }

    /** Which banned family this stack belongs to, or {@code null} when it may be traded normally. */
    public static Family familyOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return familyOf(stack.getItem());
    }

    /** Which banned family this item belongs to, or {@code null}. */
    public static Family familyOf(Item item) {
        if (item == null) {
            return null;
        }
        if (item instanceof SpawnEggItem) {
            return Family.SPAWN_EGG;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        String path = id == null ? "" : id.getPath();
        if (path.endsWith("_ore")) {
            return Family.ORE_BLOCK;
        }
        if (item instanceof ArmorItem || item instanceof HorseArmorItem) {
            return Family.ARMOR;
        }
        // BowItem / CrossbowItem both extend ProjectileWeaponItem
        if (item instanceof SwordItem || item instanceof ProjectileWeaponItem
                || item instanceof TridentItem || item instanceof ShieldItem) {
            return Family.WEAPON;
        }
        if (item instanceof DiggerItem || item instanceof ShearsItem || item instanceof FishingRodItem
                || item instanceof FlintAndSteelItem || item instanceof BrushItem
                || item instanceof FoodOnAStickItem) {
            return Family.TOOL;
        }
        return null;
    }

    /** Same check by registry id, for call sites that only hold an id string. */
    public static Family familyOfId(String itemId) {
        Item item = itemFor(itemId);
        return item == null ? null : familyOf(item);
    }

    public static boolean isBanned(ItemStack stack) {
        return familyOf(stack) != null;
    }

    public static boolean isBannedId(String itemId) {
        return familyOfId(itemId) != null;
    }

    /** Player facing reason, e.g. "§c盔甲已被服务器永久禁止交易（既不能买，也不能卖）". */
    public static String message(Family family) {
        return "§c" + (family == null ? "该物品类别" : family.label())
                + "已被服务器永久禁止交易（既不能买，也不能卖）";
    }

    /** The same message for a stack, or an empty string when the stack is allowed. */
    public static String reasonFor(ItemStack stack) {
        Family family = familyOf(stack);
        return family == null ? "" : message(family);
    }

    private static Item itemFor(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        try {
            ResourceLocation id = ResourceLocation.tryParse(itemId.toLowerCase(java.util.Locale.ROOT));
            return id == null || !BuiltInRegistries.ITEM.containsKey(id) ? null : BuiltInRegistries.ITEM.get(id);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
