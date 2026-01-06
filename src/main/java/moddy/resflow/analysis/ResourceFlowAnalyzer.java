package moddy.resflow.analysis;

import game.time.TIME;
import init.race.RACES;
import init.race.RaceResources.RaceResource;
import init.resources.RESOURCE;
import init.resources.RESOURCES;
import lombok.Getter;
import moddy.resflow.ModConfig;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.misc.util.RESOURCE_TILE;
import settlement.room.industry.module.INDUSTRY_HASER;
import settlement.room.industry.module.Industry;
import settlement.room.industry.module.IndustryResource;
import settlement.room.industry.module.ROOM_PRODUCER_INSTANCE;
import settlement.room.infra.stockpile.ROOM_STOCKPILE;
import settlement.room.infra.stockpile.StockpileInstance;
import settlement.room.main.Room;
import settlement.room.main.RoomInstance;
import settlement.room.service.market.MarketBridge;
import snake2d.LOG;
import snake2d.util.datatypes.COORDINATE;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Analyzes the game state to track resource flow over time.
 * Uses HAI interface to track haulers and their destinations.
 * Tracks:
 * - Production and consumption at workshops
 * - Storage in stockpiles
 * - Active haulers moving resources (using h.ai().resourceCarried(), h.ai().getDestination())
 * - Flow path categorization (production → storage → consumption)
 */
public class ResourceFlowAnalyzer {

    /**
     * -- GETTER --
     * Get the data object for saving
     */
    @Getter
    private final ResourceFlowData data;
    // Track active haulers to detect when they complete trips
    private final Map<Integer, HaulerState> activeHaulers = new HashMap<>();
    // Cached previous storage amounts for detecting production/consumption
    private final Map<RESOURCE, Integer> lastStorageAmounts = new HashMap<>();
    // Resource chain cache: maps resource -> set of resources needed to produce it
    private final Map<RESOURCE, Set<RESOURCE>> resourceChainCache = new HashMap<>();
    private double timeSinceLastUpdate = 0.0;
    private boolean chainCacheBuilt = false;
    public ResourceFlowAnalyzer(ResourceFlowData data) {
        this.data = data;
    }

    /**
     * Check if a room actually produces a specific resource
     * Uses ONLY industry APIs - comprehensive and accurate for ALL room types
     */
    public static boolean roomProducesResource(RoomInstance instance, RESOURCE resource) {
        if (resource == null) return false;

        // Check INDUSTRY_HASER (multiple industries: canteens, workshops, farms, mines, etc.)
        if (instance.blueprint() instanceof settlement.room.industry.module.INDUSTRY_HASER industryBlue) {
            for (settlement.room.industry.module.Industry industry : industryBlue.industries()) {
                if (industry.out(resource) != null) {
                    return true;
                }
            }
        }

        // Check ROOM_PRODUCER_INSTANCE (single industry: eateries, etc.)
        if (instance instanceof settlement.room.industry.module.ROOM_PRODUCER_INSTANCE producer) {
            settlement.room.industry.module.Industry industry = producer.industry();
            return industry != null && industry.out(resource) != null;
        }

        return false;
    }

    /**
     * Check if a room actually consumes a specific resource uses only industry APIs
     */
    public static boolean roomConsumesResource(RoomInstance instance, RESOURCE resource) {
        if (resource == null) return false;

        // Check INDUSTRY_HASER (multiple industries)
        if (instance.blueprint() instanceof settlement.room.industry.module.INDUSTRY_HASER industryBlue) {
            for (settlement.room.industry.module.Industry industry : industryBlue.industries()) {
                for (settlement.room.industry.module.IndustryResource input : industry.ins()) {
                    if (input.resource == resource) {
                        return true;
                    }
                }
            }
        }

        // Check ROOM_PRODUCER_INSTANCE (single industry)
        if (instance instanceof settlement.room.industry.module.ROOM_PRODUCER_INSTANCE producer) {
            settlement.room.industry.module.Industry industry = producer.industry();
            if (industry != null) {
                for (settlement.room.industry.module.IndustryResource input : industry.ins()) {
                    if (input.resource == resource) {
                        return true;
                    }
                }
            }
        }

        // Check markets
        if (instance.blueprint() instanceof settlement.room.service.market.ROOM_MARKET) {
            return MarketBridge.usesResource(instance, resource);
        }

        return false;
    }

    private double updateInterval() {
        return ModConfig.FLOW_ANALYZER_UPDATE_INTERVAL;
    }

    /**
     * Update the analyzer - called each frame
     */
    public void update(double deltaSeconds) {
        timeSinceLastUpdate += deltaSeconds;
        data.updateTime(deltaSeconds);

        if (timeSinceLastUpdate >= updateInterval()) {
            performAnalysis(timeSinceLastUpdate);
            timeSinceLastUpdate = 0.0;
        }
    }

    /**
     * Perform full analysis of resource flow
     */
    private void performAnalysis(double elapsedSeconds) {
        try {
            // First, scan all active haulers and detect trip completions
            scanHaulers();

            // Clear previous bottlenecks
            data.getBottlenecks().clear();

            // Pre-calc in one pass O(Rooms) instead of O(Rooms * Resources)
            Map<RESOURCE, Integer> prodCounts = new HashMap<>();
            Map<RESOURCE, Integer> consCounts = new HashMap<>();
            Map<RESOURCE, Integer> storageLocs = new HashMap<>();
            Map<RESOURCE, Double> potentials = new HashMap<>();

            // 1. Single pass over all rooms for production/consumption sites
            for (int i = 0; i < SETT.ROOMS().map.max(); i++) {
                Room room = SETT.ROOMS().map.getByIndex(i);
                if (!(room instanceof RoomInstance instance)) continue;

                // Detect bottlenecks for this room
                detectBottlenecks(instance);

                // Industry production/consumption (covers most rooms)
                if (instance.blueprint() instanceof INDUSTRY_HASER industryBlue) {
                    double totEff = instance.employees().totEfficiency();
                    for (Industry industry : industryBlue.industries()) {
                        for (IndustryResource out : industry.outs()) {
                            prodCounts.merge(out.resource, 1, Integer::sum);
                            double pot = totEff * out.rateSeconds * TIME.secondsPerDay();
                            potentials.merge(out.resource, pot, Double::sum);
                        }
                        for (IndustryResource in : industry.ins()) {
                            consCounts.merge(in.resource, 1, Integer::sum);
                        }
                    }
                } else if (instance instanceof ROOM_PRODUCER_INSTANCE producer) {
                    Industry industry = producer.industry();
                    if (industry != null) {
                        double totEff = instance.employees().totEfficiency();
                        for (IndustryResource out : industry.outs()) {
                            prodCounts.merge(out.resource, 1, Integer::sum);
                            double pot = totEff * out.rateSeconds * TIME.secondsPerDay();
                            potentials.merge(out.resource, pot, Double::sum);
                        }
                        for (IndustryResource in : industry.ins()) {
                            consCounts.merge(in.resource, 1, Integer::sum);
                        }
                    }
                }

                // Special consumption eg Markets
                if (instance.blueprint() instanceof settlement.room.service.market.ROOM_MARKET) {
                    // Check which resources this specific market handles
                    for (int ri = 0; ri < RACES.res().ALL.size(); ri++) {
                        RaceResource r = RACES.res().ALL.get(ri);
                        if (MarketBridge.usesResource(instance, r.res)) {
                            consCounts.merge(r.res, 1, Integer::sum);
                        }
                    }
                }

            }

            // 2. Single pass over all stockpiles for storage location counts
            ROOM_STOCKPILE stockpileBlueprint = SETT.ROOMS().STOCKPILE;
            for (int i = 0; i < stockpileBlueprint.instancesSize(); i++) {
                StockpileInstance instance = stockpileBlueprint.getInstance(i);
                if (instance != null) {
                    // This is still O(Stockpiles * Resources), but Stockpiles << Rooms
                    for (RESOURCE resource : RESOURCES.ALL()) {
                        int amount = stockpileBlueprint.tally().amount.get(resource, instance);
                        int space = stockpileBlueprint.tally().space.get(resource, instance);
                        if (amount > 0 || space > 0) {
                            storageLocs.merge(resource, 1, Integer::sum);
                        }
                    }
                }
            }

            // 3. Then analyze each resource using batch data
            for (RESOURCE resource : RESOURCES.ALL()) {
                ResourceFlowData.ResourceFlowStats stats = data.getStats(resource);

                // Analyze storage (current total amount and capacity)
                stats.currentStored = SETT.ROOMS().STOCKPILE.tally().amountTotal(resource);
                stats.storageCapacity = SETT.ROOMS().STOCKPILE.tally().space.total(resource);
                stats.storageLocations = storageLocs.getOrDefault(resource, 0);

                // Sites from batch results
                stats.productionSites = prodCounts.getOrDefault(resource, 0);
                stats.consumptionSites = consCounts.getOrDefault(resource, 0);

                // Detect production/consumption events from storage changes
                detectFlowEvents(resource, stats);

                // Compute maintenance/janitor consumption estimate (multi-resource)
                stats.maintenanceConsumptionPerDay = estimateMaintenanceConsumptionPerDay(consCounts, resource);

                // Calculate efficiency (Actual Production / Potential Production)
                double pot = potentials.getOrDefault(resource, 0.0);
                if (pot > 0) {
                    stats.efficiency = Math.max(0.0, Math.min(1.0, (double) stats.productionRatePerDay / pot));
                } else {
                    stats.efficiency = 1.0;
                }

                // Take snapshot
                stats.takeSnapshot(elapsedSeconds);
            }

//            LOG.ln("Flow Analysis Batch: " + data.getUpdateCount() + " updates, " +
//                   data.getTotalHaulEvents() + " haul events, " +
//                   activeHaulers.size() + " active haulers");

        } catch (Exception e) {
            LOG.err("Error in resource flow analysis: " + e.getMessage());
        }
    }

    /**
     * Scan all humanoids for those carrying resources using HAI interface
     */
    private void scanHaulers() {
        Set<Integer> currentHaulers = new HashSet<>();
        double currentTime = data.getGameTime();

        try {
            for (ENTITY entity : SETT.ENTITIES().getAllEnts()) {
                if (entity == null) continue;
                if (!(entity instanceof Humanoid h)) continue;

                if (h.ai() == null) continue;

                // Check if carrying a resource
                RESOURCE carried = h.ai().resourceCarried();
                if (carried == null) continue;

                var dest = h.ai().getDestination();
                if (dest == null) continue;

                int entityId = h.id();  // Use game's entity ID
                currentHaulers.add(entityId);

                // Get current position
                int currentTx = h.tc().x();
                int currentTy = h.tc().y();
                int destTx = dest.x();
                int destTy = dest.y();

                // Track traffic heatmap (increment current tile traffic)
                long trafficKey = (long) currentTx << 32 | currentTy;
                data.getTileTraffic().merge(trafficKey, 0.5f, Float::sum);

                // Check if this is a new hauler we haven't seen
                if (!activeHaulers.containsKey(entityId)) {
                    // Determine room types for flow path categorization
                    RoomFlowType startType = classifyTile(currentTx, currentTy, carried);
                    RoomFlowType destType = classifyTile(destTx, destTy, carried);

                    activeHaulers.put(entityId, new HaulerState(
                        entityId, carried,
                        currentTx, currentTy,
                        destTx, destTy,
                        currentTime,
                        startType, destType
                    ));

                    // Update active hauler count
                    data.getStats(carried).activeHaulers++;
                }
            }

            // Check for completed trips (haulers no longer in active list)
            Set<Integer> completedHaulers = new HashSet<>(activeHaulers.keySet());
            completedHaulers.removeAll(currentHaulers);

            for (Integer entityId : completedHaulers) {
                HaulerState state = activeHaulers.remove(entityId);
                if (state != null && state.resource() != null) {
                    // Record the completed trip
                    double distance = state.getDistance();
                    ResourceFlowData.FlowPathType pathType = categorizeFlowPath(
                        state.startRoomType(), state.destRoomType());

                    data.recordHaulTrip(state.resource(), distance, pathType);

                    // Update active hauler count
                    ResourceFlowData.ResourceFlowStats stats = data.getStats(state.resource());
                    stats.activeHaulers = Math.max(0, stats.activeHaulers - 1);
                }
            }

        } catch (Exception e) {
            LOG.err("Error scanning haulers: " + e.getMessage());
        }
    }

    /**
     * Classify a tile location by what type of room is there
     */
    private RoomFlowType classifyTile(int tx, int ty, RESOURCE resource) {
        try {
            Room room = SETT.ROOMS().map.get(tx, ty);
            if (room == null) return RoomFlowType.NONE;

            // Check if stockpile
            if (room.blueprint() instanceof ROOM_STOCKPILE) {
                return RoomFlowType.STORAGE;
            }

            // Check if industry room
            if (room.blueprint() instanceof settlement.room.industry.module.INDUSTRY_HASER industryBlue) {

                boolean produces = false;
                boolean consumes = false;

                for (settlement.room.industry.module.Industry industry : industryBlue.industries()) {
                    if (industry.out(resource) != null) produces = true;
                    for (var input : industry.ins()) {
                        if (input.resource == resource) {
                            consumes = true;
                            break;
                        }
                    }
                }

                if (produces && consumes) return RoomFlowType.BOTH;
                if (produces) return RoomFlowType.PRODUCTION;
                if (consumes) return RoomFlowType.CONSUMPTION;
            }

            return RoomFlowType.NONE;
        } catch (Exception e) {
            return RoomFlowType.NONE;
        }
    }

    /**
     * Convert room types to flow path type
     */
    private ResourceFlowData.FlowPathType categorizeFlowPath(RoomFlowType start, RoomFlowType dest) {
        if (start == RoomFlowType.PRODUCTION || start == RoomFlowType.BOTH) {
            if (dest == RoomFlowType.STORAGE) {
                return ResourceFlowData.FlowPathType.PROD_TO_STORAGE;
            } else if (dest == RoomFlowType.CONSUMPTION || dest == RoomFlowType.BOTH) {
                return ResourceFlowData.FlowPathType.PROD_TO_CONS;
            }
        } else if (start == RoomFlowType.STORAGE) {
            if (dest == RoomFlowType.PRODUCTION || dest == RoomFlowType.BOTH) {
                return ResourceFlowData.FlowPathType.STORAGE_TO_PROD;
            } else if (dest == RoomFlowType.CONSUMPTION) {
                return ResourceFlowData.FlowPathType.STORAGE_TO_CONS;
            }
        }
        return ResourceFlowData.FlowPathType.UNKNOWN;
    }


    private void detectBottlenecks(RoomInstance instance) {
        if (!instance.active()) return;

        // Only check bottlenecks for rooms that actually do production/consumption/storage
        boolean isIndustry = instance.blueprint() instanceof INDUSTRY_HASER;
        boolean isStockpile = instance.blueprint() instanceof ROOM_STOCKPILE;

        // Skip rooms that don't produce, consume, or store resources (like hearts, wells, etc.)
        if (!isIndustry && !isStockpile) return;

        // Check employment (only for rooms that need employees)
        if (instance.employees().max() > 0 && instance.employees().employed() == 0) {
            data.getBottlenecks().add(new ResourceFlowData.BottleneckInfo(
                instance.index(), ResourceFlowData.BottleneckType.EMPLOYMENT_LOW, null
            ));
            return; // Don't check other bottlenecks if no one is working
        }

        // Industry rooms
        if (isIndustry) {
            detectIndustryBottlenecks(instance);
        } else if (isStockpile) {
            detectStockpileBottlenecks(instance);
        }
    }

    private void detectIndustryBottlenecks(RoomInstance instance) {
        INDUSTRY_HASER indu = (INDUSTRY_HASER) instance.blueprint();

        Set<RESOURCE> outputResources = new HashSet<>();
        Set<RESOURCE> inputResources = new HashSet<>();
        for (Industry in : indu.industries()) {
            for (IndustryResource out : in.outs()) outputResources.add(out.resource);
            for (IndustryResource input : in.ins()) inputResources.add(input.resource);
        }

        boolean[] rCheck = new boolean[RESOURCES.ALL().size()];
        boolean[] rHasRoom = new boolean[RESOURCES.ALL().size()];
        int[] rAmount = new int[RESOURCES.ALL().size()];

        for (COORDINATE c : instance.body()) {
            if (instance.is(c)) {
                RESOURCE_TILE t = instance.resourceTile(c.x(), c.y());
                if (t != null && t.resource() != null) {
                    int resIdx = t.resource().index();
                    rCheck[resIdx] = true;
                    if (t.hasRoom()) rHasRoom[resIdx] = true;
                    rAmount[resIdx] += t.amount();
                }
            }
        }

        // Output bottlenecks: All tiles for an output resource are full
        for (RESOURCE res : outputResources) {
            if (rCheck[res.index()] && !rHasRoom[res.index()]) {
                data.getBottlenecks().add(new ResourceFlowData.BottleneckInfo(
                    instance.index(), ResourceFlowData.BottleneckType.OUTPUT_FULL, res
                ));
            }
        }

        // Input bottlenecks: No amount for an input resource
        for (RESOURCE res : inputResources) {
            if (rCheck[res.index()] && rAmount[res.index()] == 0) {
                data.getBottlenecks().add(new ResourceFlowData.BottleneckInfo(
                    instance.index(), ResourceFlowData.BottleneckType.INPUT_MISSING, res
                ));
            }
        }
    }

    private void detectStockpileBottlenecks(RoomInstance instance) {
        // Stockpile is full if most of its tiles have no room
        int totalTiles = 0;
        int fullTiles = 0;
        for (COORDINATE c : instance.body()) {
            if (instance.is(c)) {
                totalTiles++;
                RESOURCE_TILE t = instance.resourceTile(c.x(), c.y());
                if (t != null && !t.hasRoom()) {
                    fullTiles++;
                }
            }
        }

        if (totalTiles > 0 && (float) fullTiles / totalTiles > 0.95f) {
            data.getBottlenecks().add(new ResourceFlowData.BottleneckInfo(
                instance.index(), ResourceFlowData.BottleneckType.STORAGE_FULL, null
            ));
        }
    }

    /**
     * Detect production and consumption events from storage changes
     */
    private void detectFlowEvents(RESOURCE resource, ResourceFlowData.ResourceFlowStats stats) {
        Integer lastAmount = lastStorageAmounts.get(resource);
        int currentAmount = stats.currentStored;

        if (lastAmount != null) {
            int delta = currentAmount - lastAmount;

            // If storage increased, something was produced or delivered
            if (delta > 0) {
                if (stats.productionSites > 0) {
                    data.recordProduction(resource, delta);
                }
            }
            // If storage decreased, something was consumed or picked up
            else if (delta < 0) {
                if (stats.consumptionSites > 0) {
                    data.recordConsumption(resource, -delta);
                }
            }
        }

        lastStorageAmounts.put(resource, currentAmount);
    }


    /**
     * Get count of currently active haulers
     */
    public int getActiveHaulerCount() {
        return activeHaulers.size();
    }

    /**
     * Get summary of flow for a resource
     */
    public String getFlowSummary(RESOURCE resource) {
        ResourceFlowData.ResourceFlowStats stats = data.getStats(resource);

        StringBuilder sb = new StringBuilder();
        sb.append(resource.name).append(": ");
        sb.append("P:").append(stats.productionRatePerDay).append("/day ");
        sb.append("C:").append(stats.consumptionRatePerDay).append("/day ");
        sb.append("Net:").append(stats.netFlowPerDay).append("/day ");
        sb.append("Stored:").append(stats.currentStored).append("/").append(stats.storageCapacity);

        if (stats.netFlowPerDay < 0) {
            double daysLeft = stats.getDaysUntilEmpty();
            if (daysLeft < 100) {
                sb.append(" (empty in ").append(String.format("%.1f", daysLeft)).append("d)");
            }
        } else if (stats.netFlowPerDay > 0 && stats.storageCapacity > 0) {
            double daysToFull = stats.getDaysUntilFull();
            if (daysToFull < 100) {
                sb.append(" (full in ").append(String.format("%.1f", daysToFull)).append("d)");
            }
        }

        return sb.toString();
    }

    /**
     * Build resource dependency chain cache by scanning all industries
     * This maps each resource to all resources needed to produce it
     */
    public void buildResourceChainCache() {
        resourceChainCache.clear();

        // Scan all room blueprints to find production chains
        for (int i = 0; i < SETT.ROOMS().all().size(); i++) {
            settlement.room.main.RoomBlueprint blueprint = SETT.ROOMS().all().get(i);

            // Check INDUSTRY_HASER (multiple industries)
            if (blueprint instanceof settlement.room.industry.module.INDUSTRY_HASER industryBlue) {
                for (settlement.room.industry.module.Industry industry : industryBlue.industries()) {
                    // For each output resource, record what inputs are needed
                    for (settlement.room.industry.module.IndustryResource output : industry.outs()) {
                        RESOURCE outputRes = output.resource;

                        // Get or create the set of inputs for this resource
                        Set<RESOURCE> inputs = resourceChainCache.computeIfAbsent(
                            outputRes, k -> new HashSet<>()
                        );

                        // Add all input resources to the chain
                        for (settlement.room.industry.module.IndustryResource input : industry.ins()) {
                            inputs.add(input.resource);
                        }
                    }
                }
            }
        }

        chainCacheBuilt = true;
        LOG.ln("ResourceFlowAnalyzer: Built chain cache with " + resourceChainCache.size() + " resources");
    }

    /**
     * Get all resources needed for the full production chain of the given resources
     * This recursively expands to include inputs of inputs
     */
    public Set<RESOURCE> getExpandedResourceChain(Set<RESOURCE> baseResources) {
        if (baseResources == null || baseResources.isEmpty()) {
            return new HashSet<>();
        }

        // Build cache if not already built
        if (!chainCacheBuilt) {
            buildResourceChainCache();
        }

        Set<RESOURCE> expanded = new HashSet<>(baseResources);
        Set<RESOURCE> toProcess = new HashSet<>(baseResources);
        Set<RESOURCE> processed = new HashSet<>();

        // Recursively expand the chain
        while (!toProcess.isEmpty()) {
            RESOURCE current = toProcess.iterator().next();
            toProcess.remove(current);
            processed.add(current);

            // Get inputs needed to produce this resource
            Set<RESOURCE> inputs = resourceChainCache.get(current);
            if (inputs != null) {
                for (RESOURCE input : inputs) {
                    if (!processed.contains(input) && !expanded.contains(input)) {
                        expanded.add(input);
                        toProcess.add(input);  // Also process this input's dependencies
                    }
                }
            }
        }

        return expanded;
    }

    /**
     * Get direct inputs needed to produce a resource (one level, not recursive)
     */
    public Set<RESOURCE> getDirectInputs(RESOURCE resource) {
        if (!chainCacheBuilt) {
            buildResourceChainCache();
        }
        return resourceChainCache.getOrDefault(resource, new HashSet<>());
    }

    /**
     * Check if a resource has production dependencies
     */
    public boolean hasProductionChain(RESOURCE resource) {
        if (!chainCacheBuilt) {
            buildResourceChainCache();
        }
        return resourceChainCache.containsKey(resource) && !resourceChainCache.get(resource).isEmpty();
    }


    /**
     * Room type for flow path categorization
     */
    private enum RoomFlowType {
        PRODUCTION,
        CONSUMPTION,
        STORAGE,
        BOTH,
        NONE
    }

    /**
     * Tracks state of a single hauler for trip completion detection
     */
    private record HaulerState(
        int entityId,
        RESOURCE resource,
        int startTileX,
        int startTileY,
        int destTileX,
        int destTileY,
        double startTime,
        RoomFlowType startRoomType,
        RoomFlowType destRoomType
    ) {
        double getDistance() {
            int dx = destTileX - startTileX;
            int dy = destTileY - startTileY;
            return Math.sqrt(dx * dx + dy * dy);
        }
    }

    /**
     * Reset analyzer-side cached state (does not clear saved data).
     * Used when the user manually resets flow data.
     */
    public void resetCaches() {
        activeHaulers.clear();
        lastStorageAmounts.clear();
    }

    /**
     * Estimate janitor/maintenance resource consumption in units/day for a specific resource.
     *
     * IMPORTANT: The game's MAINTENANCE.cons values are already expressed in "per day" units
     * (they're derived from tilesPerDay/resRate constants). MAINTENANCE.estimateGlobal(res)
     * applies maintenance speed() on top of that.
     */
    private int estimateMaintenanceConsumptionPerDay(Map<RESOURCE, Integer> consCounts, RESOURCE resource) {
        try {
            var m = SETT.MAINTENANCE();
            if (m == null)
                return 0;

            // This is already "units per day" (scaled by maintenance speed()).
            double perDayD = m.estimateGlobal(resource);
            if (!(perDayD > 0) || !Double.isFinite(perDayD))
                return 0;

            int perDay = (int) Math.round(perDayD);
            if (perDay <= 0)
                return 0;

            // Treat janitors as a consumer-site indicator so storage deltas can be attributed.
            try {
                int janitors = SETT.ROOMS().JANITOR.instancesSize();
                if (janitors > 0) {
                    consCounts.merge(resource, janitors, Integer::sum);
                }
            } catch (Exception ignored) {
            }

            // Safety clamp: maintenance shouldn't dwarf total stock by orders of magnitude.
            int cap = Math.max(10000, (int) (SETT.ROOMS().STOCKPILE.tally().space.total(resource) * 10L));
            return Math.min(perDay, cap);

        } catch (Exception e) {
            return 0;
        }
    }
}
