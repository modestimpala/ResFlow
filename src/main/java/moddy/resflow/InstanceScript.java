package moddy.resflow;

import init.sprite.SPRITES;
import init.sprite.UI.UI;
import lombok.Getter;
import moddy.resflow.analysis.ResourceFlowAnalyzer;
import moddy.resflow.analysis.ResourceFlowData;
import moddy.resflow.overlay.ResourceFlowTracker;
import moddy.resflow.overlay.ResourceStorageOverlay;
import moddy.resflow.ui.GameUiApi;
import moddy.resflow.ui.HorizontalResourcePanel;
import moddy.resflow.ui.OverlayPanelInterrupter;
import moddy.resflow.ui.OverlayPanelManager;
import script.SCRIPT;
import settlement.main.SETT;
import snake2d.CORE;
import snake2d.LOG;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.misc.ACTION;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import view.keyboard.KEYS;
import view.keyboard.KeyButt;
import view.main.VIEW;
import view.sett.ui.minimap.UIMinimapPanelButts;
import view.sett.ui.minimap.UIMinimapSett;

import java.io.IOException;
import java.util.Optional;

/**
 * ResFlow Instance script.
 */
final class InstanceScript implements SCRIPT.SCRIPT_INSTANCE {

    // --- Overlays & analysis ---
    @Getter
    private final ResourceStorageOverlay storageOverlay = new ResourceStorageOverlay();

    @Getter
    private final ResourceFlowTracker flowTracker = new ResourceFlowTracker();

    private final ResourceFlowData flowData = new ResourceFlowData();
    private final ResourceFlowAnalyzer flowAnalyzer = new ResourceFlowAnalyzer(flowData);

    // --- UI wiring ---
    private OverlayPanelManager panelManager;
    @SuppressWarnings("FieldCanBeLocal")
    private OverlayPanelInterrupter panelInterrupter;

    // --- State ---
    private boolean storageOverlayEnabled;
    private boolean flowTrackerEnabled;

    // Status message display
    private String statusMessage = "";
    private double statusMessageTimer = 0.0;

    private boolean initialized;

    /**
     * Constructor - called by createInstance() for both new games and loaded games
     */
    public InstanceScript() {
        initializeMod();
    }

    private static double statusMessageDuration() {
        return ModConfig.STATUS_MESSAGE_DURATION;
    }

    private static void debug(String msg) {
        ModConfig.debug("ResFlow: " + msg);
    }

    // Init
    private void initializeMod() {
        if (initialized) return;
        initialized = true;

        wireAnalyzer();
        initOverlayPanel();
        injectMinimapButtons();
    }

    // 1
    private void wireAnalyzer() {
        flowTracker.setAnalyzer(flowAnalyzer);
        HorizontalResourcePanel.setAnalyzer(flowAnalyzer);
    }

    // 2
    private void initOverlayPanel() {
        panelManager = OverlayPanelManager.getInstance();
        panelManager.setAnalyzer(flowAnalyzer);
        panelManager.setFlowTracker(flowTracker);
        panelManager.setStorageOverlay(storageOverlay);

        panelInterrupter = new OverlayPanelInterrupter(panelManager, VIEW.inters().manager);
        debug("Initialized horizontal resource panel and analytics panel");
    }

    // 3
    private void injectMinimapButtons() {
        try {
            Optional<UIMinimapSett> minimapOpt = GameUiApi.getInstance()
                .findUIElementInSettlementView(UIMinimapSett.class);

            if (minimapOpt.isEmpty()) {
                LOG.err("ResFlow: Could not find UIMinimapSett to inject buttons");
                return;
            }

            UIMinimapPanelButts buttons = minimapOpt.get().panel();
            addMinimapButtons(buttons);
            debug("Successfully added mod buttons to minimap panel");

        } catch (Exception e) {
            LOG.err("ResFlow: Failed to inject minimap buttons: " + e.getMessage());
            if (ModConfig.DEBUG_LOGGING) {
                e.printStackTrace();
            }
        }
    }



    @Override
    public void save(FilePutter file) {
        flowData.save(file);
    }

    @Override
    public void load(FileGetter file) throws IOException {
        try {
            flowData.load(file);
            debug("Loaded resource flow data: " + flowData.getUpdateCount() + " updates");
        } catch (Exception e) {
            LOG.err("ResFlow: Failed to load flow data, starting fresh: " + e.getMessage());
            flowData.clear();
        }
    }

    private void addMinimapButtons(UIMinimapPanelButts buttons) {

        ACTION storageAction = () -> setStorageOverlayEnabled(!storageOverlayEnabled);
        GButt.ButtPanel storageButton = new UIMinimapSett.Butt(SPRITES.icons().s.storage) {
            @Override
            protected void clickA() {
                storageAction.exe();
            }

            @Override
            protected void renAction() {
                selectedSet(storageOverlayEnabled);
            }
        };
        buttons.padd(KeyButt.wrap(storageAction, storageButton, KEYS.SETT(), "TOGGLE_STORAGE_OVERLAY",
            "Storage Overlay", "Toggle Resource Storage Overlay"));

        ACTION flowAction = () -> {
            setFlowTrackerEnabled(!flowTrackerEnabled);
            debug("FlowTracker button clicked, enabled=" + flowTrackerEnabled);
        };
        GButt.ButtPanel flowButton = new UIMinimapSett.Butt(SPRITES.icons().s.drop) {
            @Override
            protected void clickA() {
                flowAction.exe();
            }

            @Override
            protected void renAction() {
                selectedSet(flowTrackerEnabled);
            }
        };
        buttons.padd(KeyButt.wrap(flowAction, flowButton, KEYS.SETT(), "TOGGLE_FLOW_TRACKER",
            "Flow Tracker", "Toggle Resource Flow Tracker"));

        adjustMinimapButtonSection(buttons);
    }

    // UI Fix
    private static void adjustMinimapButtonSection(UIMinimapPanelButts buttons) {
        try {
            java.lang.reflect.Field sectionField = buttons.getClass().getDeclaredField("section");
            sectionField.setAccessible(true);
            snake2d.util.gui.GuiSection section = (snake2d.util.gui.GuiSection) sectionField.get(buttons);
            section.body().setWidth(section.body().width() + 52);
            section.body().moveX1(section.body().x1() - 52);
        } catch (Exception e) {
            LOG.err("ResFlow: Failed to adjust minimap button section size: " + e.getMessage());
        }
    }

    private void setStorageOverlayEnabled(boolean enabled) {
        storageOverlayEnabled = enabled;
        showStatusMessage(storageOverlayEnabled ? "Storage Overlay Enabled" : "Storage Overlay Disabled");
    }

    private void setFlowTrackerEnabled(boolean enabled) {
        flowTrackerEnabled = enabled;
        showStatusMessage(flowTrackerEnabled ? "Flow Tracker Enabled" : "Flow Tracker Disabled");
    }

    private boolean isAnyOverlayActive() {
        return storageOverlayEnabled || flowTrackerEnabled;
    }

    @Override
    public void update(double ds) {
        // Add overlays every frame when enabled
        if (storageOverlayEnabled) {
            storageOverlay.add();
        }
        if (flowTrackerEnabled) {
            flowTracker.add();
        }

        // Update panel visibility based on overlay state
        if (panelManager != null) {
            panelManager.setOverlayActive(isAnyOverlayActive());
        }

        // Update both overlays to refresh their caches
        if (SETT.ENTITIES() != null) {
            storageOverlay.update(ds);
            flowTracker.update(ds);
        }

        // Update status message timer
        if (statusMessageTimer > 0) {
            statusMessageTimer -= ds;
        }
    }

    @Override
    public void hoverTimer(double mouseTimer, GBox text) {}

    @Override
    public void render(Renderer renderer, float ds) {
        // Render status message
        if (statusMessageTimer > 0) {
            renderStatusMessage(renderer, statusMessage);
        }
    }

    /**
     * Render status message overlay
     */
    private void renderStatusMessage(Renderer renderer, String message) {
        try {
            util.gui.misc.GText statusText = new util.gui.misc.GText(UI.FONT().H1, message);

            // Position
            int screenWidth = CORE.getGraphics().nativeWidth;
            int x = (screenWidth - statusText.width()) / 2;
            int y = 80; // Below top UI panel

            // Render with background for visibility
            int padding = 10;
            int bgX = x - padding;
            int bgY = y - padding;
            int bgWidth = statusText.width() + (padding * 2);
            int bgHeight = statusText.height() + (padding * 2);
            snake2d.util.color.COLOR bgColor = new snake2d.util.color.ColorImp(0, 0, 0);
            bgColor.render(renderer, bgX, bgX + bgWidth, bgY, bgY + bgHeight);
            statusText.render(renderer, x, y);

        } catch (Exception ignored) {
            // Fail silently
        }
    }

    /**
     * Show a status message temporarily
     */
    private void showStatusMessage(String message) {
        statusMessage = message;
        statusMessageTimer = statusMessageDuration();
    }

    @Override
    public void keyPush(KEYS key) {
        // Key binds are handled via KeyButt.wrap(...) bindings.
    }

    @Override
    public void mouseClick(MButt button) {  }

    @Override
    public void hover(COORDINATE mCoo, boolean mouseHasMoved) { }

    @Override
    public boolean handleBrokenSavedState() {
        return SCRIPT.SCRIPT_INSTANCE.super.handleBrokenSavedState();
    }
}
