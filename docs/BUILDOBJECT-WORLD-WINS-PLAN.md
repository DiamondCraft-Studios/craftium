# BuildObjects: world wins, blocks preserved

Drafted 2026-11-13, replacing the earlier "lifted objects" plan. Nothing here is built except the core classes listed under Status. Builds on [BUILD-OBJECTS-PLAN.md](BUILD-OBJECTS-PLAN.md) (tagged blocks, option A).

## What was rejected, and why

**Lifted objects** (blocks held outside the world, drawn and collided with by a client mixin) were designed and dropped. They need collision and rendering mixins on both Minecraft versions, are invisible to vanilla clients and Paper, and ignore mobs and items. The same goals are reached more cheaply by placing real blocks.

## Goal

A BuildObject is placed into the world as real blocks, immediately when it is released or when the player confirms with Enter. Placing never destroys either side's blocks:

- **The world always wins.** A solid world block (anything that is not air, a fluid or replaceable) is never overwritten by an object.
- **The object's blocks are preserved.** A block that loses is *dormant*: the object keeps its state but does not write it to the world.

## Rule: one occupant per cell

For each cell of an object being placed:

1. A solid world block wins. The object's block is dormant.
2. If another object owns the cell, hierarchy order decides (the later one wins; a child beats its parent). The loser's block is dormant.
3. Otherwise the block is written to the world.

Dormant blocks are never deleted. Moving the object resolves its cells again at the new place, so cells that were blocked come back where the world is free. Overlapping objects are re-resolved together, so when B moves off A, A's cells return.

Resolution happens only when an object is placed or moved. If a solid world block is removed later, the dormant block beneath it does **not** appear until the object is placed again. That is predictable and needs no live hooks.

The ghost preview shows dormant cells (faint outline or x-ray) before the player confirms.

## Storage

- **Where:** the same per-dimension file as the objects, `craftium/buildobjects/<dimension>.nbt`, on the server only. The file format is bumped when the dormant data is added; older files load with none.
- **Written cells:** no copy of their state is kept. The world already holds it.
- **Dormant cells:** a packed `long[]` of positions plus a `short[]` of indexes into a per-object palette of block-state strings, about 10 bytes per cell.
- **Replaced blocks** (decided 2026-11-13: moving an object gives them back): the same structure, only for world blocks that were not air.
- **Clients** get dormant positions for the x-ray, not states.
- **Safeguards:** up to `Selection.LIMIT` dormant and as many replaced cells per object, and 8,388,608 held cells per dimension (`Tree.HELD_LIMIT`, about 80 MB at most). Held server-side only (not copied into each player's session). Undo entries that store dormant changes in the same packed form are still to do.

Estimates, not measurements. A typical partly overlapping object has thousands of dormant cells (tens of KB). The worst case, an object of `Selection.LIMIT` (2,097,152) cells buried in terrain, is about 20 MB. Today's `List<Point>` already costs roughly 36 bytes a cell, so a packed `long[]` for an object's own cells would be a separate saving.

## Phases

Each phase is its own PR, with one commit per logical change, and nothing is pushed until asked.

1. **Raise the object limit** from 64 to 1,024 per dimension (see below). Independent of the rest.
2. **Core.** `ObjectVolume` and `OccupancyResolver` (done), plus the packed dormant storage.
3. **Server.** Resolution on place and move, re-resolving overlapping objects, dormant data in the store and in history entries, caps.
4. **Preview.** Dormant cells in the ghost preview; the Enter flow and release-to-place for BuildObjects.
5. **Tool tie-ins and docs.**

## Limit increase (phase 1)

`SavedSelections.MAX` goes from 64 to 1,024 per dimension. It is used by the create, duplicate, paste and ungroup paths in `SavedSelections`, the load check in `BuildObjectStore`, the check in `EditorServer`, and the stored-history check in `HistoryStore`. `HierarchyView.MAX` (remembered fold and visibility choices) goes from 2,000 to 4,000 so it stays at least double the object limit.

Known follow-ups: the whole tree is re-sent to each editor when it changes (about 60 KB at 1,024 rows, to be checked in a gametest); the Hierarchy window has not been checked for scrolling at hundreds of rows; and a per-dimension total-cell budget is the real memory guard and is not built yet. An older build opening a file with more than 64 objects renames it to `.bad` and starts with none.

## Status

Done, with tests (736 pass on 26.2; both versions compile): the BuildObject limit of 1,024; `ObjectVolume`; `OccupancyResolver`; `PackedCells` (the packed store); dormant and replaced cells on each BuildObject with the caps; saving and loading them; `ObjectMove`, the pure function that moves selected blocks by a one-to-one map of cells with the world winning (dormant, replaced and priority rules, resampling, block entity data; 12 tests). Dormant cells keep block entity data (a chest keeps its items), saved with the world.

Not built: using `ObjectMove` from the editor, the dormant data in undo entries, the x-ray preview, the Hierarchy scroll check at hundreds of rows. Nothing writes dormant data yet, because the Move, nudge and Transform actions in `EditorServer` still rewrite the selected cells directly.

### What wiring it in involves (found while reading the edit path)
- The Move, nudge and Transform actions work on selected *world* cells and the objects follow through `ObjectRemap`. A dormant block is not in the world, so those tools cannot carry it on their own.
- **Decision (2026-11-13): dormant blocks are lifted while a move or transform runs.** `ModelTransform.apply` builds a map of cell to block from the selected cells and samples it. For an object that moves whole (all its world blocks selected), its dormant blocks are sampled the same way, per object and with the same box and steps, so a shift, a turn, a scale or a free-angle rotation treats them like any other block. While the transform is previewed they show as ghost blocks. When it is placed (release, or Enter), each landed block goes through the world-wins rule again: written where the world is free, dormant again where it is not. Nothing is refused.
- `ObjectMove.move` takes where each landed cell came from (and the same for each moving object's dormant blocks), so it covers shifts, turns, mirrors and resampling. `ObjectMove.shift` is the convenience for one-to-one maps.
- `Session.SelectionChange` carries only each object's own cells for undo and redo. It must also carry the dormant and replaced stores (before and after), merged correctly in `then`, `andObjects` and `withObjects`, applied in `handBack`, and saved by `HistoryStore`.
- The staged flow (preview, then Enter) already keeps `pendingObjectsBefore`; the held stores need the same.
- The server needs a view of the world as block state text plus block entity data, and a `solid` test (not air, not a fluid, not replaceable) built from the live `BlockState`.
- **Place here** (Hierarchy action, decided 2026-11-13): re-resolves an object where it stands, so dormant blocks whose cell is now free are written. It is the only way to bring back an object whose blocks are all dormant (it has no world cells to select), and it picks up a changed hierarchy order or a removed blocker.

## Open questions

1. Non-90-degree rotate and scale resample and are lossy, as in the BuildObjects plan; dormant cells are resampled with the rest.
