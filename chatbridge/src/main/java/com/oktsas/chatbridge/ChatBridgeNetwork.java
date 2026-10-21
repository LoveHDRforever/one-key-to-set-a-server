package com.oktsas.chatbridge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Listens for the button list pushed by the server side of onekeytosetaserver.
 *
 * <p>The channel belongs to the main mod's namespace ({@code onekeytosetaserver:chatbuttons}) because the
 * server owns the list. Both sides accept "vanilla" (i.e. absent) so a client without this addon, or a server
 * without the main mod, simply never exchanges anything.</p>
 */
public final class ChatBridgeNetwork {

    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("onekeytosetaserver", ChatBridge.CHANNEL),
            () -> VERSION, version -> true, version -> true);

    private ChatBridgeNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, SyncButtons.class, SyncButtons::encode, SyncButtons::decode,
                SyncButtons::handle);
    }

    /** The whole button list as one JSON string - the same shape as the local config file. */
    public record SyncButtons(String json) {

        public static void encode(SyncButtons message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.json == null ? "[]" : message.json, 262144);
        }

        public static SyncButtons decode(FriendlyByteBuf buffer) {
            return new SyncButtons(buffer.readUtf(262144));
        }

        public static void handle(SyncButtons message, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> ChatButtons.replace(message.json));
            context.get().setPacketHandled(true);
        }
    }
}
