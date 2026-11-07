# Spec pitfalls: facing, shapes and what the tools can't check

## Orientation

Renders are flat colour and cannot show which way anything faces, so you cannot check a stair by looking. Use the
rule below, then read the blocks back (last bullet).

### Compass

North is -z, south is +z, east is +x, west is -x. Mix these up and every facing comes out backwards.

### Stairs

`facing` is the side the stair goes UP toward: its tall back is on that side. This matches the game's own stair
model (the one that `facing=east` uses has its tall half on the east side, and the other directions are rotations
of it). `half=top` turns the stair upside down and keeps the same `facing`.

The `roof` and `stairs` ops and a turned or mirrored `group` set all of this for you, corner shapes included. The
rules below are for stairs you write by hand.

The error that keeps happening is naming the slope instead of the stair. Do not ask "which way does this roof slope
face?" Ask "which side is higher?" On a roof, **every stair faces the ridge**, the higher side, for both slopes:

| Ridge runs along | Slope | Stairs are |
|---|---|---|
| x (east-west) | north slope | `facing=south` |
| x (east-west) | south slope | `facing=north` |
| z (north-south) | west slope | `facing=east` |
| z (north-south) | east slope | `facing=west` |

A south-facing slope is the one that descends toward the south, so its stairs face NORTH, up toward the ridge.

Other cases:
- A staircase you climb: `facing` is the direction you walk to go up.
- The underside of an overhang or an eave: `half=top`, same `facing` rule.
- Corners of a hip roof or an L-shaped roof can need `shape=inner_left`, `inner_right`, `outer_left` or
  `outer_right`. A plain gable has none (`shape=straight`). If a corner matters, set the shape explicitly.

### Check it by reading the blocks, not by looking

A render can't show facing, but the block data can. As soon as you have previewed a roof, and before you apply it or
show it as done, read it with `get_preview_blocks` over a box that wraps the roof. Leave `format` on `auto`: for a
roof it answers as boxes, grouped by block, so each facing is its own entry with the boxes it fills. For a gable
whose ridge runs along x at z=11, it looks like this (shortened):

```
"blocks": [
  {"block": "minecraft:spruce_stairs[facing=south,half=bottom,...]", "count": 76,
   "boxes": [[697,-52,7, 715,-52,7], [697,-51,8, 715,-51,8], [697,-50,9, 715,-50,9], [697,-49,10, 715,-49,10]]},
  {"block": "minecraft:spruce_stairs[facing=north,half=bottom,...]", "count": 76,
   "boxes": [[697,-52,15, 715,-52,15], [697,-51,14, 715,-51,14], [697,-50,13, 715,-50,13], [697,-49,12, 715,-49,12]]}
]
```

Each box is `[x1, y1, z1, x2, y2, z2]`, both corners included. Confirm both things:
1. Each facing's boxes lie on the side that rises toward the ridge: here `facing=south` is at z below 11 and
   `facing=north` at z above 11.
2. Within a group the y rises as the boxes get closer to the ridge (z=7 at y=-52 up to z=10 at y=-49).

If either is wrong, fix the spec and preview again; nothing has been built. After you apply, `get_blocks` reads the
same thing from the world, to confirm what was actually built.

### Other blocks

- Logs and pillars: `axis=x|y|z` runs along the length.
- Doors: set both halves (`half=lower|upper`), plus `facing` and `hinge`.
- Fences, walls and panes connect to their neighbours on their own. Don't set their sides.

## Shape behaviour

These come from the build code (`BuildSpec.java`).

- `hollow` keeps every cell that has a face-neighbour outside the shape. Boxes and cylinders therefore keep their TOP
  and BOTTOM layers too.
- `hollow` places only the shell. The inside is left as it was, not cleared. To empty something, add an air op for
  the interior.
- Later ops overwrite earlier ones. Carve doors and windows with `minecraft:air` after the walls.
- `set` with a list of positions is capped at 4,096 positions.
- A build ignores the player's brush settings (mask, symmetry, height range) and places exactly what the spec says.

## What the tools can't tell you

- Water and lava flow is not simulated. Place liquids last and enclose them with solid blocks (or leave a clear rim)
  so they cannot spill when built.
- Torches, signs, doors and similar blocks are not checked for support.
- Unknown block ids and bad specs are rejected with the operation named. Fix and retry. That error is reliable. A
  block that previews without error does exist in this game version.
- Primitives suit pillars, walls, towers, domes and similar shapes. Organic shapes such as trees or terrain are
  better done with the brush tools, or with many small `set` ops if you must.
- If something is a guess (a block state, flow behaviour), say so to the player.
