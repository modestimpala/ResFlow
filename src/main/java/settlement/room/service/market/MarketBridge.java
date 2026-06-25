package settlement.room.service.market;

import init.resources.RESOURCE;
import settlement.room.main.RoomInstance;

/**
 * Bridge class to allow access to MarketInstance from outside the package.
 * This is necessary because MarketInstance is package-private.
 *
 * As of v71 markets distribute resources through a RoomDistribution
 * ({@code ROOM_MARKET.dist}); the old per-instance {@code uses()}/{@code amount()}
 * methods on MarketInstance were removed.
 */
public class MarketBridge {

    /**
     * Check if a room instance is a market
     */
    public static boolean isMarket(RoomInstance instance) {
        return instance instanceof MarketInstance;
    }

    /**
     * Check if a market distributes a specific resource
     */
    public static boolean usesResource(RoomInstance instance, RESOURCE resource) {
        if (!(instance instanceof MarketInstance market)) {
            return false;
        }

        try {
            // "all" holds every resource this market's distribution handles
            return market.blueprintI().dist.all.contains(resource);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the amount of a resource currently stored in a market
     */
    public static int getAmount(RoomInstance instance, RESOURCE resource) {
        if (!(instance instanceof MarketInstance market)) {
            return 0;
        }

        try {
            var dist = market.blueprintI().dist;
            if (!dist.all.contains(resource)) {
                return 0;
            }
            return dist.stored(resource).get(instance);
        } catch (Exception e) {
            return 0;
        }
    }
}
