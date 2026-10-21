package com.oktsas.onekeytosetaserver.world;

/**
 * Operator tunables for the per-world rules, stored in {@code <server root>/multiverse/world-rules.json}.
 *
 * <p>World admins (op level {@link #exemptOpLevel} and above) are not forced around by the world they stand
 * in: an admin who switches to creative in another dimension must not be snapped back to survival. Each rule
 * has its own switch so an owner can keep, for example, forced game modes but still exempt PvP.</p>
 */
public final class WorldRuleConfig {

    /** Op level that is exempt from the forced rules below. */
    public int exemptOpLevel = 3;

    /** Exempt admins from the per-world forced game mode. */
    public boolean exemptGameMode = true;

    /** Exempt admins from the per-world flight rule. */
    public boolean exemptFlight = true;

    /** Exempt admins from the per-world hunger rule. */
    public boolean exemptHunger = true;

    /** Exempt admins from the per-world auto heal. */
    public boolean exemptAutoHeal = true;

    /** Exempt admins from the per-world "no PvP" rule. */
    public boolean exemptPvp = true;
}
