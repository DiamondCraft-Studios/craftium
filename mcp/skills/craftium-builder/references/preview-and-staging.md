# The preview limit and building in stages

## The limit

An AI preview is described to the game up to **32,768 blocks**, visible blocks first, and that is what `render_view`
draws. The ghost in the game draws block models for the first 8,192 of them (the player's setting) and the rest of
what is described as an outline. Blocks beyond 32,768 are NOT shown at all but ARE in the preview and WILL be built
when applied. A "missing" roof or wing is usually this, not a mistake in the spec.

The tools tell you when it happens. `get_state`, the `state` inside a `preview_build` reply, and `render_view` all
carry:

- `previewBlocks`: the true total in your waiting preview.
- `previewBlocksDrawn`: how many of them are described to the game, which is what `render_view` draws.
- `previewTruncated`: true when the preview is only partly described, so parts of it are not shown.
- `previewWarning`: plain-language text, present only when it is truncated.

If `previewTruncated` is true you are not seeing the whole build. Tell the player, and do not judge the build from
that picture. To see what the game did not describe, read it: `read_blocks` reads every block of the preview, drawn
or not, with its state. (`render_view` also reports `previewBlocksShown`, which counts only the blocks that fell inside the
box you rendered, so it can be lower than `previewBlocksDrawn` without anything being cut.)

## Estimate before previewing

- box: (dx+1)(dy+1)(dz+1)
- cylinder: about pi * r^2 * h
- sphere: about 4/3 * pi * r^3
- hollow shapes: about the surface area

Add the ops up. Over about 32,000 means the preview will be cut short. Estimating first saves a preview.

## Build in stages

Each phase of the build is its own preview, so each stays small. A phase that is still over about 32,000 blocks
splits by region (for example west half, then east half).

- A new `preview_build` replaces the old preview, so the order is: preview part 1, check it, `apply_preview`, then
  preview part 2 on top of the real world. `render_view` then shows the built part plus the new ghost.
- Order stages so later ones don't overwrite earlier ones by accident: terrain, then structure, then details.
- Prefer `hollow` for large masses (hills, domes, towers) whose inside will never be seen. It is cheaper than
  staging. Remember `hollow` keeps the top and bottom layers too (see `spec-pitfalls.md`).
- Apply phase by phase even when the build is small enough to preview whole: the player sees it grow and can judge
  its look as it goes, which is the point. Only something tiny (a lamp post, a single pillar) is worth one preview
  and one apply.
- If the player asked to stay preview-only, nothing is applied, so each preview holds the whole build so far (see
  "Staying preview-only" in `SKILL.md`), and the 32,768 limit applies to the cumulative total.

## The undo stack

Each `apply_preview` is one undo step, and `undo` only reverses the LAST one. To change an early phase after later
ones, either undo back to it or overwrite it with a corrective op. Get the blockout and the shell right before
moving on. The undo list is dropped when the player leaves the world, reconnects or changes dimension.

## Other limits

Each tool's description gives its own limits (box sizes, chunks, ops per spec), and a request past one is refused
with what to do instead, so they are not repeated here. Two shape a plan:

- Every reply is held to about 60 KB, so read a big build or a wide area a part at a time. `get_surface` summarises a
  wide area on its own, and `read_blocks` answers in boxes, which are short.
- A build may reach at most 36 chunks nobody has loaded (a square about 96 blocks across). Build far-off things in
  pieces.
