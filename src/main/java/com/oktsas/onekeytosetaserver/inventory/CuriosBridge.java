package com.oktsas.onekeytosetaserver.inventory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Accessory-slot isolation for Curios.
 *
 * <p>This class references Curios types directly, so it is only ever touched when the mod list says Curios
 * is loaded ({@code ModList.get().isLoaded("curios")}). Without Curios the class is never resolved and the
 * mod keeps working on a plain Forge server.</p>
 *
 * <h2>Why the codec is written here and not taken from Curios</h2>
 *
 * <p>0.2.4 read and wrote accessory slots with {@code ICuriosItemHandler#saveInventory(boolean)} and
 * {@code #loadInventory(ListTag)}. Two things were wrong with that:</p>
 * <ul>
 *   <li><b>it clears while it saves.</b> {@code saveInventory(true)} empties the slots as a side effect, and
 *       the inventory snapshot code called it on the "first visit" path too - so simply entering a managed
 *       world silently stripped a player's accessories (and the next snapshot then wrote that empty state
 *       over the stored one).</li>
 *   <li><b>it can fail without saying so.</b> {@code loadInventory} skips slots whose type or index it does
 *       not recognise and hands those stacks to {@code loseInvalidStack}, i.e. the items are gone and the
 *       caller has no way to notice.</li>
 * </ul>
 *
 * <p>What is used instead is the pair Curios uses for its own player data:
 * {@code IDynamicStackHandler#serializeNBT()} / {@code #deserializeNBT(CompoundTag)}, stored under the same
 * {@code Stacks} / {@code Cosmetics} keys the old format used. That makes the pool file layout unchanged
 * (so files written by 0.2.0 - 0.2.4 stay readable) while every individual stack is placed by
 * {@link #apply(ServerPlayer, ListTag) myself}, which can <b>report</b> what it could not place instead of
 * throwing it away.</p>
 */
final class CuriosBridge {

    /** Key of one slot type inside the {@code curios} list of a pool snapshot. */
    static final String KEY_IDENTIFIER = "Identifier";
    /** Legacy key used by 0.2.0 - 0.2.4 snapshots; understood on read, never written. */
    static final String KEY_LEGACY_IDENTIFIER = "Slot";
    static final String KEY_STACKS = "Stacks";
    static final String KEY_COSMETICS = "Cosmetics";

    private CuriosBridge() {
    }

    /**
     * Copies the accessory slots into a snapshot. <b>Nothing is removed from the player.</b>
     *
     * @return one compound per slot type, or {@code null} when the player has no Curios capability at all
     */
    static ListTag snapshot(ServerPlayer player) {
        return CuriosApi.getCuriosInventory(player)
                .map(handler -> {
                    ListTag entries = new ListTag();
                    for (Map.Entry<String, ICurioStacksHandler> entry : entriesOf(handler)) {
                        ICurioStacksHandler stacksHandler = entry.getValue();
                        if (stacksHandler == null) {
                            continue;
                        }
                        CompoundTag tag = new CompoundTag();
                        tag.putString(KEY_IDENTIFIER, entry.getKey());
                        IDynamicStackHandler stacks = stacksHandler.getStacks();
                        if (stacks != null) {
                            tag.put(KEY_STACKS, stacks.serializeNBT());
                        }
                        IDynamicStackHandler cosmetics = stacksHandler.getCosmeticStacks();
                        if (cosmetics != null) {
                            tag.put(KEY_COSMETICS, cosmetics.serializeNBT());
                        }
                        entries.add(tag);
                    }
                    return entries;
                })
                .orElse(null);
    }

    /**
     * Puts a snapshot back into the accessory slots, one stack at a time.
     *
     * <p>An occupied destination slot is never overwritten: the incoming stack is reported back instead, so
     * the caller can keep it (the pool loader mails those). The same happens for a slot type the player no
     * longer has and for an index past the end of the slot.</p>
     *
     * @return the stacks that could <b>not</b> be equipped; never null, empty when everything was placed
     */
    static List<ItemStack> apply(ServerPlayer player, ListTag entries) {
        List<ItemStack> leftOver = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return leftOver;
        }
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entry = entries.getCompound(i);
                String identifier = identifierOf(entry);
                ICurioStacksHandler stacksHandler = identifier.isEmpty() ? null
                        : handler.getStacksHandler(identifier).orElse(null);
                if (stacksHandler == null) {
                    // the slot type is gone (mod removed / data pack changed) or was never recorded: keep the
                    // items, do not drop them
                    leftOver.addAll(itemsOf(entry.getCompound(KEY_STACKS)));
                    leftOver.addAll(itemsOf(entry.getCompound(KEY_COSMETICS)));
                    continue;
                }
                leftOver.addAll(fill(stacksHandler.getStacks(), entry.getCompound(KEY_STACKS)));
                IDynamicStackHandler cosmetics = stacksHandler.getCosmeticStacks();
                if (cosmetics != null) {
                    leftOver.addAll(fill(cosmetics, entry.getCompound(KEY_COSMETICS)));
                }
                // NOTE: the slot count is deliberately left alone. Curios derives it from the slot type plus
                // the modifier of whatever is equipped, so forcing a stored size back on would either invent
                // slots the player has no item for or be undone by the next update() - and a shrunk handler
                // throws the tail slots away. Stacks that do not fit are reported instead (see the class doc).
            }
            handler.clearCachedSlotModifiers();
        });
        return leftOver;
    }

    /** Empties the accessory slots without keeping the items (leaving a pool / first visit to an isolated one). */
    static void clear(ServerPlayer player) {
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
            for (ICurioStacksHandler stacksHandler : valuesOf(handler)) {
                if (stacksHandler == null) {
                    continue;
                }
                empty(stacksHandler.getStacks());
                empty(stacksHandler.getCosmeticStacks());
            }
        });
    }

    /** Number of non-empty stacks a snapshot holds; for logging and for the round-trip check. */
    static int countItems(ListTag entries) {
        int count = 0;
        if (entries == null) {
            return 0;
        }
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            count += itemsOf(entry.getCompound(KEY_STACKS)).size();
            count += itemsOf(entry.getCompound(KEY_COSMETICS)).size();
        }
        return count;
    }

    /** Non-empty stacks of the player's accessory slots right now; for logging and for the round-trip check. */
    static List<ItemStack> items(ServerPlayer player) {
        List<ItemStack> items = new ArrayList<>();
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
            for (ICurioStacksHandler stacksHandler : valuesOf(handler)) {
                if (stacksHandler == null) {
                    continue;
                }
                items.addAll(itemsOf(stacksHandler.getStacks()));
                items.addAll(itemsOf(stacksHandler.getCosmeticStacks()));
            }
        });
        return items;
    }

    /** Slot identifier -> size, for the headless round-trip test and the operator report. */
    static Map<String, Integer> slots(ServerPlayer player) {
        Map<String, Integer> slots = new java.util.LinkedHashMap<>();
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
            for (ICurioStacksHandler stacksHandler : valuesOf(handler)) {
                if (stacksHandler == null || stacksHandler.getIdentifier() == null) {
                    continue;
                }
                slots.put(stacksHandler.getIdentifier(), stacksHandler.getSlots());
            }
        });
        return slots;
    }

    /** Puts one stack into one slot; used by the self test and by nothing else. */
    static boolean set(ServerPlayer player, String identifier, int index, ItemStack stack) {
        return CuriosApi.getCuriosInventory(player)
                .map(handler -> handler.getStacksHandler(identifier)
                        .map(stacksHandler -> {
                            IDynamicStackHandler stacks = stacksHandler.getStacks();
                            if (index < 0 || index >= stacks.getSlots()) {
                                return false;
                            }
                            stacks.setStackInSlot(index, stack);
                            return true;
                        })
                        .orElse(false))
                .orElse(false);
    }

    /**
     * Tells the client what the accessory slots now hold.
     *
     * <p>Without this the server-side restore is invisible: the accessory bar is drawn from the client's own
     * copy, which Curios only refreshes with {@code SPacketSyncStack} (at login, at slot changes). 0.2.4
     * never sent one, so a player could wear a full set of accessories that the game refused to show until
     * the next login - which is exactly the "the bar is empty until I relog" report.</p>
     */
    static void sync(ServerPlayer player) {
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
            for (Map.Entry<String, ICurioStacksHandler> entry : entriesOf(handler)) {
                ICurioStacksHandler stacksHandler = entry.getValue();
                if (stacksHandler == null) {
                    continue;
                }
                IDynamicStackHandler stacks = stacksHandler.getStacks();
                if (stacks != null) {
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        sync(player, entry.getKey(), i, stacks.getStackInSlot(i),
                                top.theillusivec4.curios.common.network.server.sync.SPacketSyncStack.HandlerType
                                        .EQUIPMENT);
                    }
                }
                IDynamicStackHandler cosmetics = stacksHandler.getCosmeticStacks();
                if (cosmetics != null) {
                    for (int i = 0; i < cosmetics.getSlots(); i++) {
                        sync(player, entry.getKey(), i, cosmetics.getStackInSlot(i),
                                top.theillusivec4.curios.common.network.server.sync.SPacketSyncStack.HandlerType
                                        .COSMETIC);
                    }
                }
            }
        });
    }

    private static void sync(ServerPlayer player, String identifier, int index, ItemStack stack,
                             top.theillusivec4.curios.common.network.server.sync.SPacketSyncStack.HandlerType type) {
        try {
            top.theillusivec4.curios.common.network.NetworkHandler.INSTANCE.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new top.theillusivec4.curios.common.network.server.sync.SPacketSyncStack(player.getId(),
                            identifier, index, stack, type, new CompoundTag()));
        } catch (Throwable ignored) {
            // no client / channel not registered: the slots stay correct on the server, the client catches up
            // with the next login sync
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Places the stacks of one saved handler; returns the ones that did not fit. */
    private static List<ItemStack> fill(IDynamicStackHandler destination, CompoundTag saved) {
        List<ItemStack> failed = new ArrayList<>();
        if (destination == null || saved == null || saved.isEmpty()) {
            return failed;
        }
        // the slot count is never grown here - see the note in apply()
        ListTag items = saved.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemTag = items.getCompound(i);
            int slot = itemTag.contains("Slot", Tag.TAG_ANY_NUMERIC) ? itemTag.getInt("Slot") : i;
            ItemStack stack = ItemStack.of(itemTag);
            if (stack.isEmpty()) {
                continue;
            }
            if (slot < 0 || slot >= destination.getSlots() || !destination.getStackInSlot(slot).isEmpty()) {
                failed.add(stack);
                continue;
            }
            destination.setStackInSlot(slot, stack);
        }
        return failed;
    }

    private static void empty(IDynamicStackHandler handler) {
        if (handler == null) {
            return;
        }
        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) {
                handler.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
    }

    /** Every non-empty stack of one saved handler. */
    private static List<ItemStack> itemsOf(IDynamicStackHandler handler) {
        List<ItemStack> items = new ArrayList<>();
        if (handler == null) {
            return items;
        }
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                items.add(stack);
            }
        }
        return items;
    }

    /** Every non-empty stack inside one {@code {Size, Items}} compound (used for survivors of a lost slot type). */
    private static List<ItemStack> itemsOf(CompoundTag saved) {
        List<ItemStack> items = new ArrayList<>();
        if (saved == null || saved.isEmpty()) {
            return items;
        }
        ListTag list = saved.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.of(list.getCompound(i));
            if (!stack.isEmpty()) {
                items.add(stack);
            }
        }
        return items;
    }

    private static String identifierOf(CompoundTag entry) {
        if (entry.contains(KEY_IDENTIFIER, Tag.TAG_STRING)) {
            return entry.getString(KEY_IDENTIFIER);
        }
        if (entry.contains(KEY_LEGACY_IDENTIFIER, Tag.TAG_STRING)) {
            return entry.getString(KEY_LEGACY_IDENTIFIER);
        }
        return "";
    }

    /** A copy of the handler map: {@code getStacks()} may update sizes while iterating. */
    private static List<Map.Entry<String, ICurioStacksHandler>> entriesOf(ICuriosItemHandler handler) {
        return new ArrayList<>(handler.getCurios().entrySet());
    }

    private static List<ICurioStacksHandler> valuesOf(ICuriosItemHandler handler) {
        return new ArrayList<>(handler.getCurios().values());
    }
}
