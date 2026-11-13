# Noise Painter window plan

Status: 2026-11-13. Step 0 and step 1 are built; the rest is still a plan.

## Why

Noise Painter used to take its second material from the **Background block**. Its "Secondary block" field was the same setting (`Options.secondary`), so pressing X (swap foreground/background) or changing the background silently changed the noise. The tool also only blended two materials unless the Materials list had three or more entries.

## Step 0: done, interim

- `EditPlanner` no longer reads `secondary` for Noise Painter. It uses the Materials list: 1 entry paints that block, 2 entries split at the Blend slider, 3 or more use the weighted palette.
- The "Secondary block" field is removed from the tool panel. The Noise seed field moved up.
- The tool description now says it blends the Materials palette.
- `Options.secondary` stays; it is still the Background block (swap, palette slots).

## Step 1: done, a dockable Materials window

Materials is now a panel of the editor's own window system (`WorkspaceLayout`), like Clipboard and Magic select: it can be moved, resized, folded, snapped and closed, its place is saved with the layout, and it opens from the Materials row in the tool panel of Noise Painter, Fluid Block and Gradient Painter, from Window > Toggle materials, or from the Studio's "Materials window" button. It replaces the fullscreen palette screen. Each block is a row with its icon (click to change it), a weight slider (0.1 to 10, Ctrl-click to type up to 100), its share of the total, and up, down and remove buttons. Blocks are added with Add block, Add hotbar, or by dragging a block onto the window; Sort puts them dark to light. Saving and loading palettes by name was removed for now. The edits are in `MaterialPalette`. The three tools share the one global Materials list. Not yet checked in-game: the panel layouts.

## Goal

A dedicated Noise Painter window, opened from the tool panel, so everything about the noise lives in one place and nothing is borrowed from the Background block.

## Proposed contents

1. **Materials**: an ordered list with a block picker, a weight per row, add/remove/reorder, and a live bar showing the distribution. This replaces the comma-separated Materials text field for this tool.
2. **Noise**: family, fractal type, octaves, lacunarity, gain, scale (per axis), 2D/3D, seed with a re-roll button.
3. **Blend**: the existing Blend slider for the two-material case, hidden when there are three or more rows.
4. **Preview**: a small 2D slice of the noise through the current materials, so changes are visible before painting.
5. **Presets**: save/load a named noise setup (reuse the Blueprint/Selections window pattern).

## Implementation outline

- Client: new `NoisePainterScreen`, opened by a button in the tool panel (same pattern as `MaskScreen` and `PaletteScreen`). It edits `Options.materials`, `materialWeights`, noise fields and seed; no new server logic for the first cut.
- Server: none beyond step 0, since `EditPlanner` already reads `materials`, `materialWeights`, `noiseScale`, `density` and `seed`.
- Wire: no new options in the first cut, so `ProtocolSnapshot` does not change. If presets are stored per tool they need a client-side file, not a packet.
- Tests: extend `ToolMetadataTest` only if the tool description changes again; add a planner test for the 1, 2 and 3+ material cases.
- Docs: update the Noise Painter row in `FEATURES.md` ("no weighted material editor" no longer applies once the window exists).

## Open questions

1. Should the window replace the panel fields entirely, or should the panel keep seed/scale/blend and the window only own the materials?
2. Should Noise Painter's Materials be separate from the global Materials list other tools use, or shared?
3. Is a preview slice worth the cost, or is the existing in-world preview enough?
4. Presets: needed in the first cut, or later?
5. Paper: any of this server-side only, or is it Fabric-only like the other windows?
