package moddy.resflow;


import lombok.NoArgsConstructor;
import script.SCRIPT;
import util.info.INFO;

/**
 * Entry point
 */
@NoArgsConstructor
@SuppressWarnings("unused") // used by the game via reflection
public final class MainScript implements SCRIPT {

    private final INFO info = new INFO("ResFlow",
        "Adds toggleable visual overlays for resource flow tracking and resource storage amounts.");


    @Override
    public CharSequence name() {
        return info.name;
    }

    @Override
    public CharSequence desc() {
        return info.desc;
    }

    /**
     * Called before an actual game is started or loaded
     */
    @Override
    public void initBeforeGameCreated() {
        ModConfig.load();
    }

    /**
     * @return whether mod shall be selectable when starting a new game
     */
    @Override
    public boolean isSelectable() {
        return SCRIPT.super.isSelectable();
    }

    /**
     * @return whether mod shall be loaded into existing saves or not
     */
    @Override
    public boolean forceInit() {
        return true;
    }

    /**
     * This actually creates the "instance" of your script.
     */
    @Override
    public SCRIPT_INSTANCE createInstance() {
        return new InstanceScript();
    }
}
