# Jigsaw planner plan

Trial-and-error jigsaw generation with live ghost previews, plus a pool editor. Saved 2026-11-10.

## Decisions

| Question | Answer |
|---|---|
| Where pools live | A datapack at `<world>/datapacks/craftium/` (created with its `pack.mcmeta` on first use) |
| Start height | The selection's Y. `rigid` pieces follow from it; `terrain_matching` pieces snap to the real world surface |
| Pool editing | A pool editor in the UI, built for people who don't know the JSON format |
| Versions | 1.21.10 first, 26.2 after |
| Out of scope for now | Seed-sweep gallery, MCP tool, Paper plugin parity (the old Phase 4) |

## How it works

Template pools are worldgen registry entries. They're frozen when the world loads and `/reload` doesn't refresh them, so the planner never uses the world's registry:

1. Each run reads `data/<ns>/worldgen/template_pool/*.json` from the datapack with vanilla's pool codec, into a **private registry** used only by the planner.
2. It runs vanilla `JigsawPlacement.addPieces` with that registry, a seed, a start pool, a start jigsaw name, a depth, a max distance and the start position.
3. It turns pieces into blocks with `StructureTemplate.processBlockInfos` (processors, rotation and mirror), and turns jigsaw blocks into their `final_state`. Templates come from `StructureTemplateManager`, with cached entries cleared for files that changed.
4. The blocks go to the client through the AI-preview ghost path (32,768 blocks described and outlined, a setting for how many models to draw). Applying is one undoable edit.

Same seed and same files give the same layout.

Templates: `StructureTemplateManager` reads `generated/<ns>/structures` (where Craftium's structure export saves) before datapack `data/<ns>/structure`. The planner reads both. The pool editor offers to copy generated templates into the datapack so the pack works on its own.

## Phase 1: Engine (1.21.10)

- Server-side `JigsawPlanner`: private registry, `addPieces`, conversion from pieces to blocks, start at the selection (origin and Y).
- `Compat` methods for the version-specific `addPieces` and `processBlockInfos` signatures.
- Command for testing: `/craftium jigsaw preview <start_pool> [seed] [depth]`, then apply and undo through the existing commands.
- Game test: a fixture datapack with a small pool gives the same pieces for a fixed seed, and editing a pool file changes the next run without a restart.

## Phase 2: Previews at real sizes

- **Layout view:** one outline per piece, coloured by pool, with the piece's template name on hover. Always on, any size.
- **Block view:** a full ghost while the run fits under the limit. Above it, the view shows the layout plus blocks for the hovered piece only.
- Controls: reroll, back and forward through the last 20 seeds, pin a seed, and a depth slider that shows the first N pieces in generation order.

## Phase 3: Planner window

- A Studio panel: datapack namespace, start pool, start jigsaw, depth (0 to 20), max distance, seed, Generate, Reroll, Apply.
- **Live loop:** watch the datapack and `generated/` folders. Saving a structure block or a pool reruns the preview with the same seed.
- **Diagnostics:**
  - Lint: missing templates, missing fallback pools, jigsaw targets that no element in the target pool can match, empty pools.
  - After a run: open connectors (jigsaws that stayed unconnected), each with a likely reason: empty target pool, no matching name, or no room or depth left.

## Phase 4: Pool editor

Writes the same JSON vanilla reads, so the datapack also works as normal worldgen.

- **Pool list** (left): every pool in the namespace, with new, rename, duplicate and delete, plus a warning badge from the lint.
- **Pool view** (centre): one card per element, showing:
  - a 3D thumbnail (reusing the structure export preview)
  - a weight slider showing each element's share, as a percentage of the pool's total weight
  - a projection toggle: Rigid or Follow terrain
  - a processor list picker
  - the element type: single, list, feature or empty.

  The pool view also has a fallback pool picker. Templates can be added by drag and drop from a template browser, or "Add from selection" (exports the selection as a template, then adds it).
- **Connections** (right): the jigsaws inside the selected template. For each one: its name, target pool, target name, joint (rollable or aligned) and priorities, editable in place. Each jigsaw also lists what it can connect to, i.e. the elements in the target pool that have a jigsaw with a matching name. A connector that can't connect to anything is flagged in red.
- **Pool graph** (tab): pools as nodes, connections as arrows, to show loops and dead ends.
- **Try it:** every pool has a Preview from here button that runs Phase 3's generation with that pool as the start.
- Undo and redo inside the editor, and saving writes the files, which the live loop picks up.

## Risks

| Risk | Mitigation |
|---|---|
| Large structures go past the 32,768-block preview | Layout view first, blocks only when they fit or for the hovered piece |
| Vanilla picks the start height from worldgen noise | Use the selection's Y; `terrain_matching` uses the real surface |
| Terrain adaptation (beardifier) isn't block placement | Not shown in the preview; noted in the UI |
| Processors that read the world | Run them against the real level, like worldgen does |
| Jigsaw API drift between versions | Behind `Compat`; game tests on 1.21.10 now, 26.2 when ported |
| Template entities and loot tables | Blocks only at first |
| Hand-written pool JSON the editor doesn't understand | Show the element as read-only "custom" rather than rewriting it |

## Open questions

- Should Apply also place the structure's entities and loot (block entity data), or only blocks?
- Should the editor manage `worldgen/structure` and `structure_set` JSON (so the structure generates in new chunks), or only pools?

## Status (2026-11-10)

Built on 1.21.10 in `feat/jigsaw-planner`. What differs from the plan above:

- **Commands:** the planner is `/jigsawplan`, not `/craftium jigsaw`, because the client owns the `craftium` command root and rejects subcommands it does not know.
- **Phase 2:** drawing only the blocks of the hovered piece for huge runs was left out; the layout-only view is the fallback. Controls are commands (back, next, depth, view, pin) and, from Phase 3, buttons on the planner screen (Structure → Jigsaw planner…).
- **Phase 3:** the report is text under the buttons; the open-connector reason is "no piece fit here, or the depth ran out" unless the pool or name is missing, because vanilla does not record which applied.
- **Phase 4:** the processor list and the fallback are text boxes (Enter applies), not pickers; there is no drag and drop from a template browser, an "Add template" panel searches instead; the open element's thumbnail is an isometric picture, not a rotatable 3D view. Pools are listed from every pack, and editing one from another pack writes an override under the same name into `datapacks/craftium`.
- **Open questions answered by default:** Apply places blocks only, and the editor manages pools only (not `worldgen/structure` or `structure_set`).
- **Apply workflow:** edits are staged in `<world>/craftium-draft`, a pack folder layered over the craftium pack, so the editor, the preview and the live rerun see them while `datapacks/craftium` stays as it was until Apply. A pool deleted in the draft stays visible to the planner until Apply (the editor hides it). One draft is shared by everyone on the world.
- **Graph:** pools can be dragged to arrange them; positions are kept while the game runs, not saved to disk.
- **Not done:** 26.2 (the tab's actions are not registered there), game tests (skipped on request), the seed gallery, an MCP tool and Paper parity (Phase 4 of the old plan).
