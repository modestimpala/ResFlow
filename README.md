# ResFlow - Resource & Logistics Visualization Mod

**For Songs of Syx V70**

See where resources go. ResFlow adds two overlays to visualize stockpiles and hauling flows, plus quick filtering so you can focus on one resource or whole chains.

Adds: Storage Overlay • Flow Tracker Overlay • Resource filter bar • Analytics panels

## Features

### Two Main Overlay Modes

#### 1. Storage Overlay

Visualizes stockpile contents with color-coded fills and resource details at multiple zoom levels.

**What it shows:**

- Stockpile fill levels (0-100%)
- Individual resource amounts in each stockpile
- Visual resource icons and quantities
- Production/consumption room indicators (when Resource Chains enabled)

**Visual elements:**

- **Background color**: Entire stockpile area colored by fill level
- **Resource icons**: Top resources displayed at stockpile center
- **Fill bars**: Horizontal or vertical bars showing capacity
- **Amount text**: Numeric quantities with K/M suffixes

**Color coding by fill level:**

- **Blue**: Empty to Low (0-25%)
- **Green**: Medium (25-50%)
- **Gold**: High (50-85%)
- **Red**: Full (85-100%)

Colors smoothly interpolate between these levels with pulsing animation at 75%+ full.

**Zoom-levels:**

- **Zoom 0 (Closest)**: Full detail - 5 resource icons with individual bars and amounts
- **Zoom 1 (Medium)**: 4 resource icons with fill indicators
- **Zoom 2 (Far)**: 3 stacked horizontal bars with icons
- **Zoom 3 (Farthest)**: Large vertical bar with top resource icon (most practical for overview)

**Code reference:**

- [`src/main/java/moddy/resflow/overlay/ResourceStorageOverlay.java`](src/main/java/moddy/resflow/overlay/ResourceStorageOverlay.java)
- [`src/main/java/moddy/resflow/ui/HorizontalResourcePanel.java`](src/main/java/moddy/resflow/ui/HorizontalResourcePanel.java)

#### 2. Flow Tracker

Shows the complete logistics network - where resources are produced, consumed, stored, and how they move.

**What it shows:**

- Production rooms
- Consumption rooms
- Storage facilities (stockpiles)
- Active transport routes with animated flow
- Hauler positions (updates periodically)
- Traffic density heatmaps
- Bottleneck warnings

**Code reference:**

- [`src/main/java/moddy/resflow/overlay/ResourceFlowTracker.java`](src/main/java/moddy/resflow/overlay/ResourceFlowTracker.java)
- [`src/main/java/moddy/resflow/overlay/FlowParticleSystem.java`](src/main/java/moddy/resflow/overlay/FlowParticleSystem.java)
- Data / analysis
  - [`src/main/java/moddy/resflow/analysis/ResourceFlowAnalyzer.java`](src/main/java/moddy/resflow/analysis/ResourceFlowAnalyzer.java)
  - [`src/main/java/moddy/resflow/analysis/ResourceFlowData.java`](src/main/java/moddy/resflow/analysis/ResourceFlowData.java)

### 3. Advanced Analytics Panel

Advanced analytics with summary metrics for every resource.

**Metrics displayed:**

- **Buffer Health**: Safety stock analysis (%), with current/target buffer numbers when available
- **Supply Chain Stability**: Stability score (0-100%)
- **Workforce**: Utilization percentage
- **Trend**: UP/DN/FL with a % change indicator
- **Recommendation**: Short status message (e.g. "! Low buffer", "OK Healthy")

**Code reference:**

- [`src/main/java/moddy/resflow/ui/AdvancedAnalyticsPanel.java`](src/main/java/moddy/resflow/ui/AdvancedAnalyticsPanel.java)
- [`src/main/java/moddy/resflow/ui/FlowAnalyticsPanel.java`](src/main/java/moddy/resflow/ui/FlowAnalyticsPanel.java)
- [`src/main/java/moddy/resflow/ui/OverlayPanelManager.java`](src/main/java/moddy/resflow/ui/OverlayPanelManager.java)

### Room Color Coding

When Flow Tracker is enabled, rooms are colored based on their function.

| Color      | Meaning                       | Example Rooms                                     |
| ---------- | ----------------------------- | ------------------------------------------------- |
| **Green**  | Production                    | Farms, Mines, etc.                                |
| **Red**    | Consumption                   | Carpenter, Bakeries, etc.                         |
| **Blue**   | Storage                       | Stockpiles                                        |
| **Yellow** | Both Production & Consumption | Workshops that consume inputs and produce outputs |

**Code reference (color constants & room classification):**

- [`src/main/java/moddy/resflow/overlay/ResourceFlowTracker.java`](src/main/java/moddy/resflow/overlay/ResourceFlowTracker.java)

### Flow Path Visualization

Animated arrows show how resources move through your settlement.

Particle colors:

| Color          | Flow Type                 | Meaning                                                 |
| -------------- | ------------------------- | ------------------------------------------------------- |
| **Green-Cyan** | Production to Storage     | Resources harvested and stored in stockpiles            |
| **Red-Orange** | Storage to Consumption    | Resources delivered from stockpiles to consumers        |
| **Yellow**     | Production to Consumption | Direct supply (no storage step)                         |
| **Light Blue** | Storage to Storage        | Redistribution between stockpiles                       |
| **Orange**     | Active Hauler             | Active hauler carrying resources (updates periodically) |

**Code reference:**

- [`src/main/java/moddy/resflow/overlay/ResourceFlowTracker.java`](src/main/java/moddy/resflow/overlay/ResourceFlowTracker.java)
- [`src/main/java/moddy/resflow/overlay/FlowParticleSystem.java`](src/main/java/moddy/resflow/overlay/FlowParticleSystem.java)

**Arrow types:**

- **Static small arrows**: Show path structure (sampled at intervals)
- **Animated large arrows**: Wave effect showing active flow direction
- **Resource icons**: Appear on paths at regular intervals (configurable)
- **Overlapping indicators**: Paths cycle through overlapping paths.

### Traffic Heatmap

Visualizes hauler traffic density on tiles (tiles haulers have crossed).

| Color      | Traffic Level      |
| ---------- | ------------------ |
| **Blue**   | Low traffic        |
| **Cyan**   | Light traffic      |
| **Green**  | Moderate traffic   |
| **Yellow** | Heavy traffic      |
| **Red**    | Very heavy traffic |

The heatmap uses a gradient from Blue -> Cyan -> Green -> Yellow -> Red based on traffic density.

**Code reference:**

- [`src/main/java/moddy/resflow/overlay/ResourceFlowTracker.java`](src/main/java/moddy/resflow/overlay/ResourceFlowTracker.java)

### Bottleneck Indicators

Warning icons appear at room centers experiencing problems.

| Symbol           | Color      | Problem Type   | Meaning                                     |
| ---------------- | ---------- | -------------- | ------------------------------------------- |
| Warning triangle | **Red**    | Output Full    | Production stopped because storage is full  |
| Warning triangle | **Yellow** | Storage Full   | Stockpile at capacity for this resource     |
| Question mark    | **Orange** | Input Missing  | Not receiving required input resources      |
| X mark           | **Cyan**   | Employment Low | Room understaffed, operating below capacity |

Icons pulse for visibility. Resource icon overlays on bottleneck indicator when specific to one resource.

**Code reference:**

- [`src/main/java/moddy/resflow/overlay/ResourceFlowTracker.java`](src/main/java/moddy/resflow/overlay/ResourceFlowTracker.java)

### Minimap Buttons

Two new buttons appear below the minimap (top-right of screen):

| Icon Type     | Name            | Function                     | Visual Feedback   |
| ------------- | --------------- | ---------------------------- | ----------------- |
| Storage box   | Storage Overlay | Toggle storage visualization | Glows when active |
| Water droplet | Flow Tracker    | Toggle flow visualization    | Glows when active |

**Keyboard shortcuts**: Can be bound in game using vanilla keybind methods:

- `TOGGLE_STORAGE_OVERLAY`
- `TOGGLE_FLOW_TRACKER`

**Code reference:**

- [`src/main/java/moddy/resflow/InstanceScript.java`](src/main/java/moddy/resflow/InstanceScript.java)

### Resource Filtering Panel

When either overlay is active, a horizontal resource panel appears showing resource icons.

**How to use:**

- **Hover** over a resource icon to see only that resource's flows/storage
- **Left-click** to lock/pin a resource (stays selected even when not hovering)
- **Click multiple resources** to track several at once
- **Left-click again** on a locked resource to unlock it

**Panel features:**

- **Expanded panel**: Click the Question Mark to expand filter options
- **Selection highlighting**: Locked resources show with border/highlight

**Toggle Options (in expanded panel):**

- **Room Colors**: Show/hide colored room overlays
- **Flow Paths**: Show/hide animated flow arrows
- **Active Haulers**: Show/hide tracked hauler positions
- **Resource Chains**: Auto-expand to show complete production chains (e.g., selecting bread shows grain and wood)
- **Analytics Panel**: Show/hide detailed statistics panel
- **Advanced Analytics**: Show/hide the advanced insights panel
- **Config Panel**: Open the in-game settings panel with sliders
- **Logistics Heatmap**: Show/hide hauler traffic density visualization
- **Efficiency Colors**: Color rooms by production efficiency instead of type (green=100%, red=0%)
- **Show Bottlenecks**: Show/hide bottleneck warning icons

**Code reference:**

- [`src/main/java/moddy/resflow/ui/HorizontalResourcePanel.java`](src/main/java/moddy/resflow/ui/HorizontalResourcePanel.java)
- [`src/main/java/moddy/resflow/ui/OverlayPanelManager.java`](src/main/java/moddy/resflow/ui/OverlayPanelManager.java)

### Analytics Panel

Opens when "Analytics Panel" toggle is enabled. Shows a per-resource summary table plus small production/consumption history graphs.

**Metrics shown include:**

- Production & consumption totals
- Production/consumption rates per day
- Net flow per day
- Storage (current/capacity and %)
- Status line (Stable / Decreasing / Increasing, and time-to-empty/full when applicable)
- Active haulers, haul trips, and average haul distance
- Session totals (produced/consumed)

**Code reference:**

- [`src/main/java/moddy/resflow/ui/FlowAnalyticsPanel.java`](src/main/java/moddy/resflow/ui/FlowAnalyticsPanel.java)
- [`src/main/java/moddy/resflow/analysis/ResourceFlowAnalyzer.java`](src/main/java/moddy/resflow/analysis/ResourceFlowAnalyzer.java)

#### Room Filtering (Shift Key)

Hold **Shift** while hovering over a room to see ONLY the flows connected to that room.

#### Minimap View

When you open the full-screen map (press M or click minimap), the overlays render there too.

**What renders:**

- Room color backgrounds (production/consumption/storage)
- Flow connection paths as colored lines
- Active hauler positions as bright dots

#### Particle System

Animated particles flow along hauling paths showing resource movement.

**Visual characteristics:**

- **Glow particles (optional)**: Render as a 3-layer bloom (outer glow + middle glow + core)
- **Dot particles**: Simple solid dots (used when glow is disabled)
- **Speed variation**: Based on flow volume (faster = busier route)
- **Size variation**: Based on flow importance
- **Color-coded**: Matches flow type colors

**Particle lifecycle:**

- Spawned at source room based on production rate
- Travel along cached path at variable speed
- Despawn at destination room
- Throttled to maximum count per screen

**Performance controls:**

- Automatic culling of off-screen particles
- Frame rate adaptive spawning
- Configurable via `FLOW_PARTICLE_ENABLED` setting
- Can be disabled for low-end systems

**Code reference:**

- [`src/main/java/moddy/resflow/overlay/FlowParticleSystem.java`](src/main/java/moddy/resflow/overlay/FlowParticleSystem.java)
- [`src/main/java/moddy/resflow/ModConfig.java`](src/main/java/moddy/resflow/ModConfig.java)

## Installation

1. **Steam Workshop** (recommended):
   - Subscribe to ResFlow on Steam Workshop
   - Enable in game launcher

2. **Manual Installation**:
   - Download ResFlow.zip
   - Extract to: `%AppData%/Roaming/songsofsyx/mods/`

- Enable in game launcher

3. Add "ResFlow" script to a new game or use [Edit Save Scripts](https://steamcommunity.com/sharedfiles/filedetails/?id=3639915741) to add to an existing save.

**First Launch:**

- Buttons will appear below minimap
- No overlays active by default - click buttons to enable
- Resource panel appears when an overlay is active
- Config uses defaults unless you edit/create `ResFlow.txt` (or save from the in-game Config Panel)

## Compatibility

- **Game Version**: V70.21
- **Save Game**: Safe to add to existing saves
- **Other Mods**: Compatible with most mods (doesn't modify vanilla classes)

## Technical Details

- Two independent overlay systems (Storage, Flow Tracker)
- Cached data for performance (rebuilds every 1-2 seconds)
- Particle system with object pooling
- Flow-path rendering uses the game’s **component pathing** (not full A\*) and caches a simplified path per connection
- Fast per-tile lookup of “which connections pass through this tile” via a precomputed tile-to-connection map

**Performance characteristics:**  
Performance depends heavily on settlement size, number of rooms, active hauls, zoom level, and which toggles are enabled.

## Credits

**Author**: Moddy aka ModestImpala  
**Game**: Songs of Syx by Jake de Laval

**Special Thanks:**

- Jake de Laval for creating Songs of Syx and making it very easily moddable.
- [4rg0n](https://github.com/4rg0n) for the excellent example mod, tooling and documentation that quickstarted my development.

## Support

**Bug Reports**: Open an issue on GitHub with:

- Game version
- Mod version
- Description of bug
- Steps to reproduce
- Screenshots if applicable

---
