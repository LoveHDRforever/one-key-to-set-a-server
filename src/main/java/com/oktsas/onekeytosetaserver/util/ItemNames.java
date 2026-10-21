package com.oktsas.onekeytosetaserver.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * Item names for messages and GUIs.
 *
 * <p>Never glue the raw id into a literal when showing an item to a player: a {@code Component} built from the
 * item's own translation key is translated by the <b>client</b>, so a Chinese client reads "石头" while an
 * English one reads "Stone" - without the server knowing anything about languages. The raw id is still worth
 * showing to operators, which is what {@link #withId} and {@link #idLine} are for.</p>
 */
public final class ItemNames {

    private ItemNames() {
    }

    /** The translated name of an item id, or the id itself when no such item exists. */
    public static Component of(String itemId) {
        ItemStack stack = stackOf(itemId);
        return stack.isEmpty() ? Component.literal(String.valueOf(itemId)) : stack.getHoverName();
    }

    /** "石头 §8(minecraft:stone)" - translated name plus the raw id. */
    public static Component withId(String itemId) {
        MutableComponent name = of(itemId).copy();
        return name.append(Component.literal(" §8(" + itemId + ")"));
    }

    /** The grey raw id line used inside GUIs. */
    public static Component idLine(String itemId) {
        return Component.literal("§8" + itemId);
    }

    /** An item stack by id, or an empty stack when the id is unknown. */
    public static ItemStack stackOf(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return ItemStack.EMPTY;
        }
        try {
            ResourceLocation id = ResourceLocation.tryParse(itemId.toLowerCase(Locale.ROOT));
            Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
            return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    /** The translated name of a stack, or its raw id when the stack is empty. */
    public static Component of(ItemStack stack) {
        return stack == null || stack.isEmpty()
                ? Component.literal("（空）")
                : stack.getHoverName();
    }

    /**
     * "石头 ×64" - the translated name plus a plain count. The count is a literal on purpose: the item name
     * still translates on the client, while the number stays exactly what the server sent.
     */
    public static Component withCount(ItemStack stack) {
        return of(stack).copy().append(Component.literal(" ×" + (stack == null ? 0 : stack.getCount())));
    }

    /** The display name for a stack, falling back to a stored label when the stack cannot be decoded. */
    public static Component displayOr(ItemStack stack, String fallback) {
        return stack == null || stack.isEmpty()
                ? Component.literal(fallback == null ? "" : fallback)
                : stack.getHoverName();
    }
}
