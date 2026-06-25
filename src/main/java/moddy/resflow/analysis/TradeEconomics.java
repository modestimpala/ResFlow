package moddy.resflow.analysis;

import game.faction.FACTIONS;
import init.resources.RESOURCE;
import init.trade.TR;

/**
 * Helper for the v71 trade economy. Centralizes access to the game's refactored
 * trade/price system ({@link FACTIONS#PRICE()} / ResourcePrices) so the
 * resource-flow UI can pair internal logistics with import/export pricing.
 *
 * <p>v71 reworked trade so every resource has a market price, averaged across NPC
 * sellers and weighted by their population. That price is roughly the per-unit
 * cost to import a resource (or the value of exporting your surplus). It does not
 * include the per-trade toll/tariff, which is distance- and faction-dependent.
 *
 * <p>Isolating these calls here keeps the v71 trade API in one place, so a future
 * game update only touches this file (same rationale as the package bridges).
 */
public final class TradeEconomics {

    private TradeEconomics() {}

    /**
     * Average market price for one unit of the given resource, as used by the
     * game's trade system.
     *
     * @return price per unit, or {@code -1} if pricing is unavailable (e.g. before
     *         a settlement/region exists, or the resource is not tradable).
     */
    public static int importPrice(RESOURCE res) {
        if (res == null) {
            return -1;
        }
        try {
            return FACTIONS.PRICE().get(TR.get(res));
        } catch (Exception e) {
            return -1;
        }
    }
}
