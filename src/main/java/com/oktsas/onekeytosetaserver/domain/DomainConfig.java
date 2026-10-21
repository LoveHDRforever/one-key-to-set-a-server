package com.oktsas.onekeytosetaserver.domain;

/**
 * Operator tunables of the claim system, stored in {@code <server root>/multiverse/res-config.json} next to
 * the claims themselves. Everything here can be changed in game with {@code /res admin ...}.
 */
public final class DomainConfig {

    /** Price of one block of claim volume (length x width x height). */
    public double pricePerBlock = 1.5D;

    /** When true a player may {@code /res remove} a claim that belongs to somebody else. */
    public boolean allowPlayerDeleteOthers = false;

    /** How many claims one player may own per world ({@code /res admin` unlimited for op 4}). */
    public int maxClaimsPerPlayer = 1;

    /** Op level that ignores every protection and may dismantle any claim. */
    public int opLevel = 4;

    /** How often the selection outline is redrawn, in milliseconds. */
    public int outlineIntervalMs = 800;

    /** Blocks further away than this are left out of the outline, so huge selections stay cheap. */
    public int outlineRange = 48;
}
