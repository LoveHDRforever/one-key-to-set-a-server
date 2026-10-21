package com.oktsas.onekeytosetaserver.teleport;

import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import net.minecraft.server.MinecraftServer;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Phase 0 Spike D, part 1: every cross-dimension transfer goes through this queue and is executed at the
 * end of a server tick.
 *
 * <p>Never change dimension from inside an entity tick: the entity can end up half-removed from the old
 * level and not yet added to the new one, which is how servers crash on cross-dimension travel.</p>
 */
public final class TeleportQueue {
    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    private TeleportQueue() {
    }

    public static void submit(Runnable task) {
        PENDING.add(task);
    }

    public static int size() {
        return PENDING.size();
    }

    /** Called from the server tick event (Phase.END). */
    public static void drain(MinecraftServer server) {
        Runnable task;
        while ((task = PENDING.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                OneKeyToSetAServer.LOGGER.error("[Phase0] queued teleport task failed", t);
            }
        }
    }
}
