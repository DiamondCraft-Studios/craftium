# Where the skill's proportion numbers come from

The `craftium-builder` skill ([mcp/skills/craftium-builder/references/proportions.md](../mcp/skills/craftium-builder/references/proportions.md))
gives the AI usual Minecraft-scale proportions. This records what they were measured from, how, and how far to trust
them. The numbers in the skill are advisory: they describe one sample, and the skill tells the AI to adapt them to the
brief rather than follow them.

## Sample

Eleven Bedrock `.mcstructure` files, one per biome (badlands, cherry, dark_forest, desert, ocean, pale, plains, snow,
snowy_taiga, swamp, taiga). They are not in the repository (`reference/nbt/` is git-ignored: other creators' builds).

- All are one style family: large, ornate, hand-built houses and halls.
- Each sits in the same 35 x 55 x 37 container on a ground pad 3 layers thick, so everything is measured from layer 3
  up, with terrain-type blocks (dirt, grass, sand, snow, water, stone and similar) left out.
- Two are less reliable: `ocean` (the ground is water) and `snow` (snow is partly a building material). `swamp` is
  mushroom houses with no real roof.

## Method

[tools/](tools/) holds the scripts (Python standard library only, read-only on the files):

| Script | Does |
| --- | --- |
| `mcs.py` | Reads Bedrock little-endian NBT |
| `measure.py` | Height, footprint, windows, roof steps, headroom and materials for each file |
| `floors.py` | Finds interior floor levels: solid cells with clear air above and a ceiling higher up |
| `render.py` | Draws an elevation and two cross-sections of a file to a PNG, with a ruler every layer |

Floor-to-floor heights were first estimated with `floors.py` and then checked by eye on cross-sections of `plains`,
`pale`, `taiga`, `dark_forest` and `cherry`.

## Results

Heights are above the ground pad.

| Build | Height | Footprint | Blocks | Distinct blocks | Names for 80% | Stairs+slabs | Detail blocks |
| --- | --- | --- | --- | --- | --- | --- | --- |
| badlands | 21 | 32 x 28 | 2,717 | 74 | 24 | 33% | 47% |
| cherry | 19 | 35 x 36 | 3,856 | 83 | 19 | 38% | 52% |
| dark_forest | 16 | 35 x 30 | 2,865 | 68 | 14 | 42% | 54% |
| desert | 16 | 25 x 29 | 2,583 | 81 | 21 | 26% | 55% |
| ocean | 12 | 20 x 20 | 1,458 | 72 | 19 | 44% | 84% |
| pale | 29 | 34 x 24 | 5,304 | 86 | 23 | 25% | 41% |
| plains | 23 | 27 x 22 | 3,885 | 95 | 19 | 24% | 42% |
| snow | 45 | 31 x 31 | 2,656 | 49 | 11 | 23% | 72% |
| snowy_taiga | 26 | 25 x 27 | 3,853 | 62 | 14 | 21% | 48% |
| swamp | 38 | 34 x 33 | 6,231 | 78 | 11 | 4% | 15% |
| taiga | 17 | 22 x 26 | 2,270 | 57 | 13 | 38% | 62% |

- **Height:** 12 to 45, median 21. **Footprint:** 20 to 36 on a side, median about 27 x 27 (this may include fences
  and yards).
- **Storeys:** about 6 to 8 blocks floor to floor. Seen directly: `plains` 7 (6 clear), `pale` 8 (7 clear), `taiga` an
  open pavilion with about 8 clear. The floor-finder put the rest between 5 and 8, with `cherry` near 4 to 5. The
  cherry cross-section cuts a shrine gate, so it is not evidence about storeys.
- **Roofs:** 8 to 15 blocks above the eaves in the pitched builds, as tall as the walls or taller. Where roofs slope,
  45 degree stair roofs dominate (adjacent roof columns differ by one block); the 2:1 slope is rare (3% or less of
  column pairs in 8 of 11, about 22% in `pale`). `desert` and `ocean` are mostly flat.
- **Shaped blocks:** stairs and slabs are 21 to 44% of the blocks (not `swamp`), and detail blocks (stairs, slabs,
  trapdoors, fences, walls, lanterns, doors and similar) about 40 to 60% in most builds.
- **Materials:** 49 to 95 distinct blocks per build, but 80% of the blocks come from only 11 to 24 of them.

## What it could not measure

- **Windows.** Seven of the eleven contain no glass, and the rest are too different to give a rule. `dark_forest` has
  eight 1 x 3 windows and `snow` is mostly 1 x 1.
- **Eave overhang** and **the number of masses** in a build. Neither is reliably readable from voxels.
- **Storey height by the headroom method.** Clear air between a floor and a block above it gave 2 to 8 with no
  winner, because attics, lofts and sloped ceilings pollute it. The floor-finder and the cross-sections replaced it.

## Limits

- Eleven builds, one style family, all large. A small cottage will use shorter storeys and smaller roofs.
- The floor-finder can mistake a mezzanine for a floor. It agreed with the pictures where they were checked.
- Bedrock block names differ from Java ones (a stair stores `weirdo_direction`, not `facing`). Heights, widths and
  ratios do not depend on names, so this matters only for orientation, which was not measured from these files.

## Other sources

The roof pitch rules, the 1 to 2 storey roof height and the odd or even width rule come from the
[Minecraft Wiki roof construction guidelines](https://minecraft.wiki/w/Tutorial:Roof_construction_guidelines). The 60/30/10
material split and the mass ratios come from
[Craftdex's article on architecture of builds](https://craftdex.net/fr/articles/the-architecture-of-builds). Window sizes
and a 1-block overhang are common builder habits with no source here, and the skill labels them as convention.

## Repeating it

```sh
python -I reference/tools/measure.py reference/nbt
python -I reference/tools/floors.py reference/nbt
S=20 P=1 python -I reference/tools/render.py reference/nbt/plains.mcstructure plains.png   # P=1 is the z cross-section
```
