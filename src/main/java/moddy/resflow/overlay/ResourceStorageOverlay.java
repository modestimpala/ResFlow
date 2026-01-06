package moddy.resflow.overlay;

import init.constant.C;
import init.resources.RESOURCE;
import init.resources.RESOURCES;
import init.sprite.SPRITES;
import init.sprite.UI.UI;
import moddy.resflow.ModConfig;
import moddy.resflow.analysis.ResourceFlowAnalyzer;
import moddy.resflow.ui.HorizontalResourcePanel;
import settlement.main.SETT;
import settlement.overlay.Addable;
import settlement.room.infra.stockpile.ROOM_STOCKPILE;
import settlement.room.infra.stockpile.StockpileInstance;
import settlement.room.main.Room;
import settlement.room.main.RoomInstance;
import snake2d.Renderer;
import snake2d.util.MATH;
import snake2d.util.color.COLOR;
import snake2d.util.color.ColorImp;
import snake2d.util.datatypes.RECTANGLE;
import util.rendering.RenderData;
import util.rendering.RenderData.RenderIterator;
import view.main.VIEW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Toggleable overlay showing resource storage amounts with visual scaling.
 * Highlights stockpile areas and shows resource icons and amounts.
 * Color-codes based on fill level: blue (low) -> green (medium) -> gold (high) -> red (full).
 */
public class ResourceStorageOverlay extends Addable {

    // Color scheme for storage visualization
    // Room background colors (renderBelow) - visible but not overpowering
    private static final COLOR COLOR_EMPTY_BG = new ColorImp(40, 40, 80);        // Medium blue-gray
    private static final COLOR COLOR_LOW_BG = new ColorImp(50, 80, 140);         // Medium blue
    private static final COLOR COLOR_MED_BG = new ColorImp(50, 120, 50);         // Medium green
    private static final COLOR COLOR_HIGH_BG = new ColorImp(140, 120, 30);       // Medium gold
    private static final COLOR COLOR_FULL_BG = new ColorImp(140, 50, 50);        // Medium red
    // Bar/icon colors (renderAbove) - bright and saturated for high contrast
    private static final COLOR COLOR_EMPTY_BAR = new ColorImp(60, 60, 100);      // Muted blue-gray
    private static final COLOR COLOR_LOW_BAR = new ColorImp(50, 150, 255);       // Bright blue
    private static final COLOR COLOR_MED_BAR = new ColorImp(50, 255, 50);        // Bright green
    private static final COLOR COLOR_HIGH_BAR = new ColorImp(255, 215, 0);       // Gold
    private static final COLOR COLOR_FULL_BAR = new ColorImp(255, 50, 50);       // Bright red
    private static final CharSequence ¤¤name = "Resource Storage";
    private static final CharSequence ¤¤desc = "Shows stockpile fill levels. Blue=empty, Green=partial, Gold=high, Red=full.";

    static {
        // Intentionally empty (TODO: localization?).
    }

    // Cache for stockpile fill levels (stockpile index -> total amount)
    private final Map<Integer, StockpileData> stockpileCache = new HashMap<>();
    // Track which stockpiles we've logged as missing to avoid spam
    private final java.util.Set<Integer> loggedMissingStockpiles = new java.util.HashSet<>();
    private final java.util.Set<Integer> loggedLargeStockpileChecks = new java.util.HashSet<>();
    private double timeSinceLastUpdate = 0.0;
    // Track last hovered resource and selection to detect changes
    private RESOURCE lastHoveredResource = null;
    private int lastSelectedCount = 0;
    private boolean lastChainToggleState = true;

    /**
     * Constructor - registers this as a proper overlay
     */
    public ResourceStorageOverlay() {
        super(
            UI.icons().m.workshop,
            "RESOURCE_STORAGE",
            ¤¤name,
            ¤¤desc,
            true,   // renderBelow - color the entire stockpile area
            true    // renderAbove - show icons and amounts
        );
    }

    private double cacheUpdateInterval() {
        return ModConfig.STORAGE_OVERLAY_CACHE_UPDATE_INTERVAL;
    }

    private RESOURCE getHoveredResource() {
        // Use horizontal panel
        return HorizontalResourcePanel.currentlyHoveredResource;
    }

    /**
     * Check if a resource should be displayed (either selected or hovered)
     * Includes resource chain expansion when enabled
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

    public void invalidateCache() {
        stockpileCache.clear();
    }

    /**
     * Update cache periodically or when hovered resource, selection, or chain toggle changes
     */
    public void update(double ds) {
        if (!added()) return;

        RESOURCE currentHovered = getHoveredResource();
        boolean currentChainState = HorizontalResourcePanel.showResourceChains;

        // Check if hovered resource, selection, or chain toggle changed
        boolean hoveredChanged = (currentHovered != lastHoveredResource);
        boolean selectionChanged = lastSelectedCount != HorizontalResourcePanel.selectedResources.size();
        boolean chainToggleChanged = (currentChainState != lastChainToggleState);

        if (hoveredChanged || selectionChanged || chainToggleChanged) {
            lastHoveredResource = currentHovered;
            lastSelectedCount = HorizontalResourcePanel.selectedResources.size();
            lastChainToggleState = currentChainState;
            // Debug logging
            ModConfig.debug("StorageOverlay: Filter changed (hover: " + (currentHovered != null ? currentHovered.name : "null") +
                ", selected: " + lastSelectedCount +
                ", chains: " + currentChainState + "), rebuilding cache");
            // Immediately rebuild cache when filter changes
            rebuildCache();
            timeSinceLastUpdate = 0.0;
            return;
        }

        // Otherwise rebuild periodically
        timeSinceLastUpdate += ds;
        if (stockpileCache.isEmpty() || timeSinceLastUpdate >= cacheUpdateInterval()) {
            rebuildCache();
            timeSinceLastUpdate = 0.0;
        }
    }

    @Override
    public void initBelow(RenderData data) {
        if (stockpileCache.isEmpty()) {
            rebuildCache();
        }
    }

    @Override
    public void renderBelow(Renderer r, RenderIterator it) {
        Room room = SETT.ROOMS().map.get(it.tx(), it.ty());
        if (room == null) {
            return;
        }

        // Handle stockpiles
        if (room.blueprint() instanceof ROOM_STOCKPILE) {
            StockpileInstance instance = (StockpileInstance) room;
            StockpileData data = stockpileCache.get(instance.index());

            // Debug logging for missing cache entries
            if (data == null) {
                // Only log once per stockpile to avoid spam
                if (!loggedMissingStockpiles.contains(instance.index())) {
                    ModConfig.debug("StorageOverlay: No cache data for stockpile " + instance.index() +
                        " (area=" + instance.area() + " tiles)");
                    loggedMissingStockpiles.add(instance.index());
                }
                return;
            }

            // Skip stockpiles that don't contain any filtered resources
            if (data.totalAmount == 0) {
                return;
            }

            renderStockpileBackground(r, it, data);
            return;
        }

        // Handle production/consumption rooms when chains enabled and filters active
        if (HorizontalResourcePanel.showResourceChains && hasActiveFilters()) {
            renderProductionRoomBackground(r, it, room);
        }
    }

    private void renderStockpileBackground(Renderer r, RenderIterator it, StockpileData data) {

        // Use smooth color interpolation for background (dark colors)
        ColorImp bgColor = new ColorImp();
        double fillRatio = data.fillRatio();

        if (fillRatio < 0.25) {
            // Empty to Low (dark blue-gray to dark blue)
            bgColor.interpolate(COLOR_EMPTY_BG, COLOR_LOW_BG, fillRatio / 0.25);
        } else if (fillRatio < 0.50) {
            // Low to Med (dark blue to dark green)
            bgColor.interpolate(COLOR_LOW_BG, COLOR_MED_BG, (fillRatio - 0.25) / 0.25);
        } else if (fillRatio < 0.85) {
            // Med to High (dark green to dark gold)
            bgColor.interpolate(COLOR_MED_BG, COLOR_HIGH_BG, (fillRatio - 0.50) / 0.35);
        } else {
            // High to Full (dark gold to dark red)
            bgColor.interpolate(COLOR_HIGH_BG, COLOR_FULL_BG, (fillRatio - 0.85) / 0.15);
        }

        // Add subtle pulsing for high fill levels
        if (fillRatio > 0.75) {
            double time = VIEW.renderSecond() * 1.5;
            double pulse = MATH.mod(time, 2);
            pulse = MATH.distanceC(pulse, 1, 2);
            bgColor.shadeSelf(0.8 + pulse * 0.2);
        }

        bgColor.bind();
        SPRITES.cons().BIG.filled_striped.render(r, 0x0F, it.x(), it.y());
        COLOR.unbind();
    }

    /**
     * Check if filters are active (selected resources or hovered resource)
     */
    private boolean hasActiveFilters() {
        return !HorizontalResourcePanel.selectedResources.isEmpty() ||
            HorizontalResourcePanel.currentlyHoveredResource != null;
    }

    /**
     * Render background for production/consumption rooms
     */
    private void renderProductionRoomBackground(Renderer r, RenderIterator it, Room room) {
        if (!(room instanceof RoomInstance instance)) {
            return;
        }

        // Get filtered resources
        java.util.Set<RESOURCE> filteredResources = HorizontalResourcePanel.getEffectiveSelectedResources();
        if (filteredResources.isEmpty() && HorizontalResourcePanel.currentlyHoveredResource != null) {
            filteredResources = new java.util.HashSet<>();
            filteredResources.add(HorizontalResourcePanel.currentlyHoveredResource);
            filteredResources = HorizontalResourcePanel.getExpandedResourceChain(filteredResources);
        }

        if (filteredResources.isEmpty()) {
            return;
        }

        // Check if this room produces or consumes any filtered resources
        boolean produces = false;
        boolean consumes = false;

        for (RESOURCE res : filteredResources) {
            if (ResourceFlowAnalyzer.roomProducesResource(instance, res)) {
                produces = true;
            }
            if (ResourceFlowAnalyzer.roomConsumesResource(instance, res)) {
                consumes = true;
            }
        }

        if (!produces && !consumes) {
            return;
        }

        // Render subtle background tint
        ColorImp roomColor = new ColorImp();
        if (produces && consumes) {
            roomColor.set(100, 100, 20);  // Yellow-ish for both
        } else if (produces) {
            roomColor.set(20, 100, 20);   // Green for production
        } else {
            roomColor.set(100, 20, 20);   // Red for consumption
        }

        roomColor.bind();
        SPRITES.cons().BIG.filled_striped.render(r, 0x03, it.x(), it.y());  // Very subtle pattern
        COLOR.unbind();
    }

    @Override
    public void initAbove(RenderData data) {
        // Nothing special needed
    }

    @Override
    public boolean render(Renderer r, RenderIterator it) {
        Room room = SETT.ROOMS().map.get(it.tx(), it.ty());
        if (room == null) {
            return false;
        }

        // Handle stockpiles
        if (room.blueprint() instanceof ROOM_STOCKPILE) {
            StockpileInstance instance = (StockpileInstance) room;

            // For stockpile details, we want to render once per stockpile, not once per tile
            // Use the first tile we encounter (top-left) for rendering
            int roomX1 = instance.body().x1();
            int roomY1 = instance.body().y1();

            // Only render on the first visible tile of the stockpile
            if (it.tx() != roomX1 || it.ty() != roomY1) {
                return false;
            }

            int zoomLevel = VIEW.s().getWindow().zoomout();
            StockpileData data = stockpileCache.get(instance.index());

            if (data == null || data.totalAmount == 0) {
                return false;
            }

            return renderStockpileDetails(r, it, instance, data, zoomLevel);
        }

        // Handle production/consumption rooms when chains enabled and filters active
        if (HorizontalResourcePanel.showResourceChains && hasActiveFilters() && room instanceof RoomInstance) {
            return renderProductionRoomIcon(r, it, (RoomInstance) room);
        }

        return false;
    }

    /**
     * Render icon for production/consumption rooms at center tile
     */
    private boolean renderProductionRoomIcon(Renderer r, RenderIterator it, RoomInstance instance) {
        // Only render on center tile
        if (it.tx() != instance.body().cX() || it.ty() != instance.body().cY()) {
            return false;
        }

        // Get filtered resources
        java.util.Set<RESOURCE> filteredResources = HorizontalResourcePanel.getEffectiveSelectedResources();
        if (filteredResources.isEmpty() && HorizontalResourcePanel.currentlyHoveredResource != null) {
            filteredResources = new java.util.HashSet<>();
            filteredResources.add(HorizontalResourcePanel.currentlyHoveredResource);
            filteredResources = HorizontalResourcePanel.getExpandedResourceChain(filteredResources);
        }

        if (filteredResources.isEmpty()) {
            return false;
        }

        // Check what this room does with filtered resources
        boolean produces = false;
        boolean consumes = false;

        for (RESOURCE res : filteredResources) {
            if (ResourceFlowAnalyzer.roomProducesResource(instance, res)) {
                produces = true;
            }
            if (ResourceFlowAnalyzer.roomConsumesResource(instance, res)) {
                consumes = true;
            }
        }

        if (!produces && !consumes) {
            return false;
        }

        int zoomLevel = VIEW.s().getWindow().zoomout();
        int iconSize = (zoomLevel >= 3) ? 128 : (zoomLevel == 2) ? 64 : (zoomLevel == 1) ? 48 : 32;

        int cx = it.x() + C.TILE_SIZE / 2;
        int cy = it.y() + C.TILE_SIZE / 2;
        int x = cx - iconSize / 2;
        int y = cy - iconSize / 2;

        // Render icon based on room type
        if (produces && consumes) {
            // Both: Yellow double arrow or exchange icon
            COLOR.YELLOW100.bind();
            UI.icons().m.arrow_right.renderScaled(r, x, y - iconSize / 4, iconSize / 16);
            UI.icons().m.arrow_left.renderScaled(r, x, y + iconSize / 4, iconSize / 16);
            COLOR.unbind();
        } else if (produces) {
            // Production: Green plus/arrow icon
            COLOR.GREEN100.bind();
            UI.icons().m.arrow_right.renderScaled(r, x, y, iconSize / 16);
            COLOR.unbind();
        } else {
            // Consumption: Red minus/arrow icon
            COLOR.RED100.bind();
            UI.icons().m.arrow_left.renderScaled(r, x, y, iconSize / 16);
            COLOR.unbind();
        }

        return true;
    }

    private boolean renderStockpileDetails(Renderer r, RenderIterator it, StockpileInstance instance, StockpileData data, int zoomLevel) {
        // Calculate the center position of the stockpile for rendering
        int centerTX = instance.body().cX();
        int centerTY = instance.body().cY();

        // Convert center tile coordinates to screen coordinates
        int centerX = (centerTX - it.tx()) * C.TILE_SIZE + it.x() + C.TILE_SIZE / 2;
        int centerY = (centerTY - it.ty()) * C.TILE_SIZE + it.y() + C.TILE_SIZE / 2;

        // Zoom 3 (very far) - MOST PRACTICAL VIEW: Color-coded fill level with stacked bars
        if (zoomLevel >= 3) {
            renderZoom3StorageBar(r, centerX, centerY, data);
            return true;
        }

        // Zoom 2 (far) - Show resources as stacked colored bars with icons
        if (zoomLevel == 2) {
            renderZoom2StackedView(r, centerX, centerY, data);
            return true;
        }

        // Zoom 1 (medium) - Show multiple resource icons with fill indicators
        if (zoomLevel == 1) {
            renderZoom1DetailedView(r, centerX, centerY, data);
            return true;
        }

        // Zoom 0 (close) - Full detail with icons, amounts, and bars
        renderZoom0FullDetail(r, centerX, centerY, data);
        return true;
    }

    @Override
    public void finishAbove() {
        // Nothing to clean up
    }

    /**
     * Zoom 3: MOST PRACTICAL - Large vertical bar showing fill level + top resource icon
     * Like a visible "pile" of resources - taller = more stuff
     * Zoom 3 = 8x divisor, so we need MUCH larger sizes
     */
    private void renderZoom3StorageBar(Renderer r, int cx, int cy, StockpileData data) {

        // HUGE vertical bar (compensate for 8x zoom divisor)
        int barWidth = 192;  // 24 * 8
        int maxBarHeight = 384;  // 48 * 8
        int barHeight = (int) (maxBarHeight * data.fillRatio());
        if (barHeight < 16) barHeight = 16;

        int barX = cx - barWidth / 2;
        int barY = cy + maxBarHeight / 2 - barHeight;

        // Draw bar with fill color
        COLOR fillColor = getColorForFillLevel(data.fillRatio());
        fillColor.bind();
        COLOR.WHITE100.render(r, barX, barX + barWidth, barY, barY + barHeight);
        COLOR.unbind();

        // Draw top resource icon on the bar (if any)
        if (!data.resources.isEmpty()) {
            ResourceAmount top = data.resources.get(0);
            if (top.resource != null && top.resource.icon() != null) {
                int iconSize = 160;  // 20 * 8
                int iconX = cx - iconSize / 2;
                int iconY = barY - iconSize - 16;
                COLOR.WHITE100.bind();
                top.resource.icon().render(r, iconX, iconX + iconSize, iconY, iconY + iconSize);
                COLOR.unbind();
            }
        }

        // Show amount text
        renderAmountText(r, data.totalAmount, cx, cy + maxBarHeight / 2 + 32, 3);
    }

    /**
     * Zoom 2: Stacked horizontal bars with resource icons showing composition
     * Zoom 2 = 4x divisor, so multiply sizes by 4
     */
    private void renderZoom2StackedView(Renderer r, int cx, int cy, StockpileData data) {

        int maxResources = 3;
        int resourcesToShow = Math.min(data.resources.size(), maxResources);
        if (resourcesToShow == 0) return;

        int iconSize = 128;  // 32 * 4
        int barWidth = 320;  // 80 * 4
        int barHeight = 32;  // 8 * 4
        int spacing = iconSize + barHeight + 16;
        int totalHeight = resourcesToShow * spacing - 16;
        int startY = cy - totalHeight / 2;

        for (int i = 0; i < resourcesToShow; i++) {
            ResourceAmount resAmt = data.resources.get(i);
            if (resAmt.resource == null || resAmt.resource.icon() == null) continue;

            int y = startY + i * spacing;

            // Icon
            int iconX = cx - barWidth / 2 - iconSize - 16;
            COLOR.WHITE100.bind();
            resAmt.resource.icon().render(r, iconX, iconX + iconSize, y, y + iconSize);
            COLOR.unbind();

            // Bar showing relative amount (as proportion of this stockpile)
            double proportion = Math.min(1.0, (double) resAmt.amount / Math.max(1, data.totalAmount));
            int fillWidth = (int) (barWidth * proportion);

            int barX = cx - barWidth / 2;
            int barY = y + iconSize / 2 - barHeight / 2;

            // Background
            COLOR.BLACK.render(r, barX, barX + barWidth, barY, barY + barHeight);
            // Fill - use overall stockpile fill level for color, not individual resource proportion
            COLOR fillColor = getColorForFillLevel(data.fillRatio());
            fillColor.render(r, barX, barX + fillWidth, barY, barY + barHeight);

            // Amount text
            renderAmountText(r, resAmt.amount, cx + barWidth / 2 + 80, barY + barHeight / 2 - 16, 2);
        }
    }

    /**
     * Zoom 1: Multiple large icons with fill indicators underneath
     * Zoom 1 = 2x divisor, so multiply sizes by 2
     */
    private void renderZoom1DetailedView(Renderer r, int cx, int cy, StockpileData data) {

        int maxResources = 4;
        int iconSize = 96;  // 48 * 2
        int resourcesToShow = Math.min(data.resources.size(), maxResources);
        if (resourcesToShow == 0) return;

        int spacing = iconSize + 16;
        int totalWidth = resourcesToShow * iconSize + (resourcesToShow - 1) * 16;
        int startX = cx - totalWidth / 2;

        for (int i = 0; i < resourcesToShow; i++) {
            ResourceAmount resAmt = data.resources.get(i);
            if (resAmt.resource == null || resAmt.resource.icon() == null) continue;

            int x = startX + i * spacing;

            // Icon
            COLOR.WHITE100.bind();
            resAmt.resource.icon().render(r, x, x + iconSize, cy - iconSize / 2 - 16, cy + iconSize / 2 - 16);
            COLOR.unbind();

            // Fill bar below icon
            int barHeight = 12;  // 6 * 2
            double ratio = Math.min(1.0, (double) resAmt.amount / Math.max(1, data.capacity / 4));
            int fillWidth = (int) (iconSize * Math.min(1.0, ratio));

            int barY = cy + iconSize / 2 - 12;
            COLOR.BLACK.render(r, x, x + iconSize, barY, barY + barHeight);
            COLOR fillColor = getColorForFillLevel(ratio);
            fillColor.render(r, x, x + fillWidth, barY, barY + barHeight);

            // Amount
            renderAmountText(r, resAmt.amount, x + iconSize / 2, barY + barHeight + 4, 1);
        }
    }

    /**
     * Zoom 0: Full detail with large icons, amounts, and comprehensive info
     */
    private void renderZoom0FullDetail(Renderer r, int cx, int cy, StockpileData data) {

        int maxResources = 5;
        int iconSize = 56;
        int resourcesToShow = Math.min(data.resources.size(), maxResources);
        if (resourcesToShow == 0) return;

        int spacing = iconSize + 16;
        int totalWidth = resourcesToShow * iconSize + (resourcesToShow - 1) * 16;
        int startX = cx - totalWidth / 2;

        // Overall fill bar at top
        int barWidth = totalWidth;
        int barHeight = 8;
        int barY = cy - iconSize / 2 - 20;
        COLOR.BLACK.render(r, startX, startX + barWidth, barY, barY + barHeight);
        int fillWidth = (int) (barWidth * data.fillRatio());
        COLOR fillColor = getColorForFillLevel(data.fillRatio());
        fillColor.render(r, startX, startX + fillWidth, barY, barY + barHeight);

        for (int i = 0; i < resourcesToShow; i++) {
            ResourceAmount resAmt = data.resources.get(i);
            if (resAmt.resource == null || resAmt.resource.icon() == null) continue;

            int x = startX + i * spacing;

            // Pulse for high amounts
            if (resAmt.amount >= 100) {
                double time = VIEW.renderSecond() * 1.5;
                double pulse = MATH.mod(time, 2);
                pulse = MATH.distanceC(pulse, 1, 2);
                ColorImp pulsedWhite = new ColorImp();
                pulsedWhite.set(COLOR.WHITE100);
                pulsedWhite.shadeSelf(0.8 + pulse * 0.2);
                pulsedWhite.bind();
            } else {
                COLOR.WHITE100.bind();
            }

            // Icon
            resAmt.resource.icon().render(r, x, x + iconSize, cy - iconSize / 2, cy + iconSize / 2);
            COLOR.unbind();

            // Amount below icon
            renderAmountText(r, resAmt.amount, x + iconSize / 2, cy + iconSize / 2 + 4, 0);
        }
    }

    /**
     * Render a horizontal fill bar
     */
    private void renderFillBar(Renderer r, int x, int y, double fillRatio) {
        // Background - use COLOR.render() which is the proper way to draw filled rects
        COLOR.BLACK.render(r, x, x + C.TILE_SIZE, y, y + 4);

        // Fill portion
        if (fillRatio > 0) {
            COLOR col = getColorForFillLevel(fillRatio);
            int fillWidth = (int) (C.TILE_SIZE * fillRatio);
            col.render(r, x, x + fillWidth, y, y + 4);
        }
    }

    /**
     * Get bright bar color based on fill level with smooth interpolation
     */
    private COLOR getColorForFillLevel(double fillRatio) {
        ColorImp barColor = new ColorImp();

        if (fillRatio < 0.01) {
            return COLOR_EMPTY_BAR;
        } else if (fillRatio < 0.25) {
            // Empty to Low (muted to bright blue)
            barColor.interpolate(COLOR_EMPTY_BAR, COLOR_LOW_BAR, fillRatio / 0.25);
        } else if (fillRatio < 0.50) {
            // Low to Med (bright blue to bright green)
            barColor.interpolate(COLOR_LOW_BAR, COLOR_MED_BAR, (fillRatio - 0.25) / 0.25);
        } else if (fillRatio < 0.85) {
            // Med to High (bright green to gold)
            barColor.interpolate(COLOR_MED_BAR, COLOR_HIGH_BAR, (fillRatio - 0.50) / 0.35);
        } else {
            // High to Full (gold to bright red)
            barColor.interpolate(COLOR_HIGH_BAR, COLOR_FULL_BAR, (fillRatio - 0.85) / 0.15);
        }

        return barColor;
    }

    /**
     * Render amount text with proper scaling per zoom level
     * Font rendering doesn't auto-scale, so we need to use larger fonts at higher zoom levels
     */
    private void renderAmountText(Renderer r, int amount, int x, int y, int zoomLevel) {
        if (amount <= 0) return;

        try {
            // Format the number with K/M suffixes
            String text;
            if (amount >= 1000000) {
                text = (amount / 1000000) + "M";
            } else if (amount >= 1000) {
                text = (amount / 1000) + "K";
            } else {
                text = String.valueOf(amount);
            }

            // Use LARGE font for zoomed out views - text doesn't scale like sprites!
            // Zoom 0-1: Medium font is fine
            // Zoom 2-3: Need HUGE font to compensate for zoom divisor
            snake2d.util.sprite.text.Font font;
            int shadowOffset;

            if (zoomLevel >= 3) {
                // Zoom 3 needs HUGE text (8x divisor)
                font = UI.FONT().H2;  // Largest font
                shadowOffset = 3;
            } else if (zoomLevel == 2) {
                // Zoom 2 needs BIG text (4x divisor)
                font = UI.FONT().H2;
                shadowOffset = 2;
            } else {
                // Zoom 0-1 can use normal font
                font = UI.FONT().M;
                shadowOffset = 1;
            }

            // Calculate text dimensions
            int textWidth = font.width(text);
            int textX = x - textWidth / 2;

            // Render thick shadow for readability (scaled by zoom)
            COLOR.BLACK.bind();
            for (int dx = -shadowOffset; dx <= shadowOffset; dx++) {
                for (int dy = -shadowOffset; dy <= shadowOffset; dy++) {
                    if (dx != 0 || dy != 0) {
                        font.render(r, text, textX + dx, y + dy);
                    }
                }
            }

            // Render main text (bright for contrast)
            COLOR.WHITE100.bind();
            font.render(r, text, textX, y);

            COLOR.unbind();
        } catch (Exception e) {
            // Fail silently to prevent crashes
        }
    }

    private COLOR getColorForRatio(double ratio, boolean isBar) {
        if (ratio <= 0.25) {
            ColorImp c = new ColorImp();
            c.interpolate(isBar ? COLOR_EMPTY_BAR : COLOR_EMPTY_BG, isBar ? COLOR_LOW_BAR : COLOR_LOW_BG, ratio / 0.25);
            return c;
        } else if (ratio <= 0.60) {
            ColorImp c = new ColorImp();
            c.interpolate(isBar ? COLOR_LOW_BAR : COLOR_LOW_BG, isBar ? COLOR_MED_BAR : COLOR_MED_BG, (ratio - 0.25) / 0.35);
            return c;
        } else if (ratio <= 0.90) {
            ColorImp c = new ColorImp();
            c.interpolate(isBar ? COLOR_MED_BAR : COLOR_MED_BG, isBar ? COLOR_HIGH_BAR : COLOR_HIGH_BG, (ratio - 0.60) / 0.30);
            return c;
        } else {
            ColorImp c = new ColorImp();
            c.interpolate(isBar ? COLOR_HIGH_BAR : COLOR_HIGH_BG, isBar ? COLOR_FULL_BAR : COLOR_FULL_BG, Math.min(1.0, (ratio - 0.90) / 0.10));
            return c;
        }
    }

    /**
     * Render simplified storage info for the full strategic minimap view
     */
    public void renderStrategicView(Renderer r, view.subview.GameWindow window, RECTANGLE absBounds) {
        if (!added()) return;

        int zoom = window.zoomout();

        // Render stockpile background colors
        for (int i = 0; i < SETT.ROOMS().STOCKPILE.instancesSize(); i++) {
            StockpileInstance instance = SETT.ROOMS().STOCKPILE.getInstance(i);
            if (instance == null) continue;

            StockpileData data = stockpileCache.get(instance.index());
            if (data == null || data.totalAmount <= 0) continue;

            COLOR col = getColorForRatio(data.fillRatio(), false);
            col.bind();

            // Draw a rectangle for the stockpile on the minimap
            int x1 = absBounds.x1() + ((instance.body().x1() * C.TILE_SIZE - window.pixels().x1()) >> zoom);
            int y1 = absBounds.y1() + ((instance.body().y1() * C.TILE_SIZE - window.pixels().y1()) >> zoom);
            int x2 = absBounds.x1() + ((instance.body().x2() * C.TILE_SIZE - window.pixels().x1()) >> zoom);
            int y2 = absBounds.y1() + ((instance.body().y2() * C.TILE_SIZE - window.pixels().y1()) >> zoom);

            if (x2 > x1 && y2 > y1) {
                col.render(r, x1, x2, y1, y2);
            }
        }
        COLOR.unbind();
    }

    /**
     * Rebuild the stockpile cache based on selected/hovered resources
     */
    private void rebuildCache() {
        stockpileCache.clear();
        loggedMissingStockpiles.clear();  // Reset logging for new cache
        loggedLargeStockpileChecks.clear();  // Reset large stockpile logging
        int stockpilesProcessed = 0;
        int stockpilesWithResources = 0;

        try {
            int totalStockpiles = SETT.ROOMS().STOCKPILE.instancesSize();

            for (int i = 0; i < totalStockpiles; i++) {
                StockpileInstance instance = SETT.ROOMS().STOCKPILE.getInstance(i);
                if (instance == null) {
                    continue;
                }

                stockpilesProcessed++;

                int totalAmount = 0;
                int capacity = 0;
                java.util.List<ResourceAmount> resourceList = new java.util.ArrayList<>();

                try {
                    // Calculate totals for filtered resources
                    for (RESOURCE res : RESOURCES.ALL()) {
                        // Filter by selected or hovered resources
                        if (!shouldDisplayResource(res)) {
                            continue;
                        }

                        try {
                            int amt = SETT.ROOMS().STOCKPILE.tally().amount.get(res, instance);
                            if (amt > 0) {
                                totalAmount += amt;
                                resourceList.add(new ResourceAmount(res, amt));
                            }
                        } catch (Exception resEx) {
                            // Skip this resource but continue with others
                            snake2d.LOG.err("StorageOverlay: Error reading resource " + res.name +
                                " from stockpile " + instance.index() + ": " + resEx.getMessage());
                        }
                    }

                    // Sort resources by amount (descending)
                    resourceList.sort((a, b) -> Integer.compare(b.amount, a.amount));

                    // Estimate capacity (each storage tile can hold ~100 of each resource)
                    // This is approximate - real capacity depends on room size
                    capacity = instance.area() * 50;  // Rough estimate

                    // ALWAYS add to cache, even if empty - this lets us distinguish between
                    // "not processed yet" (null) and "processed but empty" (totalAmount=0)
                    stockpileCache.put(instance.index(), new StockpileData(
                        totalAmount, capacity, resourceList
                    ));

                    if (totalAmount > 0) {
                        stockpilesWithResources++;
                    }

                } catch (Exception stockpileEx) {
                    snake2d.LOG.err("StorageOverlay: Error processing stockpile " + i +
                        " (index=" + instance.index() + "): " + stockpileEx.getMessage());
                    // Add empty entry to prevent repeated errors
                    stockpileCache.put(instance.index(), new StockpileData(0, 0, new java.util.ArrayList<>()));
                }
            }

            ModConfig.debug("StorageOverlay: Rebuilt cache - processed " + stockpilesProcessed +
                "/" + totalStockpiles + " stockpiles, " + stockpilesWithResources + " have filtered resources");

        } catch (Exception e) {
             snake2d.LOG.err("StorageOverlay rebuildCache critical error: " + e.getMessage());
             if (ModConfig.DEBUG_LOGGING) {
                snake2d.LOG.err("StorageOverlay rebuildCache exception: " + e);
             }
         }
    }

    /**
     * Get status text for UI display
     */
    public String getStatusText() {
        if (!added()) {
            return "Resource Storage Overlay: OFF";
        }

        RESOURCE hovered = getHoveredResource();
        int totalStored = stockpileCache.values().stream()
            .mapToInt(d -> d.totalAmount).sum();

        if (hovered == null) {
            return String.format("Storage: %d stockpiles, %d total items",
                stockpileCache.size(), totalStored);
        } else {
            return String.format("Storage (%s): %d items across %d stockpiles",
                hovered.name, totalStored, stockpileCache.size());
        }
    }

    /**
     * Cached data for a stockpile
     *
     * @param resources All resources, sorted by amount
     */
    private record StockpileData(int totalAmount, int capacity, List<ResourceAmount> resources) {

        double fillRatio() {
            return capacity > 0 ? (double) totalAmount / capacity : 0;
        }
    }

    /**
     * Resource with amount
     */
    private record ResourceAmount(RESOURCE resource, int amount) {
    }
}
