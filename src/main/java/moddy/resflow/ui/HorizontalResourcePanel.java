package moddy.resflow.ui;

import init.resources.RESOURCE;
import init.resources.RESOURCES;
import init.sprite.UI.UI;
import lombok.Getter;
import lombok.Setter;
import moddy.resflow.analysis.ResourceFlowAnalyzer;
import settlement.main.SETT;
import settlement.room.main.RoomInstance;
import snake2d.MButt;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.color.OPACITY;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sprite.text.Font;
import util.colors.GCOLOR;
import util.gui.misc.GBox;
import util.gui.misc.GButt.Checkbox;
import view.main.VIEW;

import java.util.concurrent.ConcurrentHashMap;

import static moddy.resflow.ModConfig.debug;

/**
 * Horizontal resource panel for overlay control.
 * Shows currently hovered resource for overlay filtering.
 * Filtering by clicking to lock/unlock resources.
 */
public class HorizontalResourcePanel extends GuiSection {

    // Using ConcurrentHashMap.newKeySet() for thread-safe iteration
    public static final java.util.Set<RESOURCE> selectedResources = ConcurrentHashMap.newKeySet();
    private static final CharSequence DESC = "Left-click to lock/unlock resource filter. Right-click to go to warehouse.";
    // Using volatile for thread-safe read access across render/update threads
    public static volatile RESOURCE currentlyHoveredResource = null;
    // Visualization toggles
    public static boolean showRoomColors = true;
    public static boolean showFlowPaths = true;
    public static boolean showActiveHaulers = false;  // Start disabled (too cluttered, still need to improve paths)
    public static boolean showResourceChains = true;  // Show full production chains, wood to bakery to bread when grain selected etc
    public static boolean showTrafficHeatmap = false;
    public static boolean showEfficiencyMode = false;
    public static boolean showBottlenecks = true;
    /**
     * -- SETTER --
     * Set the analyzer reference for resource chain queries
     */
    // Reference to the analyzer for resource chain queries
    @Setter
    private static ResourceFlowAnalyzer analyzer = null;
    private final GuiSection scrollableContent;
    private final java.util.Map<RENDEROBJ, RESOURCE> buttonToResource = new java.util.HashMap<>();
    @Getter
    private boolean isActive = false;
    private RESOURCE lastLoggedResource = null; // For debug logging
    private boolean isExpanded = false;
    @Getter
    private GuiSection expandedPanel = null;

    public HorizontalResourcePanel(int y1) {
        GuiSection mainSection = new GuiSection();

        // Create expand/collapse button
        util.gui.misc.GButt.ButtPanel expandButton = new util.gui.misc.GButt.ButtPanel("▼");
        expandButton.clickActionSet(() -> {
            isExpanded = !isExpanded;
            if (expandedPanel != null) {
                expandedPanel.visableSet(isExpanded);
            }
            if (isExpanded) {
                OverlayPanelManager manager = OverlayPanelManager.getInstance();
                if (manager != null) {
                    manager.markExpandedPanelOpened();
                }
            }
            debug("Expanded panel: " + isExpanded);
        });
        expandButton.hoverInfoSet("Toggle resource filter list");
        expandButton.body().setDim(24, 32);
        expandButton.pad(4, 4);
        mainSection.add(expandButton);
        GuiSection panel = new GuiSection();
        boolean firstButton = true;
        for (RESOURCE res : RESOURCES.ALL()) {
            // Add category separator before new categories (except first)
            if (!firstButton && hasNext(res)) {
                RESOURCE prev = getPrevious(res);
                if (prev != null && res.category != prev.category) {
                    RENDEROBJ separator = createSeparator();
                    panel.addRightC(4, separator);
                }
            }

            RENDEROBJ button = createResourceButton(res);
            buttonToResource.put(button, res); // Map button to resource

            if (firstButton) {
                panel.add(button);
                firstButton = false;
            } else {
                panel.addRightC(2, button);
            }
        }

        // Add some padding around the panel
        panel.pad(4, 4);

        mainSection.addRightC(4, panel);

        scrollableContent = panel;
        add(mainSection);

        // Create an expanded filter panel (initially hidden)
        createExpandedPanel(y1);
        body().moveY1(y1);
        visableSet(false);
    }

    /**
     * Get all resources needed for the full production chain of the given resources
     * Delegates to the analyzer for chain expansion
     */
    public static java.util.Set<RESOURCE> getExpandedResourceChain(java.util.Set<RESOURCE> baseResources) {
        if (!showResourceChains || baseResources == null || baseResources.isEmpty()) {
            return baseResources;
        }

        // Delegate to analyzer if available
        if (analyzer != null) {
            return analyzer.getExpandedResourceChain(baseResources);
        }

        // Fallback if analyzer not set
        return baseResources;
    }

    /**
     * Get the effective resource set considering chain expansion
     * This is what should be used for filtering
     */
    public static java.util.Set<RESOURCE> getEffectiveSelectedResources() {
        return getExpandedResourceChain(selectedResources);
    }

    private void createExpandedPanel(int y1) {
        expandedPanel = new GuiSection() {
            @Override
            public void render(SPRITE_RENDERER r, float ds) {
                if (visableIs()) {
                    // Render background
                    GCOLOR.UI().panBG.render(r, body());
                    GCOLOR.UI().borderH(r, body(), 0);
                    super.render(r, ds);
                }
            }
        };

        // Create two columns for toggles
        GuiSection col1 = new GuiSection();
        GuiSection col2 = new GuiSection();
        Font font = UI.FONT().S;

        // Room Colors toggle
        Checkbox roomColorsToggle = new Checkbox(font.getText("Room Colors")) {
            @Override
            protected void clickA() {
                showRoomColors = !showRoomColors;
                selectedToggle();
            }

            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
                selectedSet(showRoomColors);
                super.render(r, ds, isActive, isSelected, isHovered);
            }
        };
        roomColorsToggle.hoverInfoSet("Toggle room color overlays (Green=production, Red=consumption, Blue=storage)");

        // Flow Paths toggle
        Checkbox flowPathsToggle = new Checkbox(font.getText("Flow Paths")) {
            @Override
            protected void clickA() {
                showFlowPaths = !showFlowPaths;
                selectedToggle();
            }

            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
                selectedSet(showFlowPaths);
                super.render(r, ds, isActive, isSelected, isHovered);
            }
        };
        flowPathsToggle.hoverInfoSet("Toggle resource flow path visualization");

        // Active Haulers toggle
        Checkbox haulersToggle = new Checkbox(font.getText("Active Haulers")) {
            @Override
            protected void clickA() {
                showActiveHaulers = !showActiveHaulers;
                selectedToggle();
            }

            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
                selectedSet(showActiveHaulers);
                super.render(r, ds, isActive, isSelected, isHovered);
            }
        };
        haulersToggle.hoverInfoSet("Toggle active hauler visualization");

        // Resource Chains toggle
        Checkbox chainsToggle = new Checkbox(font.getText("Resource Chains")) {
            @Override
            protected void clickA() {
                showResourceChains = !showResourceChains;
                selectedToggle();
            }

            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
                selectedSet(showResourceChains);
                super.render(r, ds, isActive, isSelected, isHovered);
            }
        };
        chainsToggle.hoverInfoSet("Toggle automatic resource chain expansion");

        // Analytics Panel toggle
        Checkbox analyticsToggle = new Checkbox(font.getText("Analytics Panel")) {
            @Override
            protected void clickA() {
                OverlayPanelManager manager = OverlayPanelManager.getInstance();
                if (manager != null) {
                    manager.toggleAnalytics();
                    selectedToggle();
                }
            }

            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
                OverlayPanelManager manager = OverlayPanelManager.getInstance();
                selectedSet(manager != null && manager.isAnalyticsVisible());
                super.render(r, ds, isActive, isSelected, isHovered);
            }
        };
        analyticsToggle.hoverInfoSet("Toggle detailed flow analytics panel");

        // Advanced Analytics Panel toggle
        Checkbox advancedAnalyticsToggle = new Checkbox(font.getText("Advanced Analytics")) {
            @Override
            protected void clickA() {
                OverlayPanelManager manager = OverlayPanelManager.getInstance();
                if (manager != null) {
                    manager.toggleAdvancedAnalytics();
                    selectedToggle();
                }
            }

            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
                OverlayPanelManager manager = OverlayPanelManager.getInstance();
                selectedSet(manager != null && manager.isAdvancedAnalyticsVisible());
                super.render(r, ds, isActive, isSelected, isHovered);
            }
        };
        advancedAnalyticsToggle.hoverInfoSet("Toggle advanced analytics with buffer health, stability metrics, and smart recommendations");

        // Config panel toggle
        Checkbox configToggle = new Checkbox(font.getText("Config Panel")) {
            @Override
            protected void clickA() {
                OverlayPanelManager manager = OverlayPanelManager.getInstance();
                if (manager != null) {
                    manager.toggleModConfig();
                    selectedToggle();
                }
            }

            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
                OverlayPanelManager manager = OverlayPanelManager.getInstance();
                selectedSet(manager != null && manager.isModConfigVisible());
                super.render(r, ds, isActive, isSelected, isHovered);
            }
        };
        configToggle.hoverInfoSet("Toggle mod configuration panel with sliders");

        // Heatmap toggle
        Checkbox heatmapToggle = new Checkbox(font.getText("Logistics Heatmap")) {
            @Override
            protected void clickA() {
                showTrafficHeatmap = !showTrafficHeatmap;
                selectedToggle();
            }

            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
                selectedSet(showTrafficHeatmap);
                super.render(r, ds, isActive, isSelected, isHovered);
            }
        };
        heatmapToggle.hoverInfoSet("Visualizes resource transport traffic concentration");

        // Bottlenecks toggle
        Checkbox bottlenecksToggle = new Checkbox(font.getText("Show Bottlenecks")) {
            @Override
            protected void clickA() {
                showBottlenecks = !showBottlenecks;
                selectedToggle();
            }

            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
                selectedSet(showBottlenecks);
                super.render(r, ds, isActive, isSelected, isHovered);
            }
        };
        bottlenecksToggle.hoverInfoSet("Highlights rooms stalled by various factors");

        // Efficiency toggle
        Checkbox efficiencyToggle = new Checkbox(font.getText("Efficiency Colors")) {
            @Override
            protected void clickA() {
                showEfficiencyMode = !showEfficiencyMode;
                selectedToggle();
            }

            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
                selectedSet(showEfficiencyMode);
                super.render(r, ds, isActive, isSelected, isHovered);
            }
        };
        efficiencyToggle.hoverInfoSet("Colors rooms by production efficiency");

        // Layout in columns
        col1.add(roomColorsToggle);
        col1.addDownC(4, haulersToggle);
        col1.addDownC(4, analyticsToggle);
        col1.addDownC(4, advancedAnalyticsToggle);
        col1.addDownC(4, configToggle);
        col1.addDownC(4, bottlenecksToggle);

        col2.add(flowPathsToggle);
        col2.addDownC(4, chainsToggle);
        col2.addDownC(4, heatmapToggle);
        col2.addDownC(4, efficiencyToggle);

        GuiSection toggles = new GuiSection();
        toggles.add(col1);
        toggles.addRightC(12, col2);

        expandedPanel.add(toggles);

        //  Select All / Clear All
        GuiSection controlButtons = new GuiSection();
        util.gui.misc.GButt.ButtPanel selectAllButton = new util.gui.misc.GButt.ButtPanel(font.getText("Select All Res"));
        selectAllButton.clickActionSet(() -> {
            selectedResources.clear();
            for (RESOURCE res : RESOURCES.ALL()) {
                selectedResources.add(res);
            }
        });

        util.gui.misc.GButt.ButtPanel clearAllButton = new util.gui.misc.GButt.ButtPanel(font.getText("Clear All Res"));
        clearAllButton.clickActionSet(selectedResources::clear);

        controlButtons.add(selectAllButton);
        controlButtons.addRightC(4, clearAllButton);

        expandedPanel.addDownC(12, controlButtons);
        expandedPanel.pad(10, 10);

        expandedPanel.body().moveY1(y1 + 42);
        expandedPanel.body().moveX1(10);

        expandedPanel.visableSet(false);
    }

    /**
     * Keep internal expanded state in sync when other UI layers (like right-click close)
     * change the expanded panel visibility.
     */
    public void forceExpanded(boolean expanded) {
        this.isExpanded = expanded;
        if (expandedPanel != null) {
            expandedPanel.visableSet(expanded);
        }
    }

    private RENDEROBJ createResourceButton(RESOURCE res) {
        GuiSection button = new GuiSection() {
            int warehouseIndex = 0;

            @Override
            public void hoverInfoGet(GUI_BOX text) {
                res.hoverDetailed(text);
                GBox b = (GBox) text;
                b.sep();
                b.text(DESC);
                if (selectedResources.contains(res)) {
                    b.sep();
                    b.textL("LOCKED");
                }
                super.hoverInfoGet(text);
            }

            @Override
            public void render(SPRITE_RENDERER r, float ds) {
                // Calculate fill level
                double amount = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
                double capacity = SETT.ROOMS().STOCKPILE.tally().space.total(res);
                double fillRatio = 0;
                if (capacity > 0) {
                    fillRatio = amount / capacity;
                }

                // Render background meter
                if (fillRatio > 0.9) {
                    util.gui.misc.GMeter.render(r, util.gui.misc.GMeter.C_REDPURPLE, fillRatio, body());
                } else if (capacity > 0) {
                    util.gui.misc.GMeter.render(r, util.gui.misc.GMeter.C_REDGREEN, fillRatio, body());
                } else {
                    util.gui.misc.GMeter.render(r, util.gui.misc.GMeter.C_INACTIVE, fillRatio, body());
                }

                // Import threshold indicator
                if (SETT.ROOMS().IMPORT.tally.capacity.get(res) > 0) {
                    double threshold = SETT.ROOMS().IMPORT.tally.importWhenBelow.getD(res);
                    if (threshold > 0) {
                        int y1 = (int) (body().y1() + threshold * (body().height() - 2));
                        COLOR.WHITE85.render(r, body().x1(), body().x2(), y1, y1 + 1);
                    }
                }

                // Track hover state
                if (hoveredIs()) {
                    currentlyHoveredResource = res;
                }

                // Highlight if selected/locked
                boolean isSelected = selectedResources.contains(res);

                // Darken if not hovered and not selected
                if (!hoveredIs() && !isSelected) {
                    OPACITY.O25.bind();
                    COLOR.BLACK.render(r, body(), -1);
                    OPACITY.unbind();
                }

                // Draw selection border for locked resources
                if (isSelected) {
                    COLOR.YELLOW100.render(r, body().x1(), body().x2(), body().y1(), body().y1() + 2);
                    COLOR.YELLOW100.render(r, body().x1(), body().x2(), body().y2() - 2, body().y2());
                    COLOR.YELLOW100.render(r, body().x1(), body().x1() + 2, body().y1(), body().y2());
                    COLOR.YELLOW100.render(r, body().x2() - 2, body().x2(), body().y1(), body().y2());
                }

                // Handle right-click to go to warehouse
                if (hoveredIs() && MButt.RIGHT.consumeClick()) {
                    for (int i = 0; i < SETT.ROOMS().STOCKPILE.instancesSize(); i++) {
                        warehouseIndex++;
                        if (warehouseIndex >= SETT.ROOMS().STOCKPILE.instancesSize()) {
                            warehouseIndex = 0;
                        }

                        RoomInstance instance = SETT.ROOMS().STOCKPILE.getInstance(warehouseIndex, res);
                        if (instance != null) {
                            VIEW.s().getWindow().centererTile.set(instance.body().cX(), instance.body().cY());
                            break;
                        }
                    }
                }

                super.render(r, ds);
            }

            @Override
            public boolean click() {
                // Left-click toggles selection
                if (selectedResources.contains(res)) {
                    selectedResources.remove(res);
                    debug("Unlocked resource: " + res.name);
                } else {
                    selectedResources.add(res);
                    debug("Locked resource: " + res.name);
                }
                return true;
            }
        };

        // Add icon
        button.add(res.icon().small, 0, 0);
        button.pad(4, 4);

        return button;
    }

    private RENDEROBJ createSeparator() {
        return new RENDEROBJ.RenderImp(2, 32) {
            @Override
            public void render(SPRITE_RENDERER r, float ds) {
                GCOLOR.UI().borderH(r, body().x1(), body().x2(), body().y1(), body().y2());
            }
        };
    }

    private boolean hasNext(RESOURCE res) {
        int index = findResourceIndex(res);
        return index >= 0 && index < RESOURCES.ALL().size() - 1;
    }

    private int findResourceIndex(RESOURCE res) {
        for (int i = 0; i < RESOURCES.ALL().size(); i++) {
            if (RESOURCES.ALL().get(i) == res) {
                return i;
            }
        }
        return -1;
    }

    private RESOURCE getPrevious(RESOURCE res) {
        int index = findResourceIndex(res);
        if (index > 0) {
            return RESOURCES.ALL().get(index - 1);
        }
        return null;
    }

    public void setActive(boolean active) {
        this.isActive = active;
        visableSet(active);
    }

    @Override
    public boolean hover(snake2d.util.datatypes.COORDINATE mCoo) {
        if (!visableIs()) {
            currentlyHoveredResource = null;
            return false;
        }

        // Call parent hover to propagate to children
        boolean hovered = super.hover(mCoo);

        // Check if any resource button is hovered
        if (hovered && scrollableContent != null) {
            // Get the hovered element from the scrollableContent
            RENDEROBJ hoveredElement = scrollableContent.getHovered();
            if (hoveredElement != null && buttonToResource.containsKey(hoveredElement)) {
                // This is one of our resource buttons - set the current hovered resource
                currentlyHoveredResource = buttonToResource.get(hoveredElement);

                // Debug logging when resource changes
                if (currentlyHoveredResource != lastLoggedResource) {
                    debug("HorizontalPanel: Hovering " + (currentlyHoveredResource != null ? currentlyHoveredResource.name : "null"));
                    lastLoggedResource = currentlyHoveredResource;
                }
            } else {
                // Might be hovering a separator or other element
                currentlyHoveredResource = null;
                if (lastLoggedResource != null) {
                    debug("HorizontalPanel: Hovering null (separator or padding)");
                    lastLoggedResource = null;
                }
            }
        } else {
            currentlyHoveredResource = null;
            if (lastLoggedResource != null) {
                debug("HorizontalPanel: No hover");
                lastLoggedResource = null;
            }
        }

        return hovered;
    }

    @Override
    public void render(SPRITE_RENDERER r, float ds) {
        if (visableIs()) {
            // Render background panel
            GCOLOR.UI().panBG.render(r, body());
            GCOLOR.UI().borderH(r, body(), 0);
            super.render(r, ds);
        }
    }
}
