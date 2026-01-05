package moddy.resflow.ui;

import init.sprite.UI.UI;
import moddy.resflow.ModConfig;
import snake2d.SPRITE_RENDERER;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import util.colors.GCOLOR;
import util.data.DOUBLE;
import util.data.INT.INTE;
import util.gui.misc.GButt;
import util.gui.misc.GText;
import util.gui.slider.GSliderInt;
import util.gui.table.GScrollRows;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration panel for mod settings.
 */
public class ModConfigPanel extends GuiSection {

    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 600;
    private static final int SLIDER_WIDTH = 240;

    public ModConfigPanel() {
        // Create scrollable content
        List<RENDEROBJ> rows = new ArrayList<>();

        // ==================== TITLE ====================
        rows.add(createTextRow("ResFlow Configuration", UI.FONT().M));
        rows.add(createSpacer(10));

        // ==================== PARTICLE SYSTEM ====================
        rows.add(createTextRow("--- Particle System ---", UI.FONT().S));
        rows.add(createSpacer(5));

        // Particles Enabled
        GButt.Checkbox particlesEnabled = new GButt.Checkbox("Particles Enabled") {
            @Override
            public void clickA() {
                ModConfig.FLOW_PARTICLE_ENABLED = !ModConfig.FLOW_PARTICLE_ENABLED;
                selectedToggle();
            }

            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
                selectedSet(ModConfig.FLOW_PARTICLE_ENABLED);
                super.render(r, ds, isActive, isSelected, isHovered);
            }
        };
        particlesEnabled.selectedSet(ModConfig.FLOW_PARTICLE_ENABLED);
        rows.add(particlesEnabled);

        // Particle Base Speed (0.1 - 50.0, stored as 1-500)
        rows.add(createSlider("Particle Base Speed", new INTE() {
            public int min() {
                return 1;
            }

            public int max() {
                return 500;
            }

            public int get() {
                return (int) (ModConfig.FLOW_PARTICLE_BASE_SPEED * 10);
            }

            public void set(int t) {
                ModConfig.FLOW_PARTICLE_BASE_SPEED = t / 10.0;
            }
        }));

        // Particle Max Speed (0.1 - 100.0, stored as 1-1000)
        rows.add(createSlider("Particle Max Speed", new INTE() {
            public int min() {
                return 1;
            }

            public int max() {
                return 1000;
            }

            public int get() {
                return (int) (ModConfig.FLOW_PARTICLE_MAX_SPEED * 10);
            }

            public void set(int t) {
                ModConfig.FLOW_PARTICLE_MAX_SPEED = t / 10.0;
            }
        }));

        // Particle Base Size (0.5 - 10.0, stored as 5-100)
        rows.add(createSlider("Particle Base Size", new INTE() {
            public int min() {
                return 5;
            }

            public int max() {
                return 100;
            }

            public int get() {
                return (int) (ModConfig.FLOW_PARTICLE_BASE_SIZE * 10);
            }

            public void set(int t) {
                ModConfig.FLOW_PARTICLE_BASE_SIZE = (float) (t / 10.0);
            }
        }));

        // Particle Max Size (1.0 - 20.0, stored as 10-200)
        rows.add(createSlider("Particle Max Size", new INTE() {
            public int min() {
                return 10;
            }

            public int max() {
                return 200;
            }

            public int get() {
                return (int) (ModConfig.FLOW_PARTICLE_MAX_SIZE * 10);
            }

            public void set(int t) {
                ModConfig.FLOW_PARTICLE_MAX_SIZE = (float) (t / 10.0);
            }
        }));

        // Glow Effect
        GButt.Checkbox glowEnabled = new GButt.Checkbox("Glow Effect") {
            @Override
            public void clickA() {
                ModConfig.FLOW_PARTICLE_USE_GLOW = !ModConfig.FLOW_PARTICLE_USE_GLOW;
                selectedToggle();
            }

            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
                selectedSet(ModConfig.FLOW_PARTICLE_USE_GLOW);
                super.render(r, ds, isActive, isSelected, isHovered);
            }
        };
        glowEnabled.selectedSet(ModConfig.FLOW_PARTICLE_USE_GLOW);
        rows.add(glowEnabled);
        rows.add(createSpacer(10));

        // ==================== FLOW TRACKER ====================
        rows.add(createTextRow("--- Flow Tracker ---", UI.FONT().S));
        rows.add(createSpacer(5));

        rows.add(createDoubleInput("Cache Update Interval (s)", new DOUBLE.DOUBLE_MUTABLE() {
            @Override
            public double getD() {
                return ModConfig.FLOW_TRACKER_CACHE_UPDATE_INTERVAL;
            }

            @Override
            public DOUBLE.DOUBLE_MUTABLE setD(double d) {
                ModConfig.FLOW_TRACKER_CACHE_UPDATE_INTERVAL = d;
                return this;
            }
        }, 0.1, 60.0));

        rows.add(createDoubleInput("Transport Update Interval (s)", new DOUBLE.DOUBLE_MUTABLE() {
            @Override
            public double getD() {
                return ModConfig.FLOW_TRACKER_TRANSPORT_UPDATE_INTERVAL;
            }

            @Override
            public DOUBLE.DOUBLE_MUTABLE setD(double d) {
                ModConfig.FLOW_TRACKER_TRANSPORT_UPDATE_INTERVAL = d;
                return this;
            }
        }, 0.1, 10.0));

        // Particle Pulse Speed (0.1 - 10.0, stored as 1-100)
        rows.add(createSlider("Pulse Speed", new INTE() {
            public int min() {
                return 1;
            }

            public int max() {
                return 100;
            }

            public int get() {
                return (int) (ModConfig.FLOW_TRACKER_PULSE_SPEED * 10);
            }

            public void set(int t) {
                ModConfig.FLOW_TRACKER_PULSE_SPEED = t / 10.0;
            }
        }));

        // Arrow Animation Speed (0.1 - 10.0, stored as 1-100)
        rows.add(createSlider("Arrow Anim Speed", new INTE() {
            public int min() {
                return 1;
            }

            public int max() {
                return 100;
            }

            public int get() {
                return (int) (ModConfig.FLOW_TRACKER_ARROW_ANIM_SPEED * 10);
            }

            public void set(int t) {
                ModConfig.FLOW_TRACKER_ARROW_ANIM_SPEED = t / 10.0;
            }
        }));

        rows.add(createSlider("Flow Icon Interval", new INTE() {
            public int min() {
                return 1;
            }

            public int max() {
                return 10;
            }

            public int get() {
                return ModConfig.FLOW_ICON_INTERVAL;
            }

            public void set(int t) {
                ModConfig.FLOW_ICON_INTERVAL = t;
            }
        }));
        rows.add(createSpacer(10));

        // ==================== ANALYZER & DATA ====================
        rows.add(createTextRow("--- Analyzer & Data ---", UI.FONT().S));
        rows.add(createSpacer(5));

        rows.add(createDoubleInput("Analyzer Update Interval (s)", new DOUBLE.DOUBLE_MUTABLE() {
            @Override
            public double getD() {
                return ModConfig.FLOW_ANALYZER_UPDATE_INTERVAL;
            }

            @Override
            public DOUBLE.DOUBLE_MUTABLE setD(double d) {
                ModConfig.FLOW_ANALYZER_UPDATE_INTERVAL = d;
                return this;
            }
        }, 1.0, 300.0));

        rows.add(createDoubleInput("Snapshot Interval (s)", new DOUBLE.DOUBLE_MUTABLE() {
            @Override
            public double getD() {
                return ModConfig.FLOW_DATA_SNAPSHOT_INTERVAL;
            }

            @Override
            public DOUBLE.DOUBLE_MUTABLE setD(double d) {
                ModConfig.FLOW_DATA_SNAPSHOT_INTERVAL = d;
                return this;
            }
        }, 10.0, 3600.0));

        rows.add(createSpacer(10));

        // ==================== STORAGE OVERLAY ====================
        rows.add(createTextRow("--- Storage Overlay ---", UI.FONT().S));
        rows.add(createSpacer(5));

        rows.add(createDoubleInput("Storage Cache Update (s)", new DOUBLE.DOUBLE_MUTABLE() {
            @Override
            public double getD() {
                return ModConfig.STORAGE_OVERLAY_CACHE_UPDATE_INTERVAL;
            }

            @Override
            public DOUBLE.DOUBLE_MUTABLE setD(double d) {
                ModConfig.STORAGE_OVERLAY_CACHE_UPDATE_INTERVAL = d;
                return this;
            }
        }, 0.1, 10.0));
        rows.add(createSpacer(10));

        // ==================== UI SETTINGS ====================
        rows.add(createTextRow("--- UI Settings ---", UI.FONT().S));
        rows.add(createSpacer(5));

        rows.add(createDoubleInput("Status Message Duration (s)", new DOUBLE.DOUBLE_MUTABLE() {
            @Override
            public double getD() {
                return ModConfig.STATUS_MESSAGE_DURATION;
            }

            @Override
            public DOUBLE.DOUBLE_MUTABLE setD(double d) {
                ModConfig.STATUS_MESSAGE_DURATION = d;
                return this;
            }
        }, 0.5, 20.0));
        rows.add(createSpacer(10));

        // ==================== SAVE/RESET BUTTONS ====================
        // Save to file button
        GButt.ButtPanel saveButton = new GButt.ButtPanel("Save to File") {
            @Override
            protected void clickA() {
                ModConfig.save();
            }
        };
        saveButton.hoverInfoSet("Save current settings to ResFlow.txt config file");
        rows.add(saveButton);

        // Reset button
        GButt.ButtPanel resetButton = new GButt.ButtPanel("Reset All to Defaults") {
            @Override
            protected void clickA() {
                resetAllToDefaults();
            }
        };
        resetButton.hoverInfoSet("Reset all mod settings to default values");
        rows.add(resetButton);

        // Reset flow/analytics data button
        GButt.ButtPanel resetFlowButton = new GButt.ButtPanel("Reset Flow Data") {
            @Override
            protected void clickA() {
                OverlayPanelManager.getInstance().resetFlowData();
            }
        };
        resetFlowButton.hoverInfoSet("Clears all tracked flow stats/history and restarts analytics from zero");
        rows.add(resetFlowButton);

        rows.add(createSpacer(10));

        // Create scrolable rows
        RENDEROBJ[] rowArray = rows.toArray(new RENDEROBJ[0]);
        GScrollRows scrollRows = new GScrollRows(rowArray, PANEL_HEIGHT - 20, PANEL_WIDTH - 20);

        // Add scroll view
        add(scrollRows.view(), 10, 10);

        // Set dimensions
        body().setDim(PANEL_WIDTH, PANEL_HEIGHT);
    }

    /**
     * Helper method to create a text row (wrapped in GuiSection)
     */
    private GuiSection createTextRow(String text, snake2d.util.sprite.text.Font font) {
        GuiSection section = new GuiSection();
        GText label = new GText(font, text);
        section.add(label, 0, 0);
        section.body().setDim(SLIDER_WIDTH, 16);
        return section;
    }

    /**
     * Helper method to create a slider with label
     */
    private GuiSection createSlider(String label, INTE inte) {
        GuiSection section = new GuiSection();

        GText labelText = new GText(UI.FONT().S, label);
        section.add(labelText, 0, 0);

        GSliderInt slider = new GSliderInt(inte, SLIDER_WIDTH, false);
        section.add(slider, 0, 16);

        section.body().setDim(SLIDER_WIDTH, 36);
        return section;
    }

    /**
     * Helper method to create a double input with label.
     */
    private GuiSection createDoubleInput(String label, DOUBLE.DOUBLE_MUTABLE mutable, double min, double max) {
        GuiSection section = new GuiSection();

        GText labelText = new GText(UI.FONT().S, label);
        section.add(labelText, 0, 0);

        GInputDouble input = new GInputDouble(mutable, min, max);
        section.add(input, 0, 16);

        section.body().setDim(SLIDER_WIDTH, 36);
        return section;
    }

    /**
     * Create a vertical spacer
     */
    private GuiSection createSpacer(int height) {
        GuiSection spacer = new GuiSection();
        spacer.body().setDim(PANEL_WIDTH - 40, height);
        return spacer;
    }

    /**
     * Reset all settings to default values
     */
    private void resetAllToDefaults() {
        // Particle system
        ModConfig.FLOW_PARTICLE_ENABLED = true;
        ModConfig.FLOW_PARTICLE_BASE_SPEED = 0.5;
        ModConfig.FLOW_PARTICLE_MAX_SPEED = 2.0;
        ModConfig.FLOW_PARTICLE_BASE_SIZE = 1.0f;
        ModConfig.FLOW_PARTICLE_MAX_SIZE = 3.0f;
        ModConfig.FLOW_PARTICLE_USE_GLOW = true;

        // Flow tracker
        ModConfig.FLOW_TRACKER_CACHE_UPDATE_INTERVAL = 2.0;
        ModConfig.FLOW_TRACKER_TRANSPORT_UPDATE_INTERVAL = 0.5;
        ModConfig.FLOW_TRACKER_PULSE_SPEED = 1.5;
        ModConfig.FLOW_TRACKER_ARROW_ANIM_SPEED = 2.0;
        ModConfig.FLOW_ICON_INTERVAL = 3;

        // Analyzer & Data
        ModConfig.FLOW_ANALYZER_UPDATE_INTERVAL = 5.0;
        ModConfig.FLOW_DATA_SNAPSHOT_INTERVAL = 60.0;

        // Storage overlay
        ModConfig.STORAGE_OVERLAY_CACHE_UPDATE_INTERVAL = 1.0;

        // UI settings
        ModConfig.STATUS_MESSAGE_DURATION = 3.0;

        snake2d.LOG.ln("ResFlow: All settings reset to defaults");
    }

    @Override
    public void render(SPRITE_RENDERER r, float ds) {
        // Draw background panel
        GCOLOR.UI().bg().render(r, body());
        GCOLOR.UI().border().render(r, body(), 2);

        super.render(r, ds);
    }
}
