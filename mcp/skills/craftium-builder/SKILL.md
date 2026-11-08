---
name: craftium-builder
description: Build structures in a player's Minecraft world with the Craftium MCP tools (get_state, read_selection, get_surface, read_blocks, render_view, preview_build, apply_preview). Use when the user asks to build, recreate from an image, extend or restyle something in their world. Teaches a builder's sequence (brief, site, blockout, shell, roof, openings, materials, detail, context, verify) and Minecraft-scale proportions, so builds come out proportioned, varied and correct on the first apply.
---

# Building with Craftium

Syntax and hard limits live in the `preview_build` tool description. This skill is the craft: the order a good
builder works in, and what to check at each step. Build like a person would: rough form first, detail last, never
detail on a form nobody has approved.

If the player asks for a blockout, greybox or whitebox of a room or set of rooms (for example for a mod's dungeon or
temple), or gives a room ruleset, use the `craftium-blockout` skill instead. It is a different job: cubes only,
colour-coded placeholder blocks, preview only.

## Read these when

- [references/proportions.md](references/proportions.md): before the brief and the blockout, and whenever you
  choose a size. Minecraft scale is not real-world scale, and the numbers in it are usual values, not rules.
- [references/preview-and-staging.md](references/preview-and-staging.md): before the first `preview_build` of
  anything over about 1,000 blocks, and any time a render looks incomplete (a missing roof, for example).
- [references/spec-pitfalls.md](references/spec-pitfalls.md): before roofs, doors, logs, water, or anything with a
  facing, and before using `hollow`.
- [references/examples.md](references/examples.md): before your first spec. A gable cottage written phase by phase
  and a round tower, each with what its `preview_build` reply should show.

## The loop

Every phase: write the ops for that phase only, `preview_build`, read its reply, judge, then fix or move on. Fix a
problem in the phase where it appears. Detail layered on a wrong shape is wasted work.

The `preview_build` reply says what the spec made: placed and cleared blocks, the box they fill, the commonest
blocks and how the ones that face a way are turned. Check it every time: a build in the wrong place, a missing
material or a roof with all its stairs facing one way shows there without a picture.

Pictures cost the most, so render where the look is the question: the blockout, the roof and the final check (two
or three angles each, in one `render_view` call with `angles`). For the phases in between, the reply is usually enough; render one angle if it raises a
doubt.

By default each phase is applied as soon as it checks out, so the player watches the build grow in the world and can
judge its look as it goes. Every applied phase is one undo step, and you can still change earlier phases afterwards
(`references/preview-and-staging.md`).

There is one commit point: after the blockout, always. Show it and get a yes on size, shape and position. That yes
also covers building the phases, so don't stop for approval between them. Report what you did.

## Changing what the player selected

When the player has a selection and asks for a change to it without giving coordinates ("change this to deepslate",
"make these stairs darker", "swap these for stone bricks"), "this" and "these" are the selection. Call `read_selection`
to see what is in it, then use a `replace_selection` op (see the `preview_build` guide): it needs no coordinates, a
replaced stair keeps the way it faces and its half, and it never changes the selection. Preview it like any build.
Say what you understood in one line ("changing the 48 spruce stairs you selected to deepslate tile stairs").

- If the selection holds several kinds of block and the request could mean any of them, ask once, or use `find` to
  change only the kind they named.
- If nothing is selected, don't guess: ask them to select it, or to give coordinates.
- A selection is a help, not a rule. If they give coordinates or describe a place instead, use those.
- Check it as you check any preview: `read_blocks` shows each new block with its facing before you apply it.

## Staying preview-only

The player decides how much gets built. If they say or imply they want to look before anything is built ("just show
me", "don't build it yet", "let me see it first", "preview only", "I want to check it"), keep every phase as a preview
and do not call `apply_preview` until they say to build. They don't have to name a mode, and they can switch back at
any time ("looks good, build it"). If it is unclear which they want, ask once.

- Send each new phase with `"mode": "add"`: it goes on top of the waiting preview, so you send only the new part,
  its `replace` ops see the preview's blocks, and the reply describes what the phase added. Everything waiting is
  applied, and undone, as one build. To redo a phase, preview the whole build again without `mode`.
- A preview is shown up to 32,768 blocks (the game draws models for the first 8,192 and the rest as an outline). Check `previewTruncated`; if a big build can't be shown whole, say so and
  offer to apply in stages instead.
- You can still check your work without applying anything: `read_blocks` reads every block of the preview with
  its state, drawn or not (`references/spec-pitfalls.md`).
- When they say to build, apply what is waiting. A build too big to preview whole is applied in stages as usual.

## Other sessions building at the same time

The player can run several Claude sessions at once, each building somewhere else. Each has a preview and an undo list of its own, so
`apply_preview`, `cancel_preview` and `undo` only ever touch yours.

- Give every `preview_build` a short `label` ("Egyptian pillar", "Market stall"): the player tells sessions apart by it in the game.
- `get_state` lists the others as `otherSessions` (label, state, box). Keep your site clear of those boxes, and tell the player if the
  place they asked for is taken. A `preview_build` reply with `conflicts` means your build overlaps one: move it or ask.
- If a tool says the player stopped this session, stop at once. Do not retry or build anything else; say what you were doing and wait.
- If you are told the player already has six sessions, ask them to forget or stop one in the AI menu (Build sessions).

## Phase 0: Brief

- Decide, and state in one or two lines: what, where, how big, what style. Ask only if the answer would change the
  whole build (era, scale, placement). Otherwise choose defaults and say them.
- From a reference image, write down the proportions before touching a tool: width : depth : height, repeating units
  (windows, columns, steps), and the 3 or 4 dominant materials. Build to those numbers.
- Palette: 3 materials plus 1 accent, put in the spec `palette`. `get_state` gives the player's recent blocks (a hint of their taste) and the biome (a hint
  of what fits the place).
- Name shaped blocks by material where you can: `"material": "spruce"` on the spec, then `"@stairs"`, `"@slab"`,
  `"@fence"` (or `"stone_bricks@wall"` written out). The server spells the id, and changing the material restyles the
  whole build when the player asks for "the same in dark oak".
- Read `references/proportions.md` now.

## Phase 1: Site

- `get_state` first. The selection is a hint, never modify it. `previewBlocks` is a waiting preview you will replace.
- If the player says they selected a place ("I have selected a platform for you to build on"), or `get_state` shows a
  selection, call `read_selection`. Its `summary` gives the shape, the material, whether it is flat and what is in the
  way above it, which usually settles the site at once. It reads the exact shape, so a lasso or magic-wand selection
  of a platform is the platform, not its bounding box. If something is in the way (trees, fences), say so and suggest
  clearing it or building around it. It works for any selection the editor allows, up to a million blocks.
- A selection is a help, not a requirement. The player may give coordinates or describe the place instead; then find
  the site yourself. Build inside the selection unless they say otherwise, and if the build won't fit, say so and
  propose a size that does.
- To find ground yourself, read the area with `get_surface`: a wide area comes back as a summary (height range,
  how level it is, what it is made of, a coarse map), which is enough to pick a spot. Then read the footprint you
  chose with `detail: "full"` for exact heights.
- Decide the ground level and the footprint, then `read_blocks` the footprint (`source: "world"`) to confirm it is clear, or note what
  must be cleared or filled.
- Write the build near [0,0,0] and set `origin`, so one number moves it.

## Phase 2: Blockout (preview only)

Purpose: get size, silhouette and placement right while it is cheap.

- Plain solid boxes, cylinders and pyramids in ONE neutral material. No hollow, no windows, no detail. Usually 3 to
  10 ops.
- Include every major mass: body, wings, tower, porch, roof volume.
- Render `se`, `top` and a level side view in one call (`"angles": ["se", "top", "south"]`). Compare proportions with the brief and with `references/proportions.md`,
  and check how it sits on the ground.
- Do NOT apply. Replace the preview until the player agrees.

## Phase 3: Shell (apply)

- Foundation, floors, walls and corner posts at final materials. No roof yet.
- Foundation in a heavier block than the wall. Corner posts a different block from the wall.
- Hollow walls only if the interior will be seen.
- Check the preview budget (`references/preview-and-staging.md`), apply, and continue on the real world.

## Phase 4: Roof (apply)

The roof makes or breaks the silhouette. Check it twice.

- Use the `roof` op: one op per roof section, over the outer corners of the walls, at the height of the lowest
  course (usually one above the wall top). It faces every stair, shapes the hip corners, fills the gable ends, caps
  the ridge and builds the walls up under the overhang, so the orientation trap below is handled for you.
- Overhang usually 1 block on eaves and gables (the op's default).
- Gable: two slopes meeting a ridge, the usual cottage roof; the ridge runs along the longer side unless you set
  `ridge`. Hip: four slopes, calmer and lower-looking. Shed: one slope, for lean-tos and porches.
- A roof rises one block per block in, so its height is half the span. Big or wide builds need several roof
  sections (a main roof and lower roofs over wings), not one huge one.
- Write stairs by hand only for what the op does not make (a dormer, a curved or flared roof). Then stair facing is
  the orientation trap: on a roof, every stair faces the ridge (the higher side), on both slopes. Renders cannot show
  it. The `preview_build` reply counts the stairs by facing: a gable needs two opposite facings in about equal
  numbers. Read the roof with `read_blocks` to check each facing is on the right side
  (`references/spec-pitfalls.md`); after applying, the same read shows the world.
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
- A mix scatters blocks by weight wherever a block goes: `{"mix": [["stone_bricks", 6], ["mossy_stone_bricks", 3],
  ["cracked_stone_bricks", 1]]}`, usually as a palette entry. It is the same each time the spec is built. A mix
  alone is uniform noise, so put a mossier mix on the lower courses and the corners and a cleaner one higher up
  (separate ops for each band), or place patches with `set` lists.
- Add depth: recess windows, set trim proud of the wall, alternate protruding and recessed sections. A wall wider
  than about 5 blocks with no relief looks flat at a distance.

## Phase 7: Detail and trim

In the reference builds about half the blocks were shaped blocks, so expect this phase to be large, not an
afterthought. The primitives place full blocks, so a primitive-only build will look plainer than a typical
hand-built one.

- Stairs, slabs, walls, fences, trapdoors and buttons for trim, cornices, lintels, sills and shutters. Lanterns,
  chimneys, banners, flower boxes.
- Detail follows the style from Phase 0. Don't sprinkle it everywhere.
- Write a detail once and place it around the build with a `group`: a window with its sill, shutters and lintel,
  written for the south wall, becomes the north wall's with `"rotate": 180` or the east wall's with `"rotate": 90`
  and `"at"`. Stairs, trapdoors and logs inside turn with it. `repeat` on the group places a row of them.
- The preview reply's `warnings` name torches, lanterns, signs, ladders and buttons with nothing to hold them and doors
  or beds missing a half. Fix each before applying; it is the only support check there is.

## Phase 8: Context

- Blend the ground: a path to the door, a different ground block round the foundation, grass, flowers, bushes, a
  fence or hedge.
- Terrain you reshape goes first (before the shell), never over a finished build.
- Natural ground, rocks and trees read better from a `script` op than from shapes: Lua that loops over its box and
  calls `setBlock`, with noise for the shape of the ground (see the build spec guide). Keep scripts for what shapes
  cannot make; a wall, a floor or a roof is still a shape. Always give a script a `"seed"` and keep it when you
  revise the spec, so the ground does not change under the player between previews. Read the reply's warnings: a
  script that set nothing, or set blocks outside its box, says so.
- Water and lava go in LAST and enclosed. Flow is not simulated.

## Phase 9: Final check

- Render from 2 corners, `top` and a side view of the finished area.
- Read back a few critical blocks with `read_blocks` (ridge, door, a stair).
- Tell the player what was built, where (coordinates), how many blocks, and that `undo` steps back one phase at a
  time.

## Honest limits

- Unknown block ids are rejected with the operation named and the nearest real ids, and a wrong state with the
  properties the block takes. A block that previews without error exists in this game version. Fix the spec and
  retry; the earlier preview stays waiting meanwhile.
- If something is a guess (a block state, flow, support, one of the "usual" numbers), say so rather than presenting
  it as checked.
