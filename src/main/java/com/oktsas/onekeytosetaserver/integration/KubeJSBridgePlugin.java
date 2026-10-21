package com.oktsas.onekeytosetaserver.integration;

import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;

/**
 * KubeJS integration.
 *
 * <p>KubeJS discovers this class through {@code kubejs.plugins.txt} in this jar, so nothing here is loaded
 * unless KubeJS itself is installed - the mod keeps working on a plain Forge server. Scripts then get the
 * global binding {@code oktsas}:</p>
 *
 * <pre>
 * oktsas.worlds()
 * oktsas.loadWorld('resource_world')
 * oktsas.addMoney(player.username, 500)
 * oktsas.mailItem(player.username, 'minecraft:diamond', 3)
 * </pre>
 */
public class KubeJSBridgePlugin extends KubeJSPlugin {

    public KubeJSBridgePlugin() {
    }

    @Override
    public void registerBindings(BindingsEvent event) {
        event.add("oktsas", new OktsasBindings());
    }
}
