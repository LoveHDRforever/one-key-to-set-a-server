package com.oktsas.onekeytosetaserver.world;

import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import com.oktsas.onekeytosetaserver.teleport.MvTeleporter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Automatic world reset (the ResourceWorld-style feature): per world interval, chat warnings at
 * 10 / 5 / 2 / 1 minutes and 30 seconds, players evacuated to the main world, then the world content is
 * wiped and regenerated - optionally with a new seed.
 */
public final class AutoResetTicker {
    private static final long[] MARKS = {10L * 60_000L, 5L * 60_000L, 2L * 60_000L, 60_000L, 30_000L};

    private static final Map<String, Long> NEXT = new HashMap<>();
    private static final Set<String> ANNOUNCED = new HashSet<>();

    private AutoResetTicker() {
    }

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 100 != 0) {
            return; // five second resolution is plenty for hour-scale intervals
        }
        long now = System.currentTimeMillis();
        for (MvWorld world : MvWorldManager.all()) {
            if (!world.autoReset) {
                NEXT.remove(world.folder);
                clearAnnouncements(world);
                continue;
            }
            long interval = Math.max(1, world.autoResetHours) * 3_600_000L;
            long next = NEXT.computeIfAbsent(world.folder, key -> now + interval);
            long left = next - now;
            for (long mark : MARKS) {
                if (left <= mark && left > mark - 5_000L && ANNOUNCED.add(world.folder + "@" + mark)) {
                    broadcast(server, "世界 " + world.displayName() + " 将在 " + (mark / 60_000L) + " 分钟后重置");
                }
            }
            if (left > 0) {
                continue;
            }
            ServerLevel level = MvWorldManager.levelOf(world);
            if (level != null && !level.players().isEmpty()) {
                if (ANNOUNCED.add(world.folder + "@evacuating")) {
                    broadcast(server, "世界 " + world.displayName() + " 正在重置，玩家将被移出");
                }
                evacuate(server, world, level);
                continue; // teleports run at the end of this tick; reset on the next pass
            }
            broadcast(server, "世界 " + world.displayName() + " 正在重置…");
            MvWorldManager.reset(world);
            broadcast(server, "世界 " + world.displayName() + " 已重置");
            NEXT.put(world.folder, now + interval);
            clearAnnouncements(world);
        }
    }

    private static void clearAnnouncements(MvWorld world) {
        ANNOUNCED.removeIf(key -> key.startsWith(world.folder + "@"));
    }

    private static void broadcast(MinecraftServer server, String message) {
        OneKeyToSetAServer.LOGGER.info("[MV] {}", message);
        server.getPlayerList().broadcastSystemMessage(Component.literal("§e[世界] §f" + message), false);
    }

    private static void evacuate(MinecraftServer server, MvWorld world, ServerLevel level) {
        ServerLevel target = server.overworld();
        for (ServerPlayer player : new ArrayList<>(level.players())) {
            MvTeleporter.teleportPlayer(player, target, 0.5D, -1.0D, 0.5D, player.getYRot(), player.getXRot());
        }
    }
}
