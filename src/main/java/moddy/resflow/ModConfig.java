package moddy.resflow;

import init.paths.PATHS;
import snake2d.LOG;
import snake2d.util.file.Json;

/**
 * Mod config class - Loads values from ResFlow.txt in the game's config directory (including mod assets).
 */
public class ModConfig {

    /**
     * When enabled, prints extra diagnostic logs to help debug overlays and UI injection.
     * Keep this OFF for normal gameplay to avoid log spam.
     */
    public static boolean DEBUG_LOGGING = false;

    // ResourceFlowTracker settings
    public static double FLOW_TRACKER_CACHE_UPDATE_INTERVAL = 2.0;
    public static double FLOW_TRACKER_TRANSPORT_UPDATE_INTERVAL = 0.5;
    public static double FLOW_TRACKER_PULSE_SPEED = 1.5;
    public static double FLOW_TRACKER_ARROW_ANIM_SPEED = 2.0;
    public static int FLOW_ICON_INTERVAL = 3;

    // ResourceFlowAnalyzer settings
    public static double FLOW_ANALYZER_UPDATE_INTERVAL = 5.0;

    // ResourceFlowData settings
    public static double FLOW_DATA_SNAPSHOT_INTERVAL = 60.0;

    /**
     * How many in-game days of history we keep in the circular buffers.
     * This is converted to a sample count using TIME.secondsPerDay() and FLOW_DATA_SNAPSHOT_INTERVAL.
     */
    public static double FLOW_DATA_HISTORY_DAYS = 2.0;

    /**
     * Safety cap for max history samples per resource (prevents runaway memory if snapshot interval is tiny).
     */
    public static int FLOW_DATA_HISTORY_MAX_SAMPLES = 4096;

    // ResourceStorageOverlay settings
    public static double STORAGE_OVERLAY_CACHE_UPDATE_INTERVAL = 1.0;

    // UI settings
    public static double STATUS_MESSAGE_DURATION = 3.0;

    // Particle system settings
    public static boolean FLOW_PARTICLE_ENABLED = true;
    public static double FLOW_PARTICLE_BASE_SPEED = 0.5;      // Base tiles per second (was 5.0, reduced by 10x)
    public static double FLOW_PARTICLE_MAX_SPEED = 2.0;       // Max tiles per second (was 20.0, reduced by 10x)
    public static float FLOW_PARTICLE_BASE_SIZE = 1.0f;       // Base visual size
    public static float FLOW_PARTICLE_MAX_SIZE = 3.0f;        // Max visual size
    public static boolean FLOW_PARTICLE_USE_GLOW = true;      // Enable glow effect

    public static void debug(String message) {
        if (DEBUG_LOGGING) {
            LOG.ln(message);
        }
    }

    /**
     * Loads configuration from the standard mod config path.
     * Expects a file named ResFlow.txt in assets/init/config/
     */
    public static void load() {
        try {
            if (PATHS.CONFIG().exists("ResFlow")) {
                Json json = new Json(PATHS.CONFIG().get("ResFlow"));

                DEBUG_LOGGING = json.bool("DEBUG_LOGGING", DEBUG_LOGGING);

                FLOW_TRACKER_CACHE_UPDATE_INTERVAL = json.dTry("FLOW_TRACKER_CACHE_UPDATE_INTERVAL", 0.1, 60.0, FLOW_TRACKER_CACHE_UPDATE_INTERVAL);
                FLOW_TRACKER_TRANSPORT_UPDATE_INTERVAL = json.dTry("FLOW_TRACKER_TRANSPORT_UPDATE_INTERVAL", 0.1, 10.0, FLOW_TRACKER_TRANSPORT_UPDATE_INTERVAL);
                FLOW_TRACKER_PULSE_SPEED = json.dTry("FLOW_TRACKER_PULSE_SPEED", 0.1, 10.0, FLOW_TRACKER_PULSE_SPEED);
                FLOW_TRACKER_ARROW_ANIM_SPEED = json.dTry("FLOW_TRACKER_ARROW_ANIM_SPEED", 0.1, 10.0, FLOW_TRACKER_ARROW_ANIM_SPEED);
                FLOW_ICON_INTERVAL = json.i("FLOW_ICON_INTERVAL", 1, 10, FLOW_ICON_INTERVAL);

                FLOW_ANALYZER_UPDATE_INTERVAL = json.dTry("FLOW_ANALYZER_UPDATE_INTERVAL", 1.0, 300.0, FLOW_ANALYZER_UPDATE_INTERVAL);

                FLOW_DATA_SNAPSHOT_INTERVAL = json.dTry("FLOW_DATA_SNAPSHOT_INTERVAL", 10.0, 3600.0, FLOW_DATA_SNAPSHOT_INTERVAL);
                FLOW_DATA_HISTORY_DAYS = json.dTry("FLOW_DATA_HISTORY_DAYS", 0.1, 30.0, FLOW_DATA_HISTORY_DAYS);
                FLOW_DATA_HISTORY_MAX_SAMPLES = json.i("FLOW_DATA_HISTORY_MAX_SAMPLES", 10, 100000, FLOW_DATA_HISTORY_MAX_SAMPLES);

                STORAGE_OVERLAY_CACHE_UPDATE_INTERVAL = json.dTry("STORAGE_OVERLAY_CACHE_UPDATE_INTERVAL", 0.1, 10.0, STORAGE_OVERLAY_CACHE_UPDATE_INTERVAL);

                STATUS_MESSAGE_DURATION = json.dTry("STATUS_MESSAGE_DURATION", 0.5, 20.0, STATUS_MESSAGE_DURATION);

                FLOW_PARTICLE_ENABLED = json.bool("FLOW_PARTICLE_ENABLED", FLOW_PARTICLE_ENABLED);
                FLOW_PARTICLE_BASE_SPEED = json.dTry("FLOW_PARTICLE_BASE_SPEED", 0.1, 50.0, FLOW_PARTICLE_BASE_SPEED);
                FLOW_PARTICLE_MAX_SPEED = json.dTry("FLOW_PARTICLE_MAX_SPEED", 0.1, 100.0, FLOW_PARTICLE_MAX_SPEED);
                FLOW_PARTICLE_BASE_SIZE = (float) json.dTry("FLOW_PARTICLE_BASE_SIZE", 0.5, 10.0, FLOW_PARTICLE_BASE_SIZE);
                FLOW_PARTICLE_MAX_SIZE = (float) json.dTry("FLOW_PARTICLE_MAX_SIZE", 1.0, 20.0, FLOW_PARTICLE_MAX_SIZE);
                FLOW_PARTICLE_USE_GLOW = json.bool("FLOW_PARTICLE_USE_GLOW", FLOW_PARTICLE_USE_GLOW);

                LOG.ln("ResFlow: Configuration loaded from ResFlow.txt");
            } else {
                LOG.ln("ResFlow: ResFlow.txt not found, using default configuration");
            }
        } catch (Exception e) {
            LOG.err("ResFlow: Failed to load configuration: " + e.getMessage());
        }
    }

    /**
     * Saves current configuration to ResFlow.txt
     */
    public static void save() {
        try {

            String sb = "DEBUG_LOGGING: " + DEBUG_LOGGING + ",\n" +
                "\n" +

                // Flow tracker settings
                "FLOW_TRACKER_CACHE_UPDATE_INTERVAL: " + FLOW_TRACKER_CACHE_UPDATE_INTERVAL + ",\n" +
                "FLOW_TRACKER_TRANSPORT_UPDATE_INTERVAL: " + FLOW_TRACKER_TRANSPORT_UPDATE_INTERVAL + ",\n" +
                "FLOW_TRACKER_PULSE_SPEED: " + FLOW_TRACKER_PULSE_SPEED + ",\n" +
                "FLOW_TRACKER_ARROW_ANIM_SPEED: " + FLOW_TRACKER_ARROW_ANIM_SPEED + ",\n" +
                "FLOW_ICON_INTERVAL: " + FLOW_ICON_INTERVAL + ",\n" +
                "\n" +

                // Analyzer settings
                "FLOW_ANALYZER_UPDATE_INTERVAL: " + FLOW_ANALYZER_UPDATE_INTERVAL + ",\n" +
                "\n" +

                // Flow data settings
                "FLOW_DATA_SNAPSHOT_INTERVAL: " + FLOW_DATA_SNAPSHOT_INTERVAL + ",\n" +
                "FLOW_DATA_HISTORY_DAYS: " + FLOW_DATA_HISTORY_DAYS + ",\n" +
                "FLOW_DATA_HISTORY_MAX_SAMPLES: " + FLOW_DATA_HISTORY_MAX_SAMPLES + ",\n" +
                "\n" +

                // Storage overlay settings
                "STORAGE_OVERLAY_CACHE_UPDATE_INTERVAL: " + STORAGE_OVERLAY_CACHE_UPDATE_INTERVAL + ",\n" +
                "\n" +

                // UI settings
                "STATUS_MESSAGE_DURATION: " + STATUS_MESSAGE_DURATION + ",\n" +

                // Particle system settings
                "FLOW_PARTICLE_ENABLED: " + FLOW_PARTICLE_ENABLED + ",\n" +
                "FLOW_PARTICLE_BASE_SPEED: " + FLOW_PARTICLE_BASE_SPEED + ",\n" +
                "FLOW_PARTICLE_MAX_SPEED: " + FLOW_PARTICLE_MAX_SPEED + ",\n" +
                "FLOW_PARTICLE_BASE_SIZE: " + FLOW_PARTICLE_BASE_SIZE + ",\n" +
                "FLOW_PARTICLE_MAX_SIZE: " + FLOW_PARTICLE_MAX_SIZE + ",\n" +
                "FLOW_PARTICLE_USE_GLOW: " + FLOW_PARTICLE_USE_GLOW + ",\n";

            // Write to file
            java.io.FileWriter writer = new java.io.FileWriter(PATHS.CONFIG().get("ResFlow").toFile());
            writer.write(sb);
            writer.close();

            LOG.ln("ResFlow: Configuration saved to ResFlow.txt");
        } catch (Exception e) {
            LOG.err("ResFlow: Failed to save configuration: " + e.getMessage());
        }
    }
}
