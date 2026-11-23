# Deform brush plan

Drafted 2026-11-23. Status: awaiting go-ahead.

## Goal

A Sculpt brush, **Deform**, that pushes the walls of a shape inward (or pulls them outward) by **moving** blocks, not by adding or
removing them. The use it is built for: making a hollow mountain smaller by pushing its sides in, with walls of any thickness, and no
holes left behind.

Sculpt Draw can't do this. It adds or removes columns at the surface of solid ground, so on a thin wall Push removes the wall and leaves a
hole (PR #163 stops it at the first air gap, which is what makes that hole).

## Decisions so far

| Question | Answer |
|---|---|
| Wall thickness | Arbitrary, so one-block walls must stay watertight |
| Stroke style | Paint-stroke: each dab pushes by Strength while you drag over the wall |
| Name | Deform |
| Branch | `feat/deform-brush`, stacked on `fix/sculpt-draw-direction` (PR #163) for its shared dab direction and thin-wall handling |

## How it works

For one dab, clicked on a wall, with radius R and Strength s:

1. **Direction.** `BrushRules.sculptDirection` from PR #163 gives the dab one outward direction n. The air behind a thin wall counts as
   solid, so a one-block wall gets the slope's direction.
2. **What moves.** The movable set M is the part of the shape the brush is on: the solid blocks joined to the clicked block within the
   brush, plus the open air in front of them. `BrushRules.front` already finds both. The far side is left out, so the hollow's opposite
   wall and anything standing inside the hollow never move.
3. **How far.** Each place moves by `v(x) = s · w(x) · n`. The falloff w is 1 at the centre and 0 at the rim, with smoothstep on the
   distance across n (not along it). Within one column along n, every block then moves by the same amount, so the wall keeps its
   thickness.
4. **Backward mapping, as Distort does.** Each block x in the affected area (the brush, swept s further in along -n) takes the block
   from `round(x + v(x))`. For Push the wall's blocks reach x from outside, so the wall shows up further in. This keeps the blocks
   themselves, so materials, block states, chests and signs travel with the wall (`BlockSnapshot`, as Distort copies them). Pull uses
   `x - v(x)`.
   - A source outside M is not taken. A block x that is solid and not in M (the far wall, or something inside the hollow) stays as it is,
     except as in open question 2.
5. **No holes.**
   - *No folding:* neighbouring blocks never move more than one block apart. Smoothstep's steepest slope is 1.5/R, so this holds when
     s < 2R/3. Strength is capped to that.
   - *Seam fill:* where the rounded moves of two neighbouring blocks differ by one, a one-block wall would be left touching only at an
     edge, which shows a see-through crack. So when x's own source is air, the segment between its source and the sources its face
     neighbours' moves would give is searched. The first wall block on it fills x. That thickens the wall by one block at each step, and
     the wall stays watertight.
6. **Writing.** The new blocks go through the usual `put`, so masks, Keep existing, the selection, BuildObject rules, the block limit and
   undo all apply. One dab is one undo step, as for the other brushes.

The rule is a pure function in core (with `BrushRules.Cells` and `Front`), called by both `EditPlanner` and `BrushPreview`, so the hover
ghost shows exactly what a dab will change.

## Controls

- **Pull | Push** switch (the `invert` option), as on Sculpt Draw. Default: Push.
- **Strength**, in blocks per dab, from 1 to the cap of `floor(2R/3)`. The slider's top follows the radius.
- **Flow**, as Elevation and Flatten have: how often a stroke applies a dab.
- **Radius and shape**, as the other brushes.
- Surface only, Fill and Match the ring don't apply, and aren't shown.

## Work

1. **Rule and unit tests.** `BrushRules.deform(...)` and tests on built worlds:
   - a hollow sphere with walls 1, 2 and 3 blocks thick, pushed on one side: the shell stays watertight (a flood from the inside air never
     reaches the outside air through faces), the wall keeps its thickness at the centre, and the far wall is untouched;
   - solid flat ground pushed by s goes down by s at the centre, and the top block (grass) is still on top;
   - Push then Pull by the same amount puts flat ground back;
   - the Strength cap, a slanted thin wall, determinism.
2. **The tool.** `Tool.DEFORM` in the Sculpt group, catalog, icon, lang, panel controls, `EditPlanner` case and preview case. Update
   `ToolMetadataTest`, `WireCompatibilityTest`, `ProtocolSnapshot` (a new enum value; check how the wire encodes tools before choosing
   where it goes), `ToolHintsExpected` and `PanelGameTest`.
3. **Game checks.** Golden cases `deform` and `deform_pull`, a run in the game on a hollow mountain, and a check on Paper. Paper runs the
   same server code, so no Paper code is expected.
4. **Docs.** A FEATURES.md row and a CHANGELOG entry.

## Open questions

1. **Walls meeting things inside.** When a pushed wall reaches a solid block inside the hollow that isn't part of it (a pillar, a build),
   which one wins? Recommended: **the wall wins** and overwrites it, so the wall never gets a hole. Undo brings it back. The other choice
   keeps the inside block and leaves the wall open there.
2. **Strength cap.** Should the slider's top follow the radius (recommended, as it's always visible), or should a higher value be allowed
   and quietly clamped when the dab is applied?
3. **PR base.** Open the Deform PR against `fix/sculpt-draw-direction` while #163 is open (stacked), or wait for #163 to merge and
   target main?
