# Lifted BuildObjects: plan

Drafted 2026-11-13. Nothing is built yet. Status: awaiting go-ahead for phase 0 (the spike) and answers to the open questions at the end.

## Goal

A BuildObject can be **lifted**: its blocks leave the world and live in the object, isolated from it, yet the player collides with them normally. This is option B of [BUILD-OBJECTS-PLAN.md](BUILD-OBJECTS-PLAN.md) (tagged blocks first, lifted blocks later), plus collision. Overlapping a lifted object with world blocks or another object never destroys either side's blocks.

Lifted is a per-object state next to the tagged mode, not a replacement for it (open question 1 confirms this).

## Model

- `ObjectVolume`: a sparse map of local cell to BlockState, owned by the object.
- A transform: translation plus 90-degree rotations only. Arbitrary angles make collision and rendering much harder and are out of scope.
- Children compose their parent's transform.
- In v1 objects **snap to the grid**: integer translation plus 90-degree rotation, so object cells line up exactly with world cells.

## Overlap rule: one occupant per cell

For each cell the occupant is decided in this order:

1. A **solid world block**. A world block wins unless it is air, a fluid or replaceable (grass, snow layers and the like).
2. Otherwise the **highest-priority BuildObject** with a block there. Priority is hierarchy order: lower in the list draws on top, and a child beats its own parent.
3. Otherwise the cell is empty.

Every object cell that loses is **suppressed**: neither rendered nor collided with.

- **The world always wins.** An object never cuts into the world, since hiding world blocks would need chunk mesh changes (fragile with Sodium and Iris) and would break the "world is preserved" guarantee. A per-object "carve" option may come later.
- **The mask is derived state.** It is a pure function of (world, object transforms, priority). It is never saved and has no undo entries, so it cannot lose data or drift. Hidden blocks stay in the object and reappear when the overlap ends.
- Render and collision read the same mask, so what you see is what you stand on.
- In the editor, suppressed cells show as a faint outline or x-ray and stay selectable when the object is entered.

### `OccupancyResolver` (core, pure Java)

Inputs: a solid-world-block predicate and the ordered objects. Output: a per-object set of suppressed cells. It is recomputed over the region where bounding boxes overlap, when:

- an object moves, is edited or is reordered;
- a world block changes inside an object's bounding box;
- a chunk loads (an unloaded chunk counts as empty until it arrives).

## Why this is feasible

- **Collision:** Create, Valkyrien Skies and Sable add extra voxel shapes to entity collision queries through a mixin. Craftium already has movement mixins on both sides (`EntityMovementMixin`, `ClientMovementMixin`).
- **Rendering:** `GhostBlocks` already draws real block models for blocks that do not exist in the world.
- **Server movement checks:** a server that does not know about object blocks only lets the client move more freely than it should; it never flags false collisions.

## Phases

Each phase is its own PR, with one commit per logical change, and nothing is pushed until asked.

0. **Spike, with a go/no-go.** One hard-coded floating floor. Check that it renders, that the player walks on it, and that the server accepts the movement without rubber-banding, on 26.2 and 1.21.10.
1. **Core, pure and unit-tested.** `ObjectVolume`, the transform, world/local mapping, serialization.
2. **Server store and sync.** A per-world file, section-based delta packets, a new additive capability with no protocol bump, and permissions as already decided for the Hierarchy. Lift and Drop (bake back to the world) are undoable history entries.
3. **`OccupancyResolver`.** The rule above, with unit tests for priority, nesting, world-wins, fluids and replaceables, and chunk-load recompute. Client and server hooks that trigger recompute.
4. **Collision, both sides.** A mixin into the block-collision query adds shapes from a spatial index of the objects, minus suppressed cells. Also needed: step-up, ground detection and fall damage, sneak-at-edge, the block under the feet for friction and sounds, and a crosshair raycast hit on object blocks.
5. **Rendering.** Per-object section meshes with face culling inside the object, drawn with the object transform and skipping suppressed cells (rebake affected sections when the mask changes). Lighting is sampled from the world at each cell. The ghost path is enough for small objects; thousands of blocks need baked meshes.
6. **Edit surface and tool tie-ins, the biggest cost.** Add a `BlockSurface` abstraction (`WorldSurface` and `ObjectSurface`). Like Unity's prefab mode, you enter an object and the tools then target it. Moving, rotating and 90-degree turning a whole object is only a transform change. Suppressed-cell x-ray in the editor.
7. **Hierarchy UI and AI/MCP.** A Lifted toggle per object, a marker showing which object wins, nesting and the gizmo. AI/MCP work goes on a priv/ branch.
8. **Paper and NeoForge.** See the limits.

## Limits

- Static blocks only: no redstone, fluids, crops or block entities inside objects. A chest would be a visual only.
- Mobs, items, projectiles and pathfinding ignore object blocks unless the server is hooked too (not in the early phases).
- Vanilla clients cannot see lifted objects. A fallback (BlockDisplay entities plus Shulker hitboxes) needs an entity per block or per box and gives only cube hitboxes; it is viable only as a small-object compat mode.
- Paper has no mixins, so collision would be client-mod-only there. The server would think a player standing on an object is floating, which is fine for creative editing and bad for survival.
- Objects do not light the world; their light is sampled from the world.
- Smooth, fractionally offset movement has no meaningful per-cell overlap. It needs a different rule and is out of scope for v1.
- Cost multiplier: two Minecraft versions, a render-API difference between them, and the NeoForge port.

## Risks

- Mask invalidation hooks on both client and server (world block changes, chunk loads) are easy to miss; a stale mask shows as a block that is visible but not solid, or the reverse.
- Mesh rebakes on frequent mask changes are costly; fine for static objects and per-step snapping.
- Blocks vanishing when an object is dragged into terrain can surprise people; the editor x-ray is the mitigation.
- Client and server compute the mask independently from the same inputs and can briefly disagree while chunks load.

## Decisions (2026-11-13)

1. A world block wins over an object block unless it is air, a fluid or replaceable.
2. The world never loses in v1; an object cannot cut into the world.

## Open questions

1. Lifted as a per-object state next to the tagged mode (recommended), or a replacement?
2. Is 90-degree rotation only acceptable?
3. Do objects ever move at runtime (platforms, riders), or is placement static?
4. For Paper, is client-only collision acceptable for the first version?
5. Can survival players break and place object blocks, or is that editor-only?
