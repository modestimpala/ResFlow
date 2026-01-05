package settlement.room.service.market;

import init.race.RACES;
import init.race.RaceResources;
import init.resources.RESOURCE;
import settlement.room.main.RoomInstance;

/**
 * Bridge class to allow access to MarketInstance from outside the package.
 * This is necessary because MarketInstance is package-private.
 */
public class MarketBridge {

    /**
     * Check if a room instance is a market
     */
    public static boolean isMarket(RoomInstance instance) {
        return instance instanceof MarketInstance;
    }

    /**
     * Check if a market uses a specific resource
     */
    public static boolean usesResource(RoomInstance instance, RESOURCE resource) {
        if (!(instance instanceof MarketInstance market)) {
            return false;
        }

        try {
            // Check if the market has any amount of this resource
            RaceResources.RaceResource e = RACES.res().get(resource);
            return market.uses(e);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the amount of a resource in a market
     */
    public static int getAmount(RoomInstance instance, RESOURCE resource) {
        if (!(instance instanceof MarketInstance market)) {
            return 0;
        }

        try {
            RaceResources.RaceResource e = RACES.res().get(resource);
            return market.amount(e);
        } catch (Exception e) {
            return 0;
        }
    }
}
