# The preview limit and building in stages

## The limit

The game draws at most **4,096 blocks** of a preview, both as the ghost in the game and in `render_view`. Blocks
beyond that are NOT shown but ARE in the preview and WILL be built when applied. A "missing" roof or wing is
usually this, not a mistake in the spec.

Nothing in the `preview_build` reply says the picture is cut short today. The clues are:

- `get_state` reports `previewBlocks`, the true total in your waiting preview.
- `render_view` reports `previewBlocksShown`, how many of them it actually drew.

If `previewBlocksShown` is lower than `previewBlocks`, you are not seeing the whole build. Tell the player, and
do not judge the build from that picture.

<!-- TODO(ai-skill): reword this section once preview_build, get_state and render_view report previewBlocksTotal,
     previewBlocksShown and truncated directly (plan step 1). Until then the two-number comparison above is the only
     signal. -->

## Estimate before previewing

- box: (dx+1)(dy+1)(dz+1)
- cylinder: about pi * r^2 * h
- sphere: about 4/3 * pi * r^3
- hollow shapes: about the surface area

Add the ops up. Over about 4,000 means the preview will be cut short.

## Build in stages

Each phase of the build is its own preview, so each stays small. A phase that is still over about 4,000 blocks
splits by region (for example west half, then east half).

- A new `preview_build` replaces the old preview, so the order is: preview part 1, check it, `apply_preview`, then
  preview part 2 on top of the real world. `render_view` then shows the built part plus the new ghost.
- Order stages so later ones don't overwrite earlier ones by accident: terrain, then structure, then details.
- Prefer `hollow` for large masses (hills, domes, towers) whose inside will never be seen. It is cheaper than
  staging. Remember `hollow` keeps the top and bottom layers too (see `spec-pitfalls.md`).
- A small build (under about 4,000 blocks) keeps the phase ORDER as op order but uses one preview and one apply.

## The undo stack

Each `apply_preview` is one undo step, and `undo` only reverses the LAST one. To change an early phase after later
ones, either undo back to it or overwrite it with a corrective op. Get the blockout and the shell right before
moving on. The undo list is dropped when the player leaves the world, reconnects or changes dimension.

## Reading limits that also affect planning

- `get_blocks`: at most 32,768 blocks per call.
- `get_surface`: at most 128 by 128 columns.
- Reading ground nobody has visited generates it, so one read may touch at most 16 chunks that are not already
  loaded. A build may reach 36 such chunks. If a call is refused for that reason, read a smaller area or read it in
  pieces.
- A build spec has at most 256 ops, 64 repeats, radius 128, 256 blocks along each side and about 131,000 blocks in
  total.
