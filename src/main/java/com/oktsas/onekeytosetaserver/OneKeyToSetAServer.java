package com.oktsas.onekeytosetaserver;

import com.mojang.logging.LogUtils;
import com.oktsas.onekeytosetaserver.command.MvCommand;
import com.oktsas.onekeytosetaserver.teleport.TeleportQueue;
import com.oktsas.onekeytosetaserver.world.AutoResetTicker;
import com.oktsas.onekeytosetaserver.world.MvWorld;
import com.oktsas.onekeytosetaserver.world.MvWorldManager;
import com.oktsas.onekeytosetaserver.world.WorldPropertyListener;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * One Key To Set A Server - multiverse / teleport / per-world rules, later joined by per-world
 * inventories, shops and domains.
 */
@Mod(OneKeyToSetAServer.MODID)
public class OneKeyToSetAServer {
    public static final String MODID = "onekeytosetaserver";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static MinecraftServer server;

    public OneKeyToSetAServer(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        modBus.addListener(this::commonSetup);
        // optional channel: only clients that run the e33chat addon ever receive anything on it
        com.oktsas.onekeytosetaserver.chatbutton.ChatButtonNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new WorldPropertyListener());
        MinecraftForge.EVENT_BUS.register(new com.oktsas.onekeytosetaserver.inventory.InventoryListener());
        MinecraftForge.EVENT_BUS.register(new com.oktsas.onekeytosetaserver.menu.MenuEditor());
        MinecraftForge.EVENT_BUS.register(new com.oktsas.onekeytosetaserver.domain.DomainProtection());
        MinecraftForge.EVENT_BUS.register(new com.oktsas.onekeytosetaserver.teleport.TeleportService());
        MinecraftForge.EVENT_BUS.register(new com.oktsas.onekeytosetaserver.essentials.EssentialsEvents());
        MinecraftForge.EVENT_BUS.register(new com.oktsas.onekeytosetaserver.chatbutton.ChatButtonNetwork());
        MinecraftForge.EVENT_BUS.register(new com.oktsas.onekeytosetaserver.command.ChatButtonCommand());
    }

    public static MinecraftServer server() {
        return server;
    }

    /**
     * Runs while mods are loading, i.e. before the server reads the world folder and its datapacks, which is
     * the only window where a generated dimension datapack still counts for this boot.
     */
    private void commonSetup(final FMLCommonSetupEvent event) {
        MvWorldManager.startupScan();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        MvCommand.register(event.getDispatcher());
        com.oktsas.onekeytosetaserver.command.EconomyCommand.register(event.getDispatcher());
        com.oktsas.onekeytosetaserver.command.MarketCommand.register(event.getDispatcher());
        com.oktsas.onekeytosetaserver.command.MenuCommand.register(event.getDispatcher());
        com.oktsas.onekeytosetaserver.command.ResCommand.register(event.getDispatcher());
        com.oktsas.onekeytosetaserver.essentials.EssentialsCommands.register(event.getDispatcher());
        com.oktsas.onekeytosetaserver.command.ChatButtonCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        TeleportQueue.drain(event.getServer());
        AutoResetTicker.tick(event.getServer());
        if (event.getServer().getTickCount() % 100 == 0) {
            com.oktsas.onekeytosetaserver.market.MarketManager.tick(event.getServer(), System.currentTimeMillis());
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        server = event.getServer();
        for (MvWorld world : MvWorldManager.all()) {
            LOGGER.info("[MV] known world {} (folder={}, env={}, generator={})",
                    world.dimensionPath, world.folder, world.environment, world.generator);
        }
    }

    /** Root of the old NPC feature's per-world stores - one {@code npcs.json} per managed world. */
    private static final String LEGACY_NPC_FILE = "npcs.json";

    /**
     * The command-NPC module was removed on the owner's request, so its leftover files go too: without this
     * they would just sit in every world folder forever. Each deletion is logged so the operator can see
     * exactly what happened on the first boot after the upgrade.
     *
     * @return how many files were deleted
     */
    private static int purgeLegacyNpcData(java.nio.file.Path gameDir) {
        if (gameDir == null) {
            return 0;
        }
        java.util.List<java.nio.file.Path> roots = new java.util.ArrayList<>();
        roots.add(gameDir);
        if (MvWorldManager.gameDir() != null) {
            try (var stream = java.nio.file.Files.list(MvWorldManager.gameDir())) {
                stream.filter(java.nio.file.Files::isDirectory).forEach(roots::add);
            } catch (Throwable t) {
                LOGGER.warn("[MV] could not scan the server folder for old NPC data: {}", t.toString());
            }
        }
        int removed = 0;
        for (java.nio.file.Path root : roots) {
            java.nio.file.Path file = root.resolve("multiverse").resolve(LEGACY_NPC_FILE);
            if (!java.nio.file.Files.isRegularFile(file)) {
                continue;
            }
            try {
                java.nio.file.Files.delete(file);
                removed++;
                LOGGER.info("[MV] removed the old NPC data file {} (the NPC module is gone)", file);
            } catch (Throwable t) {
                LOGGER.warn("[MV] could not delete {}: {}", file, t.toString());
            }
        }
        if (removed > 0) {
            LOGGER.info("[MV] NPC cleanup: {} old npcs.json file(s) deleted", removed);
        }
        return removed;
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        java.nio.file.Path gameDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
        com.oktsas.onekeytosetaserver.menu.MenuSlots.loadConfig(gameDir);
        purgeLegacyNpcData(gameDir);
        com.oktsas.onekeytosetaserver.shop.ShopManager.loadAll(gameDir);
        com.oktsas.onekeytosetaserver.market.MarketManager.loadAll(gameDir);
        com.oktsas.onekeytosetaserver.menu.MenuManager.loadAll(gameDir);
        com.oktsas.onekeytosetaserver.domain.DomainManager.loadAll(gameDir);
        com.oktsas.onekeytosetaserver.world.WorldPropertyListener.loadConfig(gameDir);
        com.oktsas.onekeytosetaserver.essentials.EssentialsData.loadAll(gameDir);
        com.oktsas.onekeytosetaserver.chatbutton.ChatButtonStore.loadAll(gameDir);
        // runtime-only worlds come online without a restart
        for (MvWorld world : MvWorldManager.runtimeOnlyWorlds().toArray(new MvWorld[0])) {
            MvWorldManager.loadRuntime(world);
        }
        long loaded = 0;
        for (MvWorld world : MvWorldManager.all()) {
            if (MvWorldManager.levelOf(world) != null) {
                loaded++;
            }
        }
        LOGGER.info("[MV] server started: {}/{} managed world(s) online", loaded, MvWorldManager.all().size());
        // worlds that came online through the startup datapack never ran /mv create, so they get their spawn
        // point recorded here (the ones loaded at runtime were already handled by loadRuntime)
        MvWorldManager.applyDefaultSpawns();
        com.oktsas.onekeytosetaserver.dev.SelfTest.run(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        MvWorldManager.saveAll();
        com.oktsas.onekeytosetaserver.menu.MenuManager.save();
        com.oktsas.onekeytosetaserver.menu.MenuEditor.clear();
        com.oktsas.onekeytosetaserver.domain.DomainManager.save();
        com.oktsas.onekeytosetaserver.domain.DomainManager.clearCache();
        com.oktsas.onekeytosetaserver.domain.DomainProtection.clear();
        server = null;
    }
}
