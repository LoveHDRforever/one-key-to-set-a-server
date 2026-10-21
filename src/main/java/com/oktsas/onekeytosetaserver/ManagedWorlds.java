package com.oktsas.onekeytosetaserver;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of the worlds this mod manages.
 *
 * <p>Every managed world keeps its own folder in the server root (the requirement is that the folder
 * survives on its own: import, backup, and "delete the folder to reset" all depend on it).  Vanilla
 * would put dimension data under {@code <mainWorld>/dimensions/<namespace>/<path>}, so the mapping
 * here is what the storage-path mixin uses to send each managed dimension to its own folder.</p>
 */
public final class ManagedWorlds {
    /** dimension path (the part after the namespace) -> folder name in the server root */
    private static final Map<String, String> FOLDER_BY_PATH = new ConcurrentHashMap<>();

    private ManagedWorlds() {
    }

    public static void register(String dimensionPath, String folderName) {
        FOLDER_BY_PATH.put(dimensionPath, folderName);
    }

    public static void unregister(String dimensionPath) {
        FOLDER_BY_PATH.remove(dimensionPath);
    }

    public static void clear() {
        FOLDER_BY_PATH.clear();
    }

    public static int size() {
        return FOLDER_BY_PATH.size();
    }

    /**
     * @return the root folder name that should hold this dimension's data, or {@code null} when the
     *         dimension is not managed by this mod (vanilla dimensions must keep vanilla paths).
     */
    public static String folderFor(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return null;
        }
        var location = dimension.location();
        if (!OneKeyToSetAServer.MODID.equals(location.getNamespace())) {
            return null;
        }
        String folder = FOLDER_BY_PATH.get(location.getPath());
        return folder != null ? folder : location.getPath();
    }
}
