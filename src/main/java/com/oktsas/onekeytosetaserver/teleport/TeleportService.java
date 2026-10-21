package com.oktsas.onekeytosetaserver.teleport;

import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The delayed teleports every travel command shares: {@code /res tp}, {@code /home}, {@code /spawn},
 * {@code /warp}, {@code /tpa}, {@code /back}, {@code /rtp}.
 *
 * <p>A request waits {@code delaySeconds} and is then executed on the server thread. Jumping and taking
 * damage cancel it when the caller asks for that; plain walking deliberately does <b>not</b> - the owner
 * asked for "跳跃打断" instead of the usual "移动打断".</p>
 */
public class TeleportService {

    private record Pending(ServerPlayer player, long readyAt, int delaySeconds, String label,
                           boolean interruptOnJump, boolean interruptOnHurt, Runnable action) {
    }

    private static final Map<UUID, Pending> PENDING = new LinkedHashMap<>();

    public static boolean isPending(UUID player) {
        return player != null && PENDING.containsKey(player);
    }

    /** Queues {@code action}; with a zero delay it runs immediately. */
    public static void request(ServerPlayer player, int delaySeconds, String label,
                               boolean interruptOnJump, boolean interruptOnHurt, Runnable action) {
        if (player == null) {
            return;
        }
        if (delaySeconds <= 0) {
            run(action, label);
            return;
        }
        PENDING.put(player.getUUID(), new Pending(player,
                System.currentTimeMillis() + delaySeconds * 1000L, delaySeconds, label,
                interruptOnJump, interruptOnHurt, action));
        player.displayClientMessage(Component.literal("§e即将前往 §f" + label + "§e，"
                + (interruptOnJump || interruptOnHurt ? "别跳、别挨打" : "请稍等")
                + "（" + delaySeconds + " 秒）"), true);
    }

    /** Drops a queued teleport; returns true when one was actually waiting. */
    public static boolean cancel(ServerPlayer player, String reason) {
        if (player == null) {
            return false;
        }
        if (PENDING.remove(player.getUUID()) == null) {
            return false;
        }
        if (reason != null) {
            player.displayClientMessage(Component.literal(reason), true);
        }
        return true;
    }

    public static void clear() {
        PENDING.clear();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        // interrupts first
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            Pending pending = PENDING.get(player.getUUID());
            if (pending == null) {
                continue;
            }
            if (pending.interruptOnJump && !player.onGround() && player.getDeltaMovement().y > 0.0D) {
                cancel(player, "§c检测到你跳了一下，传送已取消");
                continue;
            }
            if (pending.interruptOnHurt && player.hurtTime > 0) {
                cancel(player, "§c你受到了伤害，传送已取消");
            }
        }
        Iterator<Map.Entry<UUID, Pending>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next().getValue();
            if (pending.player.hasDisconnected() || pending.player.isRemoved()) {
                iterator.remove();
                continue;
            }
            if (now >= pending.readyAt) {
                iterator.remove();
                run(pending.action, pending.label);
            }
        }
    }

    private static void run(Runnable action, String label) {
        try {
            action.run();
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Teleport] {} failed", label, t);
        }
    }
}
