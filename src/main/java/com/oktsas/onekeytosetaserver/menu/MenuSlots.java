package com.oktsas.onekeytosetaserver.menu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.world.MvWorld;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared plumbing for our chest GUIs.
 *
 * <h2>Server and client must agree on the slot list</h2>
 * <p>A vanilla client builds a {@code ChestMenu} for a {@code GENERIC_9xN} menu type, and that menu always
 * contains the container rows <b>plus 27 inventory slots and 9 hotbar slots = 36 player slots</b>. A server menu
 * that registers a different amount disagrees with the client, and everything that follows from that is a bug:
 * a shift click can move a display item into the client's own inventory without the server noticing (the
 * "you can take items out of the chest GUI" report), and a whole-container packet that carries more stacks than
 * the client has slots makes the client throw an {@code IndexOutOfBoundsException} on open.</p>
 *
 * <p>The player inventory holds <b>36</b> stacks in total (indices 0-8 hotbar, 9-35 main). An earlier version
 * of this class counted "36 + 9" and registered 45 player slots, which produced exactly that mismatch
 * (99 server slots against 90 client slots) - hence the explicit sanity check in
 * {@link #playerInventorySlot(Inventory, int, int)}.</p>
 *
 * <h2>Never destroy what the player is holding</h2>
 * <p>A click on a display slot is answered with the server's own truth, sent as one whole-container packet
 * (slots <b>and</b> the item on the cursor). Clearing the cursor instead - which this class used to do - deletes
 * whatever a legitimate click on the player's own inventory had just picked up. The item a player holds while a
 * GUI is open is therefore never touched; vanilla's {@code AbstractContainerMenu#removed} hands it back when the
 * screen closes.</p>
 *
 * <p>{@code multiverse/menu-config.json -> showPlayerInventory=false} switches to option B: no player slots at
 * all (the GUI becomes a pure read-only screen, at the cost of the player not being able to touch their own
 * inventory while it is open). Click handling adapts automatically - when there are no player slots a click is
 * never forwarded to the vanilla logic, so option B stays safe too.</p>
 */
public final class MenuSlots {

    /** Operator tunables; one file, loaded at startup. */
    public static final class Config {
        public boolean showPlayerInventory = true;
    }

    private static Config config = new Config();
    private static Path configFile;

    private MenuSlots() {
    }

    public static void loadConfig(Path gameDir) {
        configFile = gameDir.resolve(MvWorld.CONFIG_DIR).resolve("menu-config.json");
        try {
            if (Files.isRegularFile(configFile)) {
                Config loaded = new Gson().fromJson(Files.readString(configFile, StandardCharsets.UTF_8),
                        Config.class);
                if (loaded != null) {
                    config = loaded;
                }
            }
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Menu] could not read {}", configFile, t);
        }
        saveConfig();
        OneKeyToSetAServer.LOGGER.info("[Menu] player inventory inside GUIs: {}",
                config.showPlayerInventory ? "shown (A)" : "hidden (B)");
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
            OneKeyToSetAServer.LOGGER.error("[Menu] could not write {}", configFile, t);
        }
    }

    public static boolean showPlayerInventory() {
        return config.showPlayerInventory;
    }

    public static void setShowPlayerInventory(boolean value) {
        config.showPlayerInventory = value;
        saveConfig();
    }

    /**
     * Registers one of the 36 player slots at the same index position (and the same coordinates) vanilla's
     * {@code ChestMenu} uses, so server and client agree.
     *
     * <p>{@code addSlot} is protected, so this factory is called from the menu's own constructor.</p>
     *
     * @param index 0-26 = the main inventory (indices 9-35), 27-35 = the hotbar (indices 0-8)
     * @param rows  how many container rows the menu has already registered
     */
    public static Slot playerInventorySlot(Inventory inventory, int index, int rows) {
        int top = 18 + rows * 18;
        if (index < 0 || index >= PLAYER_SLOTS) {
            // Loud in the log, harmless in game: a menu that asks for slot 36 would otherwise silently mirror a
            // slot the client does not have - the exact class of bug this whole class exists to prevent.
            OneKeyToSetAServer.LOGGER.error("[Menu] player slot {} does not exist (0-{}), clamping",
                    index, PLAYER_SLOTS - 1);
            index = Math.max(0, Math.min(PLAYER_SLOTS - 1, index));
        }
        if (index < 27) {
            int row = index / 9;
            int column = index % 9;
            return new Slot(inventory, column + row * 9 + 9, 8 + column * 18, top + row * 18);
        }
        int column = index - 27;
        return new Slot(inventory, column, 8 + column * 18, top + 58);
    }

    /**
     * The number of player slots {@link #playerInventorySlot} knows how to place: 27 inventory + 9 hotbar.
     * Vanilla's {@code ChestMenu} registers exactly this many, so anything else desynchronises the layout.
     */
    public static final int PLAYER_SLOTS = 36;

    /** Index of the first player slot, or -1 when player slots are disabled (option B). */
    public static int playerSlotStart(AbstractContainerMenu menu, int displaySlots) {
        if (!config.showPlayerInventory || menu == null) {
            return -1;
        }
        return displaySlots;
    }

    /**
     * True when {@code slotId} hits the 27 inventory / 9 hotbar slots we registered after the display ones.
     * The size check keeps this usable as a bounds test even on a menu that has no player slots.
     */
    public static boolean isPlayerSlot(AbstractContainerMenu menu, int slotId, int displaySlots) {
        return config.showPlayerInventory && menu != null && slotId >= displaySlots
                && slotId < Math.min(displaySlots + PLAYER_SLOTS, menu.slots.size());
    }

    /**
     * Whether a shift click from {@code index} may reach the vanilla quick-move logic. Items that start on a
     * display slot are never moved (the default implementation returns nothing anyway), and with option B the
     * layouts differ so nothing is moved at all.
     */
    public static boolean allowQuickMove(int index, int displaySlots) {
        return config.showPlayerInventory && index >= displaySlots;
    }

    /**
     * The quick-move answer for our display menus: nothing.
     *
     * <p>{@code quickMoveStack} is abstract in this Forge runtime, so every menu has to implement it, but none
     * of them should ever move anything: shift clicking a display item must not hand out a copy, and shift
     * clicking your own item has nowhere sensible to go because the top rows are labels rather than storage.
     * Returning nothing is also what keeps a shift click from becoming a dupe - the client predicts the move,
     * {@link #resync} takes it back.</p>
     */
    public static ItemStack noQuickMove() {
        return ItemStack.EMPTY;
    }

    /**
     * Whether the click on {@code slotId} may be forwarded to the vanilla click logic.
     *
     * <p>Qualifies: a click outside the window ({@code slotId < 0}, which vanilla answers by dropping the stack
     * on the cursor) and, in option A, the player's own slots. Everything else is a label. Option B registers no
     * player slots while the client still sends clicks for its own inventory, so an out-of-range slot must never
     * reach the vanilla logic.</p>
     *
     * <p>Menus that really do take items (the trash can and the sell area) call the vanilla logic for their own
     * storage slots themselves - see {@code ShopMenus}.</p>
     */
    public static boolean allowVanillaClick(int slotId, int displaySlots) {
        if (slotId < 0) {
            return true;
        }
        return config.showPlayerInventory && slotId >= displaySlots;
    }

    /**
     * Undoes whatever the client predicted locally: the whole container (every slot <b>and</b> the stack on the
     * cursor) is re-sent from the server's authoritative state.
     *
     * <p>Two properties matter here and both were learned the hard way:</p>
     * <ul>
     *   <li>the packet is the <b>whole</b> container, including the carried stack - a per-slot diff cannot fix a
     *       cursor the client invented, and {@code ClientboundContainerSetSlotPacket} cannot even address the
     *       cursor (slot -1 would index past the slot list);</li>
     *   <li>the server's carried stack is sent as it is, never blanked. Blanking it destroyed whatever the
     *       player had just picked up from their own inventory (the "menu eats my items" report).</li>
     * </ul>
     *
     * <p>{@code sendAllDataToRemote()} does exactly this: it refreshes the menu's shadow copy and asks the
     * synchronizer for one {@code ClientboundContainerSetContentPacket} carrying all slots plus the cursor. It
     * is a no-op for a player without a connection (the headless self test uses a {@code FakePlayer}).</p>
     */
    public static void resync(AbstractContainerMenu menu, Player player) {
        if (menu == null || !(player instanceof ServerPlayer serverPlayer) || serverPlayer.connection == null) {
            return;
        }
        try {
            menu.sendAllDataToRemote();
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.debug("[Menu] could not resync {}: {}", menu.getClass().getSimpleName(),
                    t.toString());
        }
    }

    /**
     * The exact slot count the client builds for this menu, used by the self test to prove that both sides agree
     * instead of trusting a hard coded number.
     *
     * @return the client side slot count, or -1 when the menu type cannot be instantiated here
     */
    public static int clientSlotCount(AbstractContainerMenu menu, Player player) {
        if (menu == null || player == null) {
            return -1;
        }
        try {
            AbstractContainerMenu client = menu.getType().create(menu.containerId, player.getInventory());
            return client == null ? -1 : client.slots.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Replays what the client does with the packet {@link #resync} sends, so the headless self test can prove
     * that the client will not index past the end of its slot list.
     *
     * @return null when it worked, otherwise the failure text
     */
    public static String clientApplyCheck(AbstractContainerMenu menu, Player player) {
        if (menu == null || player == null) {
            return "FAILED(no menu)";
        }
        try {
            AbstractContainerMenu client = menu.getType().create(menu.containerId, player.getInventory());
            if (client == null) {
                return "FAILED(client menu type " + menu.getType() + " produced nothing)";
            }
            int serverSlots = menu.slots.size();
            int clientSlots = client.slots.size();
            // this is literally ClientPacketListener#handleContainerContent -> initializeContents
            client.initializeContents(menu.incrementStateId(), menu.getItems(), menu.getCarried());
            return serverSlots == clientSlots ? null : "ok(size " + serverSlots + "<" + clientSlots + ")";
        } catch (Throwable t) {
            return "FAILED(" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
    }
}
