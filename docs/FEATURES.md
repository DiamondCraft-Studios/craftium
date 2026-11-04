# Craftium 0.3.1-dev coverage

This is a functional development preview. Implemented equivalents do not establish identical algorithms, complete option coverage or complete Axiom parity. The [documentation audit](PARITY-AUDIT.md) tracks the source pages; the list below describes actual code behavior.

## Tools

| Tool | Implemented behavior | Limits / remaining differences |
| --- | --- | --- |
| Magic Wand | Block/state/solid/any compare; gap range, corners, directions and count limit | 128-block search radius; synchronous search |
| Box Select | Two clicks or coordinates; replace/add/subtract/intersect | No direct box face scale handles |
| Freehand Select | Boolean brush selection | Samples brush dabs rather than a continuous rasterized stroke |
| Lasso Select | Screen polygon projected through depth | Adaptive ray sampling; 8192 rays and 32768 selected blocks |
| Ruler | Chains, Euclidean/Manhattan totals, circles, splits, center selection, point axes | No complete yaw/pitch/bounds statistics panel |
| Painter | Surface painting, exact source-state masks, compatible properties and variant family replacement | Variant matching is name based |
| Noise Painter | Eight noise choices, fractal controls, axis scales, 2D/3D and material lists | Cellular/metaball/splatter behavior is Craftium-specific; no weighted material editor |
| Biome Painter | Native quart-biome editing, vertical fill, undo and biome label | Local biome-cell visualization is bounded around the player |
| Clentaminator | Biome change plus surface material conversion | Simple explicit list of convertible surface blocks |
| Gradient Painter | Palette sampled along two control endpoints; dithering | No separate draggable multi-stop gradient editor |
| Script Brush | Bounded Lua brush and Lua mask | Documented helper coverage under a sandbox; third-party script compatibility is limited |
| Freehand Draw | Solid/hollow drawing and carving with transformed brush shapes | No complete simulated edit preview |
| Sculpt Draw | Estimated surface normal, Y mask, strength and carve | Discrete voxel-density normal |
| Rock | Coherent noisy rock, denoising, Gaussian density melding | Approximate rock generator |
| Weld / Melt | Gaussian voxel-density fill/erosion with threshold | Kernel radius capped at three |
| Text | Original 5×7 font or AWT/TTF/OTF text, rotations, thickness and slab text | 48 UTF-16 units; imported-font availability determines glyph support; slab mode needs a slab material |
| Modelling | Convex hull, triangle strip/fan, flat/curved lofts and control points | 64 nodes; Smart currently uses convex hull |
| Stamp | Clipboard scattering, spacing, chance, yaw and mirroring | Grid sampling; does not fully conform each stamp to uneven terrain |
| Shape | Rotated/scaled primitives, superspheres, polyhedra, torus, polygon and spiral | Voxel approximations, bounded extent; viewport envelope is simpler than the actual shape |
| Path | DDA/Bresenham-style lines, Bezier, Catmull-Rom and catenary-like paths; loops, grounding and stair/slab choice | Curve, catenary and stair heuristics differ from Axiom |
| Floodfill | Count-limited connected fill with direction/corner controls | 128-block search radius |
| Fluid Ball | Bounded water/lava/snow flow; support, edges, sources and connectivity controls | Approximate flow solver, not an identical simulator |
| Elevation | Soft column raising/lowering and imported PNG heightmaps | Local height scan and limited vertical displacement |
| Flatten | Terrain height interpolation toward target level | Local scan |
| Slope | Two anchors defining a height plane | Linear plane with local scan |
| Smooth | Gaussian-density ranking preserving eligible solid-cell count | Bounded local kernel; skips block entities |
| Distort | 3D noise resampling including block entity snapshots | Nearest sampling may repeat a container's contents; intended for creative builds |
| Roughen | Seeded exposed-face changes with exposed-face threshold | Craftium-specific surface noise |
| Shatter | Cellular fracture seams, air or selected material | Approximate fracture model |
| Extrude | Connected planar-face search and outward/inward extrusion | Count limit; discrete clicked face |
| Modify | Translate Copies (relative/absolute offsets), Revolve around the cursor on X/Y/Z with angle and Add Translation, Rotate Copies (Equidistant/Custom Angle), Twist about an axis | Settings are in the Studio inspector; no in-world inner/outer revolve guide lines or live preview yet; block states are not turned |
| Annotation | Saved text, line, box, polyline and image notes with undo | 128 notes/dimension, eight images, image resolution at most 32×32 |
| Smear | Stretch selection/clipboard along offset, stopping at solid obstructions | Integer offset, bounded line length |
| Line / Erase / Tinker | Straight thick lines, brush deletion and named block-state property cycling | Line radius ≤4; incomplete Axiom Tinker variant behavior |

## Editing systems

- Exact selected-voxel overlays, merged into rectangular render boxes; compressed selection synchronization.
- Selection fill/outline/walls/top/bottom, fill-nearest, replace/type-replace, biome, bounded ray-lit autoshade, drain, waterlog, snow smoothing, gravity, trigger-updates, hollow and fill-gaps.
- Selection filter, bounds, convex hull, Gaussian smoothing, noise distortion, translation, scaling, expansion, shrinking and inversion. Analyse reports type counts and distribution.
- Clipboard copy/cut/paste with blocks, block entities and optional non-player entities/passengers; facing/axis/half state transforms, mirroring, nearest-neighbour geometry transforms, pending placement, snap-to-ground, paste-copy, paste-and-select and shape merging. Entity limits: 128 roots, 512 with passengers.
- Named presets and palettes, structure NBT, Sponge schematic v2/v3 import, v2 export, numeric-ID legacy schematic import via Minecraft data upgrades, CSV output.
- Blueprint browser with Client/Server source switch, requested export downloads, local transfer, names, author/tags, nested folders and top-down thumbnails. Uploaded server blueprints get thumbnails; local imports get metadata after first loading. Listing: 256 files, five levels. Transfers: 1 MiB.
- Display/marker creation, raw NBT, select-nearest, duplicate/delete, fractional transform, reset, named grouping and group transforms, with undo. Group and gizmo edits compose the existing transform matrix.
- Saved views, weather/time and gamerules, persistent custom gravity/drop/trampling/invulnerability controls. Custom world rules apply across dimensions.
- Persistent named and automatic hotbars with complete item components; automatic slots 0–4095. Inventory click-to-pick blocks; no drag/drop inventory editing.
- Sandboxed Lua: numeric `blocks.<id>`, getBlock/getBlockState/setBlock, isSolid, getHighestBlockYAt, withBlockProperty/getBlockProperty/isBlockTagged, getBlockRGB/findClosestBlockToRGB, simplex/Voronoi-edge noise, table/math, typed variables and `$once$`. Separate mask variables. Limits: 500,000 instructions and 32 MiB heap delta. No host file/network/Java access; third-party script compatibility is limited.
- Visual nested drag/drop rule blocks and copy/paste expressions. Boolean masks: all/any/not, offset, block/state, above/below, near radius 1–8, neighbour/adjacent, Y range, angle, selection, sky and surface. Near syntax: `near(minecraft:stone,3)`. Maximum nesting 32; a block-query budget aborts costly masks before applying an edit.
- Texture-colour picker: RGB/HSB/hex, active-block eyedropper, full/solid/opaque/uniform filters, OKLab nine-slot gradient with endpoints or multiple palette stops, alternate matches, brush/hotbar export. Multi-texture block colour and biome tint remain approximations.
- Enhanced flight, camera-direction flight, momentum, water opacity, minimum brightness, key hints and entity markers; bulldozer, replace/force/no-updates/tinker, fast placement, angel placement, no-clip, extended picking; collision/light/void diagnostics, expanded hitboxes, solid fluid targeting and interaction prevention.
- Flat dark inspector, original icons, movable/resizable/snapping panels, visibility/layout save/copy/paste, configurable colours, local image references with resize, filtering, opacity and in-game visibility. This is not a full tabbed docking system.
- Axiom documented tool defaults, contextual command chords, Ctrl-drag camera gestures and camera-relative nudges. Editor chords and gestures are configurable through View → Keybinds; native tool mappings use Minecraft Controls. See [controls audit](KEYBINDS.md) for remaining gizmo-interaction and configuration differences.

## Added editing workflows

- Virtual tenth slot: seven builder tools, corner/face selection, axis-constrained scrolling, previews, confirmation and undo. Stack uses repeated offsets; complete multi-axis grid editing is pending. Previews use outlines rather than textured holograms.
- Half-block symmetry pivots, mirrored X/Y/Z and two/fourfold Y rotation, including normal Craftium builder placement and breaking.
- History saved per world/player/dimension, undo/redo reload, clickable multi-step rewind and a history browser. Atomic file replacement and shutdown flushing.
- Animated rebuild with timed propagation, recovery saved before clearing, cancellation and shutdown restoration. Planning remains on the server thread.
- CIELAB colour-field generation with unique placement and undo.
- Autoshade: player/custom/sun lights, voxel shadow rays, hemisphere AO, up to four diffuse surface-light iterations, dithering, exposure, weighted palettes and filtered automatic resource-pack matching. Limits: 16,384 exposed cells and 4,000,000 ray queries. Global illumination is approximated.
- Editor gizmos: axis/centre/plane moves, rotation rings, scale nodes, world/local orientation, selection resampling, placement transforms and fractional entity editing. Gizmo mode and local-axis actions can be assigned in View → Keybinds; they start unbound. Arbitrary local rotation composition and annotation handles remain incomplete.
- Commands for editor access, diagnostics, capabilities, local cheats, position visualization and world rules. WorldEdit position commands are observed; broader integration is untested.

## Outstanding work

Full Axiom parity remains incomplete. Material gaps:

1. RotSprite and edge-aware Scale2x/Scale3x; complete multi-axis builder grid stacking; textured pending block holograms; exact orientation for block types with no legal vertical variant.
2. Arbitrary dock/tab containers, pinned viewport views, complete theme/style controls and all documented configuration toggles.
3. High-resolution textured world-image annotations, all text styling, fine annotation gizmos, native inventory item drag/drop and full display-creation property forms.
4. Complete schematic biome/offset metadata and broad legacy/modded-asset compatibility; pre-load thumbnails for arbitrary local imports.
5. Exact Axiom algorithms and all options: Smart modelling, conforming stamps, fluid simulation, smoothing, path stairs/slabs, lighting and Tinker remain independent approximations.
6. Broad multiplayer, WorldEdit, protection-plugin, shader/modpack and performance validation. State transforms preserve representable vanilla properties; unsupported orientations use a legal approximation.

Axiom licensing/accounts/whitelist infrastructure is outside this independent MIT mod. No Axiom code or assets were copied. Validation evidence is in [VALIDATION.md](VALIDATION.md).

## Paper and Purpur companion

The server plugin shares the native editing engine and uses a bounded, fragmented plugin-message connection to the updated Fabric client. Permissions, world restrictions, WorldGuard integration and a cancellable preflight event protect operations. Four custom Fabric world rules remain unavailable on these servers. See [SERVER-PLUGIN.md](SERVER-PLUGIN.md) for installation and limitations.
