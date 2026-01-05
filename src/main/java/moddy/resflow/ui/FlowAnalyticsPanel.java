package moddy.resflow.ui;

import init.resources.RESOURCE;
import init.sprite.UI.UI;
import moddy.resflow.analysis.ResourceFlowAnalyzer;
import moddy.resflow.analysis.ResourceFlowData;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.ColorImp;
import snake2d.util.gui.GuiSection;
import util.colors.GCOLOR;
import util.data.GETTER;
import util.gui.misc.GBox;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.gui.table.GStaples;
import util.gui.table.GTableBuilder;
import util.info.GFORMAT;

/**
 * Analytics panel showing detailed flow statistics for selected/hovered resources.
 * Displays:
 * - Production/consumption rates and deltas
 * - Net flow and trend indicators
 * - Storage status and projections
 * - Haul statistics
 * - Flow path breakdowns
 */
public class FlowAnalyticsPanel extends GuiSection {

    private static final ColorImp COLOR_POSITIVE = new ColorImp(100, 255, 100);  // Green
    private static final ColorImp COLOR_NEGATIVE = new ColorImp(255, 100, 100);  // Red
    private static final ColorImp COLOR_MAINT = new ColorImp(255, 210, 80);      // Amber

    private final ResourceFlowAnalyzer analyzer;
    private final GuiSection tableSection;
    private RESOURCE currentResource = null;

    public FlowAnalyticsPanel(ResourceFlowAnalyzer analyzer, int width) {
        this.analyzer = analyzer;

        body().setWidth(width);
        body().setHeight(500); // Increased for graphs

        tableSection = new GuiSection();
        tableSection.body().setDim(width - 24, 300);

        rebuildTable();
        add(tableSection, 12, 40);

        // Add historical graphs section
        GuiSection graphs = new GuiSection();
        graphs.body().setDim(width - 24, 100);

        // Bar count must match available width; GStaples will early-out if bar width < 3.
        // Also, hover math divides by (body.width/amount) so amount must never be 0.
        final int graphW = Math.max(40, (width - 40) / 2);
        final int graphBarsMax = Math.max(10, ResourceFlowData.ResourceFlowStats.DEFAULT_GRAPH_SAMPLES);
        final int graphBars = Math.max(10, Math.min(graphBarsMax, graphW / 3));

        // Production history graph
        GStaples prodGraph = new GStaples(graphBars) {
            @Override
            public double getValue(int i) {
                if (currentResource == null) return 0;
                ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(currentResource);
                int hs = stats.getHistorySize();
                if (hs <= 0) return 0;

                int samples = Math.min(hs, graphBars);
                int offset = graphBars - samples;
                if (i < offset) return 0;

                int fromOldest = i - offset;
                return stats.productionAtOldestIndex(fromOldest);
            }

            @Override
            public void setColor(ColorImp c, int i, double v) {
                c.set(COLOR_POSITIVE);
            }

            @Override
            public void hover(GBox box, int i) {
                if (currentResource == null) return;
                box.text().add("Produced: ");
                GFORMAT.i(box.text(), (int) getValue(i));
            }
        };
        prodGraph.body().setDim(graphW, 80);
        graphs.add(prodGraph);

        // Consumption history graph
        GStaples consGraph = new GStaples(graphBars) {
            @Override
            public double getValue(int i) {
                if (currentResource == null) return 0;
                ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(currentResource);
                int hs = stats.getHistorySize();
                if (hs <= 0) return 0;

                int samples = Math.min(hs, graphBars);
                int offset = graphBars - samples;
                if (i < offset) return 0;

                int fromOldest = i - offset;
                return stats.consumptionAtOldestIndex(fromOldest);
            }

            @Override
            public void setColor(ColorImp c, int i, double v) {
                c.set(COLOR_NEGATIVE);
            }

            @Override
            public void hover(GBox box, int i) {
                if (currentResource == null) return;
                box.text().add("Consumed: ");
                GFORMAT.i(box.text(), (int) getValue(i));
            }
        };
        consGraph.body().setDim(graphW, 80);
        graphs.addRightC(8, consGraph);

        add(graphs, 12, 360);

        // Labels for graphs
        GStat graphLabels = new GStat() {
            @Override
            public void update(GText text) {
                text.clear();
                if (currentResource != null) {
                    ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(currentResource);
                    ResourceFlowData.HistoryWindow window = ResourceFlowData.computeHistoryWindow(stats);
                    text.add("History: ");
                    text.add(window.format());
                }
            }
        };
        add(graphLabels.r(), 12, 345);
    }

    private void rebuildTable() {
        tableSection.clear();

        GTableBuilder builder = new GTableBuilder() {
            @Override
            public int nrOFEntries() {
                return 10; // Rows: Prod, Cons, Maint, Net, Storage, Projection, Haulers, Trips, Distance, Session
            }
        };

        // Column 1: Label
        builder.column("Metric", 110, new GTableBuilder.GRowBuilder() {
            @Override
            public snake2d.util.gui.renderable.RENDEROBJ build(final GETTER<Integer> ier) {
                GStat s = new GStat() {
                    @Override
                    public void update(GText text) {
                        text.clear();
                        switch (ier.get()) {
                            case 0:
                                text.add("Production");
                                break;
                            case 1:
                                text.add("Consumption");
                                break;
                            case 2:
                                text.add("Maintenance");
                                break;
                            case 3:
                                text.add("Net Flow");
                                break;
                            case 4:
                                text.add("Storage");
                                break;
                            case 5:
                                text.add("Status");
                                break;
                            case 6:
                                text.add("Haulers");
                                break;
                            case 7:
                                text.add("Trips");
                                break;
                            case 8:
                                text.add("Distance");
                                break;
                            case 9:
                                text.add("Session");
                                break;
                        }
                    }
                };
                return s.r();
            }
        });

        // Column 2: Primary Value
        builder.column("Value", 120, new GTableBuilder.GRowBuilder() {
            @Override
            public snake2d.util.gui.renderable.RENDEROBJ build(final GETTER<Integer> ier) {
                GStat s = new GStat() {
                    @Override
                    public void update(GText text) {
                        text.clear();
                        if (currentResource == null) return;
                        ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(currentResource);
                        switch (ier.get()) {
                            case 0:
                                GFORMAT.i(text, stats.totalProduced);
                                break;
                            case 1:
                                GFORMAT.i(text, stats.totalConsumed);
                                break;
                            case 2:
                                // Maintenance is an estimate, not a lifetime counter.
                                text.add("-");
                                break;
                            case 3:
                                int net = stats.netFlowPerDay;
                                if (net > 0) text.add("+");
                                GFORMAT.i(text, net);
                                break;
                            case 4:
                                GFORMAT.i(text, stats.currentStored);
                                text.add("/");
                                GFORMAT.i(text, stats.storageCapacity);
                                break;
                            case 5:
                                // Only show projections if they're meaningful:
                                // 1. Net flow must be significant (> 20/day) to avoid noise
                                // 2. Projection must be reasonable (< 30 days) to be useful
                                break;
                            case 6:
                                GFORMAT.i(text, stats.activeHaulers);
                                break;
                            case 7:
                                GFORMAT.i(text, stats.totalHaulTrips);
                                break;
                            case 8:
                                GFORMAT.f1(text, stats.avgHaulDistance);
                                break;
                            case 9:
                                GFORMAT.i(text, stats.producedThisSession);
                                break;
                        }
                    }
                };
                return s.r();
            }
        });

        // Column 3: Rate / Additional Info
        builder.column("Rate/Detail", 110, new GTableBuilder.GRowBuilder() {
            @Override
            public snake2d.util.gui.renderable.RENDEROBJ build(final GETTER<Integer> ier) {
                GStat s = new GStat() {
                    @Override
                    public void update(GText text) {
                        text.clear();
                        if (currentResource == null) return;
                        ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(currentResource);

                        // Default text color
                        text.color(GCOLOR.T().NORMAL);

                        switch (ier.get()) {
                            case 0:
                                text.color(COLOR_POSITIVE);
                                text.add("+");
                                GFORMAT.i(text, stats.productionRatePerDay);
                                text.add("/d");
                                break;
                            case 1:
                                text.color(COLOR_NEGATIVE);
                                text.add("-");
                                GFORMAT.i(text, stats.consumptionRatePerDay);
                                text.add("/d");
                                break;
                            case 2:
                                // Maintenance is always a drain, but not necessarily "bad".
                                text.color(COLOR_MAINT);
                                text.add("-");
                                GFORMAT.i(text, stats.maintenanceConsumptionPerDay);
                                text.add("/d");
                                break;
                            case 3:
                                if (Math.abs(stats.velocityTrend) > 1) {
                                    text.add(stats.velocityTrend > 0 ? "▲ accel" : "▼ decel");
                                }
                                break;
                            case 4:
                                if (stats.storageCapacity > 0) {
                                    GFORMAT.i(text, (int) ((stats.currentStored * 100.0) / stats.storageCapacity));
                                    text.add("%");
                                }
                                break;
                            case 5:
                                // Status row: color should reflect the message.
                                double daysUntilChange;
                                if (stats.netFlowPerDay < -20) {
                                    text.color(COLOR_NEGATIVE);
                                    daysUntilChange = stats.getDaysUntilEmpty();
                                    if (daysUntilChange < 30) {
                                        text.add("Empty ");
                                        GFORMAT.f1(text, daysUntilChange);
                                        text.add("d");
                                    } else {
                                        text.add("Decreasing");
                                    }
                                } else if (stats.netFlowPerDay > 20 && stats.storageCapacity > 0) {
                                    text.color(COLOR_POSITIVE);
                                    daysUntilChange = stats.getDaysUntilFull();
                                    if (daysUntilChange < 30) {
                                        text.add("Full ");
                                        GFORMAT.f1(text, daysUntilChange);
                                        text.add("d");
                                    } else {
                                        text.add("Increasing");
                                    }
                                } else {
                                    text.add("Stable");
                                }
                                break;
                            case 6:
                                text.add("active");
                                break;
                            case 7:
                                text.add("total");
                                break;
                            case 8:
                                text.add("avg");
                                break;
                            case 9:
                                text.add("-");
                                GFORMAT.i(text, stats.consumedThisSession);
                                break;
                        }
                    }
                };
                return s.r();
            }
        });

        tableSection.add(builder.create(10, true));
    }

    /**
     * Update displayed data for a resource
     */
    public void updateResource(RESOURCE resource) {
        this.currentResource = resource;
    }

    /**
     * Clear all displayed data
     */
    private void clearDisplay() {
        this.currentResource = null;
    }

    /**
     * Refresh the display (e.g., on each frame)
     */
    public void refresh() {
        // Nothing to do here, GStat handles updates
    }

    @Override
    public void render(SPRITE_RENDERER r, float ds) {
        if (!visableIs()) {
            return;
        }

        // Render panel background
        GCOLOR.UI().panBG.render(r, body());
        GCOLOR.UI().borderH(r, body(), 0);

        // Render Title
        if (currentResource != null) {
            UI.FONT().M.render(r, currentResource.name, body().x1() + 12, body().y1() + 10);
        } else {
            UI.FONT().M.render(r, "No Resource Selected", body().x1() + 12, body().y1() + 10);
        }

        super.render(r, ds);
    }

    /**
     * Set which resource to display (called externally)
     */
    public void setResource(RESOURCE resource) {
        updateResource(resource);
    }

    /**
     * Get currently displayed resource
     */
    public RESOURCE getCurrentResource() {
        return currentResource;
    }
}
