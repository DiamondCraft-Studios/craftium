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

### Check it with get_blocks, not with a render

After you apply a roof, read a thin strip across it, 1 block wide and running across the ridge, with `get_blocks`.
The palette gives each stair's state. Confirm both things:
1. Every stair's `facing` points toward the ridge.
2. The y of the stairs rises toward the ridge on both sides.

If either is wrong, fix it with a corrective op before telling the player it is done.

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
