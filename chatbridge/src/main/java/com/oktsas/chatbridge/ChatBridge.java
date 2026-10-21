package com.oktsas.chatbridge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Client side addon for <b>e33chat</b> and <b>onekeytosetaserver</b>: a small column of configurable command
 * buttons at the bottom of the chat sidebar.
 *
 * <p>This jar is an addon: it does nothing on its own. {@code mods.toml} marks it {@code clientSideOnly} and
 * declares e33chat as a required dependency and onekeytosetaserver as an optional one. Buttons come from
 * {@code config/oktsas-chatbuttons.json} and can be replaced at runtime by the server (the addon listens on
 * the {@code onekeytosetaserver:chatbuttons} channel).</p>
 */
@Mod(ChatBridge.MODID)
public class ChatBridge {

    public static final String MODID = "oktsas_chatbridge";
    public static final String CHANNEL = "chatbuttons";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ChatBridge() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            // mods.toml marks this mod clientSideOnly, so this should never run - but never touch client
            // classes unless we are really on a client.
            return;
        }
        ChatButtons.load();
        ChatBridgeNetwork.register();
        LOGGER.info("[ChatBridge] ready - an addon for e33chat + onekeytosetaserver");
    }
}
