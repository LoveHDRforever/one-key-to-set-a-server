package com.oktsas.onekeytosetaserver.market;

/**
 * {@code <server root>/multiverse/market/config.json} - the free-market knobs the operator may change
 * without a rebuild. Written back with the defaults on first start, then left alone (the file is the
 * source of truth: it is only ever read at start-up and by {@code /market config reload}).
 *
 * <p>The fee table of the finite durations is deliberately <em>not</em> configurable; only the flat rate
 * of a permanent listing is ({@link #permanentFeeRate}).</p>
 */
public final class MarketConfig {

    /** Allow {@code /market sell <价格> 0} = a listing that never expires. */
    public boolean allowPermanent = true;

    /** Fee rate charged on a permanent listing (0.10 = 10% of the sale price). */
    public double permanentFeeRate = 0.10D;

    /** Longest finite duration a player may pick, in hours. */
    public int maxHours = 240;

    /** Duration used when {@code /market sell <价格>} is called without the hour argument. */
    public int defaultHours = 24;

    /** How many listings one player may have up at the same time. */
    public int maxListings = 9;
}
