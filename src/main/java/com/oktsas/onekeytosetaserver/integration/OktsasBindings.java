package com.oktsas.onekeytosetaserver.integration;

import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.domain.Domain;
import com.oktsas.onekeytosetaserver.domain.DomainManager;
import com.oktsas.onekeytosetaserver.economy.MoneyService;
import com.oktsas.onekeytosetaserver.market.MarketManager;
import com.oktsas.onekeytosetaserver.shop.ShopManager;
import com.oktsas.onekeytosetaserver.world.MvWorld;
import com.oktsas.onekeytosetaserver.world.MvWorldManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The object scripts see as {@code oktsas}.
 *
 * <p>Every method is defensive: a script that asks for a world that does not exist gets {@code false} or an
 * empty string, never a stack trace that kills the script. Players are addressed by name (online) or by
 * UUID string, which keeps the API usable from both {@code ServerEvents} and plain command scripts.</p>
 */
public final class OktsasBindings {

    public OktsasBindings() {
    }

    private static MinecraftServer server() {
        return net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
    }

    /** UUID of an online player name, or of a UUID string. Null when neither matches. */
    private static UUID resolve(String player) {
        if (player == null || player.isBlank()) {
            return null;
        }
        MinecraftServer server = server();
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayerByName(player);
            if (online != null) {
                return online.getUUID();
            }
        }
        try {
            return UUID.fromString(player.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ worlds

    public String version() {
        return "0.1.0-phase0";
    }

    /** Folder names of every managed world. */
    public List<String> worlds() {
        List<String> result = new ArrayList<>();
        for (MvWorld world : MvWorldManager.all()) {
            result.add(world.folder);
        }
        return result;
    }

    /** Folder names of the managed worlds that are currently loaded. */
    public List<String> loadedWorlds() {
        List<String> result = new ArrayList<>();
        for (MvWorld world : MvWorldManager.all()) {
            if (MvWorldManager.levelOf(world) != null) {
                result.add(world.folder);
            }
        }
        return result;
    }

    public boolean isLoaded(String folder) {
        MvWorld world = MvWorldManager.byName(folder);
        return world != null && MvWorldManager.levelOf(world) != null;
    }

    /** One-line summary; empty string when the world is unknown. */
    public String worldInfo(String folder) {
        MvWorld world = MvWorldManager.byName(folder);
        if (world == null) {
            return "";
        }
        return world.displayName() + " | " + OneKeyToSetAServer.MODID + ":" + world.dimensionPath
                + " | env=" + world.environment + " gen=" + world.generator + " seed=" + world.seed
                + " | pvp=" + world.pvp + " gamemode=" + world.gameMode + " border=" + world.borderSize
                + " | separateInventory=" + world.separateInventory + " autoReset=" + world.autoReset
                + " | loaded=" + (MvWorldManager.levelOf(world) != null);
    }

    public boolean loadWorld(String folder) {
        try {
            MvWorld world = MvWorldManager.byName(folder);
            if (world == null && MvWorldManager.gameDir() != null
                    && java.nio.file.Files.isDirectory(MvWorldManager.gameDir().resolve(folder))) {
                world = MvWorldManager.create(folder, "NORMAL", 0L, "DEFAULT");
            }
            return world != null && MvWorldManager.loadRuntime(world);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[KubeJS] loadWorld({}) failed", folder, t);
            return false;
        }
    }

    public boolean unloadWorld(String folder) {
        try {
            MvWorld world = MvWorldManager.byName(folder);
            return world != null && MvWorldManager.unload(world);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[KubeJS] unloadWorld({}) failed", folder, t);
            return false;
        }
    }

    public boolean resetWorld(String folder) {
        try {
            MvWorld world = MvWorldManager.byName(folder);
            return world != null && MvWorldManager.reset(world);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[KubeJS] resetWorld({}) failed", folder, t);
            return false;
        }
    }

    public boolean deleteWorld(String folder) {
        try {
            MvWorld world = MvWorldManager.byName(folder);
            return world != null && MvWorldManager.delete(world);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[KubeJS] deleteWorld({}) failed", folder, t);
            return false;
        }
    }

    /** Creates and loads a world; returns the folder name, or an empty string on failure. */
    public String createWorld(String folder, String environment, String generator, long seed) {
        try {
            MvWorld world = MvWorldManager.create(folder, environment == null ? "NORMAL" : environment, seed,
                    generator == null ? "DEFAULT" : generator);
            return world == null ? "" : world.folder;
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[KubeJS] createWorld({}) failed", folder, t);
            return "";
        }
    }

    /** Sets one property through {@code /mv modify}; returns false when the world or the server is missing. */
    public boolean setWorldProperty(String folder, String property, String value) {
        MinecraftServer server = server();
        if (server == null || MvWorldManager.byName(folder) == null || property == null || value == null) {
            return false;
        }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "mv modify " + folder + " " + property + " " + value);
        return true;
    }

    // ------------------------------------------------------------------ money

    public double money(String player) {
        UUID id = resolve(player);
        return id == null || server() == null ? 0.0D : MoneyService.get(server(), id);
    }

    public boolean setMoney(String player, double amount) {
        UUID id = resolve(player);
        if (id == null || server() == null) {
            return false;
        }
        MoneyService.set(server(), id, amount);
        return true;
    }

    public boolean addMoney(String player, double amount) {
        UUID id = resolve(player);
        if (id == null || server() == null) {
            return false;
        }
        MoneyService.deposit(server(), id, amount);
        return true;
    }

    public boolean takeMoney(String player, double amount) {
        UUID id = resolve(player);
        return id != null && server() != null && MoneyService.withdraw(server(), id, amount);
    }

    public boolean transferMoney(String from, String to, double amount) {
        UUID a = resolve(from);
        UUID b = resolve(to);
        return a != null && b != null && server() != null && MoneyService.transfer(server(), a, b, amount);
    }

    // ------------------------------------------------------------------ domains

    public List<String> domains() {
        List<String> result = new ArrayList<>();
        for (Domain domain : DomainManager.all()) {
            result.add(domain.id + " | " + domain.name + " | " + domain.ownerName + " | " + domain.world
                    + " | " + domain.sizeX() + "x" + domain.sizeZ());
        }
        return result;
    }

    /** Name of the domain at a position, or an empty string. */
    public String domainAt(String world, int x, int y, int z) {
        MinecraftServer server = server();
        if (server == null || world == null) {
            return "";
        }
        net.minecraft.server.level.ServerLevel level =
                server.getLevel(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        new net.minecraft.resources.ResourceLocation(
                                world.contains(":") ? world : OneKeyToSetAServer.MODID + ":" + world)));
        if (level == null) {
            return "";
        }
        Domain domain = DomainManager.at(level, new net.minecraft.core.BlockPos(x, y, z));
        return domain == null ? "" : domain.name;
    }

    public boolean deleteDomain(String idOrName) {
        if (idOrName == null) {
            return false;
        }
        Domain domain = DomainManager.byId(idOrName);
        if (domain == null) {
            for (Domain candidate : DomainManager.all()) {
                if (candidate.name.equalsIgnoreCase(idOrName)) {
                    domain = candidate;
                    break;
                }
            }
        }
        return domain != null && DomainManager.delete(domain);
    }

    // ------------------------------------------------------------------ shop / market

    public double shopPrice(String itemId) {
        return itemId == null ? 0.0D : ShopManager.priceOf(itemId);
    }

    public boolean setShopPrice(String itemId, double price) {
        if (itemId == null || price < 0.0D) {
            return false;
        }
        ShopManager.setBuyPrice(itemId, price);
        return true;
    }

    public boolean removeFromShop(String itemId) {
        return itemId != null && ShopManager.removeItem(itemId);
    }

    public boolean setRecyclePrice(String itemId, double price) {
        if (itemId == null || price < 0.0D) {
            return false;
        }
        ShopManager.setRecyclePrice(itemId, price);
        return true;
    }

    public List<String> marketListings() {
        List<String> result = new ArrayList<>();
        for (MarketManager.Listing listing : MarketManager.listings()) {
            result.add(listing.id + " | " + listing.label + " | " + listing.price + " | "
                    + listing.sellerName + " | " + MarketManager.durationLabel(listing.hours));
        }
        return result;
    }

    public boolean banMarketItem(String itemId, boolean banned) {
        if (itemId == null) {
            return false;
        }
        if (banned) {
            MarketManager.blacklistAdd(server(), itemId);
            return true;
        }
        return MarketManager.blacklistRemove(itemId);
    }

    public List<String> marketBlacklist() {
        return new ArrayList<>(MarketManager.blacklist());
    }

    public int mailboxSize(String player) {
        UUID id = resolve(player);
        return id == null ? 0 : MarketManager.mailboxSize(id);
    }

    /** Puts {@code count} of an item into a player's mailbox; false when the item id is unknown. */
    public boolean mailItem(String player, String itemId, int count) {
        UUID id = resolve(player);
        MinecraftServer server = server();
        if (id == null || server == null || itemId == null || count <= 0) {
            return false;
        }
        try {
            net.minecraft.resources.ResourceLocation key = new net.minecraft.resources.ResourceLocation(
                    itemId.contains(":") ? itemId : "minecraft:" + itemId);
            net.minecraft.world.item.Item item =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                return false;
            }
            MarketManager.sendMail(server, id, new ItemStack(item, count));
            return true;
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[KubeJS] mailItem({}, {}) failed", player, itemId, t);
            return false;
        }
    }

    @Override
    public String toString() {
        return "oktsas(" + version() + ")";
    }
}
