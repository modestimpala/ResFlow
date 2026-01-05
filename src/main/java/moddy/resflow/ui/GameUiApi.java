package moddy.resflow.ui;

import moddy.resflow.util.ReflectionUtil;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import view.main.VIEW;
import view.sett.SettView;
import view.ui.top.UIPanelTop;
import view.world.WorldView;

import java.util.Optional;

/**
 * API for accessing and injecting elements into game UI.
 * Uses reflection to access private UI components.
 * <a href="https://github.com/4rg0n/songs-of-syx-mod-example/blob/master/doc/howto/add_ui_element.md">Credit</a>
 */
public class GameUiApi {

    private static GameUiApi instance;

    private GameUiApi() {
        // Singleton
    }

    public static GameUiApi getInstance() {
        if (instance == null) {
            instance = new GameUiApi();
        }
        return instance;
    }

    /**
     * Get settlement view
     */
    public SettView settlement() {
        return VIEW.s();
    }

    /**
     * Get world view
     */
    public WorldView world() {
        return VIEW.world();
    }

    /**
     * Get battle view
     */
    public view.sett.invasion.SBattleView battle() {
        return VIEW.s().battle;
    }

    /**
     * Find a UI element in settlement view
     */
    public <T> Optional<T> findUIElementInSettlementView(Class<T> clazz) {
        return ReflectionUtil.getDeclaredFieldValue("inters", settlement().uiManager)
            .flatMap(inters -> extractFromIterable((Iterable<?>) inters, clazz));
    }

    /**
     * Find a UI element in world view
     */
    public <T> Optional<T> findUIElementInWorldView(Class<T> clazz) {
        return ReflectionUtil.getDeclaredFieldValue("inters", world().uiManager)
            .flatMap(inters -> extractFromIterable((Iterable<?>) inters, clazz));
    }

    /**
     * Find a UI element in battle view
     */
    public <T> Optional<T> findUIElementInBattleView(Class<T> clazz) {
        return ReflectionUtil.getDeclaredFieldValue("inters", battle().uiManager)
            .flatMap(inters -> extractFromIterable((Iterable<?>) inters, clazz));
    }

    /**
     * Inject a UI element into all top panels
     */
    public void injectIntoUITopPanels(RENDEROBJ element) {
        injectIntoBattleUITopPanel(element);
        injectIntoWorldUITopPanel(element);
        injectIntoSettlementUITopPanel(element);
    }

    /**
     * Inject into world UI top panel
     */
    public void injectIntoWorldUITopPanel(RENDEROBJ element) {
        try {
            Object object = findUIElementInWorldView(UIPanelTop.class)
                .flatMap(uiPanelTop -> ReflectionUtil.getDeclaredFieldValue("right", uiPanelTop))
                .orElse(null);

            if (object == null) {
                throw new RuntimeException("Could not find ui element in World UIPanelTop");
            }

            GuiSection right = (GuiSection) object;
            right.addRelBody(8, snake2d.util.datatypes.DIR.W, element);
        } catch (Exception e) {
            throw new RuntimeException("Could not inject ui element into World UIPanelTop#right", e);
        }
    }

    /**
     * Inject into settlement UI top panel
     */
    public void injectIntoSettlementUITopPanel(RENDEROBJ element) {
        try {
            Object object = findUIElementInSettlementView(UIPanelTop.class)
                .flatMap(uiPanelTop -> ReflectionUtil.getDeclaredFieldValue("right", uiPanelTop))
                .orElse(null);

            if (object == null) {
                throw new RuntimeException("Could not find ui element in Settlement UIPanelTop");
            }

            GuiSection right = (GuiSection) object;
            right.addRelBody(8, snake2d.util.datatypes.DIR.W, element);
        } catch (Exception e) {
            throw new RuntimeException("Could not inject ui element into Settlement UIPanelTop#right", e);
        }
    }

    /**
     * Inject into battle UI top panel
     */
    public void injectIntoBattleUITopPanel(RENDEROBJ element) {
        try {
            Object object = findUIElementInBattleView(UIPanelTop.class)
                .flatMap(uiPanelTop -> ReflectionUtil.getDeclaredFieldValue("right", uiPanelTop))
                .orElse(null);

            if (object == null) {
                throw new RuntimeException("Could not find ui element in Battle UIPanelTop");
            }

            GuiSection right = (GuiSection) object;
            right.addRelBody(8, snake2d.util.datatypes.DIR.W, element);
        } catch (Exception e) {
            throw new RuntimeException("Could not inject ui element into Battle UIPanelTop#right", e);
        }
    }

    /**
     * Extract an instance of a specific class from an iterable
     */
    private <T> Optional<T> extractFromIterable(Iterable<?> iterable, Class<T> clazz) {
        for (Object inter : iterable) {
            if (clazz.isInstance(inter)) {
                return Optional.of(clazz.cast(inter));
            }
        }
        return Optional.empty();
    }

    /**
     * Check if minimap is in fullscreen mode
     */
    public boolean isMinimapFullscreen() {
        try {
            return settlement() != null && settlement().mini.openIs();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the GameWindow used for strategic minimap view via reflection
     */
    public Optional<view.subview.GameWindow> getStrategicWindow() {
        return findUIElementInSettlementView(view.sett.ui.minimap.UIMinimapSett.class)
            .flatMap(mini -> ReflectionUtil.getDeclaredFieldValue("view", mini))
            .flatMap(view -> ReflectionUtil.getDeclaredFieldValue("window", view))
            .map(obj -> (view.subview.GameWindow) obj);
    }

    /**
     * Recursively find any hovered RENDEROBJ in a GuiSection
     */
    public Optional<RENDEROBJ> findHoveredElement(GuiSection section) {
        try {
            // Check if this section itself is hovered
            if (section.hoveredIs()) {
                return Optional.of(section);
            }

            // Recursively check child elements
            Optional<Object> elementsOpt = ReflectionUtil.getDeclaredFieldValue("elements", section);
            if (elementsOpt.isPresent() && elementsOpt.get() instanceof Iterable<?> elements) {
                for (Object element : elements) {
                    if (element instanceof RENDEROBJ renderObj) {
                        //                        if (renderObj.hoveredIs()) {
//                            return Optional.of(renderObj);
//                        }
                        // Recursively search if it's a GuiSection
                        if (element instanceof GuiSection) {
                            Optional<RENDEROBJ> hovered = findHoveredElement((GuiSection) element);
                            if (hovered.isPresent()) {
                                return hovered;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail
        }
        return Optional.empty();
    }
}
