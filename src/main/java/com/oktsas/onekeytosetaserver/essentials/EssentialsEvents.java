package com.oktsas.onekeytosetaserver.essentials;

import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Event glue for the Essentials style commands: mute enforcement, {@code /ignore} filtering, the {@code /nick}
 * chat prefix, the {@code /r} reply book, the death point for {@code /back}, the admin toggles that have to be
 * re-applied after a login, and the {@code /powertool} trigger.
 */
public class EssentialsEvents {

    public EssentialsEvents() {
    }

    // ------------------------------------------------------------------ session

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        EssentialsData.touch(player.getUUID(), player.getGameProfile().getName());
        EssentialsData.PlayerData data = EssentialsData.of(player.getUUID());
        if (data.god) {
            player.getAbilities().invulnerable = true;
            player.onUpdateAbilities();
        }
        if (data.vanish) {
            player.setInvisible(true);
            player.setInvulnerable(true);
        }
        if (EssentialsData.isMuted(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c你还在被禁言中（"
                    + EssentialsData.muteRemaining(player.getUUID()) + "）"));
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EssentialsData.markOffline(player.getUUID(), player.getGameProfile().getName());
            EssentialsCommands.socialSpies().remove(player.getUUID());
        }
    }

    /** Death point goes into the same queue {@code /back} reads from. */
    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EssentialsData.pushBack(player.getUUID(), EssentialsData.Point.of(player, "死亡点"));
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.getServer().getTickCount() % 20 == 0) {
            EssentialsCommands.tick();
        }
    }

    // ------------------------------------------------------------------ chat

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        ServerPlayer sender = event.getPlayer();
        MinecraftServer server = sender.getServer();
        if (server == null) {
            return;
        }
        String raw = event.getRawText() == null ? "" : event.getRawText();

        EssentialsData.PlayerData data = EssentialsData.of(sender.getUUID());
        if (data.afk) {
            data.afk = false;
            EssentialsData.save();
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("§7" + sender.getGameProfile().getName() + " 回来了"), false);
        }

        if (EssentialsData.isMuted(sender.getUUID())) {
            event.setCanceled(true);
            String reason = data.muteReason == null || data.muteReason.isBlank() ? "" : " 原因: " + data.muteReason;
            sender.sendSystemMessage(Component.literal("§c你被禁言了（"
                    + EssentialsData.muteRemaining(sender.getUUID()) + "）" + reason));
            return;
        }

        String nick = data.nick == null ? "" : data.nick.trim();
        String senderKey = sender.getGameProfile().getName().toLowerCase(Locale.ROOT);
        boolean filtered = false;
        List<ServerPlayer> receivers = new ArrayList<>();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (EssentialsData.of(other.getUUID()).ignore.contains(senderKey)) {
                filtered = true;
            } else {
                receivers.add(other);
            }
        }
        if (nick.isEmpty() && !filtered) {
            return;   // plain vanilla chat: leave the event alone so other mods keep working
        }
        event.setCanceled(true);
        Component rendered = Component.literal((nick.isEmpty() ? "" : "§7[" + nick + "] ")
                + "<" + sender.getGameProfile().getName() + "> " + raw);
        for (ServerPlayer receiver : receivers) {
            receiver.sendSystemMessage(rendered);
        }
        OneKeyToSetAServer.LOGGER.info("[Chat] <{}> {}", sender.getGameProfile().getName(), raw);
    }

    /**
     * Remembers who whispered to whom so {@code /r} can answer, and mirrors whispers to {@code /socialspy}
     * holders. The vanilla command still does the actual sending.
     */
    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        MinecraftServer server = event.getParseResults().getContext().getSource().getServer();
        if (server == null || !(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer sender)) {
            return;
        }
        String input = event.getParseResults().getReader().getString();
        if (input == null) {
            return;
        }
        String trimmed = input.startsWith("/") ? input.substring(1) : input;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String body = null;
        for (String prefix : new String[]{"msg ", "tell ", "w ", "whisper ", "m "}) {
            if (lower.startsWith(prefix)) {
                body = trimmed.substring(prefix.length()).trim();
                break;
            }
        }
        if (body == null || body.isEmpty()) {
            return;
        }
        int space = body.indexOf(' ');
        if (space <= 0) {
            return;
        }
        String targetName = body.substring(0, space);
        ServerPlayer target = server.getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            return;
        }
        EssentialsCommands.replyBook().put(sender.getUUID(), target.getUUID());
        EssentialsCommands.replyBook().put(target.getUUID(), sender.getUUID());
        String text = body.substring(space + 1);
        for (UUID spy : EssentialsCommands.socialSpies()) {
            if (spy.equals(sender.getUUID()) || spy.equals(target.getUUID())) {
                continue;
            }
            ServerPlayer watcher = server.getPlayerList().getPlayer(spy);
            if (watcher != null) {
                watcher.sendSystemMessage(Component.literal("§8[社交间谍] " + sender.getGameProfile().getName()
                        + " -> " + target.getGameProfile().getName() + ": " + text));
            }
        }
    }

    // ------------------------------------------------------------------ powertool

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        powertool(event);
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        powertool(event);
    }

    private void powertool(PlayerInteractEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String bound = EssentialsData.powertool(player.getUUID());
        if (bound == null) {
            return;
        }
        int split = bound.indexOf('|');
        if (split <= 0) {
            return;
        }
        String itemId = bound.substring(0, split);
        String command = bound.substring(split + 1);
        String held = String.valueOf(ForgeRegistries.ITEMS.getKey(player.getMainHandItem().getItem()));
        if (!itemId.equals(held)) {
            return;
        }
        event.setCanceled(true);
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        String cleaned = command.startsWith("/") ? command.substring(1) : command;
        try {
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), cleaned);
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.error("[Ess] powertool command failed: {}", cleaned, t);
        }
    }
}
