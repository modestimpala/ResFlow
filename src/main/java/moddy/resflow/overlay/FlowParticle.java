package moddy.resflow.overlay;

import init.resources.RESOURCE;
import lombok.Getter;
import snake2d.Renderer;
import snake2d.util.color.COLOR;
import snake2d.util.color.ColorImp;
import snake2d.util.rnd.RND;

import java.util.List;

/**
 * Represents a single particle flowing along a resource path.
 * Particles animate from source to destination, visualizing actual resource movement.
 * Mathematical properties:
 * - Position interpolated using normalized path distance (0.0 to 1.0)
 * - Speed based on flow volume and hauler efficiency
 * - Size/opacity varies with flow intensity
 */
public class FlowParticle {

    // Position along the path (0.0 = start, 1.0 = end)
    @Getter
    private double pathProgress;

    // Speed of travel (units per second)
    private double speed;

    // Reference to the path this particle follows
    private List<PathTile> pathTiles;

    // Visual properties
    @Getter
    private COLOR color;
    private float baseSize;
    @Getter
    private RESOURCE resource;

    // Particle variety (inspired by FireSparks.java and WeatherDownfallRenderer.java)
    private float sizeVariation;   // Random size multiplier (0.8 to 1.2)
    private float colorVariation;  // Random brightness variation (0.9 to 1.1)

    // Getters
    // Lifecycle
    @Getter
    private boolean alive;
    @Getter
    private double age; // Seconds since spawn

    // Current world tile position (cached for rendering)
    @Getter
    private int currentTileX;
    @Getter
    private int currentTileY;
    @Getter
    private ParticleType type;

    /**
     * Create a new flow particle (for initial allocation)
     */
    public FlowParticle() {
        // Empty constructor for pooling
    }

    /**
     * Initialize or reset a particle with new parameters
     *
     * @param pathTiles Path to follow
     * @param speed     Movement speed (tiles per second)
     * @param color     Particle color
     * @param size      Base size multiplier
     * @param resource  Associated resource (can be null)
     * @param type      Visual style
     */
    public void reset(List<PathTile> pathTiles, double speed, COLOR color, float size,
                      RESOURCE resource, ParticleType type) {
        this.pathTiles = pathTiles;
        this.speed = speed;
        this.color = color;
        this.baseSize = size;
        this.resource = resource;
        this.type = type;
        this.pathProgress = 0.0;
        this.alive = true;
        this.age = 0.0;
        this.currentTileX = 0;
        this.currentTileY = 0;

        // Add variety to each particle (like FireSparks and WeatherDownfallRenderer)
        this.sizeVariation = 0.8f + RND.rFloat() * 0.4f;    // 0.8 to 1.2
        this.colorVariation = 0.9f + RND.rFloat() * 0.2f;   // 0.9 to 1.1
    }

    /**
     * Update particle position and lifecycle
     *
     * @param deltaSeconds Time since last update
     * @return true if particle should continue living
     */
    public boolean update(double deltaSeconds) {
        if (!alive || pathTiles == null || pathTiles.isEmpty()) {
            return false;
        }

        age += deltaSeconds;

        // Advance along path based on speed
        // Normalize speed by path length to get progress per second
        double pathLength = pathTiles.size();
        double progressDelta = speed / Math.max(1.0, pathLength) * deltaSeconds;
        pathProgress += progressDelta;

        // Kill particle when it reaches the end
        if (pathProgress >= 1.0) {
            alive = false;
            return false;
        }

        // Calculate current position on path
        updatePosition();

        return true;
    }

    /**
     * Interpolate position along the path based on progress
     * Uses linear interpolation between path tiles
     */
    private void updatePosition() {
        if (pathTiles.isEmpty()) return;

        // Find which segment we're on
        double scaledProgress = pathProgress * (pathTiles.size() - 1);
        int segmentIndex = Math.min((int) scaledProgress, pathTiles.size() - 2);
        double segmentProgress = scaledProgress - segmentIndex;

        // Interpolate between two tiles
        PathTile start = pathTiles.get(segmentIndex);
        PathTile end = pathTiles.get(Math.min(segmentIndex + 1, pathTiles.size() - 1));

        currentTileX = (int) (start.x() + (end.x() - start.x()) * segmentProgress);
        currentTileY = (int) (start.y() + (end.y() - start.y()) * segmentProgress);
    }

    /**
     * Render the particle at its current tile position
     * This is called during tile iteration when the iterator reaches this particle's tile
     *
     * @param renderer Renderer instance
     * @param screenX  Screen X coordinate of the tile
     * @param screenY  Screen Y coordinate of the tile
     * @param tileSize Tile size in pixels (C.TILE_SIZE)
     */
    public void renderAtTile(Renderer renderer, int screenX, int screenY, int tileSize) {
        if (!alive) return;

        // Improved opacity curves inspired by WeatherDownfallRenderer.java
        double opacity = getOpacity();

        // Render based on particle type
        switch (type) {
            case DOT -> renderDot(renderer, screenX, screenY, tileSize, opacity);
            case GLOW -> renderGlow(renderer, screenX, screenY, tileSize, opacity);
            case RESOURCE -> renderResource(renderer, screenX, screenY, tileSize, opacity);
        }
    }

    private double getOpacity() {
        double opacity = 1.0;

        // Smooth fade-in using quadratic easing
        double fadeInDuration = 0.3;
        if (age < fadeInDuration) {
            double t = age / fadeInDuration;
            opacity = t * t; // Quadratic ease-in
        }

        // Smooth fade-out using cubic easing
        double fadeOutStart = 0.85;
        if (pathProgress > fadeOutStart) {
            double t = (pathProgress - fadeOutStart) / (1.0 - fadeOutStart);
            opacity *= (1.0 - t * t * t); // Cubic ease-out
        }

        opacity = Math.max(0.0, Math.min(1.0, opacity));
        return opacity;
    }

    private void renderDot(Renderer renderer, int x, int y, int tileSize, double opacity) {
        // Simple solid dot - render in center of tile
        int centerX = x + tileSize / 2;
        int centerY = y + tileSize / 2;
        int pixelSize = Math.max(2, (int) (baseSize * 2 * sizeVariation));

        // Apply color variation and opacity
        ColorImp renderColor = new ColorImp(color);
        renderColor.shadeSelf(opacity * colorVariation);
        renderColor.bind();

        for (int dx = -pixelSize / 2; dx <= pixelSize / 2; dx++) {
            for (int dy = -pixelSize / 2; dy <= pixelSize / 2; dy++) {
                renderer.renderParticle(centerX + dx, centerY + dy);
            }
        }
        COLOR.unbind();
    }

    private void renderGlow(Renderer renderer, int x, int y, int tileSize, double opacity) {
        // Multi-layer bloom effect inspired by FireSparks.java
        int centerX = x + tileSize / 2;
        int centerY = y + tileSize / 2;
        int pixelSize = Math.max(3, (int) (baseSize * 4 * sizeVariation));

        // Apply color variation to all layers
        double finalOpacity = opacity * colorVariation;

        // Layer 1: Outermost glow (largest, most transparent)
        ColorImp glowColor = new ColorImp(color);
        glowColor.shadeSelf(finalOpacity * 0.15); // Very transparent outer glow
        glowColor.bind();
        for (int dx = -pixelSize; dx <= pixelSize; dx++) {
            for (int dy = -pixelSize; dy <= pixelSize; dy++) {
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist <= pixelSize) {
                    renderer.renderParticle(centerX + dx, centerY + dy);
                }
            }
        }

        // Layer 2: Middle glow
        glowColor.set(color);
        glowColor.shadeSelf(finalOpacity * 0.4);
        glowColor.bind();
        int midSize = (int) (pixelSize * 0.6);
        for (int dx = -midSize; dx <= midSize; dx++) {
            for (int dy = -midSize; dy <= midSize; dy++) {
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist <= midSize) {
                    renderer.renderParticle(centerX + dx, centerY + dy);
                }
            }
        }

        // Layer 3: Bright core
        glowColor.set(color);
        glowColor.shadeSelf(finalOpacity * 0.85); // Bright, nearly opaque core
        glowColor.bind();
        int coreSize = Math.max(1, pixelSize / 3);
        for (int dx = -coreSize; dx <= coreSize; dx++) {
            for (int dy = -coreSize; dy <= coreSize; dy++) {
                renderer.renderParticle(centerX + dx, centerY + dy);
            }
        }

        COLOR.unbind();
    }

    private void renderResource(Renderer renderer, int x, int y, int tileSize, double opacity) {
        // For now, render as colored dot
        // Future: could render actual resource icon if available
        renderGlow(renderer, x, y, tileSize, opacity);
    }

    // Kill particle manually
    public void kill() {
        this.alive = false;
    }

    // Particle types for different visual styles
    public enum ParticleType {
        DOT,        // Simple dot
        GLOW,       // Glowing particle with bloom
        RESOURCE    // Shows resource icon
    }

    // Simple tile representation
    public record PathTile(int x, int y) {
    }
}
