# Placeholder legend (Craftium default)

There is no industry standard for placeholder colours, in Minecraft building or in level design. What builders and
level designers do agree on is the practice: colour by function, keep the colours consistent, and keep a legend. This
is Craftium's default legend. A ruleset from the user replaces any of it, and you always tell the user which legend
you used.

Concrete is the default because it is a flat colour with no texture, so roles stay readable in `render_view` and in the
preview reply's list of the commonest blocks. Wool, terracotta or carpet are fine when the user asks for them.

| Role | Palette name | Block | Notes |
|---|---|---|---|
| Walls, ceiling, mass | `wall` | `light_gray_concrete` | plain geometry |
| Floor | `floor` | `gray_concrete` | darker than the walls so floors read from above |
| Entrance (a way into the room) | `entrance` | `orange_concrete` | mark the threshold; the direction it faces matters for rotation |
| Loot (chests) | `loot` | `yellow_concrete` | one block per chest |
| Spawner or mob area | `spawner` | `red_concrete` | |
| Boss or key area | `boss` | `purple_concrete` | |
| Trap or hazard | `trap` | `black_concrete` | not red, which is the spawner |
| Secret or hidden space | `secret` | `cyan_concrete` | |
| Foundation, ground fill | `foundation` | `brown_concrete` | shows how the room meets uneven ground |
| Decor to be replaced later | `decor` | `magenta_concrete` | the "missing texture" pink |
| Water | `water` | `blue_concrete` | |
| Lava | `lava` | `red_terracotta` | orange is already the entrance |
| Ramp (stairs or slabs only) | `ramp` | `stone_stairs`, `stone_slab` | the one place non-cube blocks are allowed |

## Using it

Put the roles in the spec's `palette` and use the names in the ops:

```
"palette": {"wall": "light_gray_concrete", "floor": "gray_concrete", "entrance": "orange_concrete", "loot": "yellow_concrete"}
```

Only put the roles the room uses in the palette. A swap to the user's own colours is then one edit of the palette.

Give a marker the size of the thing it stands for: a chest is one block, a spawner is one block, a boss area is the
floor it covers. Do not colour whole walls to show a function; walls stay `wall`.
