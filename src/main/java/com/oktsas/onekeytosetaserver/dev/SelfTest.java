package com.oktsas.onekeytosetaserver.dev;

import com.mojang.authlib.GameProfile;
import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.inventory.PlayerInventoryStore;
import com.oktsas.onekeytosetaserver.world.MvWorld;
import com.oktsas.onekeytosetaserver.world.MvWorldManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Development-only end-to-end check of the command surface. A headless dev server started by Gradle has no
 * console stdin, so the commands are driven through the dispatcher here. Kept behind a constant so the shipped
 * jar does nothing by itself.
 */
public final class SelfTest {
    /** Enabled only when {@code -Doktsas.selftest=true} is passed (the dev run configurations do that). */
    public static final boolean ENABLED = Boolean.getBoolean("oktsas.selftest");

    private SelfTest() {
    }

    public static void run(MinecraftServer server) {
        if (!ENABLED) {
            return;
        }
        try {
            CommandSourceStack source = server.createCommandSourceStack();
            OneKeyToSetAServer.LOGGER.info("[SELFTEST] ---- begin ----");
            // A fresh dev run has no managed world yet; most tests need resource_world to be online.
            step(server, source, "mv create resource_world NORMAL DEFAULT 12345");
            step(server, source, "mv list");
            step(server, source, "mv info resource_world");
            step(server, source, "mv modify resource_world pvp false");
            step(server, source, "mv modify resource_world autoReset true");
            step(server, source, "mv modify resource_world separateInventory true");
            inventoryPoolTest(server);
            curiosRoundTripTest(server);
            menuTest(server);
            menuEditorTest(server);
            domainTest(server);
            domainReloadTest(server);
            shopTest(server);
            shopGuiTest(server);
            shopLayoutTest(server);
            shopSellTest(server);
            essentialsTest(server);
            chatButtonTest(server);
            blockTest(server);
            sellGuiTest(server);
            storageTest(server);
            marketTest(server);
            storeLockTest(server);
            mailOutTest(server);
            inventoryCommandTest(server);
            marketGuiTest(server);
            marketFilterTest(server);
            menuSlotTest(server);
            extrasTest(server);
            tradeBlacklistTest(server);
            nbtShopTest(server);
            shopUpgradeTest(server);
            sellAllSlotsTest(server);
            spawnTest(server);
            step(server, source, "mv create selftest_world FLAT DEFAULT 12345");
            spawnTest(server);
            step(server, source, "mv info selftest_world");
            step(server, source, "mv reset selftest_world");
            step(server, source, "mv unload selftest_world");
            step(server, source, "mv load selftest_world");
            step(server, source, "mv delete selftest_world");
            step(server, source, "mv info resource_world");
            step(server, source, "mv list");
            OneKeyToSetAServer.LOGGER.info("[SELFTEST] ---- end ----");
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] failed", t);
        }
    }

    /**
     * Exercises the per-world inventory pools on a fake player: an isolated world must take the items away and
     * the shared world must give them back. This is the part of the feature that can be verified headless.
     */
    /** Creates, persists, spawns and removes a command NPC (the rendering itself needs a real client). */
    /** Builds the world-list menu for a fake player so the definition -> container path is exercised. */
    /** Money + official shop: buy, then sell everything sellable at the recycle prices. */
    /**
     * Drives the shop GUI the same way the server does - by calling {@code clicked} - so the whole
     * category -> item -> quantity -> confirm chain is exercised without a client.
     */
    /** Confirms the four shop blocks and their recipes actually made it into the registries / data pack. */
    /** Sell area: sellable items turn into money, unsellable ones come back. */
    /** Free market: buy-out moves money (minus the duration fee) and the item; expiry fills the mailbox. */
    /** Deleting a world must mail the items stored in that world's inventory pool back to their owner. */
    /** Market screen: a listing must show up as an icon and buying it by clicking must work. */
    private static void marketGuiTest(MinecraftServer server) {
        try {
            FakePlayer seller = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_gui_seller".getBytes(StandardCharsets.UTF_8)), "oktsas_gui_seller"));
            FakePlayer buyer = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_gui_buyer".getBytes(StandardCharsets.UTF_8)), "oktsas_gui_buyer"));
            buyer.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, buyer.getUUID(), 500.0D);
            com.oktsas.onekeytosetaserver.market.MarketManager.clearAllForTest();

            com.oktsas.onekeytosetaserver.market.MarketManager.Listing listing =
                    com.oktsas.onekeytosetaserver.market.MarketManager.createForTest(
                            seller.getUUID(), "oktsas_gui_seller", new ItemStack(Items.EMERALD, 3), 60.0D, 6);

            com.oktsas.onekeytosetaserver.market.MarketMenu.MarketMenuInner menu =
                    com.oktsas.onekeytosetaserver.market.MarketMenu.create(1, buyer.getInventory(), buyer);
            int filled = 0;
            for (int i = 0; i < menu.slots.size(); i++) {
                if (!menu.getSlot(i).getItem().isEmpty()) {
                    filled++;
                }
            }
            // find the slot carrying this listing instead of assuming slot 0
            int target = -1;
            for (int i = 0; i < 45; i++) {
                ItemStack slotStack = menu.getSlot(i).getItem();
                if (slotStack.isEmpty()) {
                    continue;
                }
                net.minecraft.nbt.CompoundTag display = slotStack.getTagElement("display");
                if (display != null && display.getList("Lore", 8).toString().contains(listing.id)) {
                    target = i;
                    break;
                }
            }
            if (target >= 0) {
                menu.clicked(target, 0, net.minecraft.world.inventory.ClickType.PICKUP, buyer);
            }
            double left = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, buyer.getUUID());
            int emeralds = 0;
            for (int slot = 0; slot < buyer.getInventory().getContainerSize(); slot++) {
                ItemStack stack = buyer.getInventory().getItem(slot);
                if (stack.is(Items.EMERALD)) {
                    emeralds += stack.getCount();
                }
            }
            OneKeyToSetAServer.LOGGER.info("[SELFTEST] market gui: icons={} bought={} moneyLeft={} emeralds={}",
                    filled, com.oktsas.onekeytosetaserver.market.MarketManager.find(listing.id) == null,
                    left, emeralds);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] market gui test failed", t);
        }
    }

    private static void mailOutTest(MinecraftServer server) {
        try {
            com.oktsas.onekeytosetaserver.world.MvWorld world =
                    MvWorldManager.create("mailout_world", "NORMAL", 7L, "VOID");
            if (world == null) {
                OneKeyToSetAServer.LOGGER.info("[SELFTEST] mailout test skipped (world not created)");
                return;
            }
            FakePlayer owner = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_mail_owner".getBytes(StandardCharsets.UTF_8)), "oktsas_mail_owner"));
            owner.getInventory().clearContent();
            owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));

            java.nio.file.Path pool = MvWorldManager.folderOf(world)
                    .resolve(com.oktsas.onekeytosetaserver.world.MvWorld.CONFIG_DIR).resolve("inventories");
            com.oktsas.onekeytosetaserver.inventory.PlayerInventoryStore.save(owner, pool);
            owner.getInventory().clearContent();

            boolean deleted = MvWorldManager.delete(world);
            int inMail = com.oktsas.onekeytosetaserver.market.MarketManager.mailboxSize(owner.getUUID());
            int delivered = com.oktsas.onekeytosetaserver.market.MarketManager.deliverMail(owner);
            int diamonds = 0;
            for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
                ItemStack stack = owner.getInventory().getItem(slot);
                if (stack.is(Items.DIAMOND)) {
                    diamonds += stack.getCount();
                }
            }
            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] mailout: deleted={} queued={} delivered={} diamondsBack={}",
                    deleted, inMail, delivered, diamonds);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] mailout test failed", t);
        }
    }

    private static void marketTest(MinecraftServer server) {
        try {
            FakePlayer seller = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_seller".getBytes(StandardCharsets.UTF_8)), "oktsas_seller"));
            FakePlayer buyer = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_buyer".getBytes(StandardCharsets.UTF_8)), "oktsas_buyer"));
            seller.getInventory().clearContent();
            buyer.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.market.MarketManager.clearAllForTest();
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, seller.getUUID(), 0.0D);
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, buyer.getUUID(), 1000.0D);

            com.oktsas.onekeytosetaserver.market.MarketManager.Listing listing =
                    com.oktsas.onekeytosetaserver.market.MarketManager.createForTest(
                            seller.getUUID(), "oktsas_seller", new ItemStack(Items.DIAMOND, 2), 100.0D, 24);
            boolean bought = com.oktsas.onekeytosetaserver.market.MarketManager.buy(buyer, listing.id);
            double buyerLeft = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, buyer.getUUID());
            double sellerGot = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, seller.getUUID());
            int diamonds = 0;
            for (int slot = 0; slot < buyer.getInventory().getContainerSize(); slot++) {
                ItemStack stack = buyer.getInventory().getItem(slot);
                if (stack.is(Items.DIAMOND)) {
                    diamonds += stack.getCount();
                }
            }

            com.oktsas.onekeytosetaserver.market.MarketManager.createForTest(
                    seller.getUUID(), "oktsas_seller", new ItemStack(Items.GOLD_INGOT, 1), 50.0D, 1);
            com.oktsas.onekeytosetaserver.market.MarketManager.tick(server,
                    System.currentTimeMillis() + 2L * 3_600_000L);
            int inMail = com.oktsas.onekeytosetaserver.market.MarketManager.mailboxSize(seller.getUUID());
            int delivered = com.oktsas.onekeytosetaserver.market.MarketManager.deliverMail(seller);

            // ---- permanent listings (hours = 0) + the config knobs behind them -------------------
            com.oktsas.onekeytosetaserver.market.MarketConfig cfg =
                    com.oktsas.onekeytosetaserver.market.MarketManager.config();
            boolean configFileThere = java.nio.file.Files.isRegularFile(
                    net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                            .resolve("multiverse/market/config.json"));
            com.oktsas.onekeytosetaserver.market.MarketManager.Listing forever =
                    com.oktsas.onekeytosetaserver.market.MarketManager.createForTest(
                            seller.getUUID(), "oktsas_seller", new ItemStack(Items.NETHERITE_INGOT, 1), 77.0D, 0);
            // half a year later the permanent listing must still be there and must not have been mailed back
            com.oktsas.onekeytosetaserver.market.MarketManager.tick(server,
                    System.currentTimeMillis() + 500L * 3_600_000L);
            boolean permanentKept = com.oktsas.onekeytosetaserver.market.MarketManager.find(forever.id) != null;
            int mailAfterPermanent = com.oktsas.onekeytosetaserver.market.MarketManager.mailboxSize(seller.getUUID());
            boolean permanentFeeOk = com.oktsas.onekeytosetaserver.market.MarketManager.feeRate(0)
                    == cfg.permanentFeeRate;
            // a duration above config.maxHours is refused by sell() before the item is taken
            int beforeTooLong = com.oktsas.onekeytosetaserver.market.MarketManager.listings().size();
            boolean tooLongRefused = !com.oktsas.onekeytosetaserver.market.MarketManager.sell(
                    seller, new ItemStack(Items.DIRT, 1), 5.0D, cfg.maxHours + 1);
            boolean nothingAdded = com.oktsas.onekeytosetaserver.market.MarketManager.listings().size()
                    == beforeTooLong;
            // and with allowPermanent=false a 0-hour listing is refused too
            boolean allowPermanentSaved = cfg.allowPermanent;
            cfg.allowPermanent = false;
            boolean permanentRefused = !com.oktsas.onekeytosetaserver.market.MarketManager.sell(
                    seller, new ItemStack(Items.DIRT, 1), 5.0D, 0);
            cfg.allowPermanent = allowPermanentSaved;
            com.oktsas.onekeytosetaserver.market.MarketManager.cancel(seller, forever.id);

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] market: bought={} buyerLeft={} sellerGot={} diamonds={} expiredToMail={} "
                            + "delivered={} permanentKept={} mailAfterPermanent={} permanentFeeOk={} "
                            + "configFile={} maxHours={} allowPermanent={} defaultHours={} "
                            + "tooLongRefused={} nothingAdded={} permanentRefused={}",
                    bought, buyerLeft, sellerGot, diamonds, inMail, delivered, permanentKept, mailAfterPermanent,
                    permanentFeeOk, configFileThere, cfg.maxHours, cfg.allowPermanent, cfg.defaultHours,
                    tooLongRefused, nothingAdded, permanentRefused);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] market test failed", t);
        }
    }

    /**
     * The sell area: nothing is sold until the confirm button is pressed twice, closing without confirming
     * hands everything back, and unsellable items come back either way.
     */
    private static void sellGuiTest(MinecraftServer server) {
        try {
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_sell_probe".getBytes(StandardCharsets.UTF_8)), "oktsas_sell_probe"));
            probe.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, probe.getUUID(), 0.0D);

            // 1) put items in and just close: the shop must sell nothing and return everything
            com.oktsas.onekeytosetaserver.shop.ShopMenus.SellMenu aborted = com.oktsas.onekeytosetaserver.shop.ShopMenus
                    .createSell(1, probe.getInventory(), probe);
            aborted.getSlot(0).container.setItem(0, new ItemStack(Items.DIRT, 10));
            aborted.getSlot(1).container.setItem(1, new ItemStack(Items.BEDROCK, 3));
            aborted.removed(probe);
            double moneyAfterAbort = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, probe.getUUID());
            int dirtBack = countOf(probe, Items.DIRT);
            int bedrockBack = countOf(probe, Items.BEDROCK);
            boolean nothingSold = moneyAfterAbort == 0.0D && dirtBack == 10 && bedrockBack == 3;

            // 2) same box, but press 确认出售 twice: sold, unsellable returned
            probe.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.shop.ShopMenus.SellMenu menu = com.oktsas.onekeytosetaserver.shop.ShopMenus
                    .createSell(2, probe.getInventory(), probe);
            menu.getSlot(0).container.setItem(0, new ItemStack(Items.DIRT, 10));
            menu.getSlot(1).container.setItem(1, new ItemStack(Items.BEDROCK, 3));
            menu.clicked(com.oktsas.onekeytosetaserver.shop.ShopMenus.SellMenu.CONFIRM_SLOT, 0,
                    net.minecraft.world.inventory.ClickType.PICKUP, probe);
            double moneyMidway = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, probe.getUUID());
            int dirtStillInside = menu.getSlot(0).getItem().getCount();
            menu.clicked(com.oktsas.onekeytosetaserver.shop.ShopMenus.SellMenu.CONFIRM_SLOT, 0,
                    net.minecraft.world.inventory.ClickType.PICKUP, probe);
            menu.removed(probe);
            double money = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, probe.getUUID());
            double unit = com.oktsas.onekeytosetaserver.shop.ShopManager.recycleUnitPrice(new ItemStack(Items.DIRT));
            int bedrockAfter = countOf(probe, Items.BEDROCK);
            boolean confirmRequired = moneyMidway == 0.0D && dirtStillInside == 10;
            boolean soldOk = Math.abs(money - unit * 10.0D) < 1.0E-6 && bedrockAfter == 3
                    && countOf(probe, Items.DIRT) == 0;

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] sell gui: abortedNothingSold={} dirtBack={} bedrockBack={} confirmRequired={} "
                            + "soldOk={} earned={} bedrockReturned={}",
                    nothingSold, dirtBack, bedrockBack, confirmRequired, soldOk, money, bedrockAfter);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] sell gui test failed", t);
        }
    }

    /**
     * NBT shop: an enchanted book can be sold as goods with its enchantment, its recycle price is taken from
     * the exact entry instead of the id wildcard, and a plain book keeps using the wildcard.
     */
    private static void nbtShopTest(MinecraftServer server) {
        try {
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_nbt_probe".getBytes(StandardCharsets.UTF_8)), "oktsas_nbt_probe"));
            probe.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, probe.getUUID(), 5000.0D);

            // read-only: the shipped table prices a mending book and keeps a wildcard for plain books
            String shipped = "{StoredEnchantments:[{id:\"minecraft:mending\",lvl:1s}]}";
            double shippedBuy = com.oktsas.onekeytosetaserver.shop.ShopManager
                    .buyPriceOf("minecraft:enchanted_book", shipped);
            double wildcard = com.oktsas.onekeytosetaserver.shop.ShopManager
                    .recyclePriceOf("minecraft:enchanted_book");
            boolean shippedOk = shippedBuy > 0.0D && wildcard > 0.0D && shippedBuy > wildcard;

            // a variant the table does NOT ship, so the cleanup below cannot delete shipped data
            String snbt = "{StoredEnchantments:[{id:\"minecraft:lure\",lvl:3s}]}";
            boolean recycleSaved = com.oktsas.onekeytosetaserver.shop.ShopManager
                    .setRecyclePrice("minecraft:enchanted_book", snbt, 100.0D);
            ItemStack book = ItemStack.of(net.minecraft.nbt.TagParser.parseTag(
                    "{id:\"minecraft:enchanted_book\",Count:1b,tag:" + snbt + "}"));
            ItemStack plainBook = new ItemStack(Items.ENCHANTED_BOOK);
            double exactUnit = com.oktsas.onekeytosetaserver.shop.ShopManager.recycleUnitPrice(book);
            double plainUnit = com.oktsas.onekeytosetaserver.shop.ShopManager.recycleUnitPrice(plainBook);
            boolean exactWins = Math.abs(exactUnit - 100.0D) < 1.0E-6;
            boolean wildcardKept = Math.abs(plainUnit - wildcard) < 1.0E-6;

            // buy the goods entry: the stack handed out must carry the enchantment
            boolean bought = com.oktsas.onekeytosetaserver.shop.ShopManager.buy(probe,
                    new com.oktsas.onekeytosetaserver.shop.ShopManager.ShopItem(
                            "minecraft:enchanted_book", 500.0D, snbt), 1);
            ItemStack got = firstOf(probe, Items.ENCHANTED_BOOK);
            String gotNbt = got == null ? null : com.oktsas.onekeytosetaserver.shop.ShopManager.snbtOf(got);
            boolean nbtRoundTrip = gotNbt != null && gotNbt.contains("lure");

            int sold = com.oktsas.onekeytosetaserver.shop.ShopManager.sell(probe, book, 1);
            double money = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, probe.getUUID());
            boolean sellOk = sold == 1 && Math.abs(money - (5000.0D - 500.0D + 100.0D)) < 1.0E-6;

            // a book with a different enchantment only gets the wildcard price
            ItemStack other = ItemStack.of(net.minecraft.nbt.TagParser.parseTag(
                    "{id:\"minecraft:enchanted_book\",Count:1b,tag:{StoredEnchantments:[{id:\"minecraft:sharpness\",lvl:5s}]}}"));
            boolean otherWildcard = Math.abs(com.oktsas.onekeytosetaserver.shop.ShopManager
                    .recycleUnitPrice(other) - wildcard) < 1.0E-6;
            // ... and the legacy id-only entry still buys plain and NBT variants the same way
            boolean legacyWildcard = Math.abs(com.oktsas.onekeytosetaserver.shop.ShopManager
                    .recycleUnitPrice(new ItemStack(Items.DIAMOND))
                    - com.oktsas.onekeytosetaserver.shop.ShopManager.recyclePriceOf("minecraft:diamond")) < 1.0E-6;

            com.oktsas.onekeytosetaserver.shop.ShopManager.removeRecyclePrice("minecraft:enchanted_book", snbt);
            com.oktsas.onekeytosetaserver.shop.ShopManager.removeEntry("minecraft:enchanted_book", snbt);

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] nbt shop: shipped={} buy={} wildcard={} recycleSaved={} exactWins={} "
                            + "wildcardKept={} bought={} nbtRoundTrip={} sold={} money={} sellOk={} "
                            + "otherWildcard={} legacyWildcard={}",
                    shippedOk, shippedBuy, wildcard, recycleSaved, exactWins, wildcardKept, bought,
                    nbtRoundTrip, sold, money, sellOk, otherWildcard, legacyWildcard);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] nbt shop test failed", t);
        }
    }

    /** Armour, weapons, tools, ore blocks and spawn eggs are refused by both the shop and the market. */
    private static void tradeBlacklistTest(MinecraftServer server) {
        try {
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_ban_probe".getBytes(StandardCharsets.UTF_8)), "oktsas_ban_probe"));
            probe.getInventory().clearContent();
            probe.getInventory().add(new ItemStack(Items.DIAMOND_SWORD, 1));
            probe.getInventory().add(new ItemStack(Items.DIAMOND_CHESTPLATE, 1));
            probe.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE, 1));
            probe.getInventory().add(new ItemStack(Items.COAL_ORE, 1));
            probe.getInventory().add(new ItemStack(Items.ZOMBIE_SPAWN_EGG, 1));
            probe.getInventory().add(new ItemStack(Items.DIAMOND, 1));

            boolean sword = com.oktsas.onekeytosetaserver.trade.TradeBlacklist.isBanned(
                    new ItemStack(Items.DIAMOND_SWORD));
            boolean armor = com.oktsas.onekeytosetaserver.trade.TradeBlacklist.isBanned(
                    new ItemStack(Items.DIAMOND_CHESTPLATE));
            boolean tool = com.oktsas.onekeytosetaserver.trade.TradeBlacklist.isBanned(
                    new ItemStack(Items.DIAMOND_PICKAXE));
            boolean ore = com.oktsas.onekeytosetaserver.trade.TradeBlacklist.isBanned(
                    new ItemStack(Items.COAL_ORE));
            boolean egg = com.oktsas.onekeytosetaserver.trade.TradeBlacklist.isBanned(
                    new ItemStack(Items.ZOMBIE_SPAWN_EGG));
            boolean diamondFree = !com.oktsas.onekeytosetaserver.trade.TradeBlacklist.isBanned(
                    new ItemStack(Items.DIAMOND));
            boolean bookFree = !com.oktsas.onekeytosetaserver.trade.TradeBlacklist.isBanned(
                    new ItemStack(Items.ENCHANTED_BOOK));

            boolean shopRefused = com.oktsas.onekeytosetaserver.shop.ShopManager.sell(probe,
                    new ItemStack(Items.DIAMOND_SWORD), 1) == 0
                    && !com.oktsas.onekeytosetaserver.shop.ShopManager.sellBlocker(
                            new ItemStack(Items.DIAMOND_SWORD)).isEmpty();
            boolean marketRefused = !com.oktsas.onekeytosetaserver.market.MarketManager.sell(probe,
                    new ItemStack(Items.DIAMOND_CHESTPLATE), 10.0D, 24);
            int permanent = com.oktsas.onekeytosetaserver.market.MarketManager
                    .blacklistAdd(server, "minecraft:diamond_sword");
            boolean cannotUnban = !com.oktsas.onekeytosetaserver.market.MarketManager
                    .blacklistRemove("minecraft:diamond_sword");

            // a listing that predates the ban is taken down and mailed back on the next load
            com.oktsas.onekeytosetaserver.market.MarketManager.createForTest(probe.getUUID(), "oktsas_ban_probe",
                    new ItemStack(Items.IRON_SWORD, 1), 20.0D, 24);
            int mailboxBefore = com.oktsas.onekeytosetaserver.market.MarketManager.mailboxSize(probe.getUUID());
            com.oktsas.onekeytosetaserver.market.MarketManager.loadAll(
                    net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
            boolean swept = com.oktsas.onekeytosetaserver.market.MarketManager.listings().stream()
                    .noneMatch(listing -> listing.seller.equals(probe.getUUID().toString()));
            int mailboxAfter = com.oktsas.onekeytosetaserver.market.MarketManager.mailboxSize(probe.getUUID());
            com.oktsas.onekeytosetaserver.market.MarketManager.deliverMail(probe);
            com.oktsas.onekeytosetaserver.market.MarketManager.clearAllForTest();

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] trade blacklist: sword={} armor={} tool={} ore={} egg={} diamondFree={} "
                            + "bookFree={} shopRefused={} marketRefused={} permanent={} cannotUnban={} "
                            + "swept={} mailbox {}->{}",
                    sword, armor, tool, ore, egg, diamondFree, bookFree, shopRefused, marketRefused,
                    permanent == com.oktsas.onekeytosetaserver.market.MarketManager.BLACKLIST_PERMANENT,
                    cannotUnban, swept, mailboxBefore, mailboxAfter);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] trade blacklist test failed", t);
        }
    }

    /**
     * Upgrading a 0.2.5 style table: banned families and their recycle rows leave, the empty spawn egg
     * category disappears, the books/potions category and its wildcard recycle prices are added, and the
     * legacy {@code id -> price} recycle map is rewritten as the entry list.
     */
    private static void shopUpgradeTest(MinecraftServer server) {
        java.nio.file.Path temp = null;
        boolean ok = false;
        boolean booksAdded = false;
        boolean bannedGone = false;
        boolean legacyRewritten = false;
        boolean wildcardAdded = false;
        boolean restored = false;
        try {
            temp = java.nio.file.Files.createTempDirectory("oktsas_shop_upgrade");
            java.nio.file.Path shop = temp.resolve("multiverse").resolve("shop");
            java.nio.file.Files.createDirectories(shop);
            java.nio.file.Files.writeString(shop.resolve("categories.json"),
                    "[{\"id\":\"blocks\",\"title\":\"方块\",\"items\":["
                            + "{\"itemId\":\"minecraft:stone\",\"buyPrice\":4.0},"
                            + "{\"itemId\":\"minecraft:diamond_sword\",\"buyPrice\":100.0}]},"
                            + "{\"id\":\"spawn_eggs\",\"title\":\"刷怪蛋\",\"items\":["
                            + "{\"itemId\":\"minecraft:zombie_spawn_egg\",\"buyPrice\":9.0}]}]",
                    java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.writeString(shop.resolve("recycle.json"),
                    "{\"minecraft:stone\":1.0,\"minecraft:diamond_sword\":25.0}",
                    java.nio.charset.StandardCharsets.UTF_8);

            com.oktsas.onekeytosetaserver.shop.ShopManager.loadAll(temp);
            booksAdded = com.oktsas.onekeytosetaserver.shop.ShopManager.category("books_potions") != null;
            bannedGone = com.oktsas.onekeytosetaserver.shop.ShopManager.category("spawn_eggs") == null
                    && com.oktsas.onekeytosetaserver.shop.ShopManager.priceOf("minecraft:diamond_sword") == 0.0D
                    && com.oktsas.onekeytosetaserver.shop.ShopManager
                            .recyclePriceOf("minecraft:diamond_sword") == 0.0D;
            wildcardAdded = com.oktsas.onekeytosetaserver.shop.ShopManager
                    .recyclePriceOf("minecraft:enchanted_book") > 0.0D;
            legacyRewritten = java.nio.file.Files.readString(shop.resolve("recycle.json"))
                    .trim().startsWith("[");
            ok = booksAdded && bannedGone && wildcardAdded && legacyRewritten;
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] shop upgrade test failed", t);
        } finally {
            try {
                com.oktsas.onekeytosetaserver.shop.ShopManager.loadAll(
                        net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
                restored = com.oktsas.onekeytosetaserver.shop.ShopManager.itemCount() > 0;
            } catch (Throwable t) {
                OneKeyToSetAServer.LOGGER.error("[SELFTEST] could not restore the shop table", t);
            }
        }
        OneKeyToSetAServer.LOGGER.info("[SELFTEST] shop upgrade: ok={} books={} bannedGone={} wildcard={} "
                        + "legacyRewritten={} restored={}",
                ok, booksAdded, bannedGone, wildcardAdded, legacyRewritten, restored);
    }

    /** {@code /sellall} only touches the 36 main slots; worn armour and banned items stay. */
    private static void sellAllSlotsTest(MinecraftServer server) {
        try {
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_sellall_probe".getBytes(StandardCharsets.UTF_8)),
                    "oktsas_sellall_probe"));
            probe.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, probe.getUUID(), 0.0D);
            probe.getInventory().setItem(0, new ItemStack(Items.DIRT, 5));
            probe.getInventory().setItem(1, new ItemStack(Items.DIAMOND_SWORD, 1));
            probe.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE, 1));

            double[] sold = com.oktsas.onekeytosetaserver.shop.ShopManager.sellAll(probe);
            double money = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, probe.getUUID());
            boolean armorKept = !probe.getInventory().armor.get(2).isEmpty();
            boolean bannedKept = countOf(probe, Items.DIAMOND_SWORD) == 1;

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] sellall slots: soldStacks={} earned={} money={} armorKept={} bannedKept={}",
                    (int) sold[0], sold[1], money, armorKept, bannedKept);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] sellall slots test failed", t);
        }
    }

    /**
     * The four shop blocks were removed on the owner's request, so the test now asserts the opposite: nothing
     * of them may survive in the registries or in the recipe manager. A leftover recipe would be a crafting
     * table entry that produces a block which does not exist any more.
     */
    private static void blockTest(MinecraftServer server) {
        try {
            String[] names = {"official_shop", "sell_area", "free_market", "trash_can"};
            int leftovers = 0;
            for (String name : names) {
                net.minecraft.resources.ResourceLocation id =
                        new net.minecraft.resources.ResourceLocation(OneKeyToSetAServer.MODID, name);
                if (net.minecraftforge.registries.ForgeRegistries.BLOCKS.containsKey(id)
                        || net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(id)
                        || server.getRecipeManager().byKey(id).isPresent()) {
                    leftovers++;
                    OneKeyToSetAServer.LOGGER.warn("[SELFTEST] shop block '{}' is still registered", name);
                }
            }
            OneKeyToSetAServer.LOGGER.info("[SELFTEST] shop blocks removed: leftovers={}/4", leftovers);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] block test failed", t);
        }
    }

    /**
     * The category screen must have the layout the owner asked for: goods in rows 3 and 4 (nothing above them),
     * the viewer's head bottom left and the red close button bottom right, and rows 1/2/5 otherwise empty.
     */
    private static void shopLayoutTest(MinecraftServer server) {
        try {
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_layout_probe".getBytes(StandardCharsets.UTF_8)),
                    "oktsas_layout_probe"));
            probe.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.shop.ShopMenus.ShopMenu menu =
                    com.oktsas.onekeytosetaserver.shop.ShopMenus.createShop(1, probe.getInventory(), probe);
            int first = com.oktsas.onekeytosetaserver.shop.ShopMenus.CATEGORY_FIRST_SLOT;
            int info = com.oktsas.onekeytosetaserver.shop.ShopMenus.INFO_SLOT;
            int close = com.oktsas.onekeytosetaserver.shop.ShopMenus.CLOSE_SLOT;
            int above = 0;
            for (int slot = 0; slot < first; slot++) {
                if (!menu.getSlot(slot).getItem().isEmpty()) {
                    above++;
                }
            }
            int categories = 0;
            for (int slot = first; slot < first + com.oktsas.onekeytosetaserver.shop.ShopMenus.CATEGORIES_PER_PAGE
                    && slot < info; slot++) {
                if (!menu.getSlot(slot).getItem().isEmpty()) {
                    categories++;
                }
            }
            boolean head = menu.getSlot(info).getItem().is(Items.PLAYER_HEAD);
            boolean barrier = menu.getSlot(close).getItem().is(Items.BARRIER);
            String headName = menu.getSlot(info).getItem().getHoverName().getString();
            OneKeyToSetAServer.LOGGER.info("[SELFTEST] shop layout: aboveSlots={} categoriesInRows34={} "
                            + "headAt{}={}({}) closeAt{}={}",
                    above, categories, info, head, headName, close, barrier);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] shop layout test failed", t);
        }
    }

    /**
     * Spawn points of managed worlds must be on the surface ({@code 0, ~, 0}), and a player who ends up inside
     * the rock has to be rescued.
     *
     * <p>Note which value this checks: a managed world has no {@code level.dat} of its own, so the server
     * derives its level data from the main world - {@code DerivedLevelData.setSpawn} is an empty method and
     * {@code getSpawnPos} forwards to the main world. The spawn point therefore lives in
     * {@code multiverse/world.json} and the guard moves buried players there.</p>
     */
    private static void spawnTest(MinecraftServer server) {
        try {
            StringBuilder sb = new StringBuilder();
            for (MvWorld world : MvWorldManager.all()) {
                ServerLevel level = MvWorldManager.levelOf(world);
                if (level == null) {
                    continue;
                }
                sb.append(world.folder).append('=').append(describeSpawn(world, level)).append("  ");
            }
            OneKeyToSetAServer.LOGGER.info("[SELFTEST] world spawn points: {}", sb.toString().trim());

            MvWorld target = MvWorldManager.byName("resource_world");
            ServerLevel level = target == null ? null : MvWorldManager.levelOf(target);
            if (level == null) {
                OneKeyToSetAServer.LOGGER.info("[SELFTEST] spawn rescue: skipped (resource_world offline)");
                return;
            }
            // the surface has to be above bedrock even for a world whose spawn chunk is not in memory yet
            BlockPos safe = MvWorldManager.surfaceSpawn(target, level);
            OneKeyToSetAServer.LOGGER.info("[SELFTEST] surface spawn of resource_world: {},{},{} aboveBedrock={} "
                            + "column={}", safe.getX(), safe.getY(), safe.getZ(),
                    safe.getY() > level.getMinBuildHeight() + 2, columnOf(level, 0, 0));

            // the reported state: a player standing inside the rock of a managed world, and the control case of a
            // player who is simply in the open - only the first one may be moved
            FakePlayer probe = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_spawn_probe".getBytes(StandardCharsets.UTF_8)),
                    "oktsas_spawn_probe"));
            probe.moveTo(0.5D, -50.0D, 0.5D, 0.0F, 0.0F);
            boolean buriedInRock = com.oktsas.onekeytosetaserver.world.WorldPropertyListener.isBuried(probe);
            boolean actedInRock = com.oktsas.onekeytosetaserver.world.WorldPropertyListener.rescue(probe, "自检");
            probe.moveTo(0.5D, 63.0D, 0.5D, 0.0F, 0.0F);
            boolean buriedInAir = com.oktsas.onekeytosetaserver.world.WorldPropertyListener.isBuried(probe);
            boolean actedInAir = com.oktsas.onekeytosetaserver.world.WorldPropertyListener.rescue(probe, "自检");
            OneKeyToSetAServer.LOGGER.info("[SELFTEST] spawn rescue: inRock(y=-50) buried={} acted={} | "
                            + "inAir(y=63) buried={} acted={} | spawnOfWorld={},{},{} ({})",
                    buriedInRock, actedInRock, buriedInAir, actedInAir, MvWorldManager.spawnOf(target)[0],
                    MvWorldManager.spawnOf(target)[1], MvWorldManager.spawnOf(target)[2],
                    buriedInRock && actedInRock && !buriedInAir && !actedInAir ? "OK" : "CHECK");
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] spawn test failed", t);
        }
    }

    /** A few block names down the column, plus the heightmap answer, to explain a strange spawn point. */
    private static String columnOf(ServerLevel level, int x, int z) {
        StringBuilder sb = new StringBuilder();
        sb.append("heightmap=").append(level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
        for (int y : new int[]{-64, -40, -38, 0, 40, 63, 70, 80, 120}) {
            BlockPos pos = new BlockPos(x, y, z);
            sb.append(' ').append(y).append(':')
                    .append(level.getBlockState(pos).getBlock().getName().getString());
        }
        return sb.toString();
    }

    /** The recorded spawn point plus whether it sits below the surface. */
    private static String describeSpawn(MvWorld world, ServerLevel level) {
        if (!world.hasSpawn()) {
            return "(no spawn point recorded)";
        }
        int x = (int) Math.floor(world.spawnX);
        int y = (int) Math.floor(world.spawnY);
        int z = (int) Math.floor(world.spawnZ);
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                x, z);
        return x + "," + y + "," + z + "(gen=" + world.generator + ",heightmap=" + surface
                + (y < surface - 1 ? ",UNDERGROUND!" : "") + ")";
    }

    private static void shopGuiTest(MinecraftServer server) {
        try {
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_gui_probe".getBytes(StandardCharsets.UTF_8)), "oktsas_gui_probe"));
            probe.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, probe.getUUID(), 5000.0D);

            com.oktsas.onekeytosetaserver.shop.ShopMenus.ShopMenu menu = com.oktsas.onekeytosetaserver.shop.ShopMenus
                    .createShop(1, probe.getInventory(), probe);
            String s1 = menu.screenName();
            menu.clicked(com.oktsas.onekeytosetaserver.shop.ShopMenus.CATEGORY_FIRST_SLOT, 0,
                    net.minecraft.world.inventory.ClickType.PICKUP, probe);
            String s2 = menu.screenName();
            menu.clicked(0, 0, net.minecraft.world.inventory.ClickType.PICKUP, probe);
            String s3 = menu.screenName();
            int amountBefore = menu.amount();
            menu.clicked(24, 0, net.minecraft.world.inventory.ClickType.PICKUP, probe);
            int amountAfter = menu.amount();
            menu.clicked(13, 0, net.minecraft.world.inventory.ClickType.PICKUP, probe);
            double moneyLeft = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, probe.getUUID());
            int got = 0;
            for (int slot = 0; slot < probe.getInventory().getContainerSize(); slot++) {
                ItemStack stack = probe.getInventory().getItem(slot);
                if (!stack.isEmpty() && com.oktsas.onekeytosetaserver.shop.ShopManager.isSellable(stack)) {
                    got += stack.getCount();
                }
            }
            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] shop gui: {} -> {} -> {} amount {} -> {} bought={} moneyLeft={}",
                    s1, s2, s3, amountBefore, amountAfter, got, moneyLeft);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] shop gui test failed", t);
        }
    }

    /**
     * New in 0.2.0: the official shop also <b>buys</b>. Drives the sell button of the quantity screen and checks
     * the three refusals (no recycle price, more than the player carries, recycle price >= buy price).
     */
    private static void shopSellTest(MinecraftServer server) {
        try {
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_shopsell_probe".getBytes(StandardCharsets.UTF_8)),
                    "oktsas_shopsell_probe"));
            probe.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, probe.getUUID(), 1000.0D);
            probe.getInventory().add(new ItemStack(Items.DIAMOND, 8));

            double unit = com.oktsas.onekeytosetaserver.shop.ShopManager.recyclePriceOf("minecraft:diamond");
            com.oktsas.onekeytosetaserver.shop.ShopMenus.ShopMenu menu = com.oktsas.onekeytosetaserver.shop.ShopMenus
                    .createShop(1, probe.getInventory(), probe);
            boolean reached = menu.openQuantityFor("minecraft:diamond");
            for (int i = 0; i < 4; i++) {
                menu.clicked(23, 0, net.minecraft.world.inventory.ClickType.PICKUP, probe);   // +1 x4
            }
            int amount = menu.amount();
            menu.clicked(com.oktsas.onekeytosetaserver.shop.ShopMenus.ShopMenu.SELL_SLOT, 0,
                    net.minecraft.world.inventory.ClickType.PICKUP, probe);
            double afterSell = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, probe.getUUID());
            int diamondsLeft = com.oktsas.onekeytosetaserver.shop.ShopManager
                    .inventoryCount(probe, "minecraft:diamond");
            boolean soldOk = Math.abs(afterSell - (1000.0D + unit * amount)) < 1.0E-6
                    && diamondsLeft == 8 - amount;

            // an item the shop does not buy back must be refused without touching the balance
            double afterSell2 = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, probe.getUUID());
            boolean refusedNoPrice = com.oktsas.onekeytosetaserver.shop.ShopManager
                    .sell(probe, "minecraft:debug_stick", 1) == 0
                    && Math.abs(com.oktsas.onekeytosetaserver.economy.MoneyService
                            .get(server, probe.getUUID()) - afterSell2) < 1.0E-6;

            // selling more than the player carries must be refused as well
            menu.openQuantityFor("minecraft:diamond");
            for (int i = 0; i < 4; i++) {
                menu.clicked(25, 0, net.minecraft.world.inventory.ClickType.PICKUP, probe);   // +32 x4
            }
            int wanted = menu.amount();
            menu.clicked(com.oktsas.onekeytosetaserver.shop.ShopMenus.ShopMenu.SELL_SLOT, 0,
                    net.minecraft.world.inventory.ClickType.PICKUP, probe);
            boolean refusedTooMany = wanted > diamondsLeft
                    && Math.abs(com.oktsas.onekeytosetaserver.economy.MoneyService
                            .get(server, probe.getUUID()) - afterSell2) < 1.0E-6
                    && com.oktsas.onekeytosetaserver.shop.ShopManager
                            .inventoryCount(probe, "minecraft:diamond") == diamondsLeft;

            // ... and the recycle price may never reach the buy price
            double buy = com.oktsas.onekeytosetaserver.shop.ShopManager.priceOf("minecraft:diamond");
            double recycleBefore = com.oktsas.onekeytosetaserver.shop.ShopManager
                    .recyclePriceOf("minecraft:diamond");
            boolean exploitGuard = !com.oktsas.onekeytosetaserver.shop.ShopManager
                    .setRecyclePrice("minecraft:diamond", buy + 1.0D)
                    && com.oktsas.onekeytosetaserver.shop.ShopManager.recyclePriceOf("minecraft:diamond")
                            == recycleBefore;

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] shop sell: reached={} amount={} soldOk={} left={} refusedNoPrice={} "
                            + "refusedTooMany={} exploitGuard={}",
                    reached, amount, soldOk, diamondsLeft, refusedNoPrice, refusedTooMany, exploitGuard);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] shop sell test failed", t);
        }
    }

    /**
     * New in 0.2.0: the Essentials style layer - homes and their fee, the bounded {@code /back} queue, mutes,
     * warps, powertool bindings and the duplicate-root guard that keeps other mods' commands safe.
     */
    private static void essentialsTest(MinecraftServer server) {
        try {
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_ess_probe".getBytes(StandardCharsets.UTF_8)),
                    "oktsas_ess_probe"));
            probe.getInventory().clearContent();
            var config = com.oktsas.onekeytosetaserver.essentials.EssentialsData.config();
            var data = com.oktsas.onekeytosetaserver.essentials.EssentialsData.of(probe.getUUID());
            data.homes.clear();

            // /sethome + /home with the fee switch on and no delay
            server.getCommands().performPrefixedCommand(probe.createCommandSourceStack(), "sethome 自检家");
            boolean homeSet = data.homes.containsKey("自检家");
            boolean feeWas = config.teleportFeesEnabled;
            int delayWas = config.teleportDelaySeconds;
            double feeWasHome = config.feeHome;
            config.teleportFeesEnabled = true;
            config.teleportDelaySeconds = 0;
            config.feeHome = 12.5D;
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, probe.getUUID(), 1000.0D);
            server.getCommands().performPrefixedCommand(probe.createCommandSourceStack(), "home 自检家");
            double left = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, probe.getUUID());
            boolean homeFeeCharged = Math.abs((1000.0D - left) - 12.5D) < 1.0E-6;
            config.teleportFeesEnabled = feeWas;
            config.teleportDelaySeconds = delayWas;
            config.feeHome = feeWasHome;

            // the /back queue is bounded and returns the newest entry
            int queueWas = config.backQueueSize;
            config.backQueueSize = 2;
            data.backs.clear();
            for (String label : new String[]{"一", "二", "三"}) {
                com.oktsas.onekeytosetaserver.essentials.EssentialsData.pushBack(probe.getUUID(),
                        com.oktsas.onekeytosetaserver.essentials.EssentialsData.Point.of(probe, label));
            }
            int queueSize = data.backs.size();
            com.oktsas.onekeytosetaserver.essentials.EssentialsData.Point popped =
                    com.oktsas.onekeytosetaserver.essentials.EssentialsData.popBack(probe.getUUID());
            boolean backOk = queueSize == 2 && popped != null && "三".equals(popped.label);
            config.backQueueSize = queueWas;

            // mute: permanent and timed, plus the remaining text
            com.oktsas.onekeytosetaserver.essentials.EssentialsData.mute(probe.getUUID(),
                    com.oktsas.onekeytosetaserver.essentials.EssentialsCommands.parseDuration("5m"), "自检");
            boolean muted = com.oktsas.onekeytosetaserver.essentials.EssentialsData.isMuted(probe.getUUID())
                    && !com.oktsas.onekeytosetaserver.essentials.EssentialsData.muteRemaining(probe.getUUID()).isEmpty();
            com.oktsas.onekeytosetaserver.essentials.EssentialsData.unmute(probe.getUUID());
            boolean unmuted = !com.oktsas.onekeytosetaserver.essentials.EssentialsData.isMuted(probe.getUUID());
            boolean permParsed = com.oktsas.onekeytosetaserver.essentials.EssentialsCommands
                    .parseDuration("perm") == Long.MAX_VALUE;

            // warps + powertool storage
            com.oktsas.onekeytosetaserver.essentials.EssentialsData.setWarp("自检点",
                    com.oktsas.onekeytosetaserver.essentials.EssentialsData.Point.of(probe, "自检点"));
            boolean warpOk = com.oktsas.onekeytosetaserver.essentials.EssentialsData.warp("自检点") != null;
            com.oktsas.onekeytosetaserver.essentials.EssentialsData.removeWarp("自检点");
            com.oktsas.onekeytosetaserver.essentials.EssentialsData.setPowertool(probe.getUUID(),
                    "minecraft:diamond", "say hi");
            boolean powertoolOk = "minecraft:diamond|say hi".equals(
                    com.oktsas.onekeytosetaserver.essentials.EssentialsData.powertool(probe.getUUID()));
            com.oktsas.onekeytosetaserver.essentials.EssentialsData.setPowertool(probe.getUUID(), null, null);

            // registering twice must not throw: the root() guard skips names that already exist
            boolean duplicateSafe = true;
            try {
                com.oktsas.onekeytosetaserver.essentials.EssentialsCommands
                        .register(server.getCommands().getDispatcher());
            } catch (Throwable t) {
                duplicateSafe = false;
            }
            boolean newCommands = server.getCommands().getDispatcher().getRoot().getChild("home") != null
                    && server.getCommands().getDispatcher().getRoot().getChild("tpa") != null
                    && server.getCommands().getDispatcher().getRoot().getChild("mute") != null
                    && server.getCommands().getDispatcher().getRoot().getChild("back") != null;

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] essentials: homeSet={} homeFeeCharged={} backOk={} queue={} muted={} unmuted={} "
                            + "permParsed={} warpOk={} powertoolOk={} duplicateSafe={} newCommands={}",
                    homeSet, homeFeeCharged, backOk, queueSize, muted, unmuted, permParsed, warpOk,
                    powertoolOk, duplicateSafe, newCommands);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] essentials test failed", t);
        }
    }

    /**
     * New in 0.2.0: the chat button store, its chest editor and the sync channel. The client addon itself
     * (the e33chat mixin) cannot be exercised headless - this checks everything on the server side of it.
     */
    private static void chatButtonTest(MinecraftServer server) {
        try {
            FakePlayer admin = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_chatbtn".getBytes(StandardCharsets.UTF_8)), "oktsas_chatbtn"));
            admin.getInventory().clearContent();
            int before = com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore.size();
            String json = com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore.toJson();
            boolean jsonShape = json.contains("\"name\"") && json.contains("\"icon\"")
                    && json.contains("\"commands\"") && json.contains("\"cooldownSeconds\"")
                    && json.contains("menu");

            com.oktsas.onekeytosetaserver.command.ChatButtonCommand.EditorMenu editor =
                    com.oktsas.onekeytosetaserver.command.ChatButtonCommand
                            .createEditor(1, admin.getInventory(), admin);
            int icons = editor.filled();
            admin.getInventory().selected = 0;
            admin.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
            editor.clicked(45, 0, net.minecraft.world.inventory.ClickType.PICKUP, admin);   // 新增
            int afterNew = com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore.size();
            editor.clicked(2, 0, net.minecraft.world.inventory.ClickType.PICKUP, admin);    // 选中第 3 个
            int selected = editor.selected();
            editor.clicked(53, 0, net.minecraft.world.inventory.ClickType.PICKUP, admin);   // 保存并推送
            int after = com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore.size();
            boolean oneAdded = afterNew == before + 1;
            boolean command = server.getCommands().getDispatcher().getRoot().getChild("chatbutton") != null;
            // syncing to a server with nobody (or nobody with the addon) online must be harmless
            com.oktsas.onekeytosetaserver.chatbutton.ChatButtonNetwork.sendAll(server);

            // put the list back exactly as it was - this test must not grow the file from run to run
            while (com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore.size() > before) {
                com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore
                        .remove(com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore.size() - 1);
            }
            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] chat buttons: jsonShape={} icons={} before={} afterNew={} oneAdded={} "
                            + "selected={} after={} restored={} command={}",
                    jsonShape, icons, before, afterNew, oneAdded, selected, after,
                    com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore.size() == before, command);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] chat button test failed", t);
        }
    }

    private static void shopTest(MinecraftServer server) {
        try {
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_shop_probe".getBytes(StandardCharsets.UTF_8)), "oktsas_shop_probe"));
            probe.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, probe.getUUID(), 1000.0D);

            boolean bought = com.oktsas.onekeytosetaserver.shop.ShopManager.buy(probe, "minecraft:diamond", 1);
            double afterBuy = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, probe.getUUID());
            int diamonds = 0;
            for (int slot = 0; slot < probe.getInventory().getContainerSize(); slot++) {
                ItemStack stack = probe.getInventory().getItem(slot);
                if (stack.is(Items.DIAMOND)) {
                    diamonds += stack.getCount();
                }
            }

            probe.getInventory().add(new ItemStack(Items.DIRT, 10));
            double[] sold = com.oktsas.onekeytosetaserver.shop.ShopManager.sellAll(probe);
            double afterSell = com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, probe.getUUID());

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] shop: bought={} moneyAfterBuy={} diamonds={} soldStacks={} earned={} moneyAfterSell={}",
                    bought, afterBuy, diamonds, (int) sold[0], sold[1], afterSell);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] shop test failed", t);
        }
    }

    /**
     * Menu editor: definition round trip through menus.json, rendering, the OP-4 gate and the editor
     * operations (slot -> button screen, icon from the held item, action cycling, chat text input, row
     * growth, save).
     */
    private static void menuEditorTest(MinecraftServer server) {
        try {
            com.oktsas.onekeytosetaserver.menu.MenuManager.delete("selftest_menu");
            com.oktsas.onekeytosetaserver.menu.MenuManager.MenuDef def =
                    com.oktsas.onekeytosetaserver.menu.MenuManager.create("selftest_menu", "§8自检菜单", 3);
            if (def == null) {
                def = com.oktsas.onekeytosetaserver.menu.MenuManager.get("selftest_menu");
            }
            def.set(5, com.oktsas.onekeytosetaserver.menu.MenuManager.button(Items.DIAMOND, "§b测试按钮",
                    com.oktsas.onekeytosetaserver.menu.MvMenus.Action.COMMAND_PLAYER, "say hi", "§7说明一"));
            def.set(6, com.oktsas.onekeytosetaserver.menu.MenuManager.button(Items.EMERALD, "§a第二按钮",
                    com.oktsas.onekeytosetaserver.menu.MvMenus.Action.OPEN_MENU,
                    com.oktsas.onekeytosetaserver.menu.MenuManager.MAIN));
            com.oktsas.onekeytosetaserver.menu.MenuManager.save();
            com.oktsas.onekeytosetaserver.menu.MenuManager.reload();

            com.oktsas.onekeytosetaserver.menu.MenuManager.MenuDef loaded =
                    com.oktsas.onekeytosetaserver.menu.MenuManager.get("selftest_menu");
            com.oktsas.onekeytosetaserver.menu.MenuManager.Button restored = loaded == null ? null : loaded.at(5);
            boolean roundTrip = restored != null
                    && com.oktsas.onekeytosetaserver.menu.MenuManager.toDisplay(restored).is(Items.DIAMOND)
                    && "§b测试按钮".equals(restored.name)
                    && restored.action() == com.oktsas.onekeytosetaserver.menu.MvMenus.Action.COMMAND_PLAYER
                    && "say hi".equals(restored.argument)
                    && restored.lore.size() == 1;

            FakePlayer admin = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_menu_admin".getBytes(StandardCharsets.UTF_8)), "oktsas_menu_admin"));
            admin.getInventory().clearContent();
            admin.getInventory().selected = 0;

            com.oktsas.onekeytosetaserver.menu.MvMenus.MvMenu view = com.oktsas.onekeytosetaserver.menu.MvMenus
                    .create(1, admin.getInventory(), admin, "selftest_menu");
            int icons = 0;
            for (int i = 0; i < view.slots.size(); i++) {
                if (!view.getSlot(i).getItem().isEmpty()) {
                    icons++;
                }
            }

            // without OP 4 (and without the test seam) the editor must refuse
            com.oktsas.onekeytosetaserver.menu.MvMenus.MvMenu gate = com.oktsas.onekeytosetaserver.menu.MvMenus
                    .createEdit(2, admin.getInventory(), admin, "selftest_menu", -1);
            gate.clicked(5, 0, net.minecraft.world.inventory.ClickType.PICKUP, admin);
            boolean gateRefused = "layout".equals(gate.screen());

            com.oktsas.onekeytosetaserver.menu.MenuEditor.setPermissionCheckForTest(false);
            admin.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 1));

            com.oktsas.onekeytosetaserver.menu.MvMenus.MvMenu layout = com.oktsas.onekeytosetaserver.menu.MvMenus
                    .createEdit(3, admin.getInventory(), admin, "selftest_menu", -1);
            com.oktsas.onekeytosetaserver.menu.MenuEditor.click(admin, layout, "slot:5");

            com.oktsas.onekeytosetaserver.menu.MvMenus.MvMenu editor = com.oktsas.onekeytosetaserver.menu.MvMenus
                    .createEdit(4, admin.getInventory(), admin, "selftest_menu", 5);
            String screen = editor.screen();
            com.oktsas.onekeytosetaserver.menu.MenuEditor.click(admin, editor, "action");
            com.oktsas.onekeytosetaserver.menu.MenuEditor.click(admin, editor, "icon");
            com.oktsas.onekeytosetaserver.menu.MenuEditor.click(admin, editor, "lore");
            com.oktsas.onekeytosetaserver.menu.MenuEditor.submitText(admin, "LORE", "§7新说明|§7第二行");
            com.oktsas.onekeytosetaserver.menu.MenuEditor.click(admin, editor, "argument");
            com.oktsas.onekeytosetaserver.menu.MenuEditor.submitText(admin, "ARGUMENT", "say bye");
            com.oktsas.onekeytosetaserver.menu.MenuEditor.click(admin, editor, "name");
            com.oktsas.onekeytosetaserver.menu.MenuEditor.submitText(admin, "NAME", "§e改名按钮");
            com.oktsas.onekeytosetaserver.menu.MenuEditor.click(admin, editor, "save");

            com.oktsas.onekeytosetaserver.menu.MvMenus.MvMenu layoutAgain = com.oktsas.onekeytosetaserver.menu.MvMenus
                    .createEdit(5, admin.getInventory(), admin, "selftest_menu", -1);
            com.oktsas.onekeytosetaserver.menu.MenuEditor.click(admin, layoutAgain, "grow:40");
            com.oktsas.onekeytosetaserver.menu.MenuEditor.click(admin, layoutAgain, "save");
            com.oktsas.onekeytosetaserver.menu.MenuEditor.setPermissionCheckForTest(true);

            com.oktsas.onekeytosetaserver.menu.MenuManager.reload();
            com.oktsas.onekeytosetaserver.menu.MenuManager.MenuDef finalDef =
                    com.oktsas.onekeytosetaserver.menu.MenuManager.get("selftest_menu");
            com.oktsas.onekeytosetaserver.menu.MenuManager.Button finalButton =
                    finalDef == null ? null : finalDef.at(5);
            com.oktsas.onekeytosetaserver.menu.MenuManager.MenuDef main =
                    com.oktsas.onekeytosetaserver.menu.MenuManager.get(com.oktsas.onekeytosetaserver.menu.MenuManager.MAIN);
            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] menu editor: roundTrip={} icons={} screen={} gateRefused={} rows={} "
                            + "action={} icon={} name={} lore={} arg={} mainButtons={}",
                    roundTrip, icons, screen, gateRefused, finalDef == null ? -1 : finalDef.rows,
                    finalButton == null ? "?" : finalButton.action,
                    finalButton == null ? "?" : com.oktsas.onekeytosetaserver.menu.MenuManager.decode(finalButton.icon)
                            .getItem().toString().replace("minecraft:", ""),
                    finalButton == null ? "?" : finalButton.name,
                    finalButton == null ? -1 : finalButton.lore.size(),
                    finalButton == null ? "?" : finalButton.argument,
                    main == null ? -1 : main.filled());
            // the throwaway menu this test created must not stay behind in the dev world
            com.oktsas.onekeytosetaserver.menu.MenuManager.delete("selftest_menu");
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] menu editor test failed", t);
        }
    }

    /**
     * Domain module: create from a selection, size limit, one-per-player, overlap refusal, protection
     * flags honoured for outsiders, members and OP bypass, and the world-reset cleanup.
     */
    private static void domainTest(MinecraftServer server) {
        try {
            com.oktsas.onekeytosetaserver.world.MvWorld world = MvWorldManager.byName("resource_world");
            ServerLevel level = world == null ? null : MvWorldManager.levelOf(world);
            if (level == null) {
                OneKeyToSetAServer.LOGGER.info("[SELFTEST] domain test skipped (world offline)");
                return;
            }
            FakePlayer owner = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_domain_owner".getBytes(StandardCharsets.UTF_8)), "oktsas_domain_owner"));
            FakePlayer stranger = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_domain_stranger".getBytes(StandardCharsets.UTF_8)), "oktsas_domain_stranger"));
            owner.moveTo(0.5D, 100.0D, 0.5D, 0.0F, 0.0F);
            String dimension = level.dimension().location().toString();
            com.oktsas.onekeytosetaserver.domain.DomainManager.clearWorld(dimension);

            // no selection yet -> create must ask for the wand
            com.oktsas.onekeytosetaserver.domain.DomainManager.Result tooBig =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.create(owner, "自检领域");
            boolean needsSelection = !tooBig.ok() && tooBig.error().contains("法杖");
            // the wand path itself: mark() must produce a corner without a client
            com.oktsas.onekeytosetaserver.domain.DomainManager.mark(owner, false);
            boolean wandMarks = com.oktsas.onekeytosetaserver.domain.DomainManager
                    .selectionOf(owner.getUUID()) != null
                    && com.oktsas.onekeytosetaserver.domain.DomainManager
                    .selectionOf(owner.getUUID()).first() != null;

            long before = System.currentTimeMillis();
            // headless runs have no stable crosshair, so the selection is fed directly from here on
            com.oktsas.onekeytosetaserver.domain.DomainManager.setSelectionForTest(owner.getUUID(), dimension,
                    new BlockPos(0, 99, 0), new BlockPos(120, 99, 120));
            // 121x1x121 = 14641 blocks => 21961.50; refused while the wallet is empty
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, owner.getUUID(), 0.0D);
            com.oktsas.onekeytosetaserver.domain.DomainManager.Result refused =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.create(owner, "太大");
            boolean priceRefused = !refused.ok() && refused.cost() > 0.0D;

            // the same selection is fine once the player can pay: 0.2.0 has no size limit any more
            owner.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, owner.getUUID(), 50000.0D);
            com.oktsas.onekeytosetaserver.domain.DomainManager.Result big =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.create(owner, "大领地");
            boolean bigOk = big.ok() && big.volume() == 121L * 121L;
            double spent = 50000.0D
                    - com.oktsas.onekeytosetaserver.economy.MoneyService.get(server, owner.getUUID());
            boolean charged = big.ok() && Math.abs(spent - big.cost()) < 1.0E-6;
            if (big.ok()) {
                com.oktsas.onekeytosetaserver.domain.DomainManager.delete(big.domain());
            }

            // a real 8x7x8 = 448 block claim (672.00) for the protection checks below
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, owner.getUUID(), 5000.0D);
            com.oktsas.onekeytosetaserver.domain.DomainManager.setSelectionForTest(owner.getUUID(), dimension,
                    new BlockPos(10, 99, 10), new BlockPos(17, 105, 17));
            com.oktsas.onekeytosetaserver.domain.DomainManager.Result created =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.create(owner, "自检领域");
            if (!created.ok()) {
                OneKeyToSetAServer.LOGGER.info("[SELFTEST] domain: created=false error={}", created.error());
                return;
            }
            com.oktsas.onekeytosetaserver.domain.Domain domain = created.domain();

            // one per player per world
            com.oktsas.onekeytosetaserver.domain.DomainManager.setSelectionForTest(owner.getUUID(), dimension,
                    new BlockPos(30, 99, 30), new BlockPos(35, 99, 35));
            boolean secondRefused = !com.oktsas.onekeytosetaserver.domain.DomainManager
                    .create(owner, "第二个领域").ok();

            // overlap: another player picks a box that shares blocks with the first one
            com.oktsas.onekeytosetaserver.domain.DomainManager.setSelectionForTest(stranger.getUUID(), dimension,
                    new BlockPos(12, 99, 12), new BlockPos(19, 99, 19));
            boolean overlapRefused = !com.oktsas.onekeytosetaserver.domain.DomainManager
                    .create(stranger, "重叠领域").ok();

            BlockPos inside = new BlockPos(14, 100, 14);
            BlockPos outside = new BlockPos(60, 100, 60);
            boolean strangerInside = !com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(stranger, level, inside, com.oktsas.onekeytosetaserver.domain.Domain.Flag.BREAK);
            boolean strangerOutside = com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(stranger, level, outside, com.oktsas.onekeytosetaserver.domain.Domain.Flag.BREAK);
            boolean ownerInside = com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(owner, level, inside, com.oktsas.onekeytosetaserver.domain.Domain.Flag.BREAK);
            boolean machineDenied = !com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(null, level, inside, com.oktsas.onekeytosetaserver.domain.Domain.Flag.BREAK);

            // trust the stranger: protection must stop applying to them
            domain.members.put(stranger.getUUID().toString(), "oktsas_domain_stranger");
            boolean memberAllowed = com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(stranger, level, inside, com.oktsas.onekeytosetaserver.domain.Domain.Flag.BREAK);
            domain.members.remove(stranger.getUUID().toString());

            // toggling a flag opens that one action up again
            domain.setDeny(com.oktsas.onekeytosetaserver.domain.Domain.Flag.PLACE, false);
            boolean placeAllowed = com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(stranger, level, inside, com.oktsas.onekeytosetaserver.domain.Domain.Flag.PLACE);
            domain.setDeny(com.oktsas.onekeytosetaserver.domain.Domain.Flag.PLACE, true);

            // OP 4 ignores protection entirely
            com.oktsas.onekeytosetaserver.domain.DomainManager.setOpTestBypass(true);
            boolean opBypass = com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(stranger, level, inside, com.oktsas.onekeytosetaserver.domain.Domain.Flag.BREAK);
            com.oktsas.onekeytosetaserver.domain.DomainManager.setOpTestBypass(false);

            // new in 0.2.0: pvp / explosion / mob griefing flags and per player overrides
            boolean sevenFlags = com.oktsas.onekeytosetaserver.domain.Domain.Flag.values().length == 7;
            boolean pvpDenied = !com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(stranger, level, inside, com.oktsas.onekeytosetaserver.domain.Domain.Flag.PVP);
            boolean explosionDenied = !com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(null, level, inside, com.oktsas.onekeytosetaserver.domain.Domain.Flag.EXPLOSION);
            boolean mobDenied = !com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(null, level, inside, com.oktsas.onekeytosetaserver.domain.Domain.Flag.MOB_GRIEFING);
            domain.setOverride(stranger.getUUID(), com.oktsas.onekeytosetaserver.domain.Domain.Flag.BREAK,
                    Boolean.FALSE);
            boolean psetOpens = com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(stranger, level, inside, com.oktsas.onekeytosetaserver.domain.Domain.Flag.BREAK);
            domain.setOverride(stranger.getUUID(), com.oktsas.onekeytosetaserver.domain.Domain.Flag.BREAK, null);
            boolean psetCleared = !com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(stranger, level, inside, com.oktsas.onekeytosetaserver.domain.Domain.Flag.BREAK);

            // the claim really is a cuboid: 6 blocks above it are already outside
            boolean aboveOutsideAllowed = com.oktsas.onekeytosetaserver.domain.DomainManager
                    .allowed(stranger, level, new BlockPos(14, 200, 14),
                            com.oktsas.onekeytosetaserver.domain.Domain.Flag.BREAK);

            // rename / transfer / delete
            domain.name = "自检领域改名";
            com.oktsas.onekeytosetaserver.domain.DomainManager.save();
            com.oktsas.onekeytosetaserver.domain.DomainManager.loadAll(
                    net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
            com.oktsas.onekeytosetaserver.domain.Domain persisted =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.byId(domain.id);
            boolean persistedOk = persisted != null && "自检领域改名".equals(persisted.name)
                    && persisted.members.size() == 1;
            int cleared = com.oktsas.onekeytosetaserver.domain.DomainManager.clearWorld(dimension);
            boolean goneAfterClear = com.oktsas.onekeytosetaserver.domain.DomainManager
                    .byId(domain.id) == null;

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] domain: needsSelection={} wandMarks={} priceRefused={} bigOk={} charged={} "
                            + "created={} volume={} secondRefused={} overlapRefused={} strangerInside={} "
                            + "strangerOutside={} ownerInside={} machineDenied={} memberAllowed={} "
                            + "placeFlagOpens={} opBypass={} sevenFlags={} pvpDenied={} explosionDenied={} "
                            + "mobDenied={} psetOpens={} psetCleared={} aboveOutside={} persisted={} "
                            + "clearedOnReset={} gone={} ms={}",
                    needsSelection, wandMarks, priceRefused, bigOk, charged, created.ok(), created.volume(),
                    secondRefused, overlapRefused, strangerInside, strangerOutside, ownerInside, machineDenied,
                    memberAllowed, placeAllowed, opBypass, sevenFlags, pvpDenied, explosionDenied, mobDenied,
                    psetOpens, psetCleared, aboveOutsideAllowed, persistedOk, cleared, goneAfterClear,
                    System.currentTimeMillis() - before);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] domain test failed", t);
        }
    }

    /**
     * {@code /res admin reload} must really re-read res.json, and a broken or missing file must be refused
     * without wiping the claims that are already in memory (nor overwriting the operator's file).
     */
    private static void domainReloadTest(MinecraftServer server) {
        try {
            java.nio.file.Path gameDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
            com.oktsas.onekeytosetaserver.domain.DomainManager.save();
            int before = com.oktsas.onekeytosetaserver.domain.DomainManager.all().size();
            com.oktsas.onekeytosetaserver.domain.DomainManager.ReloadResult live =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.reload();
            boolean liveOk = live.ok() && live.loaded() == before && live.skipped() == 0
                    && com.oktsas.onekeytosetaserver.domain.DomainManager.all().size() == before;

            java.nio.file.Path dir = gameDir.resolve("selftest_res_reload");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path sample = dir.resolve("res.json");
            java.nio.file.Files.writeString(sample, "[" + claim("aaa", "甲") + "," + claim("bbb", "乙")
                    + ",{\"id\":\"ccc\",\"owner\":\"\",\"ownerName\":\"无主\","
                    + "\"dimension\":\"minecraft:overworld\",\"name\":\"c\"}]");
            com.oktsas.onekeytosetaserver.domain.DomainManager.ReloadResult temp =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.reloadFrom(sample);
            boolean tempOk = temp.ok() && temp.loaded() == 2 && temp.skipped() == 1;
            boolean tempNamed = com.oktsas.onekeytosetaserver.domain.DomainManager.byId("aaa") != null;

            java.nio.file.Files.writeString(sample, "{ this is not json");
            int kept = com.oktsas.onekeytosetaserver.domain.DomainManager.all().size();
            com.oktsas.onekeytosetaserver.domain.DomainManager.ReloadResult broken =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.reloadFrom(sample);
            boolean brokenRefused = !broken.ok() && broken.error() != null
                    && com.oktsas.onekeytosetaserver.domain.DomainManager.all().size() == kept
                    && java.nio.file.Files.readString(sample).startsWith("{ this is not json");
            com.oktsas.onekeytosetaserver.domain.DomainManager.ReloadResult missing =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.reloadFrom(dir.resolve("nope.json"));
            boolean missingRefused = !missing.ok()
                    && com.oktsas.onekeytosetaserver.domain.DomainManager.all().size() == kept;

            // restore the real store (the temp file never touched it) and clean up
            com.oktsas.onekeytosetaserver.domain.DomainManager.loadAll(gameDir);
            java.nio.file.Files.deleteIfExists(sample);
            java.nio.file.Files.deleteIfExists(dir);
            boolean restored = com.oktsas.onekeytosetaserver.domain.DomainManager.all().size() == before;

            OneKeyToSetAServer.LOGGER.info("[SELFTEST] res reload: live={} temp={} tempNamed={} "
                            + "brokenRefused={} missingRefused={} kept={} restored={} error={}",
                    liveOk, tempOk, tempNamed, brokenRefused, missingRefused, kept, restored, broken.error());
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] res reload test failed", t);
        }
    }

    /** A minimal but complete claim entry for the reload test. */
    private static String claim(String id, String owner) {
        String uuid = UUID.nameUUIDFromBytes(("oktsas_reload_" + owner).getBytes(StandardCharsets.UTF_8))
                .toString();
        return "{\"id\":\"" + id + "\",\"owner\":\"" + uuid + "\",\"ownerName\":\"" + owner + "\","
                + "\"dimension\":\"minecraft:overworld\",\"world\":\"mainland\",\"name\":\"reload_" + id + "\","
                + "\"minX\":0,\"maxX\":1,\"minZ\":0,\"maxZ\":1,\"minY\":60,\"maxY\":70}";
    }

    /** Market filtering: category guess, text search, the "my listings" page and taking a listing back. */
    private static void marketFilterTest(MinecraftServer server) {
        try {
            FakePlayer seller = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_filter_seller".getBytes(StandardCharsets.UTF_8)), "oktsas_filter_seller"));
            seller.getInventory().clearContent();
            int before = com.oktsas.onekeytosetaserver.market.MarketManager.listings().size();
            com.oktsas.onekeytosetaserver.market.MarketManager.clearAllForTest();
            before = 0;

            com.oktsas.onekeytosetaserver.market.MarketManager.Listing sword =
                    com.oktsas.onekeytosetaserver.market.MarketManager.createForTest(seller.getUUID(),
                            "oktsas_filter_seller", new ItemStack(Items.DIAMOND_SWORD), 30.0D, 24);
            com.oktsas.onekeytosetaserver.market.MarketManager.createForTest(seller.getUUID(),
                    "oktsas_filter_seller", new ItemStack(Items.DIRT, 8), 5.0D, 24);
            com.oktsas.onekeytosetaserver.market.MarketManager.createForTest(seller.getUUID(),
                    "oktsas_filter_seller", new ItemStack(Items.EMERALD, 2), 12.0D, 24);

            String swordCategory = com.oktsas.onekeytosetaserver.market.MarketManager
                    .categoryOf(new ItemStack(Items.DIAMOND_SWORD));
            String dirtCategory = com.oktsas.onekeytosetaserver.market.MarketManager
                    .categoryOf(new ItemStack(Items.DIRT));
            String emeraldCategory = com.oktsas.onekeytosetaserver.market.MarketManager
                    .categoryOf(new ItemStack(Items.EMERALD));

            int byWeapon = com.oktsas.onekeytosetaserver.market.MarketManager
                    .filtered("武器", "", null).size();
            int bySearch = com.oktsas.onekeytosetaserver.market.MarketManager
                    .filtered("", "dirt", null).size();
            int bySeller = com.oktsas.onekeytosetaserver.market.MarketManager
                    .filtered("", "", seller.getUUID()).size();

            com.oktsas.onekeytosetaserver.market.MarketMenu.MarketMenuInner mine =
                    com.oktsas.onekeytosetaserver.market.MarketMenu.create(1, seller.getInventory(), seller,
                            "", "", true);
            int mineIcons = mine.count();
            String mineTitle = mine.title();

            com.oktsas.onekeytosetaserver.market.MarketMenu.MarketMenuInner weapons =
                    com.oktsas.onekeytosetaserver.market.MarketMenu.create(2, seller.getInventory(), seller,
                            "武器", "", false);
            int weaponIcons = weapons.count();
            // clicking the category button cycles 武器 -> 防具
            weapons.clicked(46, 0, net.minecraft.world.inventory.ClickType.PICKUP, seller);
            String cycled = weapons.category();

            // taking the sword back hands it to the seller instead of the mailbox
            boolean cancelled = com.oktsas.onekeytosetaserver.market.MarketManager.cancel(seller,
                    sword.id);
            int swordsBack = 0;
            for (int slot = 0; slot < seller.getInventory().getContainerSize(); slot++) {
                if (seller.getInventory().getItem(slot).is(Items.DIAMOND_SWORD)) {
                    swordsBack += seller.getInventory().getItem(slot).getCount();
                }
            }
            int after = com.oktsas.onekeytosetaserver.market.MarketManager.listings().size();

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] market filter: sword={} dirt={} emerald={} byWeapon={} bySearch={} bySeller={} "
                            + "mineIcons={} mineTitle={} weaponIcons={} cycledTo={} cancelled={} swordsBack={} "
                            + "listings {}->{}",
                    swordCategory, dirtCategory, emeraldCategory, byWeapon, bySearch, bySeller, mineIcons,
                    mineTitle, weaponIcons, cycled, cancelled, swordsBack, before, after);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] market filter test failed", t);
        }
    }

    /**
     * Phase 8 extras: the in-game shop price editor, the market blacklist and the trash confirmation mode.
     */
    private static void extrasTest(MinecraftServer server) {
        try {
            FakePlayer op = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_editor".getBytes(StandardCharsets.UTF_8)), "oktsas_editor"));
            op.getInventory().clearContent();
            op.getInventory().selected = 0;
            op.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 1));

            // ---- shop price editor -------------------------------------------------
            double buyBefore = com.oktsas.onekeytosetaserver.shop.ShopManager.priceOf("minecraft:diamond");
            double recycleBefore = com.oktsas.onekeytosetaserver.shop.ShopManager
                    .recycleUnitPrice(new ItemStack(Items.DIAMOND));
            com.oktsas.onekeytosetaserver.shop.ShopManager.Category originalCategory =
                    com.oktsas.onekeytosetaserver.shop.ShopManager.categoryOfItem("minecraft:diamond");
            com.oktsas.onekeytosetaserver.shop.ShopEditor.EditorMenu editor =
                    com.oktsas.onekeytosetaserver.shop.ShopEditor.create(1, op.getInventory(), op);
            String editorItem = editor.itemId();
            editor.clicked(16, 0, net.minecraft.world.inventory.ClickType.PICKUP, op);  // 买价 +10
            editor.clicked(17, 0, net.minecraft.world.inventory.ClickType.PICKUP, op);  // 买价 +100
            editor.clicked(25, 0, net.minecraft.world.inventory.ClickType.PICKUP, op);  // 回收价 +10
            double steppedBuy = editor.buyPrice();
            double steppedRecycle = editor.recyclePrice();
            editor.clicked(29, 0, net.minecraft.world.inventory.ClickType.PICKUP, op);  // 下一个分类
            String chosenCategory = editor.categoryId();
            editor.clicked(31, 0, net.minecraft.world.inventory.ClickType.PICKUP, op);  // 保存
            double buyAfter = com.oktsas.onekeytosetaserver.shop.ShopManager.priceOf(editorItem);
            double recycleAfter = com.oktsas.onekeytosetaserver.shop.ShopManager
                    .recycleUnitPrice(new ItemStack(Items.DIAMOND));
            String landedIn = com.oktsas.onekeytosetaserver.shop.ShopManager.categoryOfItem(editorItem) == null
                    ? "?" : com.oktsas.onekeytosetaserver.shop.ShopManager.categoryOfItem(editorItem).id;

            // a brand new item can be put into the shop the same way
            double beaconPriceBefore = com.oktsas.onekeytosetaserver.shop.ShopManager.priceOf("minecraft:beacon");
            op.getInventory().setItem(0, new ItemStack(Items.BEACON, 1));
            com.oktsas.onekeytosetaserver.shop.ShopEditor.EditorMenu second =
                    com.oktsas.onekeytosetaserver.shop.ShopEditor.create(2, op.getInventory(), op);
            second.step(true, 250.0D);
            second.clicked(31, 0, net.minecraft.world.inventory.ClickType.PICKUP, op);
            double beaconPrice = com.oktsas.onekeytosetaserver.shop.ShopManager.priceOf("minecraft:beacon");

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] shop editor: item={} buy {} -> stepped {} -> saved {} recycle {} -> {} "
                            + "category={} landedIn={} newItem={}",
                    editorItem, buyBefore, steppedBuy, buyAfter, recycleBefore, recycleAfter,
                    chosenCategory, landedIn, beaconPrice);
            // leave the dev shop data exactly as it was found: the prices this test touched go back, the item
            // goes back to its original category, and an item that was not in the shop before is removed again
            if (originalCategory != null) {
                com.oktsas.onekeytosetaserver.shop.ShopManager.putInCategory(originalCategory.id, editorItem,
                        buyBefore);
            } else {
                com.oktsas.onekeytosetaserver.shop.ShopManager.setBuyPrice(editorItem, buyBefore);
            }
            com.oktsas.onekeytosetaserver.shop.ShopManager.setRecyclePrice(editorItem, recycleBefore);
            if (beaconPriceBefore <= 0.0D) {
                com.oktsas.onekeytosetaserver.shop.ShopManager.removeItem("minecraft:beacon");
            } else {
                com.oktsas.onekeytosetaserver.shop.ShopManager.setBuyPrice("minecraft:beacon", beaconPriceBefore);
            }

            // ---- market blacklist --------------------------------------------------
            com.oktsas.onekeytosetaserver.market.MarketManager.clearAllForTest();
            FakePlayer seller = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_ban_seller".getBytes(StandardCharsets.UTF_8)), "oktsas_ban_seller"));
            seller.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.market.MarketManager.createForTest(seller.getUUID(), "oktsas_ban_seller",
                    new ItemStack(Items.EMERALD, 4), 40.0D, 24);
            int visibleBefore = com.oktsas.onekeytosetaserver.market.MarketManager
                    .filtered("", "", null).size();
            int takenDown = com.oktsas.onekeytosetaserver.market.MarketManager
                    .blacklistAdd(server, "minecraft:emerald");
            int visibleAfter = com.oktsas.onekeytosetaserver.market.MarketManager
                    .filtered("", "", null).size();
            boolean sellRefused = !com.oktsas.onekeytosetaserver.market.MarketManager
                    .sell(seller, new ItemStack(Items.EMERALD, 1), 10.0D, 24);
            int mailedBack = com.oktsas.onekeytosetaserver.market.MarketManager.mailboxSize(seller.getUUID());
            boolean unbanned = com.oktsas.onekeytosetaserver.market.MarketManager
                    .blacklistRemove("minecraft:emerald");
            int afterUnban = com.oktsas.onekeytosetaserver.market.MarketManager.mailboxSize(seller.getUUID());
            com.oktsas.onekeytosetaserver.market.MarketManager.deliverMail(seller);
            com.oktsas.onekeytosetaserver.market.MarketManager.clearAllForTest();

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] market blacklist: visible {} -> {} takenDown={} sellRefused={} mailed={} "
                            + "unbanned={} stillMailed={}",
                    visibleBefore, visibleAfter, takenDown, sellRefused, mailedBack, unbanned, afterUnban);

            // ---- trash confirmation mode -------------------------------------------
            FakePlayer trasher = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_trasher".getBytes(StandardCharsets.UTF_8)), "oktsas_trasher"));
            trasher.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.shop.ShopMenus.createTrash(1, trasher.getInventory(), trasher)
                    .getSlot(0).container.setItem(0, new ItemStack(Items.DIRT, 5));
            com.oktsas.onekeytosetaserver.shop.ShopMenus.TrashMenu destroyed =
                    com.oktsas.onekeytosetaserver.shop.ShopMenus.createTrash(2, trasher.getInventory(), trasher);
            destroyed.getSlot(0).container.setItem(0, new ItemStack(Items.DIRT, 5));
            destroyed.removed(trasher);
            int leftAfterDestroy = countOf(trasher, Items.DIRT);

            boolean confirmOn = com.oktsas.onekeytosetaserver.shop.ShopMenus.toggleTrashConfirm(trasher.getUUID());
            com.oktsas.onekeytosetaserver.shop.ShopMenus.TrashMenu kept =
                    com.oktsas.onekeytosetaserver.shop.ShopMenus.createTrash(3, trasher.getInventory(), trasher);
            kept.getSlot(0).container.setItem(0, new ItemStack(Items.DIRT, 7));
            kept.removed(trasher);
            int leftAfterConfirm = countOf(trasher, Items.DIRT);

            com.oktsas.onekeytosetaserver.shop.ShopMenus.TrashMenu forced =
                    com.oktsas.onekeytosetaserver.shop.ShopMenus.createTrash(4, trasher.getInventory(), trasher);
            forced.getSlot(0).container.setItem(0, new ItemStack(Items.DIRT, 7));
            forced.clicked(49, 0, net.minecraft.world.inventory.ClickType.PICKUP, trasher);
            int leftAfterForce = countOf(trasher, Items.DIRT);
            com.oktsas.onekeytosetaserver.shop.ShopMenus.toggleTrashConfirm(trasher.getUUID());

            OneKeyToSetAServer.LOGGER.info(
                    "[SELFTEST] trash: wipedImmediately={} confirmModeOn={} returnedInConfirmMode={} "
                            + "forcedByButton={}",
                    leftAfterDestroy, confirmOn, leftAfterConfirm, leftAfterForce);

            // ---- per-world scale (multiverse "scale") -------------------------------
            com.oktsas.onekeytosetaserver.world.MvWorld scaledWorld = MvWorldManager.byName("resource_world");
            net.minecraft.server.level.ServerLevel scaledLevel =
                    scaledWorld == null ? null : MvWorldManager.levelOf(scaledWorld);
            if (scaledWorld != null && scaledLevel != null) {
                FakePlayer scaledProbe = FakePlayerFactory.get(scaledLevel, new GameProfile(
                        UUID.nameUUIDFromBytes("oktsas_scale".getBytes(StandardCharsets.UTF_8)), "oktsas_scale"));
                double original = scaledWorld.scale;
                com.oktsas.onekeytosetaserver.world.WorldPropertyListener listener =
                        new com.oktsas.onekeytosetaserver.world.WorldPropertyListener();
                scaledWorld.scale = 2.0D;
                listener.apply(scaledProbe);
                net.minecraft.world.entity.ai.attributes.AttributeInstance speed = scaledProbe
                        .getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
                net.minecraft.world.entity.ai.attributes.AttributeModifier doubled = speed == null ? null
                        : speed.getModifier(com.oktsas.onekeytosetaserver.world.WorldPropertyListener
                                .SCALE_MODIFIER);
                boolean scaleApplied = doubled != null && Math.abs(doubled.getAmount() - 1.0D) < 1.0E-6;
                listener.apply(scaledProbe);
                int modifierCount = speed == null ? -1
                        : speed.getModifiers().size();
                scaledWorld.scale = 1.0D;
                listener.apply(scaledProbe);
                boolean scaleCleared = speed == null
                        || speed.getModifier(com.oktsas.onekeytosetaserver.world.WorldPropertyListener
                                .SCALE_MODIFIER) == null;
                scaledWorld.scale = original;
                listener.apply(scaledProbe);
                OneKeyToSetAServer.LOGGER.info(
                        "[SELFTEST] world scale: x2applied={} modifierCount={} clearedBack={}",
                        scaleApplied, modifierCount, scaleCleared);
            }
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] extras test failed", t);
        }
    }

    private static int countOf(net.minecraft.world.entity.player.Player player, net.minecraft.world.item.Item item) {        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** The first stack of that item in the player's inventory, or null. */
    private static ItemStack firstOf(net.minecraft.world.entity.player.Player player,
                                     net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                return stack;
            }
        }
        return null;
    }

    private static void menuTest(MinecraftServer server) {        try {
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_menu_probe".getBytes(StandardCharsets.UTF_8)), "oktsas_menu_probe"));
            com.oktsas.onekeytosetaserver.menu.MvMenus.MvMenu menu = com.oktsas.onekeytosetaserver.menu.MvMenus
                    .create(1, probe.getInventory(), probe, com.oktsas.onekeytosetaserver.menu.MvMenus.WORLDS);
            int filled = 0;
            String first = "";
            for (int i = 0; i < menu.slots.size(); i++) {
                ItemStack stack = menu.getSlot(i).getItem();
                if (!stack.isEmpty()) {
                    filled++;
                    if (first.isEmpty()) {
                        first = stack.getHoverName().getString();
                    }
                }
            }
            OneKeyToSetAServer.LOGGER.info("[SELFTEST] menu: slots={} filled={} first={}",
                    menu.slots.size(), filled, first);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] menu test failed", t);
        }
    }

    /**
     * The "you can take the display items out of the chest GUI" bug, checked by brute force.
     *
     * <p>Every own GUI is opened for a fake player and <b>every</b> slot is clicked with every click type and
     * both buttons. Two things must hold afterwards: the player still carries exactly what they started with
     * (no item can be pulled out of a display slot, by shift click or otherwise) and the slot count matches
     * what a vanilla client builds for that menu type (container rows + 36 inventory + 9 hotbar), because a
     * mismatch is exactly what made the client predict a move the server never performed.</p>
     */
    private static void menuSlotTest(MinecraftServer server) {
        try {
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_slot_probe".getBytes(StandardCharsets.UTF_8)),
                    "oktsas_slot_probe"));
            probe.getInventory().clearContent();
            probe.getInventory().setItem(9, new ItemStack(Items.BEDROCK, 64));
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, probe.getUUID(), 100000.0D);
            com.oktsas.onekeytosetaserver.market.MarketManager.clearAllForTest();
            com.oktsas.onekeytosetaserver.market.MarketManager.createForTest(probe.getUUID(), "oktsas_slot_probe",
                    new ItemStack(Items.DIAMOND, 3), 120.0D, 24);

            com.oktsas.onekeytosetaserver.menu.MenuSlots.Config defaults =
                    new com.oktsas.onekeytosetaserver.menu.MenuSlots.Config();
            boolean optionA = com.oktsas.onekeytosetaserver.menu.MenuSlots.showPlayerInventory();
            String summary = "";
            // the brute force loop clicks every slot of the chat button editor, including "新增按钮", so the
            // button count is restored afterwards to keep the dev data clean
            int buttonsBefore = com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore.size();

            // option A (default): display rows + 36 player slots - exactly what the client's ChestMenu builds
            summary += checkOneMenu("shop", true,
                    () -> com.oktsas.onekeytosetaserver.shop.ShopMenus.createShop(1, probe.getInventory(), probe),
                    probe);
            summary += checkOneMenu("world menu", true,
                    () -> com.oktsas.onekeytosetaserver.menu.MvMenus.create(1, probe.getInventory(), probe,
                            com.oktsas.onekeytosetaserver.menu.MvMenus.WORLDS),
                    probe);
            summary += checkOneMenu("market", true,
                    () -> com.oktsas.onekeytosetaserver.market.MarketMenu.create(1, probe.getInventory(), probe,
                            "", "", false),
                    probe);
            summary += checkOneMenu("trash", true,
                    () -> com.oktsas.onekeytosetaserver.shop.ShopMenus.createTrash(1, probe.getInventory(), probe),
                    probe);
            summary += checkOneMenu("shop editor", true,
                    () -> com.oktsas.onekeytosetaserver.shop.ShopEditor.create(1, probe.getInventory(), probe),
                    probe);
            summary += checkOneMenu("chat buttons", true,
                    () -> com.oktsas.onekeytosetaserver.command.ChatButtonCommand.createEditor(1,
                            probe.getInventory(), probe),
                    probe);
            summary += checkOneMenu("sell area", true,
                    () -> com.oktsas.onekeytosetaserver.shop.ShopMenus.createSell(1, probe.getInventory(), probe),
                    probe);
            summary += confirmTest(server, probe);

            // option B: no player slots at all, so only the container rows exist. The client still builds its
            // usual chest menu (container rows + its own inventory), so a different slot count is expected here
            // by design - what must hold is that the whole-container packet still fits the client's menu.
            com.oktsas.onekeytosetaserver.menu.MenuSlots.setShowPlayerInventory(false);
            summary += checkOneMenu("shop(B)", false,
                    () -> com.oktsas.onekeytosetaserver.shop.ShopMenus.createShop(3, probe.getInventory(), probe),
                    probe);
            com.oktsas.onekeytosetaserver.menu.MenuSlots.setShowPlayerInventory(optionA);
            com.oktsas.onekeytosetaserver.menu.MenuSlots.saveConfig();

            OneKeyToSetAServer.LOGGER.info("[SELFTEST] menu slots (default showPlayerInventory={}): {}",
                    defaults.showPlayerInventory, summary);
            int buttonsAfter = com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore.size();
            for (int i = buttonsAfter; i > buttonsBefore; i--) {
                com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore.remove(i - 1);
            }
            if (buttonsAfter != buttonsBefore) {
                OneKeyToSetAServer.LOGGER.info("[SELFTEST] chat buttons restored: {} -> {}",
                        buttonsAfter, buttonsBefore);
            }
            com.oktsas.onekeytosetaserver.market.MarketManager.clearAllForTest();
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] menu slot test failed", t);
        }
    }

    /**
     * Clicks every slot of one menu with every click type and both buttons, then reports what the owner's
     * reports were about:
     *
     * <ul>
     *   <li>{@code server} vs {@code client} - the client builds a vanilla chest menu for our menu type, so the
     *       two slot counts <b>must</b> match. 99 against 90 is what made an opened GUI throw
     *       {@code IndexOutOfBoundsException} on the client and let a shift click dupe items;</li>
     *   <li>{@code clientApply} - the whole-container packet our resync sends, replayed through the client's
     *       own {@code initializeContents}. Any failure here is a client side crash;</li>
     *   <li>{@code cursorKept} - picking a stack up out of your own inventory must leave it on the cursor, not
     *       delete it (the "菜单会吞物品" report);</li>
     *   <li>{@code intoDisplay} - nothing may ever end up <b>on</b> a display slot.</li>
     * </ul>
     *
     * @param expectLayoutMatch false for option B, where the client keeps its own inventory slots by design
     */
    private static String checkOneMenu(String name, boolean expectLayoutMatch,
                                       java.util.function.Supplier<net.minecraft.world.inventory.AbstractContainerMenu> open,
                                       FakePlayer probe) {
        net.minecraft.world.inventory.AbstractContainerMenu menu = open.get();
        int slots = menu.slots.size();
        int display = Math.min(54, slots);
        int clientSlots = com.oktsas.onekeytosetaserver.menu.MenuSlots.clientSlotCount(menu, probe);
        String applyFailure = com.oktsas.onekeytosetaserver.menu.MenuSlots.clientApplyCheck(menu, probe);
        boolean playerSlots = com.oktsas.onekeytosetaserver.menu.MenuSlots.isPlayerSlot(menu, display, display);
        String cursor = cursorKeptTest(menu, probe, display);
        int markerBefore = countInRange(menu, 0, display, Items.BEDROCK);
        for (net.minecraft.world.inventory.ClickType type : net.minecraft.world.inventory.ClickType.values()) {
            for (int button = 0; button <= 1; button++) {
                for (int slot = 0; slot < slots; slot++) {
                    try {
                        menu.clicked(slot, button, type, probe);
                    } catch (Throwable t) {
                        OneKeyToSetAServer.LOGGER.warn("[SELFTEST] {} slot {} {} b{} threw {}",
                                name, slot, type, button, t.toString());
                    }
                    // A headless player has nowhere to hold a picked-up stack, so put it back and forget the
                    // cursor: otherwise an action that uses the held item (the chat button editor's "新增按钮")
                    // would build buttons out of the test's marker stack.
                    probe.getInventory().setItem(9, new ItemStack(Items.BEDROCK, 64));
                    menu.setCarried(ItemStack.EMPTY);
                }
            }
        }
        int intoDisplay = countInRange(menu, 0, display, Items.BEDROCK) - markerBefore;
        String layout = slots == clientSlots ? "ok" : (expectLayoutMatch ? "MISMATCH" : "n/a(B)");
        return name + "[server=" + slots + ",client=" + clientSlots + ",layout=" + layout
                + ",clientApply=" + (applyFailure == null ? "ok" : applyFailure)
                + ",playerSlots=" + playerSlots + ",intoDisplay=" + intoDisplay + "," + cursor
                + ",stillValid=" + menu.stillValid(probe) + "] ";
    }

    /**
     * The regression the owner hit: clicking your own item inside a GUI. Vanilla moves the stack onto the
     * cursor; the old {@code resync()} then blanked the cursor and the item was gone. The stack has to be on the
     * cursor (or back in the slot) afterwards - never nowhere.
     */
    private static String cursorKeptTest(net.minecraft.world.inventory.AbstractContainerMenu menu, FakePlayer probe,
                                         int display) {
        int start = com.oktsas.onekeytosetaserver.menu.MenuSlots.playerSlotStart(menu, display);
        if (start < 0 || start >= menu.slots.size()) {
            return "cursorKept=n/a(B)";
        }
        probe.getInventory().clearContent();
        probe.getInventory().setItem(9, new ItemStack(Items.BEDROCK, 64));
        try {
            menu.clicked(start, 0, net.minecraft.world.inventory.ClickType.PICKUP, probe);
            int carried = menu.getCarried().is(Items.BEDROCK) ? menu.getCarried().getCount() : 0;
            int inSlots = countInRange(menu, start, menu.slots.size(), Items.BEDROCK);
            boolean kept = carried + inSlots >= 64;
            String result = "cursorKept=" + kept + "(carried=" + carried + ",slots=" + inSlots + ")";
            menu.setCarried(ItemStack.EMPTY);
            probe.getInventory().setItem(9, new ItemStack(Items.BEDROCK, 64));
            return result;
        } catch (Throwable t) {
            return "cursorKept=threw(" + t + ")";
        }
    }

    /** How many items of {@code item} sit on menu slots {@code [from, to)}. */
    private static int countInRange(net.minecraft.world.inventory.AbstractContainerMenu menu, int from, int to,
                                    net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = from; i < Math.min(to, menu.slots.size()); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * "一键出售背包" must not fire on the first click. The fake player is given one item so a real sell is
     * visible in the item count: click once - nothing may happen - then click again inside the 3 second window
     * and the item must be gone (turned into money).
     */
    private static String confirmTest(MinecraftServer server, FakePlayer probe) {
        probe.getInventory().clearContent();
        probe.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 4));
        com.oktsas.onekeytosetaserver.menu.MvMenus.MvMenu menu = com.oktsas.onekeytosetaserver.menu.MvMenus
                .create(9, probe.getInventory(), probe, com.oktsas.onekeytosetaserver.menu.MvMenus.MAIN);
        int slot = -1;
        for (int i = 0; i < menu.slots.size() && slot < 0; i++) {
            if (menu.getSlot(i).getItem().is(Items.HOPPER)) {
                slot = i;
            }
        }
        if (slot < 0) {
            return "confirm[buttonNotFound] ";
        }
        int before = countOf(probe, Items.DIAMOND);
        menu.clicked(slot, 0, net.minecraft.world.inventory.ClickType.PICKUP, probe);
        boolean armed = menu.awaitingConfirm();
        int afterFirst = countOf(probe, Items.DIAMOND);
        menu.clicked(slot, 0, net.minecraft.world.inventory.ClickType.PICKUP, probe);
        int afterSecond = countOf(probe, Items.DIAMOND);
        return "confirm[slot=" + slot + ",armed=" + armed + ",firstClickKept=" + (afterFirst == before)
                + ",soldOnSecond=" + (afterSecond < afterFirst) + "] ";
    }

    /**
     * The trash can and the sell area really do take items, so a click on one of their own slots has to reach the
     * vanilla logic. The guard used to be "player slots only", which silently rejected every intake slot and let
     * the follow-up resync wipe the client's prediction - the whole screen was unusable, and the brute force test
     * could not see it because it only asserts that nothing ends up on a <b>label</b>.
     */
    private static void storageTest(MinecraftServer server) {
        try {
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_storage_probe".getBytes(StandardCharsets.UTF_8)),
                    "oktsas_storage_probe"));
            String trash = placeInto("trash",
                    com.oktsas.onekeytosetaserver.shop.ShopMenus.createTrash(1, probe.getInventory(), probe), 0,
                    probe);
            String sell = placeInto("sellArea",
                    com.oktsas.onekeytosetaserver.shop.ShopMenus.createSell(1, probe.getInventory(), probe), 0,
                    probe);
            // a pure label must still refuse the item
            boolean shopRefused = !placeInto("shopLabel",
                    com.oktsas.onekeytosetaserver.shop.ShopMenus.createShop(1, probe.getInventory(), probe), 0,
                    probe).contains("=ok(");
            OneKeyToSetAServer.LOGGER.info("[SELFTEST] storage: {} {} labelRefused={}", trash, sell, shopRefused);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] storage test failed", t);
        }
    }

    /** Puts a marker stack on the cursor, clicks that slot and reports whether the stack landed in it. */
    private static String placeInto(String name,
                                    net.minecraft.world.inventory.AbstractContainerMenu menu, int slot,
                                    FakePlayer probe) {
        probe.getInventory().clearContent();
        menu.setCarried(new ItemStack(Items.BEDROCK, 5));
        try {
            menu.clicked(slot, 0, net.minecraft.world.inventory.ClickType.PICKUP, probe);
        } catch (Throwable t) {
            return name + "=threw(" + t.getClass().getSimpleName() + ")";
        }
        boolean landed = menu.getSlot(slot).getItem().is(Items.BEDROCK);
        int carried = menu.getCarried().is(Items.BEDROCK) ? menu.getCarried().getCount() : 0;
        menu.setCarried(ItemStack.EMPTY);
        return name + "=" + (landed ? "ok" : "NOT_STORED") + "(carried=" + carried + ")";
    }

    private static void inventoryPoolTest(MinecraftServer server) {        try {
            ServerLevel overworld = server.overworld();
            MvWorld world = MvWorldManager.byName("resource_world");
            ServerLevel target = world == null ? null : MvWorldManager.levelOf(world);
            if (target == null) {
                OneKeyToSetAServer.LOGGER.info("[SELFTEST] inventory pool test skipped (target world offline)");
                return;
            }
            FakePlayer probe = FakePlayerFactory.get(overworld, new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_inv_probe".getBytes(StandardCharsets.UTF_8)), "oktsas_inv_probe"));
            probe.getInventory().clearContent();
            probe.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            int before = probe.getInventory().getItem(0).getCount();
            PlayerInventoryStore.swap(probe, overworld, target);
            int afterEnter = probe.getInventory().getItem(0).getCount();
            PlayerInventoryStore.swap(probe, target, overworld);
            int afterReturn = probe.getInventory().getItem(0).getCount();
            OneKeyToSetAServer.LOGGER.info("[SELFTEST] inventory pool: before={} afterEnterIsolated={} afterReturn={}",
                    before, afterEnter, afterReturn);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] inventory pool test failed", t);
        }
    }

    /**
     * 0.2.5: the Curios accessory round trip and, above all, the rule that <b>taking a snapshot never takes
     * anything away</b>. 0.2.3/0.2.4 used Curios' {@code saveInventory(true)}, which emptied the slots while
     * saving, so entering a managed world stripped a player's accessories and the next snapshot wrote that
     * empty state over the stored one.
     *
     * <p>Needs Curios on the dev classpath and a data pack that gives the player slot types
     * ({@code run/world/datapacks/oktsas_selftest}); without either it reports a skip instead of failing.</p>
     */
    private static void curiosRoundTripTest(MinecraftServer server) {
        try {
            if (!PlayerInventoryStore.curiosLoaded()) {
                OneKeyToSetAServer.LOGGER.info("[SELFTEST] curios: skipped (Curios is not installed)");
                return;
            }
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_curios_probe".getBytes(StandardCharsets.UTF_8)),
                    "oktsas_curios_probe"));
            probe.getInventory().clearContent();
            java.util.Map<String, Integer> slots = PlayerInventoryStore.accessorySlots(probe);
            if (slots.isEmpty()) {
                OneKeyToSetAServer.LOGGER.info("[SELFTEST] curios: skipped (no slot type for minecraft:player)");
                return;
            }
            String identifier = slots.keySet().iterator().next();
            ItemStack worn = new ItemStack(Items.EMERALD, 3);
            boolean placed = PlayerInventoryStore.setAccessory(probe, identifier, 0, worn);
            int wornBefore = PlayerInventoryStore.accessoriesOf(probe).size();

            java.nio.file.Path pool = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve("multiverse").resolve("selftest_pool");
            java.nio.file.Files.createDirectories(pool);
            java.nio.file.Path file = PlayerInventoryStore.snapshotFile(pool, probe.getUUID());

            boolean saved = PlayerInventoryStore.save(probe, pool);
            // the regression that cost the players their accessories: the snapshot may not empty anything
            int stillWorn = PlayerInventoryStore.accessoriesOf(probe).size();
            boolean keptAfterSave = stillWorn == wornBefore && wornBefore == 1;
            // the pool keeps the layout 0.2.0-0.2.4 wrote (Stacks / Identifier), so old files stay readable
            boolean legacyShape = false;
            if (java.nio.file.Files.isRegularFile(file)) {
                try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
                    net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(in);
                    net.minecraft.nbt.ListTag curios =
                            root.getList("curios", net.minecraft.nbt.CompoundTag.TAG_COMPOUND);
                    legacyShape = curios.size() > 0
                            && curios.getCompound(0).contains("Stacks", net.minecraft.nbt.CompoundTag.TAG_COMPOUND)
                            && curios.getCompound(0).contains("Identifier", 8);
                }
            }

            PlayerInventoryStore.clear(probe);
            int afterClear = PlayerInventoryStore.accessoriesOf(probe).size();
            boolean loaded = PlayerInventoryStore.load(probe, pool);
            java.util.List<ItemStack> back = PlayerInventoryStore.accessoriesOf(probe);
            boolean restored = loaded && back.size() == 1 && back.get(0).is(Items.EMERALD)
                    && back.get(0).getCount() == 3;

            // a second snapshot keeps one generation of the previous file
            boolean savedAgain = PlayerInventoryStore.save(probe, pool);
            boolean backup = java.nio.file.Files.isRegularFile(pool.resolve(probe.getUUID() + ".nbt.bak"));

            // a file that is not readable (here: truncated to zero bytes) must not clear the player
            byte[] intact = java.nio.file.Files.readAllBytes(file);
            java.nio.file.Files.write(file, new byte[0]);
            int beforeBroken = PlayerInventoryStore.accessoriesOf(probe).size();
            boolean brokenRefused = !PlayerInventoryStore.load(probe, pool)
                    && PlayerInventoryStore.accessoriesOf(probe).size() == beforeBroken;
            java.nio.file.Files.write(file, intact);

            // an accessory whose slot type no longer exists is mailed, never dropped
            com.oktsas.onekeytosetaserver.market.MarketManager.deliverMail(probe);
            int mailBefore = com.oktsas.onekeytosetaserver.market.MarketManager.mailboxSize(probe.getUUID());
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
                net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(in);
                net.minecraft.nbt.CompoundTag lost = new net.minecraft.nbt.CompoundTag();
                lost.putString("Identifier", "selftest_slot_that_does_not_exist");
                net.minecraft.nbt.CompoundTag stacks = new net.minecraft.nbt.CompoundTag();
                stacks.putInt("Size", 1);
                net.minecraft.nbt.ListTag items = new net.minecraft.nbt.ListTag();
                net.minecraft.nbt.CompoundTag item = new net.minecraft.nbt.CompoundTag();
                item.putInt("Slot", 0);
                new ItemStack(Items.DIAMOND, 2).save(item);
                items.add(item);
                stacks.put("Items", items);
                lost.put("Stacks", stacks);
                net.minecraft.nbt.ListTag only = new net.minecraft.nbt.ListTag();
                only.add(lost);
                root.put("curios", only);
                try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(file)) {
                    net.minecraft.nbt.NbtIo.writeCompressed(root, out);
                }
            }
            PlayerInventoryStore.clear(probe);
            PlayerInventoryStore.load(probe, pool);
            int mailAfter = com.oktsas.onekeytosetaserver.market.MarketManager.mailboxSize(probe.getUUID());
            boolean orphanMailed = mailAfter == mailBefore + 1;
            com.oktsas.onekeytosetaserver.market.MarketManager.deliverMail(probe);

            // clean up the test pool
            java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(pool.resolve(probe.getUUID() + ".nbt.bak"));
            java.nio.file.Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));

            OneKeyToSetAServer.LOGGER.info("[SELFTEST] curios: slots={} placed={} saved={} keptAfterSave={} "
                            + "legacyShape={} clearedTo={} loaded={} restored={} savedAgain={} backup={} "
                            + "brokenRefused={} orphanMailed={}",
                    slots, placed, saved, keptAfterSave, legacyShape, afterClear, loaded, restored, savedAgain,
                    backup, brokenRefused, orphanMailed);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] curios test failed", t);
        }
    }

    /**
     * 0.2.5: a store that could not be read is never written, and while it is locked nothing may be charged or
     * sold. Covers {@code multiverse/res.json} (claims + the money path) and
     * {@code multiverse/market/listings.json} (listings).
     */
    private static void storeLockTest(MinecraftServer server) {
        java.nio.file.Path gameDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
        java.nio.file.Path marketDir = gameDir.resolve("multiverse").resolve("market");
        try {
            java.nio.file.Path resFile = gameDir.resolve("multiverse").resolve("res.json");
            if (!com.oktsas.onekeytosetaserver.domain.DomainManager.writable()
                    || !java.nio.file.Files.isRegularFile(resFile)) {
                OneKeyToSetAServer.LOGGER.info("[SELFTEST] store lock: skipped (claim store not in its normal "
                        + "state)");
                return;
            }
            String originalRes = java.nio.file.Files.readString(resFile);
            int claimsBefore = com.oktsas.onekeytosetaserver.domain.DomainManager.all().size();

            // 1) a file with an unusable entry loads what it can and locks the store against writes
            java.nio.file.Files.writeString(resFile, "[" + claim("lock_a", "甲") + "," + claim("lock_b", "乙")
                    + ",{\"id\":\"lock_c\",\"owner\":\"\",\"ownerName\":\"无主\","
                    + "\"dimension\":\"minecraft:overworld\",\"name\":\"c\"}]");
            com.oktsas.onekeytosetaserver.domain.DomainManager.ReloadResult partial =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.reload();
            boolean lockedAfterSkips = partial.ok() && partial.skipped() == 1
                    && !com.oktsas.onekeytosetaserver.domain.DomainManager.writable()
                    && com.oktsas.onekeytosetaserver.domain.DomainManager.byId("lock_a") != null;
            boolean fileKept = java.nio.file.Files.readString(resFile).contains("lock_c");

            // 2) a claim cannot be created while locked - and above all no money is taken
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_lock_probe".getBytes(StandardCharsets.UTF_8)),
                    "oktsas_lock_probe"));
            probe.getInventory().clearContent();
            com.oktsas.onekeytosetaserver.economy.MoneyService.set(server, probe.getUUID(), 1000.0D);
            com.oktsas.onekeytosetaserver.domain.DomainManager.setSelectionForTest(probe.getUUID(),
                    "minecraft:overworld", new BlockPos(0, 99, 0), new BlockPos(4, 99, 4));
            com.oktsas.onekeytosetaserver.domain.DomainManager.Result refused =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.create(probe, "锁定领地");
            boolean createRefused = !refused.ok();
            boolean notCharged = Math.abs(com.oktsas.onekeytosetaserver.economy.MoneyService
                    .get(server, probe.getUUID()) - 1000.0D) < 1.0E-6;
            boolean nothingAdded = com.oktsas.onekeytosetaserver.domain.DomainManager
                    .byName("minecraft:overworld", "锁定领地") == null;

            // 3) an empty file is not an empty store: memory keeps its claims and writes stay blocked
            java.nio.file.Files.writeString(resFile, "   ");
            com.oktsas.onekeytosetaserver.domain.DomainManager.ReloadResult blank =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.reload();
            boolean blankRefused = !blank.ok()
                    && com.oktsas.onekeytosetaserver.domain.DomainManager.byId("lock_a") != null
                    && !com.oktsas.onekeytosetaserver.domain.DomainManager.writable();

            // 4) put the real store back
            java.nio.file.Files.writeString(resFile, originalRes);
            com.oktsas.onekeytosetaserver.domain.DomainManager.ReloadResult back =
                    com.oktsas.onekeytosetaserver.domain.DomainManager.reload();
            boolean storeRestored = back.ok()
                    && com.oktsas.onekeytosetaserver.domain.DomainManager.writable()
                    && com.oktsas.onekeytosetaserver.domain.DomainManager.all().size() == claimsBefore;

            // 5) the market: a broken listings.json is never overwritten and blocks selling
            java.nio.file.Path tempRoot = gameDir.resolve("selftest_market_lock");
            java.nio.file.Path brokenDir = tempRoot.resolve("multiverse").resolve("market");
            java.nio.file.Files.createDirectories(brokenDir);
            java.nio.file.Path brokenFile = brokenDir.resolve("listings.json");
            java.nio.file.Files.writeString(brokenFile, "[{\"id\":");
            com.oktsas.onekeytosetaserver.market.MarketManager.loadAll(tempRoot);
            boolean marketLocked = !com.oktsas.onekeytosetaserver.market.MarketManager.listingsWritable();
            String brokenText = java.nio.file.Files.readString(brokenFile);
            com.oktsas.onekeytosetaserver.market.MarketManager.saveAll();
            boolean marketFileKept = brokenText.equals(java.nio.file.Files.readString(brokenFile));
            int listingsBefore = com.oktsas.onekeytosetaserver.market.MarketManager.listings().size();
            boolean sellRefused = !com.oktsas.onekeytosetaserver.market.MarketManager
                    .sell(probe, new ItemStack(Items.DIAMOND, 1), 10.0D, 24)
                    && com.oktsas.onekeytosetaserver.market.MarketManager.listings().size() == listingsBefore;

            OneKeyToSetAServer.LOGGER.info("[SELFTEST] store lock: resLocked={} fileKept={} createRefused={} "
                            + "notCharged={} nothingAdded={} blankRefused={} resRestored={} marketLocked={} "
                            + "marketFileKept={} sellRefused={}",
                    lockedAfterSkips, fileKept, createRefused, notCharged, nothingAdded, blankRefused,
                    storeRestored, marketLocked, marketFileKept, sellRefused);
            java.nio.file.Files.deleteIfExists(brokenFile);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] store lock test failed", t);
        } finally {
            // whatever happened, the running server has to end up with the real market directory again
            try {
                com.oktsas.onekeytosetaserver.market.MarketManager.loadAll(gameDir);
                OneKeyToSetAServer.LOGGER.info("[SELFTEST] store lock: market restored={} dir={}",
                        com.oktsas.onekeytosetaserver.market.MarketManager.listingsWritable(), marketDir);
            } catch (Throwable t) {
                OneKeyToSetAServer.LOGGER.error("[SELFTEST] store lock: could not restore the market", t);
            }
        }
    }

    /**
     * 0.2.5: the two operator commands really work through the command dispatcher (that is also a check of the
     * Brigadier tree and the op-4 gate): {@code /mv inventory inspect <player>} reports a pool file without
     * touching it, {@code /mv inventory recover <player>} mails its contents and renames the file.
     */
    private static void inventoryCommandTest(MinecraftServer server) {
        try {
            java.nio.file.Path gameDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
            java.nio.file.Path pool = gameDir.resolve("multiverse")
                    .resolve(com.oktsas.onekeytosetaserver.inventory.PlayerInventoryStore.GLOBAL_DIR);
            FakePlayer probe = FakePlayerFactory.get(server.overworld(), new GameProfile(
                    UUID.nameUUIDFromBytes("oktsas_invcmd_probe".getBytes(StandardCharsets.UTF_8)),
                    "oktsas_invcmd_probe"));
            probe.getInventory().clearContent();
            probe.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 4));
            com.oktsas.onekeytosetaserver.market.MarketManager.deliverMail(probe);
            int mailBefore = com.oktsas.onekeytosetaserver.market.MarketManager.mailboxSize(probe.getUUID());
            boolean saved = PlayerInventoryStore.save(probe, pool);
            java.nio.file.Path file = PlayerInventoryStore.snapshotFile(pool, probe.getUUID());

            // A pool file that still holds accessories must not lose them just because this server cannot read
            // accessory slots at all (Curios not installed). Only observable in the Curios-less dev run.
            boolean backfill = true;
            if (!PlayerInventoryStore.curiosLoaded()) {
                try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
                    net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(in);
                    net.minecraft.nbt.CompoundTag entry = new net.minecraft.nbt.CompoundTag();
                    entry.putString("Identifier", "backfill_test");
                    net.minecraft.nbt.CompoundTag stacks = new net.minecraft.nbt.CompoundTag();
                    stacks.putInt("Size", 1);
                    net.minecraft.nbt.ListTag items = new net.minecraft.nbt.ListTag();
                    net.minecraft.nbt.CompoundTag item = new net.minecraft.nbt.CompoundTag();
                    item.putInt("Slot", 0);
                    new ItemStack(Items.GOLD_INGOT, 2).save(item);
                    items.add(item);
                    stacks.put("Items", items);
                    entry.put("Stacks", stacks);
                    net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
                    list.add(entry);
                    root.put("curios", list);
                    try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(file)) {
                        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
                    }
                }
                PlayerInventoryStore.save(probe, pool);
                try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
                    net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(in);
                    backfill = root.getList("curios", net.minecraft.nbt.CompoundTag.TAG_COMPOUND).size() == 1;
                }
            }

            CommandSourceStack source = server.createCommandSourceStack();
            int inspected = server.getCommands().performPrefixedCommand(source,
                    "mv inventory inspect " + probe.getUUID());
            boolean fileUntouched = java.nio.file.Files.isRegularFile(file);

            int recovered = server.getCommands().performPrefixedCommand(source,
                    "mv inventory recover " + probe.getUUID());
            boolean renamed = !java.nio.file.Files.isRegularFile(file)
                    && java.nio.file.Files.isRegularFile(pool.resolve(file.getFileName() + ".recovered"));
            int mailAfter = com.oktsas.onekeytosetaserver.market.MarketManager.mailboxSize(probe.getUUID());
            boolean mailed = mailAfter == mailBefore + 1;

            // a second run must not hand anything out again (the file is gone)
            int second = server.getCommands().performPrefixedCommand(source,
                    "mv inventory recover " + probe.getUUID());

            // clean up: take the mail, then remove the renamed file
            com.oktsas.onekeytosetaserver.market.MarketManager.deliverMail(probe);
            java.nio.file.Files.deleteIfExists(pool.resolve(file.getFileName() + ".recovered"));

            OneKeyToSetAServer.LOGGER.info("[SELFTEST] inventory commands: saved={} backfill={} inspected={} "
                            + "fileUntouched={} recovered={} renamed={} mailed={} secondRun={}",
                    saved, backfill, inspected, fileUntouched, recovered, renamed, mailed, second);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] inventory command test failed", t);
        }
    }

    private static void step(MinecraftServer server, CommandSourceStack source, String command) {        OneKeyToSetAServer.LOGGER.info("[SELFTEST] >>> {}", command);
        try {
            server.getCommands().performPrefixedCommand(source, command);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[SELFTEST] command failed: {}", command, t);
        }
    }
}
