---
name: craftium-builder
description: Build structures in a player's Minecraft world with the Craftium MCP tools (get_state, get_surface, get_blocks, render_view, preview_build, apply_preview). Use when the user asks to build, recreate from an image, extend or restyle something in their world. Teaches a builder's sequence (brief, site, blockout, shell, roof, openings, materials, detail, context, verify) and Minecraft-scale proportions, so builds come out proportioned, varied and correct on the first apply.
---

# Building with Craftium

Syntax and hard limits live in the `preview_build` tool description. This skill is the craft: the order a good
builder works in, and what to check at each step. Build like a person would: rough form first, detail last, never
detail on a form nobody has approved.

## Read these when

- [references/proportions.md](references/proportions.md): before the brief and the blockout, and whenever you
  choose a size. Minecraft scale is not real-world scale, and the numbers in it are usual values, not rules.
- [references/preview-and-staging.md](references/preview-and-staging.md): before the first `preview_build` of
  anything over about 1,000 blocks, and any time a render looks incomplete (a missing roof, for example).
- [references/spec-pitfalls.md](references/spec-pitfalls.md): before roofs, doors, logs, water, or anything with a
  facing, and before using `hollow`.

## The loop

Every phase: write the ops for that phase only, `preview_build`, `render_view`, judge, then fix or move on. Fix a
problem in the phase where it appears. Detail layered on a wrong shape is wasted work.

Two commit points with the player:
1. After the blockout. Always. Show it and get a yes on size, shape and position.
2. Before the first apply of real blocks, unless they said "just build it".

Between those, don't interrupt for approval. Report what you did.

## Phase 0: Brief

- Decide, and state in one or two lines: what, where, how big, what style. Ask only if the answer would change the
  whole build (era, scale, placement). Otherwise choose defaults and say them.
- From a reference image, write down the proportions before touching a tool: width : depth : height, repeating units
  (windows, columns, steps), and the 3 or 4 dominant materials. Build to those numbers.
- Palette: 3 materials plus 1 accent, put in the spec `palette`. Use `get_palette` as a hint of the player's taste.
- Read `references/proportions.md` now.

## Phase 1: Site

- `get_state` first. The selection is a hint, never modify it. `previewBlocks` is a waiting preview you will replace.
- Find ground with thin `get_surface` strips (1 wide). A 128x128 read is about 16,000 numbers. Widen only where the
  ground is interesting.
- An empty column reads `heights = -2147483648`, `blocks = -1`. A height of -1 is real ground (superflat), not an
  empty column.
- Decide the ground level and the footprint, then `get_blocks` the footprint to confirm it is clear, or note what
  must be cleared or filled.
- Write the build near [0,0,0] and set `origin`, so one number moves it.

## Phase 2: Blockout (preview only)

Purpose: get size, silhouette and placement right while it is cheap.

- Plain solid boxes, cylinders and pyramids in ONE neutral material. No hollow, no windows, no detail. Usually 3 to
  10 ops.
- Include every major mass: body, wings, tower, porch, roof volume.
- Render `se`, `top` and a level side view. Compare proportions with the brief and with `references/proportions.md`,
  and check how it sits on the ground.
- Do NOT apply. Replace the preview until the player agrees.

## Phase 3: Shell (apply)

- Foundation, floors, walls and corner posts at final materials. No roof yet.
- Foundation in a heavier block than the wall. Corner posts a different block from the wall.
- Hollow walls only if the interior will be seen.
- Check the preview budget (`references/preview-and-staging.md`), apply, and continue on the real world.

## Phase 4: Roof (apply)

The roof makes or breaks the silhouette. Check it twice.

- Overhang usually 1 block on eaves and gables.
- Gable: two opposite-facing stair slopes meeting a ridge of full blocks or slabs. Hip: four slopes. Close the gable
  ends with wall blocks.
- Big or wide builds need several roof sections, not one slab.
- Stair facing is the orientation trap: on a roof, every stair faces the ridge (the higher side), on both slopes.
  Renders cannot show it, so after applying read a strip across the ridge with `get_blocks` and check the facings
  (`references/spec-pitfalls.md`).
- Seal the roof to the walls with no sky gaps.

## Phase 5: Openings and circulation

- Carve doors and windows with `minecraft:air` AFTER the walls, then place doors, glass and frames. Many builds use
  open gaps with trapdoor shutters instead of glass, so glass is optional.
- Windows in a steady rhythm: equal spacing, aligned between floors.
- The entrance should read as a feature: wider frame, steps, a roof or lantern.
- Interior stairs need a floor opening and 2 blocks of headroom.

## Phase 6: Materials and texture

Flat single-block surfaces look machine-made. Break them up.

- Vary within a family, for example stone bricks with mossy and cracked patches, denser near the base and corners,
  not uniform noise.
- There is no random-mix primitive. Use `set` with chosen position lists (cap 4,096 positions), `replace` with
  `find`, or a `repeat` pattern.
- Add depth: recess windows, set trim proud of the wall, alternate protruding and recessed sections. A wall wider
  than about 5 blocks with no relief looks flat at a distance.

## Phase 7: Detail and trim

In the reference builds about half the blocks were shaped blocks, so expect this phase to be large, not an
afterthought. The primitives place full blocks, so a primitive-only build will look plainer than a typical
hand-built one.

- Stairs, slabs, walls, fences, trapdoors and buttons for trim, cornices, lintels, sills and shutters. Lanterns,
  chimneys, banners, flower boxes.
- Detail follows the style from Phase 0. Don't sprinkle it everywhere.
- Torches, signs and doors are not support-checked. Check each one stands on or against something.

## Phase 8: Context

- Blend the ground: a path to the door, a different ground block round the foundation, grass, flowers, bushes, a
  fence or hedge.
- Terrain you reshape goes first (before the shell), never over a finished build.
- Water and lava go in LAST and enclosed. Flow is not simulated.

## Phase 9: Final check

- Render from 2 corners, `top` and a side view of the finished area.
- Read back a few critical blocks with `get_blocks` (ridge, door, a stair).
- Tell the player what was built, where (coordinates), how many blocks, and that `undo` steps back one phase at a
  time.

## Honest limits

- Unknown block ids are rejected with the operation named. A block that previews without error exists in this game
  version. Fix the spec and retry.
- If something is a guess (a block state, flow, support, one of the "usual" numbers), say so rather than presenting
  it as checked.
