package com.oktsas.onekeytosetaserver.inventory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Keeps per-world inventories in sync with where the player is: swap on world change, persist on logout,
 * restore on login.
 */
public final class InventoryListener {

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) {
            return;
        }
        ServerLevel from = player.getServer().getLevel(event.getFrom());
        ServerLevel to = player.getServer().getLevel(event.getTo());
        PlayerInventoryStore.swap(player, from, to);
    }

    @SubscribeEvent
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerInventoryStore.adoptOrLoad(player);
            int delivered = com.oktsas.onekeytosetaserver.market.MarketManager.deliverMail(player);
            if (delivered > 0) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§a已自动领取邮箱中的 " + delivered + " 个物品"));
            }
        }
    }

    @SubscribeEvent
    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerInventoryStore.save(player, PlayerInventoryStore.poolFor(player.serverLevel()));
        }
    }
}
