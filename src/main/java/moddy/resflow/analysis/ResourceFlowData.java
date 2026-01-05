package moddy.resflow.analysis;

import game.time.TIME;
import init.resources.RESOURCE;
import init.resources.RESOURCES;
import lombok.Getter;
import moddy.resflow.ModConfig;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.SAVABLE;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Savable data structure for tracking resource flow over time.
 * Stores production, consumption, movement statistics, and flow paths.
 * Tracks three main flow stages:
 * 1. Production - resources created at workshops
 * 2. Storage - resources stored in stockpiles
 * 3. Consumption - resources used at workshops/services
 * Also tracks:
 * - Active haulers moving resources between locations
 * - Historical trends for rates and amounts
 * - Flow path statistics (which routes are used most)
 */
public class ResourceFlowData implements SAVABLE {

    // Version for save compatibility
    private static final int SAVE_VERSION = 4;
    private static final float TRAFFIC_MAX = 50.0f;
    private static final float TRAFFIC_DECAY_RATE = 0.95f; // Per second decay factor
    // Flow statistics per resource
    private final Map<RESOURCE, ResourceFlowStats> flowStats = new HashMap<>();
    // === Logistics Heatmap (Transient) ===
    // Map of encoded coordinate (long) to traffic intensity (float)
    @Getter
    private final Map<Long, Float> tileTraffic = new HashMap<>();
    // Resource-specific heatmaps (transient, cleared on resource switch or periodically)
    private final Map<RESOURCE, Map<Long, Float>> resourceTraffic = new HashMap<>();
    @Getter
    private final List<BottleneckInfo> bottlenecks = new ArrayList<>();
    /**
     * -- GETTER --
     * Get current game time
     */
    // Time tracking
    @Getter
    private double gameTime = 0.0;
    /**
     * -- GETTER --
     * Get session time (time since load)
     */
    @Getter
    private double sessionTime = 0.0;  // Time since load (not saved)
    /**
     * -- GETTER --
     * Get update count
     */
    @Getter
    private int updateCount = 0;
    /**
     * -- GETTER --
     * Get total haul events across all resources
     */
    // Global flow path statistics (aggregated across all resources)
    @Getter
    private int totalHaulEvents = 0;
    private double totalDistanceHauled = 0.0;
    /**
     * -- GETTER --
     * Get average haul distance across all resources
     */
    @Getter
    private double avgHaulDistance = 0.0;
    // Snapshot tracking for rate calculation
    private double lastSnapshotTime = 0.0;

    private double snapshotInterval() {
        return ModConfig.FLOW_DATA_SNAPSHOT_INTERVAL;
    }

    /**
     * Get or create flow stats for a resource
     */
    public ResourceFlowStats getStats(RESOURCE resource) {
        return flowStats.computeIfAbsent(resource, r -> new ResourceFlowStats());
    }

    /**
     * Get all tracked resources
     */
    public Iterable<RESOURCE> getTrackedResources() {
        return flowStats.keySet();
    }

    /**
     * Update game time and take snapshots if needed
     */
    public void updateTime(double deltaTime) {
        gameTime += deltaTime;
        sessionTime += deltaTime;
        updateCount++;

        // Decay traffic heatmaps
        float decay = (float) Math.pow(TRAFFIC_DECAY_RATE, deltaTime);
        decayMap(tileTraffic, decay);

        resourceTraffic.values().removeIf(resMap -> {
            decayMap(resMap, decay);
            return resMap.isEmpty();
        });

        // Take snapshots at regular intervals
        if (gameTime - lastSnapshotTime >= snapshotInterval()) {
            double elapsed = gameTime - lastSnapshotTime;
            for (ResourceFlowStats stats : flowStats.values()) {
                stats.takeSnapshot(elapsed);
            }
            lastSnapshotTime = gameTime;
        }
    }

    private void decayMap(Map<Long, Float> map, float decay) {
        if (map.isEmpty()) return;
        map.entrySet().removeIf(entry -> {
            float newVal = entry.getValue() * decay;
            if (newVal < 0.1f) return true; // Remove insignificant values
            entry.setValue(newVal);
            return false;
        });
    }

    /**
     * Record traffic on a specific tile for a specific resource
     */
    public void recordTraffic(long encodedTile, RESOURCE resource) {
        // Record global traffic
        tileTraffic.merge(encodedTile, 0.5f, (old, val) -> Math.min(TRAFFIC_MAX, old + val));

        // Record resource traffic
        if (resource != null) {
            Map<Long, Float> resMap = resourceTraffic.computeIfAbsent(resource, k -> new HashMap<>());
            resMap.merge(encodedTile, 0.5f, (old, val) -> Math.min(TRAFFIC_MAX, old + val));
        }
    }

    /**
     * Get traffic intensity for a specific tile
     */
    public float getTraffic(long encodedTile, RESOURCE resourceFilter) {
        if (resourceFilter == null) {
            return tileTraffic.getOrDefault(encodedTile, 0.0f);
        }
        Map<Long, Float> resMap = resourceTraffic.get(resourceFilter);
        return resMap == null ? 0.0f : resMap.getOrDefault(encodedTile, 0.0f);
    }

    /**
     * Get the entire traffic map for a resource
     */
    public Map<Long, Float> getResourceTraffic(RESOURCE resource) {
        if (resource == null) return tileTraffic;
        return resourceTraffic.get(resource);
    }

    /**
     * Record a production event
     */
    public void recordProduction(RESOURCE resource, int amount) {
        ResourceFlowStats stats = getStats(resource);
        stats.totalProduced += amount;
        stats.producedThisSession += amount;
    }

    /**
     * Record a consumption event
     */
    public void recordConsumption(RESOURCE resource, int amount) {
        ResourceFlowStats stats = getStats(resource);
        stats.totalConsumed += amount;
        stats.consumedThisSession += amount;
    }

    /**
     * Record a haul trip completion
     */
    public void recordHaulTrip(RESOURCE resource, double distance, FlowPathType pathType) {
        ResourceFlowStats stats = getStats(resource);
        stats.recordHaulTrip(distance, pathType);

        totalHaulEvents++;
        totalDistanceHauled += distance;
        avgHaulDistance = totalDistanceHauled / totalHaulEvents;
    }

    /**
     * Update active hauler count for a resource
     */
    public void setActiveHaulers(RESOURCE resource, int count) {
        getStats(resource).activeHaulers = count;
    }

    @Override
    public void save(FilePutter file) {
        // Save version
        file.i(SAVE_VERSION);

        // Save time tracking
        file.d(gameTime);
        file.i(updateCount);
        file.i(totalHaulEvents);
        file.d(totalDistanceHauled);
        file.d(avgHaulDistance);
        file.d(lastSnapshotTime);

        // Save number of resources being tracked
        file.i(flowStats.size());

        // Save each resource's stats
        for (Map.Entry<RESOURCE, ResourceFlowStats> entry : flowStats.entrySet()) {
            // Save resource index
            file.i(entry.getKey().index());
            // Save stats
            entry.getValue().save(file);
        }
    }

    @Override
    public void load(FileGetter file) throws IOException {
        // Load version
        int version = file.i();
        if (version < 1 || version > SAVE_VERSION) {
            throw new IOException("Incompatible save version: " + version);
        }

        // Load time tracking
        gameTime = file.d();
        updateCount = file.i();

        if (version >= 2) {
            totalHaulEvents = file.i();
            totalDistanceHauled = file.d();
            avgHaulDistance = file.d();
            lastSnapshotTime = file.d();
        }

        // Reset session time on load
        sessionTime = 0.0;

        // Load resource stats
        flowStats.clear();
        int resourceCount = file.i();

        for (int i = 0; i < resourceCount; i++) {
            // Load resource index
            int resourceIndex = file.i();
            if (resourceIndex >= 0 && resourceIndex < RESOURCES.ALL().size()) {
                RESOURCE resource = RESOURCES.ALL().get(resourceIndex);

                // Load stats
                ResourceFlowStats stats = new ResourceFlowStats();
                stats.load(file, version);
                flowStats.put(resource, stats);
            }
        }
    }

    @Override
    public void clear() {
        gameTime = 0.0;
        sessionTime = 0.0;
        updateCount = 0;
        totalHaulEvents = 0;
        totalDistanceHauled = 0.0;
        avgHaulDistance = 0.0;
        lastSnapshotTime = 0.0;
        for (ResourceFlowStats stats : flowStats.values()) {
            stats.clear();
        }
        flowStats.clear();
    }

    // === Bottlenecks (Transient) ===
    public enum BottleneckType {
        OUTPUT_FULL,    // No room to store produced items
        INPUT_MISSING,  // No input resources available in room
        STORAGE_FULL,   // Stockpile is full
        EMPLOYMENT_LOW  // Room has no workers despite being active
    }

    /**
     * Flow path types for categorizing haul trips
     */
    public enum FlowPathType {
        PROD_TO_STORAGE,    // Production room -> Stockpile
        STORAGE_TO_PROD,    // Stockpile -> Production room (as input)
        STORAGE_TO_CONS,    // Stockpile -> Consumption site (service, etc)
        PROD_TO_CONS,       // Direct production -> consumption
        UNKNOWN             // Couldn't determine path type
    }

    /**
     * @param resource Optional, can be null
     */
    public record BottleneckInfo(int roomIndex, BottleneckType type, RESOURCE resource) {
    }

    /**
     * Statistics for a single resource's flow
     */
    public static class ResourceFlowStats {
        // === Historical data (circular buffer) ===
        // History length is config-driven (see ModConfig.FLOW_DATA_HISTORY_DAYS).
        // NOTE: Time is in *game seconds* (same unit used by game.time.TIME).

        // Safety minimum for usability.
        private static final int MIN_HISTORY_SIZE = 10;

        /**
         * Default graph sample count used by UI widgets that need a fixed size.
         * The actual history length is {@link #getHistorySize()} (config-driven).
         */
        public static final int DEFAULT_GRAPH_SAMPLES = 240;

        /**
         * @deprecated Legacy fixed size used by old saves (v1-v3). Kept for backward compatibility.
         */
        @Deprecated
        private static final int HISTORY_SIZE = 60;

        /**
         * Current history size (array length).
         */
        @Getter
        private int historySize;

        public int[] productionHistory;
        public int[] consumptionHistory;
        public int[] storageHistory;
        public int[] netFlowHistory;

        // === Production tracking ===
        public int totalProduced = 0;           // Lifetime total produced
        public int producedThisSession = 0;     // Produced since load
        public int productionRatePerDay = 0;    // Estimated daily rate
        public int productionSites = 0;         // Active production rooms
        // === Consumption tracking ===
        public int totalConsumed = 0;           // Lifetime total consumed
        public int consumedThisSession = 0;     // Consumed since load
        public int consumptionRatePerDay = 0;   // Estimated daily rate
        public int consumptionSites = 0;        // Active consumption rooms
        // === Storage tracking ===
        public int currentStored = 0;           // Current amount in stockpiles
        public int storageCapacity = 0;         // Total capacity available
        public int storageLocations = 0;        // Number of stockpiles with this resource
        public int peakStored = 0;              // Highest amount ever stored
        // === Movement/Haul tracking ===
        public int activeHaulers = 0;           // Haulers currently carrying this
        public int totalHaulTrips = 0;          // Lifetime haul trip count
        public int haulTripsThisSession = 0;    // Haul trips since load
        public double totalDistanceHauled = 0.0;// Sum of all haul distances
        public double avgHaulDistance = 0.0;    // Average distance per haul
        // === Flow path tracking ===
        public int prodToStorageTrips = 0;      // Hauls from production to storage
        public int storageToProdTrips = 0;      // Hauls from storage to production (inputs)
        public int storageToConsTrips = 0;      // Hauls from storage to consumption
        public int directProdToConsTrips = 0;   // Direct production to consumption
        // === Trend tracking ===
        public int netFlowPerDay = 0;           // production - consumption rate
        public double velocityTrend = 0.0;      // Is net flow increasing or decreasing?
        public double efficiency = 1.0;         // actual vs potential (0.0 to 1.0)

        // === Advanced Analytics ===
        public double productionEfficiencyTrend = 1.0;  // Is efficiency improving? (0-2, 1=stable)
        public int optimalBufferSize = 0;               // Recommended safety stock (units)
        public double bufferHealthScore = 1.0;          // 0-1, how safe is current stock level?
        public double supplyChainStability = 1.0;       // 0-1, how stable is the flow over time?
        public int workforceUtilization = 100;          // % of production capacity being used
        public int avgDeliveryTime = 0;                 // Average seconds for haul trips
        public int peakProductionRate = 0;              // Highest production rate seen
        public int peakConsumptionRate = 0;             // Highest consumption rate seen
        private int historyIndex = 0;

        // Snapshot values for rate calculation
        private int lastSnapshotProduced = 0;
        private int lastSnapshotConsumed = 0;
        private int lastSnapshotStored = 0;

        /**
         * Additional consumption estimate coming from settlement maintenance/janitors (units/day).
         * This is computed each analyzer tick and is not saved (derived).
         */
        public int maintenanceConsumptionPerDay = 0;

        public ResourceFlowStats() {
            resizeHistoryIfNeeded(ModConfig.FLOW_DATA_SNAPSHOT_INTERVAL);
        }

        /**
         * Ensure the history arrays match the configured time horizon.
         * Safe to call frequently; only reallocates when size changes.
         */
        public void resizeHistoryIfNeeded(double snapshotIntervalSeconds) {
            if (snapshotIntervalSeconds <= 0) snapshotIntervalSeconds = 1.0;

            double secondsPerDay = TIME.secondsPerDay();
            double targetDays = Math.max(0.0, ModConfig.FLOW_DATA_HISTORY_DAYS);
            int desired = (int) Math.ceil((targetDays * secondsPerDay) / snapshotIntervalSeconds);

            // Clamp, with a configurable safety cap.
            desired = Math.max(MIN_HISTORY_SIZE, desired);
            desired = Math.min(desired, Math.max(MIN_HISTORY_SIZE, ModConfig.FLOW_DATA_HISTORY_MAX_SAMPLES));

            if (historySize == desired && productionHistory != null) {
                return;
            }

            int oldSize = historySize;
            int oldIndex = historyIndex;

            int[] oldProd = productionHistory;
            int[] oldCons = consumptionHistory;
            int[] oldStor = storageHistory;
            int[] oldNet = netFlowHistory;

            historySize = desired;
            productionHistory = new int[historySize];
            consumptionHistory = new int[historySize];
            storageHistory = new int[historySize];
            netFlowHistory = new int[historySize];

            // Preserve as much recent history as possible.
            if (oldProd != null && oldCons != null && oldStor != null && oldNet != null && oldSize > 0) {
                int toCopy = Math.min(oldSize, historySize);
                for (int k = 0; k < toCopy; k++) {
                    int src = (oldIndex - 1 - k + oldSize) % oldSize;
                    int dst = toCopy - 1 - k;
                    productionHistory[dst] = oldProd[src];
                    consumptionHistory[dst] = oldCons[src];
                    storageHistory[dst] = oldStor[src];
                    netFlowHistory[dst] = oldNet[src];
                }
                historyIndex = toCopy % historySize;
            } else {
                historyIndex = 0;
            }
        }

        public void save(FilePutter file) {
            file.i(totalProduced);
            file.i(productionRatePerDay);
            file.i(productionSites);
            file.i(totalConsumed);
            file.i(consumptionRatePerDay);
            file.i(consumptionSites);
            file.i(currentStored);
            file.i(storageCapacity);
            file.i(storageLocations);
            file.i(peakStored);
            file.i(totalHaulTrips);
            file.d(totalDistanceHauled);
            file.d(avgHaulDistance);
            file.i(prodToStorageTrips);
            file.i(storageToProdTrips);
            file.i(storageToConsTrips);
            file.i(directProdToConsTrips);
            file.i(netFlowPerDay);
            file.d(velocityTrend);
            file.d(efficiency);
            file.i(historyIndex);

            // Save dynamic history size and arrays.
            file.i(historySize);
            for (int i = 0; i < historySize; i++) {
                file.i(productionHistory[i]);
                file.i(consumptionHistory[i]);
                file.i(storageHistory[i]);
                file.i(netFlowHistory[i]);
            }
        }

        public void load(FileGetter file, int version) throws IOException {
            totalProduced = file.i();
            productionRatePerDay = file.i();
            productionSites = file.i();
            totalConsumed = file.i();
            consumptionRatePerDay = file.i();
            consumptionSites = file.i();
            currentStored = file.i();
            storageCapacity = file.i();
            storageLocations = file.i();
            peakStored = file.i();
            totalHaulTrips = file.i();
            totalDistanceHauled = file.d();
            avgHaulDistance = file.d();
            prodToStorageTrips = file.i();
            storageToProdTrips = file.i();
            storageToConsTrips = file.i();
            directProdToConsTrips = file.i();
            netFlowPerDay = file.i();
            velocityTrend = file.d();
            if (version >= 3) {
                efficiency = file.d();
            }
            historyIndex = file.i();

            if (version >= 4) {
                int loadedSize = file.i();
                historySize = 0;
                productionHistory = null;
                consumptionHistory = null;
                storageHistory = null;
                netFlowHistory = null;

                // Allocate based on current config/game time horizon.
                resizeHistoryIfNeeded(ModConfig.FLOW_DATA_SNAPSHOT_INTERVAL);

                int toRead = Math.min(loadedSize, historySize);
                for (int i = 0; i < toRead; i++) {
                    productionHistory[i] = file.i();
                    consumptionHistory[i] = file.i();
                    storageHistory[i] = file.i();
                    netFlowHistory[i] = file.i();
                }
                // Skip any extra data from a larger saved history.
                for (int i = toRead; i < loadedSize; i++) {
                    file.i();
                    file.i();
                    file.i();
                    file.i();
                }

                historyIndex = Math.floorMod(historyIndex, historySize);
            } else {
                // Backward compatibility: older saves always stored HISTORY_SIZE entries.
                resizeHistoryIfNeeded(ModConfig.FLOW_DATA_SNAPSHOT_INTERVAL);

                int oldSize = HISTORY_SIZE;
                int[] tmpProd = new int[oldSize];
                int[] tmpCons = new int[oldSize];
                int[] tmpStor = new int[oldSize];
                int[] tmpNet = new int[oldSize];
                for (int i = 0; i < oldSize; i++) {
                    tmpProd[i] = file.i();
                    tmpCons[i] = file.i();
                    tmpStor[i] = file.i();
                    tmpNet[i] = file.i();
                }

                // Copy the most recent values into the new arrays.
                int srcIndex = Math.floorMod(historyIndex, oldSize);
                int toCopy = Math.min(oldSize, historySize);
                for (int k = 0; k < toCopy; k++) {
                    int src = (srcIndex - 1 - k + oldSize) % oldSize;
                    int dst = toCopy - 1 - k;
                    productionHistory[dst] = tmpProd[src];
                    consumptionHistory[dst] = tmpCons[src];
                    storageHistory[dst] = tmpStor[src];
                    netFlowHistory[dst] = tmpNet[src];
                }
                historyIndex = toCopy % historySize;
            }

            // Reset session counters on load
            producedThisSession = 0;
            consumedThisSession = 0;
            haulTripsThisSession = 0;
            activeHaulers = 0;
            lastSnapshotProduced = totalProduced;
            lastSnapshotConsumed = totalConsumed;
            lastSnapshotStored = currentStored;
        }

        public void clear() {
            totalProduced = 0;
            producedThisSession = 0;
            productionRatePerDay = 0;
            productionSites = 0;
            totalConsumed = 0;
            consumedThisSession = 0;
            consumptionRatePerDay = 0;
            consumptionSites = 0;
            currentStored = 0;
            storageCapacity = 0;
            storageLocations = 0;
            peakStored = 0;
            activeHaulers = 0;
            totalHaulTrips = 0;
            haulTripsThisSession = 0;
            totalDistanceHauled = 0.0;
            avgHaulDistance = 0.0;
            prodToStorageTrips = 0;
            storageToProdTrips = 0;
            storageToConsTrips = 0;
            directProdToConsTrips = 0;
            netFlowPerDay = 0;
            velocityTrend = 0.0;
            historyIndex = 0;
            lastSnapshotProduced = 0;
            lastSnapshotConsumed = 0;
            lastSnapshotStored = 0;

            resizeHistoryIfNeeded(ModConfig.FLOW_DATA_SNAPSHOT_INTERVAL);
            for (int i = 0; i < historySize; i++) {
                productionHistory[i] = 0;
                consumptionHistory[i] = 0;
                storageHistory[i] = 0;
                netFlowHistory[i] = 0;
            }
        }

        /**
         * Take a snapshot for rate calculation and update history
         */
        public void takeSnapshot(double elapsedSeconds) {
            // Ensure size is up to date in case config changed.
            resizeHistoryIfNeeded(ModConfig.FLOW_DATA_SNAPSHOT_INTERVAL);

            // Calculate rates based on change since last snapshot
            int prodDelta = totalProduced - lastSnapshotProduced;
            int consDelta = totalConsumed - lastSnapshotConsumed;
            int storageDelta = currentStored - lastSnapshotStored;
            int netFlow = prodDelta - consDelta;

            // Convert to per-day rate (game seconds to game day)
            double secondsPerDay = TIME.secondsPerDay();
            double rateMultiplier = secondsPerDay / Math.max(1.0, elapsedSeconds);

            int newProdRate = (int) (prodDelta * rateMultiplier);
            int newConsRate = (int) (consDelta * rateMultiplier);
            int newNetFlow = (int) (netFlow * rateMultiplier);

            // Smooth the rates with heavy damping to avoid wild fluctuations
            double alpha = 0.05;
            productionRatePerDay = (int) (productionRatePerDay * (1 - alpha) + newProdRate * alpha);
            consumptionRatePerDay = (int) (consumptionRatePerDay * (1 - alpha) + newConsRate * alpha);

            // Calculate velocity trend (is net flow accelerating?)
            int prevNetFlow = netFlowPerDay;
            netFlowPerDay = (int) (netFlowPerDay * (1 - alpha) + newNetFlow * alpha);
            velocityTrend = velocityTrend * (1 - alpha) + (netFlowPerDay - prevNetFlow) * alpha;

            // Update peak
            if (currentStored > peakStored) {
                peakStored = currentStored;
            }

            // Update history
            productionHistory[historyIndex] = prodDelta;
            consumptionHistory[historyIndex] = consDelta;
            storageHistory[historyIndex] = currentStored;
            netFlowHistory[historyIndex] = netFlow;
            historyIndex = (historyIndex + 1) % historySize;

            // Update snapshot values
            lastSnapshotProduced = totalProduced;
            lastSnapshotConsumed = totalConsumed;
            lastSnapshotStored = currentStored;

            // === Calculate Advanced Analytics ===

            // Track peak rates
            if (productionRatePerDay > peakProductionRate) {
                peakProductionRate = productionRatePerDay;
            }
            if (consumptionRatePerDay > peakConsumptionRate) {
                peakConsumptionRate = consumptionRatePerDay;
            }

            // Calculate supply chain stability (how consistent are the rates?)
            // Use a reasonable recent window: up to 1/2 day of snapshots, capped.
            if (historySize >= 3) {
                int samples = Math.min(historySize, Math.max(5, (int) Math.ceil((0.5 * TIME.secondsPerDay()) / ModConfig.FLOW_DATA_SNAPSHOT_INTERVAL)));
                samples = Math.min(samples, 120);

                double netFlowVariance = 0;
                double avgNet = 0;
                for (int i = 0; i < samples; i++) {
                    int idx = (historyIndex - 1 - i + historySize) % historySize;
                    avgNet += netFlowHistory[idx];
                }
                avgNet /= samples;
                for (int i = 0; i < samples; i++) {
                    int idx = (historyIndex - 1 - i + historySize) % historySize;
                    double diff = netFlowHistory[idx] - avgNet;
                    netFlowVariance += diff * diff;
                }
                netFlowVariance /= samples;

                supplyChainStability = 1.0 / (1.0 + netFlowVariance / 10000.0);
                supplyChainStability = Math.max(0.0, Math.min(1.0, supplyChainStability));
            }

            // Calculate optimal buffer size (safety stock) in *units*.
            // Buffer = days of consumption * daily consumption
            {
                int daysOfBuffer = 3;
                if (supplyChainStability < 0.5) {
                    daysOfBuffer = 5;
                } else if (supplyChainStability > 0.8) {
                    daysOfBuffer = 2;
                }

                int cons = Math.max(0, consumptionRatePerDay);
                optimalBufferSize = Math.max(0, cons * daysOfBuffer);

                if (optimalBufferSize > 0) {
                    bufferHealthScore = (double) currentStored / (double) optimalBufferSize;
                } else {
                    // If we don't consume this resource, treat buffer as "fine".
                    bufferHealthScore = 1.0;
                }

                if (!Double.isFinite(bufferHealthScore))
                    bufferHealthScore = 0;
                bufferHealthScore = Math.max(0.0, Math.min(1.0, bufferHealthScore));
            }

            // Workforce utilization / trend: compare oldest vs newest in the buffer.
            if (historySize >= 2) {
                int oldestIdx = (historyIndex + 1) % historySize;
                int newestIdx = (historyIndex + historySize - 1) % historySize;
                int oldProd = productionHistory[oldestIdx];
                int newProd = productionHistory[newestIdx];

                if (oldProd > 0) {
                    productionEfficiencyTrend = (double) newProd / oldProd;
                    productionEfficiencyTrend = Math.max(0.0, Math.min(2.0, productionEfficiencyTrend));
                } else {
                    productionEfficiencyTrend = newProd > 0 ? 1.2 : 1.0;
                }
            }

            // Workforce utilization (efficiency as percentage)
            workforceUtilization = (int) (efficiency * 100);

            // Average delivery time (if we have haul trips)
            if (totalHaulTrips > 0) {
                // Estimate: distance / walking speed (approx 4 tiles/second)
                avgDeliveryTime = (int) (avgHaulDistance / 4.0);
            }
        }

        /**
         * Record a haul trip with flow path categorization
         */
        public void recordHaulTrip(double distance, FlowPathType pathType) {
            totalHaulTrips++;
            haulTripsThisSession++;
            totalDistanceHauled += distance;
            avgHaulDistance = totalDistanceHauled / totalHaulTrips;

            switch (pathType) {
                case PROD_TO_STORAGE:
                    prodToStorageTrips++;
                    break;
                case STORAGE_TO_PROD:
                    storageToProdTrips++;
                    break;
                case STORAGE_TO_CONS:
                    storageToConsTrips++;
                    break;
                case PROD_TO_CONS:
                    directProdToConsTrips++;
                    break;
            }
        }

        /**
         * Converts a logical index (0 = oldest, size-1 = newest) into a backing-array index.
         * Returns -1 if history isn't initialized.
         */
        public int historyArrayIndexFromOldest(int fromOldest) {
            if (historySize <= 0 || productionHistory == null)
                return -1;
            int n = Math.floorMod(fromOldest, historySize);
            // historyIndex points to the next slot to write, so it's the "newest+1".
            int oldest = historyIndex;
            return (oldest + n) % historySize;
        }

        /** Returns the production delta value at a logical index (0=oldest..size-1=newest). */
        public int productionAtOldestIndex(int fromOldest) {
            int idx = historyArrayIndexFromOldest(fromOldest);
            return idx < 0 ? 0 : productionHistory[idx];
        }

        /** Returns the consumption delta value at a logical index (0=oldest..size-1=newest). */
        public int consumptionAtOldestIndex(int fromOldest) {
            int idx = historyArrayIndexFromOldest(fromOldest);
            return idx < 0 ? 0 : consumptionHistory[idx];
        }

        /**
         * Get average production from history
         */
        public double getAvgProductionFromHistory() {
            int sum = 0;
            for (int val : productionHistory) sum += val;
            return sum / (double) Math.max(1, historySize);
        }

        /**
         * Get average consumption from history
         */
        public double getAvgConsumptionFromHistory() {
            int sum = 0;
            for (int val : consumptionHistory) sum += val;
            return sum / (double) Math.max(1, historySize);
        }

        /**
         * Get storage trend (is it increasing or decreasing?)
         */
        public double getStorageTrend() {
            if (historySize < 2) return 0;
            int oldest = storageHistory[(historyIndex + 1) % historySize];
            int newest = storageHistory[(historyIndex + historySize - 1) % historySize];
            return (newest - oldest) / (double) historySize;
        }

        /**
         * Estimate days until stockpile is empty (based on net flow)
         */
        public double getDaysUntilEmpty() {
            if (netFlowPerDay >= 0) return Double.POSITIVE_INFINITY;
            return currentStored / (double) -netFlowPerDay;
        }

        /**
         * Estimate days until stockpile is full (based on net flow)
         */
        public double getDaysUntilFull() {
            if (netFlowPerDay <= 0) return Double.POSITIVE_INFINITY;
            int remaining = storageCapacity - currentStored;
            return remaining / (double) netFlowPerDay;
        }
    }

    /**
     * Summary of the effective history window currently being stored.
     * All time units are in game-time seconds (same as {@link TIME}).
     */
    public record HistoryWindow(double snapshotIntervalSeconds,
                                int snapshots,
                                double windowSeconds,
                                double windowDays) {

        public String format() {
            // Example: "~2.0d (1920 samples @ 60s)"
            String daysStr = String.format(Locale.ROOT, "%.1f", windowDays);
            String intervalStr = String.format(Locale.ROOT, "%.0f", snapshotIntervalSeconds);
            return "~" + daysStr + "d (" + snapshots + " samples @ " + intervalStr + "s)";
        }
    }

    /**
     * Computes the history window that the current configuration produces.
     */
    public static HistoryWindow computeHistoryWindow(int historySize, double snapshotIntervalSeconds) {
        if (snapshotIntervalSeconds <= 0) snapshotIntervalSeconds = 1.0;
        double windowSeconds = historySize * snapshotIntervalSeconds;
        double windowDays = windowSeconds / Math.max(1.0, TIME.secondsPerDay());
        return new HistoryWindow(snapshotIntervalSeconds, historySize, windowSeconds, windowDays);
    }

    /**
     * Convenience: compute window from a stats object and current config.
     */
    public static HistoryWindow computeHistoryWindow(ResourceFlowStats stats) {
        return computeHistoryWindow(stats.getHistorySize(), ModConfig.FLOW_DATA_SNAPSHOT_INTERVAL);
    }
}
