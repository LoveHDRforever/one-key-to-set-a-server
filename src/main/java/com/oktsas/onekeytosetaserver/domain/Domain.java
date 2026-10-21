package com.oktsas.onekeytosetaserver.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One claim (领地): an axis aligned box in one dimension plus its protection flags.
 *
 * <p>The box is always a real cuboid - {@code minY}/{@code maxY} come from the two blocks the owner
 * clicked, and the claim is paid for by volume ({@code sizeX * sizeZ * sizeY} blocks). Owners may grow or
 * shrink it with {@code /res height up|down}.</p>
 *
 * <p>Every {@link Flag} is a <b>deny</b> switch that applies to players who are neither the owner nor a
 * trusted member. {@code /res pset} additionally stores per-player overrides, which is how a single
 * player is whitelisted (or blacklisted) for one flag.</p>
 */
public class Domain {

    /** Protection switches a claim owner can toggle. */
    public enum Flag {
        BREAK("breaking", "破坏方块"),
        PLACE("placing", "放置方块"),
        INTERACT("interact", "右键交互（按钮/门/箱子）"),
        USE_ITEM("useitem", "在领地内使用物品"),
        PVP("pvp", "玩家之间的战斗"),
        EXPLOSION("explosion", "爆炸破坏"),
        MOB_GRIEFING("mobgriefing", "怪物破坏方块");

        /** Stable id used by commands and by the stored per-player overrides. */
        public final String id;
        /** Chinese description shown by {@code /res flags} and {@code /res info}. */
        public final String title;

        Flag(String id, String title) {
            this.id = id;
            this.title = title;
        }

        /** Parses an id, an English alias or the Chinese name; null when nothing matches. */
        public static Flag byId(String raw) {
            if (raw == null) {
                return null;
            }
            String key = raw.trim().toLowerCase(Locale.ROOT);
            for (Flag flag : values()) {
                if (flag.id.equals(key)) {
                    return flag;
                }
            }
            return switch (key) {
                case "break", "destroy", "破坏" -> BREAK;
                case "place", "build", "放置", "建筑" -> PLACE;
                case "use", "useitem", "use_item", "使用物品", "使用" -> USE_ITEM;
                case "interact", "container", "交互", "容器" -> INTERACT;
                case "pvp", "fight", "对战", "战斗" -> PVP;
                case "explosion", "tnt", "爆炸" -> EXPLOSION;
                case "mob", "mobgriefing", "mob_griefing", "怪物", "怪物破坏" -> MOB_GRIEFING;
                default -> null;
            };
        }
    }

    public String id = UUID.randomUUID().toString().substring(0, 8);
    public String owner = "";
    public String ownerName = "";
    /** Dimension id, e.g. {@code onekeytosetaserver:resource_world}. */
    public String dimension = "";
    /** Managing world folder, for messages only. */
    public String world = "";
    public String name = "";
    public int minX;
    public int maxX;
    public int minZ;
    public int maxZ;
    public int minY;
    public int maxY;
    public boolean denyInteract = true;
    public boolean denyBreak = true;
    public boolean denyPlace = true;
    public boolean denyUseItem = true;
    public boolean denyPvp = true;
    public boolean denyExplosion = true;
    public boolean denyMobGriefing = true;
    /** uuid -> flag id -> deny. A missing entry falls back to the claim wide value. */
    public Map<String, Map<String, Boolean>> playerFlags = new LinkedHashMap<>();
    /** uuid -> last known name. */
    public Map<String, String> members = new LinkedHashMap<>();
    public long createdAt = System.currentTimeMillis();

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    /** Height of the box; old unlimited claims (from before volume pricing) report a full column. */
    public int sizeY() {
        if (minY <= Integer.MIN_VALUE / 2 || maxY >= Integer.MAX_VALUE / 2) {
            return 384;
        }
        return Math.max(1, maxY - minY + 1);
    }

    /** How many blocks the box contains - this is what the claim cost is computed from. */
    public long volume() {
        return (long) sizeX() * (long) sizeZ() * (long) sizeY();
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= maxY;
    }

    public boolean inWorld(String dimensionId) {
        return dimension != null && dimension.equals(dimensionId);
    }

    public boolean isMember(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        String key = uuid.toString();
        return key.equals(owner) || members.containsKey(key);
    }

    public boolean isOwner(UUID uuid) {
        return uuid != null && uuid.toString().equals(owner);
    }

    /** True when this box and {@code other} share any block (only meaningful in the same dimension). */
    public boolean overlaps(Domain other) {
        if (other == null || !inWorld(other.dimension)) {
            return false;
        }
        return minX <= other.maxX && other.minX <= maxX
                && minZ <= other.maxZ && other.minZ <= maxZ
                && minY <= other.maxY && other.minY <= maxY;
    }

    /** The claim wide setting: true means "outsiders are not allowed to do this". */
    public boolean denies(Flag flag) {
        return switch (flag) {
            case INTERACT -> denyInteract;
            case BREAK -> denyBreak;
            case PLACE -> denyPlace;
            case USE_ITEM -> denyUseItem;
            case PVP -> denyPvp;
            case EXPLOSION -> denyExplosion;
            case MOB_GRIEFING -> denyMobGriefing;
        };
    }

    public void setDeny(Flag flag, boolean value) {
        switch (flag) {
            case INTERACT -> denyInteract = value;
            case BREAK -> denyBreak = value;
            case PLACE -> denyPlace = value;
            case USE_ITEM -> denyUseItem = value;
            case PVP -> denyPvp = value;
            case EXPLOSION -> denyExplosion = value;
            case MOB_GRIEFING -> denyMobGriefing = value;
        }
    }

    /** The effective value for one player: their own override when set, the claim value otherwise. */
    public boolean deniesFor(UUID uuid, Flag flag) {
        Boolean override = overrideOf(uuid, flag);
        return override != null ? override : denies(flag);
    }

    /** The per-player override, or null when this player follows the claim wide setting. */
    public Boolean overrideOf(UUID uuid, Flag flag) {
        if (uuid == null) {
            return null;
        }
        Map<String, Boolean> map = playerFlags.get(uuid.toString());
        return map == null ? null : map.get(flag.id);
    }

    /** Sets or (with a null value) clears one player's override for one flag. */
    public void setOverride(UUID uuid, Flag flag, Boolean value) {
        if (uuid == null) {
            return;
        }
        String key = uuid.toString();
        Map<String, Boolean> map = playerFlags.get(key);
        if (value == null) {
            if (map != null) {
                map.remove(flag.id);
                if (map.isEmpty()) {
                    playerFlags.remove(key);
                }
            }
            return;
        }
        if (map == null) {
            map = new LinkedHashMap<>();
            playerFlags.put(key, map);
        }
        map.put(flag.id, value);
    }

    /** Flags this player has a personal setting for - used by {@code /res info}. */
    public Map<Flag, Boolean> overridesOf(UUID uuid) {
        Map<Flag, Boolean> result = new LinkedHashMap<>();
        if (uuid == null) {
            return result;
        }
        Map<String, Boolean> map = playerFlags.get(uuid.toString());
        if (map == null) {
            return result;
        }
        for (Map.Entry<String, Boolean> entry : map.entrySet()) {
            Flag flag = Flag.byId(entry.getKey());
            if (flag != null) {
                result.put(flag, entry.getValue());
            }
        }
        return result;
    }
}
