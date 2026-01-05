package moddy.resflow.ui;

import snake2d.MButt;
import snake2d.Renderer;
import snake2d.util.datatypes.COORDINATE;
import util.gui.misc.GBox;
import view.interrupter.InterManager;
import view.interrupter.Interrupter;

/**
 * Interrupter that handles rendering and input for the horizontal resource panel.
 */
public class OverlayPanelInterrupter extends Interrupter {

    private final OverlayPanelManager panelManager;

    public OverlayPanelInterrupter(OverlayPanelManager panelManager, InterManager manager) {
        this.panelManager = panelManager;

        // Configure as persistent overlay and set to render at the end of the interrupter stack
        // (Behind other UI elements in the game's front-to-back rendering order)
        desturberSet().persistantSet().lastSet();

        // Show immediately
        show(manager);
    }

    @Override
    protected boolean render(Renderer r, float ds) {
        // Render panel if overlay is active
        if (panelManager.isOverlayActive()) {
            panelManager.render(r, ds);
        }
        return true; // Return true to continue rendering other interrupters
    }

    @Override
    protected void mouseClick(MButt button) {
        if (!panelManager.isOverlayActive())
            return;

        if (button == MButt.RIGHT) {
            // Base-game UX: right click closes the last opened UI layer (if any), not buttons.
            if (panelManager.closeLastOpenedPanel()) {
                return;
            }
        }

        if (button == MButt.LEFT) {
            panelManager.click();
        }
    }

    @Override
    protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
        if (panelManager.isOverlayActive()) {
            /*  Important: many base-game scroll widgets (tables, scroll rows, etc.)
                only react to wheelspin when they are hovered during a render()-tick.
                Calling hover() here makes sre the hovered state is updated for the correct panel. */

            return panelManager.hover(mCoo);
        }
        return false;
    }

    @Override
    protected void hoverTimer(GBox text) {
        // Hover tooltips are handled by individual resource buttons
    }

    @Override
    protected boolean update(float ds) {
        // Always update, but panel decides if it renders
        return true;
    }
}
