# BuildObjects and the Hierarchy window: plan

Drafted 2026-11-10. Nothing is built yet. Status: awaiting go-ahead and answers to the open questions at the end.

## Goal

Any selection can be turned into a **BuildObject**: a named, persistent group of blocks that sits in a **Hierarchy** window, can be nested under other BuildObjects, and can be selected, moved, rotated, scaled and edited like any other selection. Every tool that works on a selection works on a BuildObject, because selecting one *is* selecting its blocks.

## What a BuildObject is here (the key design call)

Unity's GameObject owns a transform and the world is derived from it. Craftium's world blocks are the truth and a selection is a set of world cells. Two ways to bridge that:

| | A. Tagged blocks (recommended) | B. Lifted blocks |
|---|---|---|
| Blocks | Stay in the world | Removed from the world, held as a virtual object with a transform, drawn as a ghost, baked back on commit |
| "Any tool applies" | Free: selecting the object loads its cells into the live selection | Every tool would need a second path for virtual blocks |
| Parent moves, children follow | Children are cells in the parent's effective set, so they move with it | Natural |
| Non-90° rotate/scale | Resamples the world, as the Move/Rotate/Scale tools already do | Lossless until baked |
| Cost | Builds on `SavedSelections`, `TransformGizmo`, `ClipboardTransforms` | New renderer, new collision/lighting story, new undo model |

**Recommendation: A**, shaped so B can be added later as an optional "lift" state without changing the tree or the UI. The honest difference from Unity: a BuildObject is a *named handle on blocks that exist*, so rotating a parent by 17 degrees and back is lossy, exactly as it is for a selection today.

### Model (new `core/BuildObjects`, next to `SavedSelections`)

- `BuildObject`: id, name, `parentId` (null = root), own points, color, locked, sibling order. Its **effective set** is its own points plus all descendants'.
- Each block belongs to **at most one** object. Making an object from a selection claims those cells; a previous owner loses them (one undo step restores both). This keeps "move the parent" unambiguous.
- Tree invariants: no cycles, no duplicate sibling names (reuse `SavedSelections` naming: "BuildObject 1", rename 1 to 40 chars), limit 64 at first like `SavedSelections.MAX`, effective set within `Selection.LIMIT`.
- Selecting an object swaps the live selection to its effective set, using the existing stash/restore mechanism of `SavedSelections`. Edit mode (magic select add/subtract) writes back to the object's own points.
- After any edit that relocates selected blocks (move, nudge, rotate, flip, scale, resize), the object's points are remapped. The server already knows the source-to-destination mapping when it applies the edit, so ownership follows the block rather than the cell. **To verify in phase 2:** whether the resampling in the Rotate/Scale path is an inverse mapping (destination to source); if not, ownership is recomputed by transforming each object's own cell set with the same function.
- Delete of blocks drops them from their object; deleting an *object* offers "keep blocks" (ungroup into the parent) or "delete blocks too".
- Undo and redo cover object operations (create, reparent, rename, delete, claim) as history entries. `SavedSelections.undoDelete` is timestamp-matched and should not be copied.
- AI bridge and MCP can refer to objects by path ("Castle/Tower"), as they refer to saved selections by name today.

### Relationship to the Selections window

BuildObjects supersede saved selections: the floating Selections window goes away and its entries migrate to root BuildObjects. `SavedSelections` code is kept internally for the view/edit swap and is generalized rather than rewritten. (Open question 1.)

## Layout change

Current right column (`EditorScreen`, `RIGHT=200`): Target info 27-109, Clipboard 115-216, Block palette 222 down to Tool masks, Tool masks at the bottom (98 high, 150 with limits open). Left column: Tools, then Tool options filling to the bottom.

New:

- **Right column**, top to bottom: **Hierarchy** (5/8), then the **palette + masks group** (3/8). Let `H = h - 25 - 27` (the usable height). The group height is `G = max(round(3H/8), 105 + 6 + masksHeight())`. The group top is `h - 25 - G`. The Block palette takes `G - 6 - masksHeight()` (as now, it fills down to the masks). Tool masks keeps its own height. Hierarchy is `y=27`, height `groupTop - 6 - 27`.
- **"Implicitly grouped"**: palette and masks stay two windows with their own ids, fold state and visibility, but their default places come from one calculation (replacing the independent `masksTop()`). If the masks window is hidden the palette takes the whole group. `WorkspaceLayout.prepare` already makes a window that has not been moved follow its new default place, so resizing the game window re-flows them; dragging either one detaches it, as today.
- **Target info** moves to the bottom of the left column: `x=0`, width `LEFT`, `y = h - 25 - 82`. Tool options shrinks by 88: `optionsBottom()` and the `WorkspaceLayout.height("options", h-224)` fallback lose 88 (and the `layoutKey()` hash picks up the new heights).
- **Clipboard** is off by default: add `clipboard` to the ids `WorkspaceLayout.fresh()` starts hidden (with history and magic) and give it a default floating spot that does not overlap Rulers (`w-RIGHT-244, 27`). Copy, cut, paste and the shortcuts work without the window; it opens from Window > Toggle clipboard.
- Hierarchy gets key `hierarchy` in `WorkspaceLayout.key()` and the import whitelist. Bump `WorkspaceLayout.VERSION` to 3 so saved layouts drop `target`, `clipboard`, `options`, `palette`, `masks` and take the new places.
- Edge case: on a short window the hierarchy has a minimum (120) and the group a minimum; below both, windows overlap but stay movable. The `LEFT`/`RIGHT` viewport margins (lines using `w-RIGHT-6`) do not change.

## Phases

Each phase is its own PR, with one commit per logical change, and nothing is pushed until asked.

0. **Layout only.** Hierarchy window appears (initially listing the existing saved selections, so it is useful and not empty), Target moves bottom-left, Clipboard off by default, palette + masks grouping at 3/8, layout version 3. Tests: a `WorkspaceLayout` default-places test, and the `panel` scope re-recorded before and after (the change is intended, so record the old behaviour first as in the plugin refactor).
1. **Core model.** `BuildObjects` with the invariants above, claim, reparent, effective sets, migration from `SavedSelections`. Pure unit tests (cycle refusal, exclusivity, undo of claim, name rules, limits).
2. **Server and wire.** Session state, actions (create from selection, create empty, rename, reparent, reorder, delete, ungroup, duplicate, select, edit), remap hook after relocating edits, history entries. Additive protocol: new capability name, no protocol bump (CONTRIBUTING's compatibility rules). Server-side tests, plus a gametest in the style of `SavedSelectionsGameTest`.
3. **Hierarchy UI.** Tree rows with indent and fold arrows, color swatch, click to select, Ctrl and Shift for several, double-click or F2 to rename, drag to reparent and reorder, context menu, lock toggle. Select menu gets "Create BuildObject from selection" (proposed Ctrl+G; check `EditorBindings` for conflicts first) and "Create empty parent".
4. **Tool tie-ins.** Per-object outline color in the viewport (reuses `SavedSelectionColors`), Move/Rotate/Scale remap verified for nested trees, copy/paste "as BuildObject", Structure export of an object, AI path references, docs (FEATURES, KEYBINDS, AI), changelog.
5. **Persistence.** Per-world sidecar file so objects survive a restart (open question 3).

## Risks

- `EditorScreen.java` is 2116 lines of dense code and every layout number above lives in it; phase 0 should extract the right-column and left-column placement into one small helper so Hierarchy and the group are not more hard-coded constants.
- Remap correctness under non-90-degree rotate and scale is the hardest part (phase 2). Fallback: objects are remapped by transforming their own cell sets, and any cell that lands on a block owned by another object goes to the nearer owner.
- Selection swapping already has subtle edge cases (an emptied selection never empties a saved one); BuildObjects inherit the same rule.
- The NeoForge port worktree touches the same client code; phase 0 will need a merge pass there.

## Decisions (2026-11-10)

1. Replace the Selections window with the Hierarchy: **yes.** Saved selections migrate to root BuildObjects and the floating window is removed.
2. Creating an object from a selection that overlaps an existing object takes the **entire** existing object, not just the overlapping cells. Confirmed: it works like selecting two objects at once. The whole overlapped object joins the new one as a child, intact, and a partly covered object still comes in whole.
3. Objects persist **per world and in multiplayer**. That means server-side storage (a file in the world's data), synced to every editor, so phase 5 moves up: persistence is part of the server phase, not a later add-on. New questions: are objects shared by all players in the world or per player, and who may edit or delete them (permissions)?
4. **A (tagged blocks) first, B (lifted blocks) later.** After A ships, check: (a) how often rotating or scaling by a non-90-degree angle loses blocks and bothers you; (b) whether you want to move an object around without the world changing until you place it; (c) whether the hole left in the world while an object is being moved gets in the way; (d) whether you need non-destructive rotate-and-back. If none of these hurt, B may not be needed.
5. Clipboard: off by default. Where it opens when toggled on is left to implementation.

6. Objects are **shared by everyone** in the world. Every Hierarchy action (create, rename, reparent, reorder, delete, ungroup, duplicate, lock, edit blocks) gets its own permission, checked on the server like the other editor actions. Permission names and defaults are decided in phase 2.
