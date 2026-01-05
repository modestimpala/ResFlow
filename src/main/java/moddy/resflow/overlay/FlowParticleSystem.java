package moddy.resflow.overlay;

import init.resources.RESOURCE;
import moddy.resflow.ModConfig;
import moddy.resflow.analysis.ResourceFlowData;
import snake2d.Renderer;
import snake2d.util.color.COLOR;
import snake2d.util.color.ColorImp;

import java.util.*;

import static moddy.resflow.ModConfig.debug;

/**
 * Manages particle spawning and lifecycle for flow visualization.
 * Mathematical approach:
 * - Spawn rate proportional to actual production/haul volume
 * - Particle speed based on hauler efficiency
 * - Visual intensity scales with flow volume (more particles = busier route)
 * Performance optimizations:
 * - Particle pooling to reduce allocations
 * - Maximum particle cap per connection
 * - Automatic cleanup of dead particles
 */
public class FlowParticleSystem {

    // Maximum particles per connection (performance limit)
    private static final int MAX_PARTICLES_PER_CONNECTION = 20;
    // Maximum total particles (performance cap)
    private static final int MAX_TOTAL_PARTICLES = 500;
    // Active particles
    private final List<FlowParticle> activeParticles = new ArrayList<>();
    // Spatial index: tile coordinate -> list of particles at that tile (rebuilt each update)
    private final Map<Long, List<FlowParticle>> particleTileMap = new HashMap<>();
    // Particle pool for reuse
    private final Queue<FlowParticle> particlePool = new LinkedList<>();
    // Spawn tracking per connection (to limit particle density)
    private final Map<String, ConnectionSpawnData> spawnTracking = new HashMap<>();

    /**
     * Encode tile coordinates to a single long for HashMap key
     */
    private static long encodeTile(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    /**
     * Generate connection key from connection parameters
     */
    public static String makeConnectionKey(int srcRoomIdx, int dstRoomIdx, RESOURCE resource) {
        return srcRoomIdx + "->" + dstRoomIdx + ":" + (resource != null ? resource.index() : -1);
    }

    /**
     * Update all particles and handle spawning
     *
     * @param deltaSeconds Time since last update
     */
    public void update(double deltaSeconds) {

        int deadCount = 0;
        // Update existing particles
        Iterator<FlowParticle> iterator = activeParticles.iterator();
        while (iterator.hasNext()) {
            FlowParticle particle = iterator.next();
            if (!particle.update(deltaSeconds)) {
                // Particle is dead, return to pool and remove from active list
                iterator.remove();
                returnToPool(particle);
                deadCount++;
            }
        }

        // Update spawn timers
        for (ConnectionSpawnData data : spawnTracking.values()) {
            data.timeSinceLastSpawn += deltaSeconds;
            // Decay particle count slowly to prevent getting stuck
            if (data.timeSinceLastSpawn > 5.0 && data.particleCount > 0) {
                data.particleCount = Math.max(0, data.particleCount - 1);
            }
        }

        if (deadCount > 0 && activeParticles.size() % 10 == 0) {
            debug("Particles: " + activeParticles.size() + " active, pool: " + particlePool.size() + " (" + deadCount + " died)");
        }

        // Rebuild spatial index for efficient tile lookup during rendering
        rebuildSpatialIndex();
    }

    /**
     * Rebuild the particle tile map for O(1) lookup during rendering
     */
    private void rebuildSpatialIndex() {
        particleTileMap.clear();
        for (FlowParticle particle : activeParticles) {
            long tileKey = encodeTile(particle.getCurrentTileX(), particle.getCurrentTileY());
            particleTileMap.computeIfAbsent(tileKey, k -> new ArrayList<>(4)).add(particle);
        }
    }

    /**
     * Attempt to spawn a particle on a connection
     *
     * @param connectionKey Unique identifier for this connection
     * @param pathTiles     Path for particle to follow
     * @param flowVolume    Intensity of flow (affects spawn rate and particle speed)
     * @param color         Particle color
     * @param resource      Associated resource
     * @param type          Particle visual type
     */
    public void spawnParticle(String connectionKey, List<FlowParticle.PathTile> pathTiles,
                              double flowVolume, COLOR color, RESOURCE resource,
                              FlowParticle.ParticleType type) {

        // Check global particle limit
        if (activeParticles.size() >= MAX_TOTAL_PARTICLES) {
            return;
        }

        // Get or create spawn data for this connection
        ConnectionSpawnData data = spawnTracking.computeIfAbsent(
            connectionKey, k -> new ConnectionSpawnData()
        );

        // Update spawn interval based on flow volume
        data.updateSpawnInterval(flowVolume);

        // Check if we should spawn based on timing
        if (data.timeSinceLastSpawn < data.spawnInterval) {
            return;
        }

        // Check per-connection particle limit
        if (data.particleCount >= MAX_PARTICLES_PER_CONNECTION) {
            return;
        }

        // Calculate particle properties based on flow volume
        double speed = calculateParticleSpeed(flowVolume);
        float size = calculateParticleSize(flowVolume);

        // Get particle from pool or create new one
        FlowParticle particle = getFromPool();
        if (particle == null) {
            particle = new FlowParticle();
        }

        // Initialize/reset particle with new parameters
        particle.reset(pathTiles, speed, color, size, resource, type);

        activeParticles.add(particle);
        data.particleCount++;
        data.timeSinceLastSpawn = 0.0;
    }

    /**
     * Calculate particle speed based on flow volume
     * Higher flow = faster particles (busier economy looks more active)
     */
    private double calculateParticleSpeed(double flowVolume) {
        // Base speed: 5 tiles/second
        // Scale up to 20 tiles/second for high flow
        double baseSpeed = ModConfig.FLOW_PARTICLE_BASE_SPEED;
        double maxSpeed = ModConfig.FLOW_PARTICLE_MAX_SPEED;

        // Logarithmic scaling (so 10x flow doesn't mean 10x speed)
        double scaleFactor = Math.log1p(flowVolume) / Math.log1p(10.0);
        return baseSpeed + (maxSpeed - baseSpeed) * scaleFactor;
    }

    /**
     * Calculate particle visual size based on flow volume
     * Higher flow = larger particles (more visually prominent)
     */
    private float calculateParticleSize(double flowVolume) {
        float baseSize = ModConfig.FLOW_PARTICLE_BASE_SIZE;
        float maxSize = ModConfig.FLOW_PARTICLE_MAX_SIZE;

        // Linear scaling capped at max
        float scale = (float) (flowVolume / 10.0);
        scale = Math.min(1.0f, scale);

        return baseSize + (maxSize - baseSize) * scale;
    }

    /**
     * Render particles at a specific tile during overlay rendering
     * Called from ResourceFlowTracker.render() for each tile
     * Uses spatial index for O(1) lookup instead of O(n) iteration
     *
     * @param renderer Renderer instance
     * @param tileX    Tile X coordinate
     * @param tileY    Tile Y coordinate
     * @param screenX  Screen X coordinate
     * @param screenY  Screen Y coordinate
     * @param tileSize Tile size (C.TILE_SIZE)
     */
    public void renderAtTile(Renderer renderer, int tileX, int tileY, int screenX, int screenY, int tileSize) {
        if (particleTileMap.isEmpty()) return;

        // O(1) lookup using spatial index
        long tileKey = encodeTile(tileX, tileY);
        List<FlowParticle> particlesAtTile = particleTileMap.get(tileKey);
        if (particlesAtTile == null) return;

        for (FlowParticle particle : particlesAtTile) {
            particle.renderAtTile(renderer, screenX, screenY, tileSize);
        }
    }

    /**
     * Clear all particles (useful for resource switching)
     */
    public void clear() {
        activeParticles.clear();
        particleTileMap.clear();
        spawnTracking.clear();
    }

    /**
     * Clear particles for a specific connection
     */
    public void clearConnection(String connectionKey) {
        activeParticles.removeIf(p -> connectionKey.equals(getConnectionKey(p)));
        spawnTracking.remove(connectionKey);
    }

    /**
     * Get unique key for a particle's connection
     */
    private String getConnectionKey(FlowParticle particle) {
        // For now, we'll need to track this externally
        // This is a placeholder - in practice, particles should store their connection ID
        return null;
    }

    /**
     * Get particle from pool or return null if pool is empty
     */
    private FlowParticle getFromPool() {
        return particlePool.poll();
    }

    /**
     * Return particle to pool for reuse
     */
    private void returnToPool(FlowParticle particle) {
        // Only pool simple particles (not worth pooling complex ones)
        if (particlePool.size() < 100) {
            particlePool.offer(particle);
        }
    }

    /**
     * Get current particle count
     */
    public int getParticleCount() {
        return activeParticles.size();
    }

    /**
     * Get active connection count
     */
    public int getActiveConnectionCount() {
        return spawnTracking.size();
    }

    /**
     * Spawn particles based on flow connection data
     * This is the main entry point for creating particles from actual game data
     */
    public void spawnForConnection(int srcRoomIdx, int dstRoomIdx, RESOURCE resource,
                                   List<FlowParticle.PathTile> pathTiles,
                                   ResourceFlowData.FlowPathType flowType,
                                   double flowVolume) {

        String connectionKey = makeConnectionKey(srcRoomIdx, dstRoomIdx, resource);

        // Determine color based on flow type
        COLOR color = getColorForFlowType(flowType);

        // Determine particle type based on settings
        FlowParticle.ParticleType type = ModConfig.FLOW_PARTICLE_USE_GLOW
            ? FlowParticle.ParticleType.GLOW
            : FlowParticle.ParticleType.DOT;

        spawnParticle(connectionKey, pathTiles, flowVolume, color, resource, type);
    }

    /**
     * Get particle color based on flow path type
     */
    private COLOR getColorForFlowType(ResourceFlowData.FlowPathType flowType) {
        return switch (flowType) {
            case PROD_TO_STORAGE -> new ColorImp(100, 255, 150);   // Green-cyan (harvest)
            case STORAGE_TO_CONS -> new ColorImp(255, 150, 100);   // Red-orange (delivery)
            case PROD_TO_CONS -> new ColorImp(255, 255, 100);      // Yellow (direct)
            case STORAGE_TO_PROD -> new ColorImp(150, 200, 255);   // Blue (inputs)
            default -> new ColorImp(255, 200, 50);                 // Gold (unknown)
        };
    }

    /**
     * Tracks spawn data for a single connection
     */
    private static class ConnectionSpawnData {
        int particleCount = 0;
        double timeSinceLastSpawn = 0.0;
        double spawnInterval = 1.0; // Seconds between spawns

        // Flow volume affects spawn rate (higher volume = faster spawning)
        void updateSpawnInterval(double flowVolume) {
            // Base spawn interval is 1 second, reduced by flow volume
            // flowVolume of 1.0 = spawn every 1 sec
            // flowVolume of 10.0 = spawn every 0.1 sec
            spawnInterval = Math.max(0.1, 1.0 / Math.max(0.5, flowVolume));
        }
    }
}
