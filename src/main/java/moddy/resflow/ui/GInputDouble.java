package moddy.resflow.ui;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.gui.GuiSection;
import snake2d.util.misc.CLAMP;
import snake2d.util.sprite.text.StringInputSprite;
import util.data.DOUBLE;
import util.gui.misc.GInput;

/**
 * Simple decimal input field that binds to a {@link DOUBLE.DOUBLE_MUTABLE}.
 */
public class GInputDouble extends GuiSection {

    private final DOUBLE.DOUBLE_MUTABLE in;
    private final double min;
    private final double max;
    private final int decimals;

    private final StringInputSprite sp;

    /**
     * When true, the user is currently editing. While editing we don't auto-format the contents,
     * otherwise clamping/formatting fights the user's keystrokes (e.g. can’t backspace past min).
     */
    private boolean editing = false;

    private final GInput input;

    public GInputDouble(DOUBLE.DOUBLE_MUTABLE in, double min, double max) {
        this(in, min, max, 3);
    }

    public GInputDouble(DOUBLE.DOUBLE_MUTABLE in, double min, double max, int decimals) {
        this.in = in;
        this.min = min;
        this.max = max;
        this.decimals = Math.max(0, decimals);

        this.sp = new StringInputSprite(12, UI.FONT().S) {
            @Override
            protected void change() {
                // Mark as editing and apply if parseable.
                editing = true;
                parseAndApply();
            }
        }.placeHolder("0");

        // Ensure visual matches value from the start.
        syncFromModel();

        input = new GInput(sp);
        add(input);
    }

    @Override
    public void render(SPRITE_RENDERER r, float ds) {
        // If the field isn't focused, we can safely normalize the display.
        // While focused, let the user edit freely.
        if (snake2d.Mouse.currentClicked != input) {
            if (editing) {
                // User finished editing; normalize to the stored value.
                editing = false;
            }
            syncFromModel();
        }
        super.render(r, ds);
    }

    private void parseAndApply() {
        CharSequence txt = sp.text();
        if (txt == null)
            return;

        String s = txt.toString().trim();
        if (s.isEmpty() || s.equals("-") || s.equals(".") || s.equals("-.") || s.equals("+"))
            return;

        try {
            double v = Double.parseDouble(s);
            // Only apply if within bounds; if outside, clamp but do not rewrite the text immediately.
            v = CLAMP.d(v, min, max);
            in.setD(v);
        } catch (Exception ignored) {
            // While editing, ignore bad intermediate states (like just "-")
        }
    }

    private void syncFromModel() {
        double v = CLAMP.d(in.getD(), min, max);
        String formatted;
        if (decimals == 0) {
            formatted = String.valueOf((int) Math.round(v));
        } else {
            String fmt = "%." + decimals + "f";
            formatted = String.format(java.util.Locale.ROOT, fmt, v);
            // Trim trailing zeros so it feels like config values.
            formatted = formatted.replaceAll("\\.?0+$", "");
            if (formatted.isEmpty())
                formatted = "0";
        }

        if (!sp.text().toString().equals(formatted)) {
            sp.text().clear().add(formatted);
        }
    }
}
