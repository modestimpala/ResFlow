package moddy.resflow.ui;

import init.resources.RESOURCE;
import init.resources.RESOURCES;
import init.sprite.UI.UI;
import moddy.resflow.analysis.ResourceFlowAnalyzer;
import moddy.resflow.analysis.ResourceFlowData;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.ColorImp;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import util.colors.GCOLOR;
import util.data.GETTER;
import util.gui.misc.GHeader;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.gui.table.GTableBuilder;
import util.info.GFORMAT;

/**
 * Advanced analytics panel showing sophisticated insights:
 * - Buffer health (safety stock analysis)
 * - Supply chain stability metrics
 * - Efficiency trends over time
 * - Smart recommendations
 * - Workforce utilization
 */
public class AdvancedAnalyticsPanel extends GuiSection {

    private static final ColorImp COLOR_EXCELLENT = new ColorImp(50, 255, 50);   // Bright green
    private static final ColorImp COLOR_GOOD = new ColorImp(150, 255, 100);      // Light green
    private static final ColorImp COLOR_WARNING = new ColorImp(255, 200, 50);    // Orange
    private static final ColorImp COLOR_CRITICAL = new ColorImp(255, 50, 50);    // Red

    private final ResourceFlowAnalyzer analyzer;
    private RESOURCE currentResource = null;

    public AdvancedAnalyticsPanel(ResourceFlowAnalyzer analyzer, int width, int height) {
        this.analyzer = analyzer;

        body().setWidth(width);
        body().setHeight(height);

        // Title
        GHeader header = new GHeader("Advanced Resource Analytics");
        add(header);

        // Create table for resource stats
        GTableBuilder tb = new GTableBuilder() {
            @Override
            public int nrOFEntries() {
                return RESOURCES.ALL().size();
            }
        };

        // Column 1: Resource Name
        tb.column(null, 150, new GTableBuilder.GRowBuilder() {
            @Override
            public RENDEROBJ build(final GETTER<Integer> ier) {
                GStat s = new GStat() {
                    @Override
                    public void update(GText text) {
                        text.clear();
                        RESOURCE res = RESOURCES.ALL().get(ier.get());
                        text.add(res.name);
                    }
                };
                return s.hh(UI.icons().m.arrow_right);
            }
        });

        // Column 2: Buffer Health
        tb.column("Buffer", 130, new GTableBuilder.GRowBuilder() {
            @Override
            public RENDEROBJ build(final GETTER<Integer> ier) {
                GStat s = new GStat() {
                    @Override
                    public void update(GText text) {
                        text.clear();
                        RESOURCE res = RESOURCES.ALL().get(ier.get());
                        ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(res);

                        double score = stats.bufferHealthScore;
                        if (Double.isNaN(score) || Double.isInfinite(score))
                            score = 0;
                        if (score < 0)
                            score = 0;
                        if (score > 1)
                            score = 1;

                        if (score >= 0.8) {
                            text.color(COLOR_EXCELLENT);
                        } else if (score >= 0.5) {
                            text.color(COLOR_GOOD);
                        } else if (score >= 0.25) {
                            text.color(COLOR_WARNING);
                        } else {
                            text.color(COLOR_CRITICAL);
                        }

                        int bufferPercent = (int) (score * 100);
                        text.add(bufferPercent).add("%");

                        if (stats.optimalBufferSize > 0) {
                            text.s().add('(');
                            GFORMAT.i(text, stats.currentStored);
                            text.add('/');
                            GFORMAT.i(text, stats.optimalBufferSize);
                            text.add(')');
                        }
                    }
                };
                return s.r();
            }
        });

        // Column 3: Stability
        tb.column("Stability", 70, new GTableBuilder.GRowBuilder() {
            @Override
            public RENDEROBJ build(final GETTER<Integer> ier) {
                GStat s = new GStat() {
                    @Override
                    public void update(GText text) {
                        text.clear();
                        RESOURCE res = RESOURCES.ALL().get(ier.get());
                        ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(res);

                        double stab = stats.supplyChainStability;
                        if (Double.isNaN(stab) || Double.isInfinite(stab))
                            stab = 0;
                        if (stab < 0)
                            stab = 0;
                        if (stab > 1)
                            stab = 1;

                        if (stab >= 0.8) {
                            text.color(COLOR_EXCELLENT);
                        } else if (stab >= 0.6) {
                            text.color(COLOR_GOOD);
                        } else if (stab >= 0.4) {
                            text.color(COLOR_WARNING);
                        } else {
                            text.color(COLOR_CRITICAL);
                        }

                        int stabilityPercent = (int) (stab * 100);
                        text.add(stabilityPercent).add("%");
                    }
                };
                return s.r();
            }
        });

        // Column 4: Workforce
        tb.column("Workforce", 70, new GTableBuilder.GRowBuilder() {
            @Override
            public RENDEROBJ build(final GETTER<Integer> ier) {
                GStat s = new GStat() {
                    @Override
                    public void update(GText text) {
                        text.clear();
                        RESOURCE res = RESOURCES.ALL().get(ier.get());
                        ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(res);

                        int util = stats.workforceUtilization;
                        if (util >= 80) {
                            text.color(COLOR_EXCELLENT);
                        } else if (util >= 60) {
                            text.color(COLOR_GOOD);
                        } else if (util >= 40) {
                            text.color(COLOR_WARNING);
                        } else {
                            text.color(COLOR_CRITICAL);
                        }

                        text.add(util).add("%");
                    }
                };
                return s.r();
            }
        });

        // Column 5: Trend
        tb.column("Trend", 80, new GTableBuilder.GRowBuilder() {
            @Override
            public RENDEROBJ build(final GETTER<Integer> ier) {
                GStat s = new GStat() {
                    @Override
                    public void update(GText text) {
                        text.clear();
                        RESOURCE res = RESOURCES.ALL().get(ier.get());
                        ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(res);

                        double trend = stats.productionEfficiencyTrend;
                        if (Double.isNaN(trend) || Double.isInfinite(trend))
                            trend = 1.0;

                        if (trend > 1.1) {
                            text.color(COLOR_EXCELLENT);
                        } else if (trend > 0.95) {
                            text.color(COLOR_GOOD);
                        } else if (trend > 0.85) {
                            text.color(COLOR_WARNING);
                        } else {
                            text.color(COLOR_CRITICAL);
                        }

                        if (trend > 1.05) {
                            text.add("UP ");
                        } else if (trend < 0.95) {
                            text.add("DN ");
                        } else {
                            text.add("FL ");
                        }

                        int trendPercent = (int) ((trend - 1.0) * 100);
                        if (trendPercent > 0) {
                            text.add("+");
                        }
                        text.add(trendPercent).add("%");
                    }
                };
                return s.r();
            }
        });

        // Column 6: Recommendation
        tb.column("Recommendation", 200, new GTableBuilder.GRowBuilder() {
            @Override
            public RENDEROBJ build(final GETTER<Integer> ier) {
                GStat s = new GStat() {
                    @Override
                    public void update(GText text) {
                        text.clear();
                        RESOURCE res = RESOURCES.ALL().get(ier.get());
                        ResourceFlowData.ResourceFlowStats stats = analyzer.getData().getStats(res);

                        // Smart recommendations based on multiple factors
                        if (stats.productionSites == 0 && stats.consumptionSites == 0) {
                            text.add("Inactive");
                        } else if (stats.bufferHealthScore < 0.25 && stats.consumptionRatePerDay > 0) {
                            text.add("! Low buffer");
                        } else if (stats.supplyChainStability < 0.4) {
                            text.add("! Unstable supply");
                        } else if (stats.workforceUtilization < 40 && stats.productionSites > 0) {
                            text.add("! Need workers");
                        } else if (stats.netFlowPerDay < -50 && stats.getDaysUntilEmpty() < 5) {
                            text.add("! Running out");
                        } else if (stats.storageCapacity > 0 && stats.getDaysUntilFull() < 2) {
                            text.add("! Storage full soon");
                        } else if (stats.avgDeliveryTime > 60 && stats.totalHaulTrips > 10) {
                            text.add("! Long haul times");
                        } else if (stats.productionEfficiencyTrend > 1.15) {
                            text.add("OK Improving");
                        } else {
                            text.add("OK Healthy");
                        }
                    }
                };
                return s.hh(UI.icons().m.arrow_right);
            }
        });

        // Build table
        GuiSection table = tb.createHeight(height - header.body().height() - 80, false);
        addDownC(5, table);
    }

    @Override
    public void render(SPRITE_RENDERER r, float ds) {
        if (!visableIs())
            return;

        // Standard panel container styling used throughout the base game UI
        GCOLOR.UI().panBG.render(r, body());
        super.render(r, ds);
        GCOLOR.UI().borderH(r, body(), 0);
    }

    /**
     * Show detailed info for a resource
     */
    public void showDetails(RESOURCE resource) {
        this.currentResource = resource;
    }

    public RESOURCE getCurrentResource() {
        return currentResource;
    }
}
