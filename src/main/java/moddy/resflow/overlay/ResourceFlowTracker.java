package moddy.resflow.overlay;

import init.constant.C;
import init.resources.RESOURCE;
import init.resources.RESOURCES;
import init.sprite.SPRITES;
import init.sprite.UI.UI;
import lombok.Setter;
import moddy.resflow.ModConfig;
import moddy.resflow.analysis.ResourceFlowAnalyzer;
import moddy.resflow.analysis.ResourceFlowData;
import moddy.resflow.ui.HorizontalResourcePanel;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.overlay.Addable;
import settlement.room.main.Room;
import settlement.room.main.RoomInstance;
import snake2d.Renderer;
import snake2d.util.MATH;
import snake2d.util.color.COLOR;
import snake2d.util.color.ColorImp;
import snake2d.util.color.OPACITY;
import snake2d.util.datatypes.DIR;
import snake2d.util.datatypes.RECTANGLE;
import util.rendering.RenderData;
import util.rendering.RenderData.RenderIterator;
import view.keyboard.KEYS;
import view.main.VIEW;

import java.util.*;

import static moddy.resflow.ModConfig.debug;

/**
 * Tracks and visualizes resource flow: production, consumption, and distribution.
 * Shows where resources are created, used, and how they move through the settlement.
 * Key features:
 * - Highlights entire room areas (not just center tiles)
 * - Shows actual pull order connections between rooms as animated arrow paths
 * - Tracks active haulers in real-time
 * - Displays trend data (production rate, consumption rate, net flow)
 * - Persists data across save/load via ResourceFlowData
 */
public class ResourceFlowTracker extends Addable {

    // Rendering colors
    private static final COLOR COLOR_PRODUCTION = new ColorImp(100, 255, 100);     // Green
    private static final COLOR COLOR_CONSUMPTION = new ColorImp(255, 100, 100);    // Red
    private static final COLOR COLOR_STORAGE = new ColorImp(100, 150, 255);        // Blue
    private static final COLOR COLOR_FLOW_LINE = new ColorImp(255, 200, 50);       // Yellow/gold (fallback)
    private static final COLOR COLOR_ACTIVE_HAUL = new ColorImp(255, 150, 50);     // Orange
    private static final COLOR COLOR_TREND_UP = new ColorImp(50, 255, 50);         // Bright green
    private static final COLOR COLOR_TREND_DOWN = new ColorImp(255, 50, 50);       // Bright red
    private static final COLOR COLOR_TREND_NEUTRAL = new ColorImp(200, 200, 200);  // Gray
    // Flow connection type colors (color-coded by source/destination types)
    private static final COLOR COLOR_FLOW_PROD_TO_STORAGE = new ColorImp(100, 255, 150);   // Green-cyan (harvest)
    private static final COLOR COLOR_FLOW_STORAGE_TO_CONS = new ColorImp(255, 150, 100);   // Red-orange (delivery)
    private static final COLOR COLOR_FLOW_PROD_TO_CONS = new ColorImp(255, 255, 100);      // Yellow (direct supply)
    private static final COLOR COLOR_FLOW_STORAGE_TO_STORAGE = new ColorImp(150, 200, 255); // Blue (redistribution)
    // Heatmap colors (Static cache to avoid allocations)
    private static final COLOR[] HEAT_GRADIENT = new COLOR[21];
    private static final CharSequence ¤¤name = "Resource Flow";
    private static final CharSequence ¤¤desc = "Shows resource flow with trends. Green=production, Red=consumption, Blue=storage. Arrows=haul paths.";
    // Throttle path updates: max requests per update tick
    private static final int MAX_PATH_UPDATES_PER_FRAME = 1; // Reduced from 2

    static {
        for (int i = 0; i <= 20; i++) {
            float t = i / 20.0f;
            if (t < 0.25f) { // Blue to Cyan
                float f = t / 0.25f;
                HEAT_GRADIENT[i] = new ColorImp(0, (int) (255 * f), 255);
            } else if (t < 0.5f) { // Cyan to Green
                float f = (t - 0.25f) / 0.25f;
                HEAT_GRADIENT[i] = new ColorImp(0, 255, (int) (255 * (1 - f)));
            } else if (t < 0.75f) { // Green to Yellow
                float f = (t - 0.5f) / 0.25f;
                HEAT_GRADIENT[i] = new ColorImp((int) (255 * f), 255, 0);
            } else { // Yellow to Red
                float f = (t - 0.75f) / 0.25f;
                HEAT_GRADIENT[i] = new ColorImp(255, (int) (255 * (1 - f)), 0);
            }
        }
    }

    static {
        //D.ts(ResourceFlowTracker.class);
    }

    // Particle system for flow visualization
    private final FlowParticleSystem particleSystem = new FlowParticleSystem();
    // Room type classification cache (keyed by room instance index)
    private final Map<Integer, RoomFlowType> roomTypeCache = new HashMap<>();
    private final Set<RESOURCE> previousSelectedResources = new HashSet<>();
    // Flow connections between rooms (source room index -> list of dest room indices)
    private final List<FlowConnection> flowConnections = new ArrayList<>();
    // Performance optimization: map of tile to connections passing through it
    private final Map<Long, List<FlowConnection>> tilePathMap = new HashMap<>();
    // Persistent flow map: key="srcIdx->dstIdx:resIdx", value=FlowConnectionData
    private final Map<String, FlowConnectionData> persistentFlows = new HashMap<>();
    private final List<ActiveTransport> activeTransports = new ArrayList<>();
    private final PulsedOpacity pulsedOpacity = new PulsedOpacity();
    // Analyzer for persistent flow tracking
    @Setter
    private ResourceFlowAnalyzer analyzer;
    private double timeSinceLastUpdate = 0.0;
    private double timeSinceTransportUpdate = 0.0;
    // Currently hovered room (for path highlighting)
    private RoomInstance hoveredRoom = null;
    // Previous filter state (to detect changes and clear particles)
    private RESOURCE previousHoveredResource = null;
    private RoomInstance previousHoveredRoom = null;

    public ResourceFlowTracker() {
        super(
            UI.icons().m.arrow_up,
            "RESOURCE_FLOW",
            ¤¤name,
            ¤¤desc,
            true,   // renderBelow - color the ground
            true    // renderAbove - draw icons and arrows
        );
    }

    // Animation timing (from ModConfig)
    private static double pulseSpeed() {
        return ModConfig.FLOW_TRACKER_PULSE_SPEED;
    }

    private static double arrowAnimSpeed() {
        return ModConfig.FLOW_TRACKER_ARROW_ANIM_SPEED;
    }

    // Cache settings (from ModConfig)
    private double cacheUpdateInterval() {
        return ModConfig.FLOW_TRACKER_CACHE_UPDATE_INTERVAL;
    }

    private double transportUpdateInterval() {
        return ModConfig.FLOW_TRACKER_TRANSPORT_UPDATE_INTERVAL;
    }

    private RESOURCE getHoveredResource() {
        return HorizontalResourcePanel.currentlyHoveredResource;
    }

    /**
     * Check if a resource should be displayed (either selected or hovered)
     * This includes resource chain expansion when enabled
     */
    private boolean shouldDisplayResource(RESOURCE res) {
        if (res == null) return false;

        // If resources are locked, show locked resources + their chains
        if (!HorizontalResourcePanel.selectedResources.isEmpty()) {
            // Get the effective set (includes chain dependencies if enabled)
            java.util.Set<RESOURCE> effectiveSet = HorizontalResourcePanel.getEffectiveSelectedResources();
            return effectiveSet.contains(res);
        }
        // Otherwise show hovered resource + its chain, or all if nothing hovered
        RESOURCE hovered = getHoveredResource();
        if (hovered == null) {
            return true;  // Show all
        }

        // Check if this resource is in the hovered resource's chain
        if (HorizontalResourcePanel.showResourceChains) {
            java.util.Set<RESOURCE> hoveredChain = new java.util.HashSet<>();
            hoveredChain.add(hovered);
            java.util.Set<RESOURCE> expandedChain = HorizontalResourcePanel.getExpandedResourceChain(hoveredChain);
            return expandedChain.contains(res);
        }

        return hovered == res;
    }

    /**
     * Check if a flow connection is related to the currently hovered room
     */
    private boolean isHoveredConnection(FlowConnection conn) {
        if (hoveredRoom == null) return false;
        int idx = hoveredRoom.index();
        return conn.sourceRoomIdx == idx || conn.destRoomIdx == idx;
    }

    /**
     * Update tracker state - called from game loop
     */
    public void update(double ds) {
        if (!added()) return;

        if (analyzer != null) {
            analyzer.update(ds);
        }

        // Update particle system
        particleSystem.update(ds);

        // Track hovered room for path filtering/highlighting - only when Shift is held
        snake2d.util.datatypes.COORDINATE mouseTile = VIEW.s().getWindow().tile();
        Room room = SETT.ROOMS().map.get(mouseTile.x(), mouseTile.y());
        if (room instanceof RoomInstance instance && KEYS.MAIN().UNDO.isPressed()) {
            hoveredRoom = instance;
        } else {
            hoveredRoom = null;
        }

        // Detect filter changes and clear particles if needed
        RESOURCE currentHovered = getHoveredResource();
        boolean resourceFilterChanged = currentHovered != previousHoveredResource ||
            !previousSelectedResources.equals(HorizontalResourcePanel.selectedResources);
        boolean roomFilterChanged = hoveredRoom != previousHoveredRoom;

        if (resourceFilterChanged || roomFilterChanged) {
            particleSystem.clear();
            previousHoveredResource = currentHovered;
            previousSelectedResources.clear();
            previousSelectedResources.addAll(HorizontalResourcePanel.selectedResources);
            previousHoveredRoom = hoveredRoom;
        }

        timeSinceLastUpdate += ds;
        timeSinceTransportUpdate += ds;

        if (roomTypeCache.isEmpty() || timeSinceLastUpdate >= cacheUpdateInterval()) {
            rebuildCache();
            timeSinceLastUpdate = 0.0;
            timeSinceTransportUpdate = 0.0;
        } else if (timeSinceTransportUpdate >= transportUpdateInterval()) {
            activeTransports.clear();
            scanActiveTransports(getHoveredResource());
            timeSinceTransportUpdate = 0.0;
        }

        // Update paths for flow connections (throttled to max N per update)
        updateFlowPaths();

        // Spawn particles for active connections
        spawnParticlesForActiveFlows();
    }

    /**
     * Update paths for flow connections (called from update loop, not render loop!)
     */
    private void updateFlowPaths() {
        if (flowConnections.isEmpty()) return;

        double currentTime = analyzer != null ? analyzer.getData().getGameTime() : VIEW.renderSecond();
        int updatesThisFrame = 0;
        boolean mapNeedsRebuild = false;

        for (FlowConnection conn : flowConnections) {
            if (updatesThisFrame >= MAX_PATH_UPDATES_PER_FRAME) {
                break; // Hit throttle limit, remaining paths update next frame
            }

            boolean needsUpdate = (currentTime - conn.lastPathUpdate >= 60.0);
            if (needsUpdate) {
                conn.updatePath(currentTime);
                updatesThisFrame++;
                mapNeedsRebuild = true;
            }
        }

        if (mapNeedsRebuild) {
            rebuildTilePathMap();
        }
    }

    /**
     * Rebuild the tile-to-connection mapping for O(1) rendering lookup
     */
    private void rebuildTilePathMap() {
        tilePathMap.clear();
        for (FlowConnection conn : flowConnections) {
            for (long tileKey : conn.pathTileSet) {
                tilePathMap.computeIfAbsent(tileKey, k -> new ArrayList<>(2)).add(conn);
            }
        }
    }

    /**
     * Spawn particles for active flow connections based on actual flow volume
     * Called each update cycle to create living visualization
     */
    private void spawnParticlesForActiveFlows() {
        if (!ModConfig.FLOW_PARTICLE_ENABLED || analyzer == null) {
            return;
        }

        // Only spawn particles for connections with valid paths
        for (FlowConnection conn : flowConnections) {
            if (conn.pathTiles.isEmpty()) {
                continue;
            }

            // Skip if this connection is filtered out (not related to selected/hovered resources)
            if (!shouldDisplayResource(conn.resource)) {
                continue;
            }

            // Skip if this connection is not related to hovered room (when shift is held)
            if (hoveredRoom != null && !isHoveredConnection(conn)) {
                continue;
            }

            // Calculate flow volume from analyzer data
            ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(conn.resource);
            double flowVolume = calculateFlowVolumeForConnection(conn, stats);

            // Flow volume now has minimum of 1.0, so no need to skip
            // (All visible connections will spawn particles)

            // Determine flow path type for color coding
            ResourceFlowData.FlowPathType pathType = mapFlowTypeToPathType(conn.flowType);

            // Convert path tiles to particle path tiles
            List<FlowParticle.PathTile> particlePath = new ArrayList<>(conn.pathTiles.size());
            for (FlowConnection.PathTile tile : conn.pathTiles) {
                particlePath.add(new FlowParticle.PathTile(tile.x(), tile.y()));
            }

            // Spawn particle for this connection
            particleSystem.spawnForConnection(
                conn.sourceRoomIdx,
                conn.destRoomIdx,
                conn.resource,
                particlePath,
                pathType,
                flowVolume
            );
        }
    }

    /**
     * Calculate flow volume for a specific connection
     * Based on production rate, consumption rate, and active haulers
     */
    private double calculateFlowVolumeForConnection(FlowConnection conn, ResourceFlowData.ResourceFlowStats stats) {
        // Base flow volume on production rate (normalized to 1-10 scale)
        // Add a minimum base flow of 1.0 to ensure particles always spawn for visible connections
        double baseFlow = Math.max(1.0, stats.productionRatePerDay / 10.0);

        // Boost based on active haulers (more haulers = busier route)
        double haulerBoost = 1.0 + (stats.activeHaulers * 0.5);

        // Boost based on connection type
        double typeBoost = switch (conn.flowType) {
            case PRODUCTION_TO_STORAGE -> 1.5;  // Production flows are important
            case STORAGE_TO_CONSUMPTION -> 1.3; // Delivery flows are important
            case PRODUCTION_TO_CONSUMPTION -> 2.0; // Direct flows are most critical
            case STORAGE_TO_STORAGE -> 0.8;     // Redistribution less critical
            default -> 1.0;
        };

        return Math.max(1.0, Math.min(10.0, baseFlow * haulerBoost * typeBoost));
    }

    /**
     * Map FlowType to FlowPathType for particle system
     */
    private ResourceFlowData.FlowPathType mapFlowTypeToPathType(FlowType flowType) {
        return switch (flowType) {
            case PRODUCTION_TO_STORAGE -> ResourceFlowData.FlowPathType.PROD_TO_STORAGE;
            case STORAGE_TO_CONSUMPTION -> ResourceFlowData.FlowPathType.STORAGE_TO_CONS;
            case PRODUCTION_TO_CONSUMPTION -> ResourceFlowData.FlowPathType.PROD_TO_CONS;
            case STORAGE_TO_STORAGE -> ResourceFlowData.FlowPathType.STORAGE_TO_PROD;
            default -> ResourceFlowData.FlowPathType.UNKNOWN;
        };
    }

    @Override
    public void initBelow(RenderData data) {
        if (roomTypeCache.isEmpty()) {
            rebuildCache();
        }
    }

    @Override
    public void renderBelow(Renderer r, RenderIterator it) {
        // 1. Traffic Heatmap
        if (HorizontalResourcePanel.showTrafficHeatmap) {
            renderTrafficHeatmapTile(r, it);
        }

        // 2. Room Colors
        if (HorizontalResourcePanel.showRoomColors) {
            renderRoomColorTile(r, it);
        }
    }

    private void renderTrafficHeatmapTile(Renderer r, RenderIterator it) {
        if (analyzer == null) return;

        long key = FlowConnection.encodeTile(it.tx(), it.ty());

        // Use hovered or first selected resource for filtering
        RESOURCE filter = getHoveredResource();
        if (filter == null && !HorizontalResourcePanel.selectedResources.isEmpty()) {
            filter = HorizontalResourcePanel.selectedResources.iterator().next();
        }

        float val = analyzer.getData().getTraffic(key, filter);

        if (val > 0.1f) {
            int idx = (int) (Math.min(1.0f, val / 5.0f) * 20);
            COLOR color = HEAT_GRADIENT[idx];
            OPACITY.O50.bind();
            color.render(r, it.x(), it.x() + init.constant.C.TILE_SIZE, it.y(), it.y() + init.constant.C.TILE_SIZE);
            OPACITY.unbind();
        }
    }

    private void renderRoomColorTile(Renderer r, RenderIterator it) {
        Room room = SETT.ROOMS().map.get(it.tx(), it.ty());
        if (!(room instanceof RoomInstance instance)) return;

        RoomFlowType flowType = roomTypeCache.get(instance.index());
        if (flowType == null || flowType == RoomFlowType.NONE) return;

        COLOR baseColor;
        if (HorizontalResourcePanel.showEfficiencyMode && analyzer != null) {
            RESOURCE res = getHoveredResource();
            if (res == null && !HorizontalResourcePanel.selectedResources.isEmpty()) {
                res = HorizontalResourcePanel.selectedResources.iterator().next();
            }

            if (res != null) {
                double efficiency = analyzer.getData().getStats(res).efficiency;
                // Red (0%) to Green (100%)
                baseColor = new ColorImp().interpolate(COLOR_TREND_DOWN, COLOR_TREND_UP, efficiency);
            } else {
                baseColor = getColorForType(flowType);
            }
        } else {
            baseColor = getColorForType(flowType);
        }

        // Pulse effect
        double time = VIEW.renderSecond() * pulseSpeed();
        double pulse = MATH.mod(time, 2);
        pulse = MATH.distanceC(pulse, 1, 2);

        ColorImp pulsedColor = new ColorImp(baseColor);
        pulsedColor.shadeSelf(0.3 + pulse * 0.4);

        boolean dimmed = hoveredRoom != null && instance.index() != hoveredRoom.index();
        if (dimmed) {
            pulsedColor.shadeSelf(0.5);
            snake2d.util.color.OPACITY.O35.bind();
        }

        pulsedColor.bind();
        SPRITES.cons().BIG.filled_striped.render(r, 0x0F, it.x(), it.y());
        COLOR.unbind();

        if (dimmed) {
            snake2d.util.color.OPACITY.unbind();
        }
    }

    @Override
    public void initAbove(RenderData data) {
        // No setup needed for above rendering
    }

    @Override
    public boolean render(Renderer r, RenderIterator it) {
        int zoomLevel = VIEW.s().getWindow().zoomout();

        // Render flow arrows at all zoom levels (like the game does)
        // Thanks to O(1) HashSet lookup optimization, we can now render detailed arrows even at zoom 2!
        // Zoom 3: Use simplified TINY sprites (dots/squares)
        // Zoom 0-2: Use detailed arrows (now performant!)
        renderFlowArrows(r, it, zoomLevel);

        // At close zoom, show room icons and trend indicators
        if (zoomLevel <= 2) {
            renderRoomCenterInfo(r, it, zoomLevel);
        }

        // Render bottlenecks
        if (HorizontalResourcePanel.showBottlenecks && zoomLevel <= 2) {
            renderBottleneckIndicators(r, it, zoomLevel);
        }

        // Render particles at this tile position
        if (ModConfig.FLOW_PARTICLE_ENABLED && HorizontalResourcePanel.showFlowPaths) {
            particleSystem.renderAtTile(r, it.tx(), it.ty(), it.x(), it.y(), C.TILE_SIZE);
        }

        return false;
    }

    @Override
    public void finishAbove() {
        // Nothing needed here - particles rendered during tile iteration
    }

    private COLOR getColorForType(RoomFlowType type) {
        return switch (type) {
            case PRODUCTION -> COLOR_PRODUCTION;
            case CONSUMPTION -> COLOR_CONSUMPTION;
            case STORAGE -> COLOR_STORAGE;
            case BOTH -> new ColorImp(200, 200, 100);
            default -> COLOR.WHITE50;
        };
    }

    private void renderFlowArrows(Renderer r, RenderIterator it, int zoomLevel) {
        int tx = it.tx();
        int ty = it.ty();

        // Render pull order connections with path-following (uses cached mapping)
        // This is O(1) per tile thanks to tilePathMap!
        if (HorizontalResourcePanel.showFlowPaths) {
            long tileKey = FlowConnection.encodeTile(tx, ty);
            List<FlowConnection> conns = tilePathMap.get(tileKey);
            if (conns != null && !conns.isEmpty()) {
                if (conns.size() > 1) {
                    renderOverlappingPaths(r, it, tx, ty, conns, zoomLevel);
                } else {
                    FlowConnection conn = conns.get(0);
                    renderPathFollowingArrow(r, it, tx, ty, conn, conn.getColor(), zoomLevel);
                }
            }
        }

        // Render active transport paths (still use straight lines as these change rapidly)
        if (HorizontalResourcePanel.showActiveHaulers) {
            for (ActiveTransport trans : activeTransports) {
                // Skip rendering non-filtered haulers when shift-filtering by room
                if (hoveredRoom != null && trans.destRoomIdx() != hoveredRoom.index()) {
                    continue;
                }

                int srcTx = trans.pixelX() / C.TILE_SIZE;
                int srcTy = trans.pixelY() / C.TILE_SIZE;
                renderConnectionArrow(r, it, tx, ty, zoomLevel,
                    srcTx, srcTy,
                    trans.destTileX(), trans.destTileY(),
                    trans.resource(), COLOR_ACTIVE_HAUL);
            }
        }
    }

    /**
     * Handle rendering when multiple flow paths pass through the same tile.
     * Uses cycling for animation and small indicators for static visibility.
     */
    private void renderOverlappingPaths(Renderer r, RenderIterator it, int tx, int ty,
                                        List<FlowConnection> conns, int zoomLevel) {
        // 1. Cycle active connection for animated arrow (every 0.8s per path)
        double cycleSpeed = 1.2;
        int activeIdx = (int) ((VIEW.renderSecond() * cycleSpeed) % conns.size());
        FlowConnection active = conns.get(activeIdx);

        // 2. Render the active path following arrow
        renderPathFollowingArrow(r, it, tx, ty, active, active.getColor(), zoomLevel);

        // 3. For close zoom, show small directional indicators for ALL overlapping paths
        // This is much more informative than just dots!
        if (zoomLevel <= 1 && conns.size() > 1) {
            int iconSize = 6;  // Smaller to fit up to 4 indicators within one tile
            int spacing = 7;
            int startX = it.x() + 2;
            int startY = it.y() + C.TILE_SIZE - iconSize - 2;

            for (int i = 0; i < Math.min(conns.size(), 4); i++) {
                FlowConnection conn = conns.get(i);

                // Skip rendering non-filtered paths when shift-filtering by room
                boolean isHovered = hoveredRoom != null && isHoveredConnection(conn);
                if (hoveredRoom != null && !isHovered) {
                    continue;
                }

                DIR dir = conn.getDirectionAtTile(tx, ty);

                // Draw a tiny background to make the arrow pop
                COLOR.BLACK.bind();
                SPRITES.cons().TINY.full.render(r, 0x0F,
                    startX + i * spacing, startX + i * spacing + iconSize,
                    startY, startY + iconSize);
                COLOR.unbind();

                // Draw a tiny directional arrow colored by flow type
                COLOR col = conn.getColor();
                boolean isCycled = (i == activeIdx);

                if (!isCycled) {
                    // Dim non-cycled ones a bit
                    new ColorImp(col).shadeSelf(0.6).bind();
                } else {
                    col.bind();
                }

                SPRITES.cons().ICO.arrows2.get(dir.id()).render(r,
                    startX + i * spacing, startX + i * spacing + iconSize,
                    startY, startY + iconSize);
                COLOR.unbind();

                // Highlight the one that is currently active in the cycle
                if (isCycled) {
                    COLOR.WHITE100.bind();
                    SPRITES.cons().TINY.outline.render(r, 0x0F,
                        startX + i * spacing - 1, startX + i * spacing + iconSize + 1,
                        startY - 1, startY + iconSize + 1);
                    COLOR.unbind();
                }
            }
        }
    }

    /**
     * Render path-following connection with static arrows + animated flow indicator
     */
    private void renderPathFollowingArrow(Renderer r, RenderIterator it, int tx, int ty,
                                          FlowConnection conn, COLOR color, int zoomLevel) {
        // Skip rendering non-filtered paths when shift-filtering by room
        if (hoveredRoom != null && !isHoveredConnection(conn)) {
            return;
        }

        // 1. Draw static arrow showing path direction (small, sampled by zoom level)
        renderStaticPathArrow(r, it, tx, ty, conn, color, zoomLevel);

        // 2. Draw animated large arrow showing flow movement
        renderAnimatedFlowArrow(r, it, tx, ty, conn, color, zoomLevel);
    }

    /**
     * Draw a small static arrow on each path tile showing direction
     */
    private void renderStaticPathArrow(Renderer r, RenderIterator it, int tx, int ty,
                                       FlowConnection conn, COLOR color, int zoomLevel) {
        // NOTE: Path check skipped for performance - caller ensures this tile is on path via tilePathMap

        // Sparse sampling at higher zoom levels (performance optimization)
        // Thanks to O(1) HashSet lookup, zoom 2 now runs as fast as zoom 0-1!
        // Zoom 0-2: Every tile (detailed), Zoom 3: Every 2nd tile (simplified)
        int samplingInterval = (zoomLevel >= 3) ? 2 : 1;
        if (samplingInterval > 1) {
            // Use tile coordinates to create a stable sampling pattern
            if ((tx + ty) % samplingInterval != 0) {
                return; // Skip this tile
            }
        }

        // At zoom 3, use TINY sprites (simple dots) like the game does
        if (zoomLevel >= 3) {
            // Use simple colored dot/square - ULTRA FAST like terrain rendering
            ColorImp dotColor = new ColorImp();
            dotColor.set(color);
            dotColor.bind();
            SPRITES.cons().TINY.dots.get(0).render(r, it.x(), it.y());
            COLOR.unbind();
            return;
        }

        // Get direction at this tile
        DIR dir = conn.getDirectionAtTile(tx, ty);
        if (dir == null) return;

        // Get small arrow sprite
        snake2d.util.sprite.SPRITE arrow = SPRITES.cons().ICO.arrows2.get(dir.id());
        if (arrow == null) return;

        // Draw semi-transparent arrow (shows path structure)
        ColorImp arrowColor = new ColorImp();
        arrowColor.set(color);
        arrowColor.shadeSelf(0.8); // Brighter than before
        arrowColor.bind();
        arrow.render(r, it.x(), it.y());
        COLOR.unbind();

        // Draw resource icon on top (scaled up x2) - only at close zoom
        // Safety check: ensure FLOW_ICON_INTERVAL is at least 1 to avoid division by zero
        int iconInterval = Math.max(1, ModConfig.FLOW_ICON_INTERVAL);
        if (zoomLevel <= 1 && conn.resource != null && conn.resource.icon() != null && (tx + ty) % iconInterval == 0) {
            int scale = 2;
            int iconWidth = conn.resource.icon().width() * scale;
            int iconHeight = conn.resource.icon().height() * scale;
            int iconX = it.x() + (C.TILE_SIZE - iconWidth) / 2;
            int iconY = it.y() + (C.TILE_SIZE - iconHeight) / 2;

            COLOR.WHITE100.bind();
            conn.resource.icon().renderScaled(r, iconX, iconY, scale);
            COLOR.unbind();
        }
    }

    /**
     * Check if tile is exactly on the path - O(1) HashSet lookup!
     * MASSIVE PERFORMANCE WIN: Instead of looping through all path segments and
     * doing distance calculations (O(n) per tile), we just check the HashSet (O(1))
     */
    private boolean isExactlyOnPath(int tx, int ty, FlowConnection conn) {
        return conn.isTileOnPath(tx, ty);
    }

    /**
     * Draw large animated arrow showing active flow movement
     * Only draws at SPECIFIC tiles where the animated wave is located
     */
    private void renderAnimatedFlowArrow(Renderer r, RenderIterator it, int tx, int ty,
                                         FlowConnection conn, COLOR color, int zoomLevel) {
        if (conn.pathTiles.isEmpty()) return;

        // NOTE: Path check skipped for performance - caller ensures this tile is on path via tilePathMap

        // Get direction at this tile
        DIR dir = conn.getDirectionAtTile(tx, ty);
        if (dir == null) return;

        // Calculate progress along path (0.0 to 1.0)
        double progress = conn.getPathProgress(tx, ty);

        // HUGE PERFORMANCE WIN: Use cached path length instead of recalculating!
        double pathLength = conn.cachedPathLength;
        if (pathLength < 1) return;

        // Animation: arrow moves FORWARD along path
        double animOffset = VIEW.renderSecond() * arrowAnimSpeed();

        // Progress in tiles along the path
        double tileProgress = progress * pathLength;

        // Component paths are in REVERSE order (dest->source), so subtract animOffset to make it appear forward
        double phase = MATH.mod(tileProgress - animOffset, 16.0);

        // Only draw when phase is between 0-2 (one large arrow pulse)
        if (phase < 2.0) {
            // At zoom 3, use bright TINY dot (simple and fast)
            if (zoomLevel >= 3) {
                COLOR.WHITE100.bind();
                SPRITES.cons().TINY.dots.get(1).render(r, it.x(), it.y());
                COLOR.unbind();
                return;
            }

            // Get arrow sprite
            snake2d.util.sprite.SPRITE arrow = SPRITES.cons().ICO.arrows2.get(dir.id());
            if (arrow == null) return;

            // Draw bright animated arrow (2x scale fits tile perfectly)
            color.bind();
            // Position: center the scaled sprite
            int spriteWidth = arrow.width();
            int spriteHeight = arrow.height();
            int scale = 2; // Reduced from 4 for better clarity and less visual mess
            int renderX = it.x() + (C.TILE_SIZE - spriteWidth * scale) / 2;
            int renderY = it.y() + (C.TILE_SIZE - spriteHeight * scale) / 2;
            arrow.renderScaled(r, renderX, renderY, scale);
            COLOR.unbind();
        }
    }

    /**
     * Legacy straight-line arrow rendering (fallback or for active transports)
     */
    private void renderConnectionArrow(Renderer r, RenderIterator it, int tx, int ty, int zoomLevel,
                                       int srcX, int srcY, int destX, int destY,
                                       RESOURCE resource, COLOR color) {
        if (!isNearLine(tx, ty, srcX, srcY, destX, destY, 2)) {
            return;
        }

        int dx = destX - srcX;
        int dy = destY - srcY;
        DIR dir = DIR.get(dx, dy);
        if (dir == null) return;

        double pathLength = Math.sqrt(dx * dx + dy * dy);
        if (pathLength < 1) return;

        double projDist = ((tx - srcX) * dx + (ty - srcY) * dy) / pathLength;
        double animOffset = VIEW.renderSecond() * arrowAnimSpeed();
        double phase = MATH.mod(projDist - animOffset, 4.0);

        if (phase < 1.0) {
            color.bind();
            SPRITES.cons().ICO.arrows2.get(dir.id()).render(r, it.x(), it.y());

            if (resource != null && resource.icon() != null && (tx + ty) % ModConfig.FLOW_ICON_INTERVAL == 0) {
                // Scaled up x2 for visibility
                int scale = 2;
                int iconWidth = resource.icon().width() * scale;
                int iconHeight = resource.icon().height() * scale;
                int iconX = it.x() + (C.TILE_SIZE - iconWidth) / 2;
                int iconY = it.y() + (C.TILE_SIZE - iconHeight) / 2;

                COLOR.WHITE100.bind();
                resource.icon().renderScaled(r, iconX, iconY, scale);
            }
            COLOR.unbind();
        }
    }

    private boolean isNearLine(int px, int py, int x1, int y1, int x2, int y2, double maxDist) {
        int minX = Math.min(x1, x2) - (int) maxDist;
        int maxX = Math.max(x1, x2) + (int) maxDist;
        int minY = Math.min(y1, y2) - (int) maxDist;
        int maxY = Math.max(y1, y2) + (int) maxDist;

        if (px < minX || px > maxX || py < minY || py > maxY) {
            return false;
        }

        double dx = x2 - x1;
        double dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;

        if (lenSq < 1) {
            return Math.abs(px - x1) <= maxDist && Math.abs(py - y1) <= maxDist;
        }

        double t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / lenSq));
        double projX = x1 + t * dx;
        double projY = y1 + t * dy;

        double dist = Math.sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY));
        return dist <= maxDist;
    }

    /**
     * Render room center info including trend indicators
     */
    private void renderBottleneckIndicators(Renderer r, RenderIterator it, int zoomLevel) {
        if (analyzer == null) return;
        List<ResourceFlowData.BottleneckInfo> bottlenecks = analyzer.getData().getBottlenecks();
        if (bottlenecks.isEmpty()) return;

        // Pulse effect for indicators
        double time = VIEW.renderSecond() * 2.0;
        float pulse = (float) (0.5 + 0.5 * Math.sin(time * Math.PI));

        for (ResourceFlowData.BottleneckInfo info : bottlenecks) {
            Room room = SETT.ROOMS().map.getByIndex(info.roomIndex());
            if (!(room instanceof RoomInstance instance)) continue;

            // Only render if the current tile is the room's center (to avoid double rendering)
            if (it.tx() == instance.body().cX() && it.ty() == instance.body().cY()) {
                renderBottleneckIcon(r, it, info, pulse);
            }
        }
    }

    private void renderBottleneckIcon(Renderer r, RenderIterator it, ResourceFlowData.BottleneckInfo info, float pulse) {
        int x = it.x() + C.TILE_SIZE / 2;
        int y = it.y() + C.TILE_SIZE / 2;

        // Choose icon and color based on bottleneck type
        COLOR color;
        snake2d.util.sprite.SPRITE icon;

        switch (info.type()) {
            case OUTPUT_FULL -> {
                color = COLOR.RED200;
                icon = SPRITES.cons().ICO.warning;
            }
            case INPUT_MISSING -> {
                color = COLOR.ORANGE100;
                icon = SPRITES.cons().ICO.unclear;
            }
            case STORAGE_FULL -> {
                color = COLOR.YELLOW100;
                icon = SPRITES.cons().ICO.warning;
            }
            case EMPLOYMENT_LOW -> {
                color = COLOR.NYAN100;
                icon = SPRITES.cons().ICO.cancel;
            }
            default -> {
                color = COLOR.WHITE200;
                icon = SPRITES.cons().ICO.warning;
            }
        }

        // Render background shadow/circle
        OPACITY.O50.bind();
        COLOR.BLACK.bind();
        SPRITES.cons().ICO.tile.render(r, x - 12, x + 12, y - 12, y + 12);
        COLOR.unbind();
        OPACITY.unbind();

        // Render pulsed icon
        color.bind();
        pulsedOpacity.val = (byte) (pulse * 255);
        pulsedOpacity.bind();
        int size = 10;
        icon.renderScaled(r, x - size, y - size, 2);
        OPACITY.unbind();
        COLOR.unbind();

        // If it's a resource bottleneck, render the resource icon too
        if (info.resource() != null) {
            info.resource().icon().renderScaled(r, x + 2, y + 2, 2);
        }
    }

    private void renderRoomCenterInfo(Renderer r, RenderIterator it, int zoomLevel) {
        Room room = SETT.ROOMS().map.get(it.tx(), it.ty());
        if (!(room instanceof RoomInstance instance)) {
            return;
        }

        int roomCenterTX = instance.body().cX();
        int roomCenterTY = instance.body().cY();

        if (it.tx() != roomCenterTX || it.ty() != roomCenterTY) {
            return;
        }

        RoomFlowType flowType = roomTypeCache.get(instance.index());
        if (flowType == null || flowType == RoomFlowType.NONE) {
            return;
        }

        int cx = it.x() + C.TILE_SIZE / 2;
        int cy = it.y() + C.TILE_SIZE / 2;

        COLOR col = getColorForType(flowType);
        col.bind();

        // Draw type indicator (2x scale at close zoom)
        if (flowType == RoomFlowType.PRODUCTION) {
            SPRITES.cons().ICO.arrows.get(DIR.N.orthoID()).renderScaled(r, cx - 16, cy - 24, 2);
        } else if (flowType == RoomFlowType.CONSUMPTION) {
            SPRITES.cons().ICO.arrows_inwards.get(DIR.N.orthoID()).renderScaled(r, cx - 16, cy - 24, 2);
        } else if (flowType == RoomFlowType.STORAGE) {
            SPRITES.cons().BIG.outline.render(r, 0x0F, it.x() + 8, it.y() + 8);

            // Show trend indicator for storage (2x scale)
            if (analyzer != null && zoomLevel <= 1) {
                RESOURCE hovered = getHoveredResource();
                if (hovered != null) {
                    ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(hovered);
                    renderTrendIndicator(r, cx + 20, cy - 20, stats);
                }
            }
        }

        COLOR.unbind();
    }

    /**
     * Render a small trend indicator (arrow up/down/flat)
     */
    private void renderTrendIndicator(Renderer r, int x, int y, ResourceFlowData.ResourceFlowStats stats) {
        int netFlow = stats.netFlowPerDay;

        COLOR trendColor;
        DIR trendDir;

        if (netFlow > 10) {
            trendColor = COLOR_TREND_UP;
            trendDir = DIR.N;
        } else if (netFlow < -10) {
            trendColor = COLOR_TREND_DOWN;
            trendDir = DIR.S;
        } else {
            trendColor = COLOR_TREND_NEUTRAL;
            trendDir = DIR.E;  // Horizontal arrow for neutral
        }

        trendColor.bind();
        SPRITES.cons().ICO.arrows.get(trendDir.orthoID()).renderScaled(r, x - 16, y - 16, 2);
        COLOR.unbind();
    }

    private void rebuildCache() {
        roomTypeCache.clear();
        flowConnections.clear();
        activeTransports.clear();

        try {
            // Build room type cache for all rooms, considering selected/hovered resources
            for (int i = 0; i < SETT.ROOMS().map.max(); i++) {
                Room room = SETT.ROOMS().map.getByIndex(i);
                if (room == null || !(room instanceof RoomInstance instance)) continue;

                // Classify room for any selected/hovered resource
                RoomFlowType type = classifyRoomForMultipleResources(instance);
                roomTypeCache.put(instance.index(), type);
            }

            // Build flow connections (already handles filtering inside)
            buildFlowConnectionsFromHaulers(null);

            // Scan active transports (already handles filtering inside)
            scanActiveTransports(null);

            // update all ~new~ paths immediately during rebuild
            double currentTime = analyzer != null ? analyzer.getData().getGameTime() : VIEW.renderSecond();
            for (FlowConnection conn : flowConnections) {
                // Only force update if path is empty or never calculated
                if (conn.pathTiles.isEmpty() || conn.lastPathUpdate == 0) {
                    conn.lastPathUpdate = 0; // Force update
                    conn.updatePath(currentTime);
                }
            }

            // Populate the tile mapping after all paths are updated
            rebuildTilePathMap();
            debug("FlowTracker: " + roomTypeCache.size() + " rooms, " +
                flowConnections.size() + " flows (" + persistentFlows.size() + " tracked), " +
                activeTransports.size() + " haulers" +
                (analyzer != null ? ", " + analyzer.getData().getTotalHaulEvents() + " total hauls" : ""));


        } catch (Exception e) {
            snake2d.LOG.err("FlowTracker rebuildCache error: " + e.getMessage());
        }
    }

    /**
     * Classify a room considering all selected/hovered resources (including chains)
     */
    private RoomFlowType classifyRoomForMultipleResources(RoomInstance instance) {
        boolean produces = false;
        boolean consumes = false;

        // If there are selected resources, check for them (including chain)
        if (!HorizontalResourcePanel.selectedResources.isEmpty()) {
            // Use the expanded resource set (includes chain dependencies if enabled)
            java.util.Set<RESOURCE> effectiveResources = HorizontalResourcePanel.getEffectiveSelectedResources();
            for (RESOURCE res : effectiveResources) {
                RoomFlowType type = classifyRoom(instance, res);
                if (type == RoomFlowType.PRODUCTION || type == RoomFlowType.BOTH) {
                    produces = true;
                }
                if (type == RoomFlowType.CONSUMPTION || type == RoomFlowType.BOTH) {
                    consumes = true;
                }
                if (type == RoomFlowType.STORAGE) {
                    return RoomFlowType.STORAGE;
                }
            }
        } else {
            // Otherwise use hovered resource (including its chain) or show all
            RESOURCE hovered = getHoveredResource();
            if (hovered != null && HorizontalResourcePanel.showResourceChains) {
                // Check for hovered resource and its chain
                java.util.Set<RESOURCE> hoveredChain = new java.util.HashSet<>();
                hoveredChain.add(hovered);
                java.util.Set<RESOURCE> expandedChain = HorizontalResourcePanel.getExpandedResourceChain(hoveredChain);
                for (RESOURCE res : expandedChain) {
                    RoomFlowType type = classifyRoom(instance, res);
                    if (type == RoomFlowType.PRODUCTION || type == RoomFlowType.BOTH) {
                        produces = true;
                    }
                    if (type == RoomFlowType.CONSUMPTION || type == RoomFlowType.BOTH) {
                        consumes = true;
                    }
                    if (type == RoomFlowType.STORAGE) {
                        return RoomFlowType.STORAGE;
                    }
                }
            } else {
                return classifyRoom(instance, hovered);
            }
        }

        if (produces && consumes) return RoomFlowType.BOTH;
        if (produces) return RoomFlowType.PRODUCTION;
        if (consumes) return RoomFlowType.CONSUMPTION;
        return RoomFlowType.NONE;
    }

    /**
     * Build flow connections from active haulers and update persistent flow map
     */
    private void buildFlowConnectionsFromHaulers(RESOURCE filter) {
        if (analyzer == null) return;

        try {
            double currentTime = analyzer.getData().getGameTime();

            // Update persistent flows from active haulers
            for (var entity : SETT.ENTITIES().getAllEnts()) {
                if (entity == null) continue;
                if (!(entity instanceof Humanoid h)) continue;

                if (h.ai() == null) continue;

                RESOURCE carried = h.ai().resourceCarried();
                if (carried == null) continue;

                // Filter by selected/hovered resources
                if (!shouldDisplayResource(carried)) continue;

                var dest = h.ai().getDestination();
                if (dest == null) continue;

                // Get destination room (where hauler is going)
                int destTx = dest.x();
                int destTy = dest.y();
                Room destRoom = SETT.ROOMS().map.get(destTx, destTy);

                if (!(destRoom instanceof RoomInstance destInstance)) continue;

                // Determine actual flow direction based on room types
                // If hauler is carrying resource TO a producer, they must be coming FROM storage/consumer
                // If hauler is carrying resource TO a consumer/storage, they must be coming FROM producer/storage
                boolean destProduces = ResourceFlowAnalyzer.roomProducesResource(destInstance, carried);
                boolean destConsumes = ResourceFlowAnalyzer.roomConsumesResource(destInstance, carried);
                boolean destIsStorage = destInstance.blueprintI() == SETT.ROOMS().STOCKPILE;

                // Skip if destination doesn't interact with this resource
                if (!destProduces && !destConsumes && !destIsStorage) continue;

                // For flow tracking, we need to find the SOURCE of the resource
                // The hauler is going TO destInstance, but they must have come FROM somewhere
                // We need to infer the source based on game logic:
                // - If dest is a consumer/storage, source is likely a producer/storage (valid flow)
                // - If dest is a producer, hauler is likely returning empty/wrong direction (skip)

                if (destProduces && !destConsumes && !destIsStorage) {
                    // Destination produces this resource - hauler shouldn't be bringing it there!
                    // This is likely a hauler going TO pick up resources, not delivering
                    // Skip this flow as it would be backwards
                    continue;
                }

                // Valid delivery: hauler is bringing resource to consumer or storage
                // We'll track this as an observed flow, but we can't determine exact source room
                // from current position alone. Instead, rely on implied flows from industry
                // relationships. Just mark this flow pair as recently active.
                // (Actual flow connections are built from industry relationships below)
            }

            // First build connections from industry production/consumption relationships
            // This adds new flows to persistentFlows
            buildFlowConnectionsFromIndustry(currentTime);

            // Then build visible connections from ALL persistent flows (including newly added ones)
            for (FlowConnectionData flowData : persistentFlows.values()) {
                if (!flowData.isActive(currentTime)) continue;

                // Filter by selected/hovered resources
                if (!shouldDisplayResource(flowData.resource)) continue;

                // Determine flow type based on actual resource production/consumption
                FlowType flowType = determineFlowType(
                    flowData.sourceRoomIdx,
                    flowData.destRoomIdx,
                    flowData.resource  // Pass the resource to check actual production/consumption!
                );

                flowConnections.add(new FlowConnection(
                    flowData.sourceRoomX, flowData.sourceRoomY,
                    flowData.sourceRoomIdx,
                    flowData.destRoomX, flowData.destRoomY,
                    flowData.destRoomIdx,
                    flowData.resource,
                    flowType
                ));
            }

        } catch (Exception e) {
            snake2d.LOG.err("buildFlowConnectionsFromHaulers error: " + e.getMessage());
        }
    }

    /**
     * Build flow connections based on industry input/output relationships
     * Uses ONLY the helper methods (which use industry APIs) - no instanceof checks
     */
    private void buildFlowConnectionsFromIndustry(double currentTime) {
        try {
            // Find production and consumption rooms using our helper methods
            Map<RESOURCE, List<RoomInstance>> producers = new HashMap<>();
            Map<RESOURCE, List<RoomInstance>> consumers = new HashMap<>();
            List<RoomInstance> storages = new ArrayList<>();

            // Build list of resources to check (including chain expansion)
            java.util.List<RESOURCE> resourcesToCheck = new java.util.ArrayList<>();
            if (!HorizontalResourcePanel.selectedResources.isEmpty()) {
                // Use expanded resource chain if enabled
                java.util.Set<RESOURCE> effectiveSet = HorizontalResourcePanel.getEffectiveSelectedResources();
                resourcesToCheck.addAll(effectiveSet);
            } else {
                RESOURCE hovered = getHoveredResource();
                if (hovered != null) {
                    // Include hovered resource and its chain
                    if (HorizontalResourcePanel.showResourceChains) {
                        java.util.Set<RESOURCE> hoveredChain = new java.util.HashSet<>();
                        hoveredChain.add(hovered);
                        resourcesToCheck.addAll(HorizontalResourcePanel.getExpandedResourceChain(hoveredChain));
                    } else {
                        resourcesToCheck.add(hovered);
                    }
                } else {
                    for (RESOURCE res : RESOURCES.ALL()) {
                        resourcesToCheck.add(res);
                    }
                }
            }

            // Scan all rooms and classify them using our helper methods
            for (int i = 0; i < SETT.ROOMS().map.max(); i++) {
                Room room = SETT.ROOMS().map.getByIndex(i);
                if (!(room instanceof RoomInstance instance)) continue;

                // Check for stockpiles
                if (instance.blueprintI() == SETT.ROOMS().STOCKPILE) {
                    storages.add(instance);
                    continue;
                }

                // For each resource, use our helper methods to check production/consumption
                for (RESOURCE res : resourcesToCheck) {
                    if (!shouldDisplayResource(res)) continue;

                    // Use helper method - covers ALL production types via INDUSTRY_HASER
                    if (ResourceFlowAnalyzer.roomProducesResource(instance, res)) {
                        producers.computeIfAbsent(res, k -> new ArrayList<>()).add(instance);
                    }

                    // Use helper method - covers ALL consumption types via INDUSTRY_HASER + bridges
                    if (ResourceFlowAnalyzer.roomConsumesResource(instance, res)) {
                        consumers.computeIfAbsent(res, k -> new ArrayList<>()).add(instance);
                    }
                }
            }

            // Create implied flow connections: producers -> storage -> consumers
            for (RESOURCE res : resourcesToCheck) {
                List<RoomInstance> prodList = producers.get(res);
                List<RoomInstance> consList = consumers.get(res);

                if (prodList != null && !storages.isEmpty()) {
                    // Producer -> Storage flows
                    for (RoomInstance prod : prodList) {
                        // Find nearest storage with capacity
                        RoomInstance nearestStorage = findNearestStorage(prod, res, storages);
                        if (nearestStorage != null) {
                            addImpliedFlow(prod, nearestStorage, res, currentTime);
                        }
                    }
                }

                if (consList != null && !storages.isEmpty()) {
                    // Storage -> Consumer flows
                    for (RoomInstance cons : consList) {
                        // Find nearest storage with resources
                        RoomInstance nearestStorage = findNearestStorage(cons, res, storages);
                        if (nearestStorage != null) {
                            addImpliedFlow(nearestStorage, cons, res, currentTime);
                        }
                    }
                }
            }

        } catch (Exception e) {
            snake2d.LOG.err("buildFlowConnectionsFromIndustry error: " + e.getMessage());
        }
    }

    /**
     * Find the nearest stockpile to a room
     */
    private RoomInstance findNearestStorage(RoomInstance from, RESOURCE res, List<RoomInstance> storages) {
        RoomInstance nearest = null;
        double minDist = Double.MAX_VALUE;

        for (RoomInstance storage : storages) {
            // Check if this stockpile has space or resources for this resource type
            try {
                settlement.room.infra.stockpile.StockpileInstance stockpile =
                    (settlement.room.infra.stockpile.StockpileInstance) storage;
                int amount = SETT.ROOMS().STOCKPILE.tally().amount.get(res, stockpile);
                int space = SETT.ROOMS().STOCKPILE.tally().space.get(res, stockpile);

                if (amount > 0 || space > 0) {
                    int dx = storage.body().cX() - from.body().cX();
                    int dy = storage.body().cY() - from.body().cY();
                    double dist = Math.sqrt(dx * dx + dy * dy);

                    if (dist < minDist) {
                        minDist = dist;
                        nearest = storage;
                    }
                }
            } catch (Exception e) {
                // Skip this storage if we can't access its data
            }
        }

        return nearest;
    }

    /**
     * Add an implied flow connection based on industry relationships
     */
    private void addImpliedFlow(RoomInstance src, RoomInstance dst, RESOURCE res, double currentTime) {
        String flowKey = src.index() + "->" + dst.index() + ":" + res.index();

        FlowConnectionData flowData = persistentFlows.get(flowKey);
        if (flowData == null) {
            flowData = new FlowConnectionData(
                src.index(), dst.index(),
                src.body().cX(), src.body().cY(),
                dst.body().cX(), dst.body().cY(),
                res
            );
            persistentFlows.put(flowKey, flowData);
        }

        // Mark as recently seen (but don't increase trip count for implied flows)
        flowData.lastSeenTime = currentTime;
    }

    private void scanActiveTransports(RESOURCE filter) {
        try {
            for (var entity : SETT.ENTITIES().getAllEnts()) {
                if (entity == null) continue;
                if (!(entity instanceof Humanoid h)) continue;

                if (h.ai() == null) continue;

                RESOURCE carried = h.ai().resourceCarried();
                if (carried == null) continue;

                // Filter by selected/hovered resources
                if (!shouldDisplayResource(carried)) continue;

                var dest = h.ai().getDestination();
                if (dest == null) continue;

                Room destRoom = SETT.ROOMS().map.get(dest.x(), dest.y());
                int destIdx = (destRoom instanceof RoomInstance ri) ? ri.index() : -1;

                activeTransports.add(new ActiveTransport(
                    h.body().cX(), h.body().cY(),
                    dest.x(), dest.y(),
                    destIdx,
                    carried,
                    h.ai().resourceA()
                ));
            }
        } catch (Exception e) {
            snake2d.LOG.err("scanActiveTransports error: " + e.getMessage());
        }
    }

    /**
     * Determine flow type based on actual source/dest room resource production/consumption
     * This is more accurate than just using room type classification
     */
    private FlowType determineFlowType(int sourceRoomIdx, int destRoomIdx, RESOURCE resource) {
        try {
            Room srcRoom = SETT.ROOMS().map.getByIndex(sourceRoomIdx);
            Room dstRoom = SETT.ROOMS().map.getByIndex(destRoomIdx);

            if (srcRoom == null || dstRoom == null) return FlowType.UNKNOWN;
            if (!(srcRoom instanceof RoomInstance srcInst && dstRoom instanceof RoomInstance dstInst)) {
                return FlowType.UNKNOWN;
            }

            // Check actual production/consumption via industry APIs
            boolean srcProduces = ResourceFlowAnalyzer.roomProducesResource(srcInst, resource);
            boolean srcStorage = srcInst.blueprintI() == SETT.ROOMS().STOCKPILE;
            boolean dstConsumes = ResourceFlowAnalyzer.roomConsumesResource(dstInst, resource);
            boolean dstStorage = dstInst.blueprintI() == SETT.ROOMS().STOCKPILE;

            // Classify flow based on actual resource behavior
            if (srcProduces && dstStorage) {
                return FlowType.PRODUCTION_TO_STORAGE;   // Green-cyan: harvest to stockpile
            }
            if (srcStorage && dstConsumes) {
                return FlowType.STORAGE_TO_CONSUMPTION;  // Red-orange: stockpile to consumer
            }
            if (srcProduces && dstConsumes) {
                return FlowType.PRODUCTION_TO_CONSUMPTION; // Yellow: direct supply (no storage)
            }
            if (srcStorage && dstStorage) {
                return FlowType.STORAGE_TO_STORAGE;      // Blue: redistribution
            }

            return FlowType.UNKNOWN;

        } catch (Exception e) {
            return FlowType.UNKNOWN;
        }
    }

    /**
     * Render simplified flow paths for the full strategic minimap view
     */
    public void renderStrategicView(Renderer r, view.subview.GameWindow window, RECTANGLE absBounds) {
        if (!added()) return;

        int zoom = window.zoomout();

        // 1. Render room background colors if enabled
        if (HorizontalResourcePanel.showRoomColors) {
            for (int i = 0; i < SETT.ROOMS().map.max(); i++) {
                Room room = SETT.ROOMS().map.getByIndex(i);
                if (!(room instanceof RoomInstance instance)) continue;

                RoomFlowType flowType = roomTypeCache.get(instance.index());
                if (flowType == null || flowType == RoomFlowType.NONE) continue;

                COLOR baseColor = getColorForType(flowType);
                baseColor.bind();

                // Draw a rectangle for the room on the minimap
                int x1 = absBounds.x1() + ((instance.body().x1() * C.TILE_SIZE - window.pixels().x1()) >> zoom);
                int y1 = absBounds.y1() + ((instance.body().y1() * C.TILE_SIZE - window.pixels().y1()) >> zoom);
                int x2 = absBounds.x1() + ((instance.body().x2() * C.TILE_SIZE - window.pixels().x1()) >> zoom);
                int y2 = absBounds.y1() + ((instance.body().y2() * C.TILE_SIZE - window.pixels().y1()) >> zoom);

                if (x2 > x1 && y2 > y1) {
                    baseColor.render(r, x1, x2, y1, y2);
                }
            }
            COLOR.unbind();
        }

        // 2. Render flow connections
        if (HorizontalResourcePanel.showFlowPaths) {
            for (FlowConnection conn : flowConnections) {
                if (!shouldDisplayResource(conn.resource)) continue;

                COLOR color = conn.getColor();
                color.bind();

                if (!conn.pathTiles.isEmpty()) {
                    for (FlowConnection.PathTile tile : conn.pathTiles) {
                        int x = absBounds.x1() + ((tile.x() * C.TILE_SIZE - window.pixels().x1()) >> zoom);
                        int y = absBounds.y1() + ((tile.y() * C.TILE_SIZE - window.pixels().y1()) >> zoom);
                        r.renderParticle(x, y);
                    }
                }
            }
            COLOR.unbind();
        }

        // 3. Render active haulers
        if (HorizontalResourcePanel.showActiveHaulers) {
            COLOR_ACTIVE_HAUL.bind();
            for (ActiveTransport trans : activeTransports) {
                if (!shouldDisplayResource(trans.resource())) continue;

                int x = absBounds.x1() + ((trans.pixelX() - window.pixels().x1()) >> zoom);
                int y = absBounds.y1() + ((trans.pixelY() - window.pixels().y1()) >> zoom);
                r.renderParticle(x, y);
            }
            COLOR.unbind();
        }

        // 4. Render Traffic Heatmap in Strategic View
        if (HorizontalResourcePanel.showTrafficHeatmap && analyzer != null) {
            RESOURCE filter = getHoveredResource();
            if (filter == null && !HorizontalResourcePanel.selectedResources.isEmpty()) {
                filter = HorizontalResourcePanel.selectedResources.iterator().next();
            }

            Map<Long, Float> trafficMap = analyzer.getData().getResourceTraffic(filter);

            if (trafficMap != null && !trafficMap.isEmpty()) {
                OPACITY.O50.bind();
                for (Map.Entry<Long, Float> entry : trafficMap.entrySet()) {
                    float val = entry.getValue();
                    if (val > 1.0f) {
                        long key = entry.getKey();
                        int tx = (int) (key >> 32);
                        int ty = (int) (key & 0xFFFFFFFFL);

                        int x = absBounds.x1() + ((tx * C.TILE_SIZE - window.pixels().x1()) >> zoom);
                        int y = absBounds.y1() + ((ty * C.TILE_SIZE - window.pixels().y1()) >> zoom);

                        int idx = (int) (Math.min(1.0f, val / 5.0f) * 20);
                        COLOR color = HEAT_GRADIENT[idx];
                        color.bind();

                        int size = Math.max(1, 16 >> zoom);
                        SPRITES.cons().ICO.tile.render(r, x, x + size, y, y + size);
                    }
                }
                OPACITY.unbind();
                COLOR.unbind();
            }
        }

        // 5. Render Bottlenecks in Strategic View
        if (HorizontalResourcePanel.showBottlenecks && analyzer != null) {
            List<ResourceFlowData.BottleneckInfo> bottlenecks = analyzer.getData().getBottlenecks();
            if (!bottlenecks.isEmpty()) {
                COLOR.RED200.bind();
                for (ResourceFlowData.BottleneckInfo info : bottlenecks) {
                    Room room = SETT.ROOMS().map.getByIndex(info.roomIndex());
                    if (!(room instanceof RoomInstance instance)) continue;

                    int tx = instance.body().cX();
                    int ty = instance.body().cY();

                    int x = absBounds.x1() + ((tx * C.TILE_SIZE - window.pixels().x1()) >> zoom);
                    int y = absBounds.y1() + ((ty * C.TILE_SIZE - window.pixels().y1()) >> zoom);

                    SPRITES.cons().ICO.tile.render(r, x - 1, x + 1, y - 1, y + 1);
                }
                COLOR.unbind();
            }
        }
    }

    private RoomFlowType classifyRoom(RoomInstance instance, RESOURCE filter) {
        boolean produces = false;
        boolean consumes = false;

        if (instance.blueprintI() == SETT.ROOMS().STOCKPILE) {
            if (filter != null) {
                int amount = SETT.ROOMS().STOCKPILE.tally().amount.get(filter,
                    (settlement.room.infra.stockpile.StockpileInstance) instance);
                if (amount > 0) {
                    return RoomFlowType.STORAGE;
                }
                return RoomFlowType.NONE;
            }
            return RoomFlowType.STORAGE;
        }

        // Use our comprehensive helper methods for production/consumption checking
        if (filter != null) {
            // Check specific resource

            produces = ResourceFlowAnalyzer.roomProducesResource(instance, filter);
            consumes = ResourceFlowAnalyzer.roomConsumesResource(instance, filter);
        } else {
            // Check if room produces/consumes ANY resource
            // Check INDUSTRY_HASER (multiple industries)
            if (instance.blueprint() instanceof settlement.room.industry.module.INDUSTRY_HASER industryBlue) {
                for (settlement.room.industry.module.Industry industry : industryBlue.industries()) {
                    if (!industry.outs().isEmpty()) {
                        produces = true;
                    }
                    if (!industry.ins().isEmpty()) {
                        consumes = true;
                    }
                }
            }

            // Check ROOM_PRODUCER_INSTANCE (single industry: eateries, etc.)
            if (instance instanceof settlement.room.industry.module.ROOM_PRODUCER_INSTANCE producer) {
                settlement.room.industry.module.Industry industry = producer.industry();
                if (industry != null) {
                    if (!industry.outs().isEmpty()) {
                        produces = true;
                    }
                    if (!industry.ins().isEmpty()) {
                        consumes = true;
                    }
                }
            }

            // Check markets
            if (instance.blueprint() instanceof settlement.room.service.market.ROOM_MARKET) {
                consumes = true;
            }
        }

        if (produces && consumes) return RoomFlowType.BOTH;
        if (produces) return RoomFlowType.PRODUCTION;
        if (consumes) return RoomFlowType.CONSUMPTION;
        return RoomFlowType.NONE;
    }

    /**
     * Get status text for UI display - now includes trend info
     */
    public String getStatusText() {
        if (!added()) {
            return "Resource Flow Tracker: OFF";
        }

        RESOURCE hovered = getHoveredResource();
        String resourceText = hovered == null ? "All" : hovered.name.toString();

        long prodCount = roomTypeCache.values().stream()
            .filter(t -> t == RoomFlowType.PRODUCTION || t == RoomFlowType.BOTH).count();
        long consCount = roomTypeCache.values().stream()
            .filter(t -> t == RoomFlowType.CONSUMPTION || t == RoomFlowType.BOTH).count();
        long storCount = roomTypeCache.values().stream()
            .filter(t -> t == RoomFlowType.STORAGE).count();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Flow (%s): P:%d C:%d S:%d | H:%d",
            resourceText, prodCount, consCount, storCount, activeTransports.size()));

        // Add trend info if analyzer is available and resource is hovered
        if (analyzer != null && hovered != null) {
            ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(hovered);
            sb.append(String.format(" | +%d/d -%d/d Net:%d",
                stats.productionRatePerDay,
                stats.consumptionRatePerDay,
                stats.netFlowPerDay));

            // Show time projections
            if (stats.netFlowPerDay < -10) {
                double days = stats.getDaysUntilEmpty();
                if (days < 100) {
                    sb.append(String.format(" (empty in %.1fd)", days));
                }
            } else if (stats.netFlowPerDay > 10 && stats.storageCapacity > 0) {
                double days = stats.getDaysUntilFull();
                if (days < 100) {
                    sb.append(String.format(" (full in %.1fd)", days));
                }
            }
        }

        // Show room filtering status
        if (hoveredRoom != null) {
            sb.append(" | Room: ").append(hoveredRoom.name());
        } else {
            sb.append(" | [Shift] for room filter");
        }

        return sb.toString();
    }

    /**
     * Room flow type classification
     */
    private enum RoomFlowType {
        PRODUCTION,
        CONSUMPTION,
        STORAGE,
        BOTH,
        NONE
    }

    /**
     * Flow types for color-coding connections
     */
    private enum FlowType {
        PRODUCTION_TO_STORAGE,   // Green-cyan: harvest/collect
        STORAGE_TO_CONSUMPTION,  // Red-orange: delivery/supply
        PRODUCTION_TO_CONSUMPTION, // Yellow: direct supply
        STORAGE_TO_STORAGE,      // Blue: redistribution
        UNKNOWN                  // Fallback
    }

    /**
     * Represents a flow connection between two rooms with cached path
     */
    private static class FlowConnection {
        final int sourceRoomX, sourceRoomY;
        final int destRoomX, destRoomY;
        final int sourceRoomIdx, destRoomIdx;
        final RESOURCE resource;
        final FlowType flowType;

        // Cached path tiles (extracted from SPath for rendering)
        final java.util.List<PathTile> pathTiles;
        // Pre-computed set of tiles on path (for O(1) lookup instead of O(n) distance calculation)
        final java.util.Set<Long> pathTileSet = new java.util.HashSet<>();
        double lastPathUpdate = 0;
        // Cached path length (calculated once when path updates) - HUGE performance win!
        double cachedPathLength = 0;

        FlowConnection(int srcX, int srcY, int srcIdx, int dstX, int dstY, int dstIdx, RESOURCE res, FlowType type) {
            this.sourceRoomX = srcX;
            this.sourceRoomY = srcY;
            this.sourceRoomIdx = srcIdx;
            this.destRoomX = dstX;
            this.destRoomY = dstY;
            this.destRoomIdx = dstIdx;
            this.resource = res;
            this.flowType = type;
            this.pathTiles = new java.util.ArrayList<>();
        }

        // Helper to encode tile coordinates into a single long
        static long encodeTile(int x, int y) {
            return ((long) x << 32) | (y & 0xFFFFFFFFL);
        }

        // Check if a tile is on the path (O(1) lookup!)
        boolean isTileOnPath(int tx, int ty) {
            return pathTileSet.contains(encodeTile(tx, ty));
        }

        // Get color based on flow type
        COLOR getColor() {
            return switch (flowType) {
                case PRODUCTION_TO_STORAGE -> COLOR_FLOW_PROD_TO_STORAGE;
                case STORAGE_TO_CONSUMPTION -> COLOR_FLOW_STORAGE_TO_CONS;
                case PRODUCTION_TO_CONSUMPTION -> COLOR_FLOW_PROD_TO_CONS;
                case STORAGE_TO_STORAGE -> COLOR_FLOW_STORAGE_TO_STORAGE;
                case UNKNOWN -> COLOR_FLOW_LINE;
            };
        }

        /**
         * Request and cache a SIMPLIFIED component-based path (much faster!)
         * Uses the pre-computed component graph instead of full A* pathfinding
         * <p>
         * CRITICAL: SCompFinder returns a SHARED, MUTABLE object that gets overwritten
         * by subsequent calls! We MUST extract and copy the data IMMEDIATELY!
         */
        boolean updatePath(double currentTime) {
            // Only request new path every 60 seconds (component paths change rarely)
            if (currentTime - lastPathUpdate < 60.0) {
                return !pathTiles.isEmpty();
            }

            lastPathUpdate = currentTime;

            // Build new path in temp list to avoid flashing (don't clear old path until new one is ready!)
            java.util.List<PathTile> newPath = new java.util.ArrayList<>();

            try {
                // Use component pathfinding - WAY faster, returns component centers
                // WARNING: This returns a SHARED MUTABLE object! Extract data immediately!
                settlement.path.components.finder.SCompFinder.SCompPath compPath =
                    SETT.PATH().comps.pather.findDest(sourceRoomX, sourceRoomY, destRoomX, destRoomY);

                if (compPath != null && compPath.path() != null) {
                    // IMMEDIATELY extract all components before they get overwritten!
                    // The compPath.path() list is reused and will be cleared on next call!
                    // CRITICAL: Component paths are in REVERSE order (dest->source)!
                    // We need to reverse them to get source->dest order for correct flow direction
                    snake2d.util.sets.LIST<settlement.path.components.SComponent> components = compPath.path();
                    int count = components.size();

                    // Add in REVERSE order to get source->dest
                    for (int i = count - 1; i >= 0; i--) {
                        settlement.path.components.SComponent comp = components.get(i);
                        if (comp != null) {
                            newPath.add(new PathTile(comp.centreX(), comp.centreY()));
                        }
                    }
                }

                // Fallback: if no component path, use straight line
                if (newPath.isEmpty()) {
                    newPath.add(new PathTile(sourceRoomX, sourceRoomY));
                    newPath.add(new PathTile(destRoomX, destRoomY));
                }

            } catch (Exception e) {
                // Silent fallback to straight line
                newPath.clear();
                newPath.add(new PathTile(sourceRoomX, sourceRoomY));
                newPath.add(new PathTile(destRoomX, destRoomY));
            }

            // Atomically swap in the new path (no flashing!)
            pathTiles.clear();
            pathTiles.addAll(newPath);

            // CRITICAL: Calculate and cache path length NOW (only once!)
            cachedPathLength = 0;
            for (int i = 0; i < pathTiles.size() - 1; i++) {
                PathTile p1 = pathTiles.get(i);
                PathTile p2 = pathTiles.get(i + 1);
                double dx = p2.x - p1.x;
                double dy = p2.y - p1.y;
                cachedPathLength += Math.sqrt(dx * dx + dy * dy);
            }

            // CRITICAL: Pre-build HashSet of all tiles on path for O(1) lookup!
            pathTileSet.clear();
            for (int i = 0; i < pathTiles.size() - 1; i++) {
                PathTile p1 = pathTiles.get(i);
                PathTile p2 = pathTiles.get(i + 1);

                // Rasterize line segment into tiles (Bresenham-like)
                int x1 = p1.x, y1 = p1.y;
                int x2 = p2.x, y2 = p2.y;

                int dx = Math.abs(x2 - x1);
                int dy = Math.abs(y2 - y1);
                int sx = x1 < x2 ? 1 : -1;
                int sy = y1 < y2 ? 1 : -1;
                int err = dx - dy;

                int x = x1, y = y1;
                while (true) {
                    pathTileSet.add(encodeTile(x, y));

                    if (x == x2 && y == y2) break;

                    int e2 = 2 * err;
                    if (e2 > -dy) {
                        err -= dy;
                        x += sx;
                    }
                    if (e2 < dx) {
                        err += dx;
                        y += sy;
                    }
                }
            }

            return !pathTiles.isEmpty();
        }

        /**
         * Check if a tile is on or near the cached path (component-level, so use larger tolerance)
         */
        boolean isNearPath(int tx, int ty, double maxDist) {
            if (pathTiles.isEmpty()) {
                // Fallback to straight line check
                return isNearStraightLine(tx, ty, sourceRoomX, sourceRoomY, destRoomX, destRoomY, maxDist);
            }

            // Since we're using component centers (coarse path), increase tolerance
            double tolerance = maxDist * 8; // Components can be 16-64 tiles wide

            // Check if tile is within tolerance of any path segment
            for (int i = 0; i < pathTiles.size() - 1; i++) {
                PathTile p1 = pathTiles.get(i);
                PathTile p2 = pathTiles.get(i + 1);

                if (isNearLineSegment(tx, ty, p1.x, p1.y, p2.x, p2.y, tolerance)) {
                    return true;
                }
            }

            return false;
        }

        /**
         * Get direction at a specific tile position along the path
         */
        DIR getDirectionAtTile(int tx, int ty) {
            if (pathTiles.isEmpty()) {
                // Fallback to straight line direction
                int dx = destRoomX - sourceRoomX;
                int dy = destRoomY - sourceRoomY;
                return DIR.get(dx, dy);
            }

            // Find closest path segment
            int closestSegment = -1;
            double closestDist = Double.MAX_VALUE;

            for (int i = 0; i < pathTiles.size() - 1; i++) {
                PathTile p1 = pathTiles.get(i);
                PathTile p2 = pathTiles.get(i + 1);

                double dist = distanceToSegment(tx, ty, p1.x, p1.y, p2.x, p2.y);
                if (dist < closestDist) {
                    closestDist = dist;
                    closestSegment = i;
                }
            }

            if (closestSegment >= 0) {
                PathTile p1 = pathTiles.get(closestSegment);
                PathTile p2 = pathTiles.get(closestSegment + 1);

                // Calculate direction from p1 to p2 (forward along path)
                int dx = p2.x - p1.x;
                int dy = p2.y - p1.y;
                DIR d = DIR.get(dx, dy);
                return d != null ? d : DIR.N;
            }

            return DIR.N;
        }

        /**
         * Get progress along path for animation (0.0 to 1.0)
         */
        double getPathProgress(int tx, int ty) {
            if (pathTiles.isEmpty()) {
                // Fallback to straight line projection
                int dx = destRoomX - sourceRoomX;
                int dy = destRoomY - sourceRoomY;
                double pathLength = Math.sqrt(dx * dx + dy * dy);
                if (pathLength < 1) return 0;

                double projDist = ((tx - sourceRoomX) * dx + (ty - sourceRoomY) * dy) / pathLength;
                return projDist / pathLength;
            }

            // Find closest point on path and calculate accumulated distance
            double totalLength = 0;
            for (int i = 0; i < pathTiles.size() - 1; i++) {
                PathTile p1 = pathTiles.get(i);
                PathTile p2 = pathTiles.get(i + 1);
                totalLength += Math.sqrt((p2.x - p1.x) * (p2.x - p1.x) + (p2.y - p1.y) * (p2.y - p1.y));
            }

            if (totalLength < 1) return 0;

            double accumulatedDist = 0;
            double closestSegmentDist = Double.MAX_VALUE;
            double progressAtClosest = 0;

            for (int i = 0; i < pathTiles.size() - 1; i++) {
                PathTile p1 = pathTiles.get(i);
                PathTile p2 = pathTiles.get(i + 1);

                double segmentLength = Math.sqrt((p2.x - p1.x) * (p2.x - p1.x) + (p2.y - p1.y) * (p2.y - p1.y));
                double dist = distanceToSegment(tx, ty, p1.x, p1.y, p2.x, p2.y);

                if (dist < closestSegmentDist) {
                    closestSegmentDist = dist;

                    // Calculate t parameter on this segment
                    double dx = p2.x - p1.x;
                    double dy = p2.y - p1.y;
                    double lenSq = dx * dx + dy * dy;
                    double t = 0;
                    if (lenSq > 0) {
                        t = Math.max(0, Math.min(1, ((tx - p1.x) * dx + (ty - p1.y) * dy) / lenSq));
                    }

                    progressAtClosest = (accumulatedDist + t * segmentLength) / totalLength;
                }

                accumulatedDist += segmentLength;
            }

            return progressAtClosest;
        }

        private boolean isNearLineSegment(int px, int py, int x1, int y1, int x2, int y2, double maxDist) {
            double dist = distanceToSegment(px, py, x1, y1, x2, y2);
            return dist <= maxDist;
        }

        private double distanceToSegment(int px, int py, int x1, int y1, int x2, int y2) {
            double dx = x2 - x1;
            double dy = y2 - y1;
            double lenSq = dx * dx + dy * dy;

            if (lenSq < 1) {
                return Math.sqrt((px - x1) * (px - x1) + (py - y1) * (py - y1));
            }

            double t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / lenSq));
            double projX = x1 + t * dx;
            double projY = y1 + t * dy;

            return Math.sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY));
        }

        private boolean isNearStraightLine(int px, int py, int x1, int y1, int x2, int y2, double maxDist) {
            int minX = Math.min(x1, x2) - (int) maxDist;
            int maxX = Math.max(x1, x2) + (int) maxDist;
            int minY = Math.min(y1, y2) - (int) maxDist;
            int maxY = Math.max(y1, y2) + (int) maxDist;

            if (px < minX || px > maxX || py < minY || py > maxY) {
                return false;
            }

            return distanceToSegment(px, py, x1, y1, x2, y2) <= maxDist;
        }

        // Simple tile representation for path rendering
        record PathTile(int x, int y) {
        }
    }

    /**
     * Tracks statistics for a persistent flow connection
     */
    private static class FlowConnectionData {
        int sourceRoomIdx, destRoomIdx;
        int sourceRoomX, sourceRoomY;
        int destRoomX, destRoomY;
        RESOURCE resource;
        int tripCount = 0;
        double lastSeenTime = 0;
        double totalDistance = 0;

        FlowConnectionData(int srcIdx, int dstIdx, int srcX, int srcY, int dstX, int dstY, RESOURCE res) {
            this.sourceRoomIdx = srcIdx;
            this.destRoomIdx = dstIdx;
            this.sourceRoomX = srcX;
            this.sourceRoomY = srcY;
            this.destRoomX = dstX;
            this.destRoomY = dstY;
            this.resource = res;
        }

        void recordTrip(double time, double distance) {
            tripCount++;
            lastSeenTime = time;
            totalDistance += distance;
        }

        // Returns true if this flow is still relevant (seen recently)
        boolean isActive(double currentTime) {
            return (currentTime - lastSeenTime) < 300.0;
        }

        double avgDistance() {
            return tripCount > 0 ? totalDistance / tripCount : 0;
        }
    }

    /**
     * Represents an active transport (hauler carrying resources)
     */
    private record ActiveTransport(
        int pixelX,
        int pixelY,
        int destTileX,
        int destTileY,
        int destRoomIdx,
        RESOURCE resource,
        double amount
    ) {
    }

    private static class PulsedOpacity implements OPACITY {
        byte val;

        @Override
        public byte get() {
            return val;
        }
    }
}

