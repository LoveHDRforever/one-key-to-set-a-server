package com.oktsas.onekeytosetaserver.chatbutton;

import com.oktsas.onekeytosetaserver.OneKeyToSetAServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Pushes the button list to clients that run the e33chat addon.
 *
 * <h2>Who owns the channel</h2>
 * <p>There is exactly <b>one</b> channel named {@code onekeytosetaserver:chatbuttons}, and Forge refuses to
 * register the same name twice ("NetworkDirection Channel ... already registered"). The addon
 * ({@value #ADDON_MODID}) registers it on the client to receive, the server side registers it to send - and a
 * client that has both jars would therefore register it twice and <b>fail to start</b>. That is the crash the
 * owner reported, so the rule now is:</p>
 *
 * <ul>
 *   <li>the addon is installed ({@link #addonPresent()}) -&gt; the addon owns the channel, this jar registers
 *       nothing and sends nothing. A dedicated server never has the addon (it is marked
 *       {@code clientSideOnly}), so this only affects clients and single player, where the addon keeps its own
 *       local button list.</li>
 *   <li>the addon is absent -&gt; this jar registers the channel and pushes the list, exactly as before.</li>
 * </ul>
 *
 * <p>The channel is created lazily for the same reason: a static field initializer would run on class load and
 * collide before {@link #register()} ever gets the chance to bail out.</p>
 */
public class ChatButtonNetwork {

    public static final String CHANNEL_NAME = "chatbuttons";
    /** The client side addon that owns the channel when it is installed next to this jar. */
    public static final String ADDON_MODID = "oktsas_chatbridge";
    private static final String VERSION = "1";

    private static SimpleChannel channel;
    private static Boolean addonPresent;

    private static SimpleChannel channel() {
        if (channel == null) {
            channel = NetworkRegistry.newSimpleChannel(
                    new ResourceLocation(OneKeyToSetAServer.MODID, CHANNEL_NAME),
                    () -> VERSION, version -> true, version -> true);
        }
        return channel;
    }

    /** Whether the e33chat addon is loaded in this process; remembered because it cannot change later. */
    public static boolean addonPresent() {
        if (addonPresent == null) {
            boolean present = false;
            try {
                present = ModList.get() != null && ModList.get().isLoaded(ADDON_MODID);
            } catch (Throwable t) {
                OneKeyToSetAServer.LOGGER.debug("[ChatButton] could not ask whether {} is loaded: {}",
                        ADDON_MODID, t.toString());
            }
            addonPresent = present;
        }
        return addonPresent;
    }

    public static void register() {
        if (addonPresent()) {
            OneKeyToSetAServer.LOGGER.info("[ChatButton] {} is installed - it owns the {} channel, this jar "
                            + "registers nothing (registering it twice is what crashed the client)",
                    ADDON_MODID, CHANNEL_NAME);
            return;
        }
        channel().registerMessage(0, SyncButtons.class, SyncButtons::encode, SyncButtons::decode,
                SyncButtons::handle);
    }

    /** Sends the current list to one player; only when this jar owns the channel. */
    public static void send(ServerPlayer player) {
        if (addonPresent() || player == null || player.connection == null) {
            return;
        }
        try {
            if (!channel().isRemotePresent(player.connection.connection)) {
                return;
            }
            channel().send(PacketDistributor.PLAYER.with(() -> player),
                    new SyncButtons(ChatButtonStore.toJson()));
        } catch (Throwable t) {
            OneKeyToSetAServer.LOGGER.debug("[ChatButton] could not sync to {}: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }

    public static void sendAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player);
        }
    }

    /** New arrivals get the list as soon as they are in. */
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player);
        }
    }

    /** The whole list as one JSON string - the same shape as the addon's local config file. */
    public record SyncButtons(String json) {

        public static void encode(SyncButtons message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.json == null ? "[]" : message.json, 262144);
        }

        public static SyncButtons decode(FriendlyByteBuf buffer) {
            return new SyncButtons(buffer.readUtf(262144));
        }

        public static void handle(SyncButtons message, Supplier<NetworkEvent.Context> context) {
            // The server never receives this packet; the addon's own copy of the channel does the receiving.
            context.get().setPacketHandled(true);
        }
    }
}
