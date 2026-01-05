package moddy.resflow.ui;

import init.constant.C;
import init.resources.RESOURCE;
import lombok.Getter;
import lombok.Setter;
import moddy.resflow.analysis.ResourceFlowAnalyzer;
import moddy.resflow.overlay.ResourceFlowTracker;
import moddy.resflow.overlay.ResourceStorageOverlay;
import snake2d.Renderer;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import view.main.VIEW;

/**
 * Manages the horizontal resource panel for overlay control.
 * Shows/hides panel based on overlay active state.
 * Handles positioning for both normal and fullscreen minimap views.
 * Also manages the analytics panel for detailed flow statistics.
 */
public class OverlayPanelManager {

    private static OverlayPanelManager instance;

    private final HorizontalResourcePanel resourcePanel;
    private final GuiSection container;
    /**
     * -- GETTER --
     * Get the mod config panel
     */
    // Mod configuration panel
    @Getter
    private final ModConfigPanel modConfigPanel;
    private boolean overlayActive = false;
    /**
     * -- GETTER --
     * Get the analytics panel
     */
    // Analytics panel for detailed flow statistics
    @Getter
    private FlowAnalyticsPanel analyticsPanel;

    /**
     * -- GETTER --
     * Get the advanced analytics panel
     */
    // Advanced analytics panel with sophisticated metrics
    @Getter
    private AdvancedAnalyticsPanel advancedAnalyticsPanel;

    /**
     * -- SETTER --
     * Set analytics panel visibility
     * -- GETTER --
     * Check if analytics panel is visible
     */
    @Getter
    @Setter
    private boolean analyticsVisible = false;

    /**
     * -- SETTER --
     * Set advanced analytics panel visibility
     * -- GETTER --
     * Check if advanced analytics panel is visible
     */
    @Getter
    @Setter
    private boolean advancedAnalyticsVisible = false;
    private ResourceFlowAnalyzer analyzer;
    /**
     * -- SETTER --
     * Set mod config panel visibility
     * -- GETTER --
     * Check if mod config panel is visible
     */
    @Getter
    @Setter
    private boolean modConfigVisible = false;

    @Setter
    private ResourceFlowTracker flowTracker;
    @Setter
    private ResourceStorageOverlay storageOverlay;

    private OverlayPanelManager() {
        // Create panel at 1/3 from bottom of screen
        int panelY = (C.HEIGHT() * 2) / 3; // 2/3 down = 1/3 from bottom
        this.resourcePanel = new HorizontalResourcePanel(panelY);

        // Wrap in container
        this.container = new GuiSection();
        this.container.add(resourcePanel);

        // Center
        int screenCenterX = C.WIDTH() / 2;
        this.container.body().moveCX(screenCenterX);

        // Create mod config panel (positioned on left side)
        this.modConfigPanel = new ModConfigPanel();
        this.modConfigPanel.body().moveX1(20);
        this.modConfigPanel.body().moveY1(100);
        this.modConfigPanel.visableSet(false); // Start hidden
    }

    public static OverlayPanelManager getInstance() {
        if (instance == null) {
            instance = new OverlayPanelManager();
        }
        return instance;
    }

    public boolean isOverlayActive() {
        return overlayActive;
    }

    /**
     * Update panel visibility based on overlay state
     */
    public void setOverlayActive(boolean active) {
        this.overlayActive = active;
        resourcePanel.setActive(active);
    }

    /**
     * Render the panel and expanded filter panel
     */
    public void render(Renderer r, float ds) {
        if (!overlayActive) {
            return;
        }

        // Render strategic overlays if minimap is fullscreen
        // Doing this here (inside Interrupter) ensures they are behind other UI
        renderStrategicOverlays(r);

        // Position panel based on current view mode
        updatePanelPosition();

        // Render main panel
        container.render(r, ds);

        // Render expanded filter panel if it exists
        GuiSection expandedPanel = resourcePanel.getExpandedPanel();
        if (expandedPanel != null && expandedPanel.visableIs()) {
            expandedPanel.render(r, ds);
        }

        // Render analytics panel if toggled on
        if (analyticsPanel != null && analyticsVisible) {
            // Update resource being displayed
            RESOURCE hovered = getHoveredResource();
            if (hovered != null || !HorizontalResourcePanel.selectedResources.isEmpty()) {
                // Show analytics for the hovered resource or first selected resource
                RESOURCE displayResource = hovered;
                if (displayResource == null) {
                    displayResource = HorizontalResourcePanel.selectedResources.iterator().next();
                }
                analyticsPanel.setResource(displayResource);
                analyticsPanel.visableSet(true);
            } else {
                analyticsPanel.visableSet(false);
            }

            analyticsPanel.render(r, ds);
        } else if (analyticsPanel != null) {
            // Hide analytics when toggled off
            analyticsPanel.visableSet(false);
        }

        // Render the advanced analytics panel if toggled on
        if (advancedAnalyticsPanel != null && advancedAnalyticsVisible) {
            advancedAnalyticsPanel.visableSet(true);

            // Keep on screen
            int margin = 10;
            int x1 = advancedAnalyticsPanel.body().x1();
            int y1 = advancedAnalyticsPanel.body().y1();

            if (advancedAnalyticsPanel.body().x2() > C.WIDTH() - margin) {
                x1 = C.WIDTH() - margin - advancedAnalyticsPanel.body().width();
            }
            if (x1 < margin) {
                x1 = margin;
            }

            if (advancedAnalyticsPanel.body().y2() > C.HEIGHT() - margin) {
                y1 = C.HEIGHT() - margin - advancedAnalyticsPanel.body().height();
            }
            if (y1 < margin) {
                y1 = margin;
            }

            advancedAnalyticsPanel.body().moveX1(x1);
            advancedAnalyticsPanel.body().moveY1(y1);

            advancedAnalyticsPanel.render(r, ds);
        } else if (advancedAnalyticsPanel != null) {
            advancedAnalyticsPanel.visableSet(false);
        }

        // Render mod config panel if toggled on
        if (modConfigPanel != null && modConfigVisible) {
            modConfigPanel.visableSet(true);
            modConfigPanel.render(r, ds);
        } else if (modConfigPanel != null) {
            modConfigPanel.visableSet(false);
        }
    }

    /**
     * Render strategic view overlays if minimap is fullscreen.
     * Rendering this inside the interrupter's render loop ensures it stays behind other UI.
     */
    private void renderStrategicOverlays(Renderer r) {
        GameUiApi uiApi = GameUiApi.getInstance();
        if (uiApi.isMinimapFullscreen()) {
            uiApi.getStrategicWindow().ifPresent(window -> {
                // Use full screen bounds
                snake2d.util.datatypes.RECTANGLE absBounds = init.constant.C.DIM();
                if (flowTracker != null && flowTracker.added()) {
                    flowTracker.renderStrategicView(r, window, absBounds);
                }
                if (storageOverlay != null && storageOverlay.added()) {
                    storageOverlay.renderStrategicView(r, window, absBounds);
                }
            });
        }
    }

    /**
     * Handle hover events for both main panel and expanded panel
     */
    public boolean hover(COORDINATE mCoo) {
        if (!overlayActive) {
            return false;
        }

        // Check mod config panel first (topmost)
        if (modConfigPanel != null && modConfigVisible && modConfigPanel.visableIs()) {
            if (modConfigPanel.hover(mCoo)) {
                return true;
            }
        }

        // Advanced analytics panel (when visible) should get first shot at input over the table.
        if (advancedAnalyticsPanel != null && advancedAnalyticsVisible && advancedAnalyticsPanel.visableIs()) {
            if (advancedAnalyticsPanel.hover(mCoo)) {
                return true;
            }
        }

        // Check analytics panel
        if (analyticsPanel != null && analyticsVisible && analyticsPanel.visableIs()) {
            if (analyticsPanel.hover(mCoo)) {
                return true;
            }
        }

        // Check expanded panel (it's rendered on top of main panel)
        GuiSection expandedPanel = resourcePanel.getExpandedPanel();
        if (expandedPanel != null && expandedPanel.visableIs()) {
            if (expandedPanel.hover(mCoo)) {
                return true;
            }
        }

        return container.hover(mCoo);
    }

    /**
     * Handle click events for both main panel and expanded panel
     */
    public boolean click() {
        if (!overlayActive) {
            return false;
        }

        // Mod config panel first (topmost)
        if (modConfigPanel != null && modConfigVisible && modConfigPanel.visableIs()) {
            if (modConfigPanel.click()) {
                return true;
            }
        }

        // Advanced analytics panel
        if (advancedAnalyticsPanel != null && advancedAnalyticsVisible && advancedAnalyticsPanel.visableIs()) {
            if (advancedAnalyticsPanel.click()) {
                return true;
            }
        }

        // Analytics panel
        if (analyticsPanel != null && analyticsVisible && analyticsPanel.visableIs()) {
            if (analyticsPanel.click()) {
                return true;
            }
        }
        GuiSection expandedPanel = resourcePanel.getExpandedPanel();
        if (expandedPanel != null && expandedPanel.visableIs()) {
            if (expandedPanel.click()) {
                return true;
            }
        }

        return container.click();
    }

    /**
     * Update panel position based on view mode
     */
    private void updatePanelPosition() {
        // Check if minimap is fullscreen
        boolean isMinimapFullscreen = isMinimapFullscreen();

        if (isMinimapFullscreen) {
            int minimapY = C.HEIGHT() / 2;
            container.body().moveY1(minimapY);
        } else {
            int normalY = (C.HEIGHT() * 2) / 3;
            container.body().moveY1(normalY);
        }

        int screenCenterX = C.WIDTH() / 2;
        container.body().moveCX(screenCenterX);
    }

    /**
     * Check if minimap is in fullscreen mode
     */
    private boolean isMinimapFullscreen() {
        try {
            // Check if SettView is active and its minimap is open
            return VIEW.s() != null && VIEW.s().mini.openIs();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the currently hovered resource
     */
    public init.resources.RESOURCE getHoveredResource() {
        return HorizontalResourcePanel.currentlyHoveredResource;
    }

    /**
     * Position panel at custom coordinates
     */
    public void setPosition(int x, int y) {
        container.body().moveX1(x);
        container.body().moveY1(y);
    }

    public HorizontalResourcePanel getPanel() {
        return resourcePanel;
    }

    /**
     * Set the flow analyzer and create the analytics panel
     */
    public void setAnalyzer(ResourceFlowAnalyzer analyzer) {
        this.analyzer = analyzer;
        if (analyzer != null && analyticsPanel == null) {
            int panelWidth = 400;
            analyticsPanel = new FlowAnalyticsPanel(analyzer, panelWidth);
            analyticsPanel.body().moveX1(C.WIDTH() - panelWidth * 2);
            analyticsPanel.body().moveY1(100); // Below top UI

            int advPanelWidth = 550;
            int advPanelHeight = 500;
            advancedAnalyticsPanel = new AdvancedAnalyticsPanel(analyzer, advPanelWidth, advPanelHeight);
            advancedAnalyticsPanel.body().moveX1((C.WIDTH() - advPanelWidth) / 2); // Center horizontally
            advancedAnalyticsPanel.body().moveY1(100); // Below top UI
        }
    }

    /**
     * Toggle analytics panel visibility
     */
    public void toggleAnalytics() {
        analyticsVisible = !analyticsVisible;
        // If showing analytics, hide advanced analytics
        if (analyticsVisible && advancedAnalyticsVisible) {
            advancedAnalyticsVisible = false;
        }
    }

    /**
     * Toggle advanced analytics panel visibility
     */
    public void toggleAdvancedAnalytics() {
        advancedAnalyticsVisible = !advancedAnalyticsVisible;
        if (advancedAnalyticsVisible) {
            lastOpenedPanel = LastPanel.ADVANCED_ANALYTICS;
        }
        // If showing advanced analytics, hide basic analytics
        if (advancedAnalyticsVisible && analyticsVisible) {
            analyticsVisible = false;
        }
    }

    /**
     * Toggle mod config panel visibility
     */
    public void toggleModConfig() {
        modConfigVisible = !modConfigVisible;
        if (modConfigVisible) {
            lastOpenedPanel = LastPanel.MOD_CONFIG;
        }
    }

    private enum LastPanel {
        EXPANDED,
        MOD_CONFIG,
        ADVANCED_ANALYTICS
    }

    private LastPanel lastOpenedPanel = null;

    /**
     * Close the most recently opened auxiliary panel.
     * @return true if a panel was closed
     */
    public boolean closeLastOpenedPanel() {
        // Prefer the last-opened, but fall back to "topmost visible" if state got out of sync.
        if (lastOpenedPanel != null) {
            switch (lastOpenedPanel) {
                case MOD_CONFIG:
                    if (modConfigVisible) {
                        modConfigVisible = false;
                        return true;
                    }
                    break;
                case ADVANCED_ANALYTICS:
                    if (advancedAnalyticsVisible) {
                        advancedAnalyticsVisible = false;
                        return true;
                    }
                    break;
                case EXPANDED:
                    GuiSection expanded = resourcePanel.getExpandedPanel();
                    if (expanded != null && expanded.visableIs()) {
                        expanded.visableSet(false);
                        resourcePanel.forceExpanded(false);
                        return true;
                    }
                    break;
            }
        }

        // Fallback: close in topmost-first order.
        if (modConfigVisible) {
            modConfigVisible = false;
            return true;
        }
        if (advancedAnalyticsVisible) {
            advancedAnalyticsVisible = false;
            return true;
        }
        GuiSection expanded = resourcePanel.getExpandedPanel();
        if (expanded != null && expanded.visableIs()) {
            expanded.visableSet(false);
            resourcePanel.forceExpanded(false);
            return true;
        }

        return false;
    }

    public void markExpandedPanelOpened() {
        lastOpenedPanel = LastPanel.EXPANDED;
    }

    public void resetFlowData() {
        if (analyzer == null) {
            return;
        }
        analyzer.getData().clear();
        analyzer.resetCaches();
    }
}
