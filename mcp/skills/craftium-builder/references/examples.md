# Worked examples

Two small builds written out phase by phase, with what to check in each `preview_build` reply. Copy the method, not
the numbers: sizes, materials and style come from the brief.

Both are written near [0,0,0] and placed with `origin`. `y = 0` is the first layer above the ground, so if
`get_surface` says the ground top is at y = 63 where the build goes, the origin's y is 64.

## 1. A spruce cottage with a gable roof

Brief: "a small spruce cottage, door to the south". 9 wide (x) by 7 deep (z), 4-block walls, one door in the
middle of the south wall. The width is odd so the door sits under the middle of the roof. The ridge runs along x,
the longer side, so the slopes face north and south and the gable ends are the west and east walls.

### Blockout (preview, get a yes)

```json
{"origin": [100, 64, 200], "ops": [
  {"op": "box", "from": [0, 0, 0], "to": [8, 4, 6], "block": "white_concrete"},
  {"op": "box", "from": [-1, 5, -1], "to": [9, 5, 7], "block": "white_concrete"},
  {"op": "box", "from": [-1, 6, 0], "to": [9, 6, 6], "block": "white_concrete"},
  {"op": "box", "from": [-1, 7, 1], "to": [9, 7, 5], "block": "white_concrete"},
  {"op": "box", "from": [-1, 8, 2], "to": [9, 8, 4], "block": "white_concrete"}
]}
```

Check: the reply's `min` is [99, 64, 199] and `size` is [11, 9, 9] (the roof overhangs by 1). Render `se`, `top`
and `south` and show the player.

### Shell (apply)

```json
{"origin": [100, 64, 200],
 "palette": {"found": "cobblestone", "wall": "spruce_planks", "post": "stripped_spruce_log[axis=y]", "floor": "spruce_planks"},
 "ops": [
  {"op": "box", "from": [0, 0, 0], "to": [8, 0, 6], "block": "found"},
  {"op": "box", "from": [0, 1, 0], "to": [8, 4, 6], "block": "wall"},
  {"op": "box", "from": [1, 1, 1], "to": [7, 4, 5], "block": "air"},
  {"op": "box", "from": [1, 0, 1], "to": [7, 0, 5], "block": "floor"},
  {"op": "box", "from": [0, 1, 0], "to": [0, 4, 0], "block": "post", "repeat": {"count": 2, "step": [8, 0, 0]}},
  {"op": "box", "from": [0, 1, 6], "to": [0, 4, 6], "block": "post", "repeat": {"count": 2, "step": [8, 0, 0]}}
]}
```

The walls are a solid box with the inside carved out, not `hollow: true`: a hollow box keeps its top and bottom
layers, which would put a ceiling at y = 4 and a plank floor at y = 1, leaving 2 blocks of headroom.

### Roof (apply after checking the facings)

```json
{"origin": [100, 64, 200],
 "palette": {"wall": "spruce_planks",
             "roofN": "spruce_stairs[facing=south,half=bottom]",
             "roofS": "spruce_stairs[facing=north,half=bottom]",
             "cap": "spruce_slab[type=bottom]"},
 "ops": [
  {"op": "box", "from": [0, 5, 0], "to": [8, 5, 0], "block": "wall", "repeat": {"count": 2, "step": [0, 0, 6]}},
  {"op": "box", "from": [0, 5, 0], "to": [0, 5, 6], "block": "wall", "repeat": {"count": 2, "step": [8, 0, 0]}},
  {"op": "box", "from": [0, 6, 1], "to": [0, 6, 5], "block": "wall", "repeat": {"count": 2, "step": [8, 0, 0]}},
  {"op": "box", "from": [0, 7, 2], "to": [0, 7, 4], "block": "wall", "repeat": {"count": 2, "step": [8, 0, 0]}},
  {"op": "box", "from": [-1, 5, -1], "to": [9, 5, -1], "block": "roofN", "repeat": {"count": 4, "step": [0, 1, 1]}},
  {"op": "box", "from": [-1, 5, 7], "to": [9, 5, 7], "block": "roofS", "repeat": {"count": 4, "step": [0, 1, -1]}},
  {"op": "box", "from": [-1, 8, 3], "to": [9, 8, 3], "block": "wall"},
  {"op": "box", "from": [-1, 9, 3], "to": [9, 9, 3], "block": "cap"}
]}
```

- The first op tops the long walls with a row of planks so no gap shows under the eaves. The next three fill the
  gable triangles on the west and east walls, one row narrower each layer.
- Each slope is one row with `repeat`: the north slope steps up and south (`step [0,1,1]`), the south slope up and
  north (`step [0,1,-1]`). Both slopes' stairs face the ridge, the higher side: the north slope's stairs face
  south, the south slope's face north.
- Check the reply: `spruce_stairs` with `"facing": {"north": 44, "south": 44}`, 4 rows of 11 per slope. Two equal
  facings is right for a gable. Then `get_preview_blocks` over the roof should show the `facing=south` boxes at z
  below the ridge (z = 3 here, written before the origin is added) and `facing=north` above it.

### Openings (apply)

```json
{"origin": [100, 64, 200], "ops": [
  {"op": "box", "from": [2, 2, 6], "to": [2, 3, 6], "block": "glass_pane", "repeat": {"count": 2, "step": [4, 0, 0]}},
  {"op": "box", "from": [2, 2, 0], "to": [2, 3, 0], "block": "glass_pane", "repeat": {"count": 3, "step": [2, 0, 0]}},
  {"op": "set", "at": [4, 1, 6], "block": "spruce_door[facing=north,half=lower,hinge=left]"},
  {"op": "set", "at": [4, 2, 6], "block": "spruce_door[facing=north,half=upper,hinge=left]"},
  {"op": "set", "at": [4, 0, 7], "block": "spruce_stairs[facing=north,half=bottom]"}
]}
```

- Panes and the door overwrite the wall blocks, so nothing needs carving first.
- A door's `facing` is the way a player faces when placing it from outside: standing south of the south wall, they
  look north. Both halves are set.
- The step faces north, the way you walk to climb it, so the reply now counts 45 north-facing spruce stairs.

## 2. A round stone tower

Brief: "a small watchtower". A cylinder of radius 3 (7 across) on a wider plinth, a cone roof with a 1-block
overhang, arrow slits and a door to the south.

```json
{"origin": [40, 70, -12],
 "palette": {"plinth": "mossy_cobblestone", "wall": "stone_bricks", "roof": "dark_oak_planks"},
 "ops": [
  {"op": "cylinder", "base": [0, 0, 0], "radius": 4, "height": 1, "block": "plinth"},
  {"op": "cylinder", "base": [0, 1, 0], "radius": 3, "height": 12, "block": "wall", "hollow": true},
  {"op": "cylinder", "base": [0, 2, 0], "radius": 2, "height": 10, "block": "air"},
  {"op": "cone", "base": [0, 13, 0], "radius": 4, "height": 6, "block": "roof"},
  {"op": "box", "from": [0, 2, 3], "to": [0, 3, 3], "block": "air"},
  {"op": "set", "at": [[3, 6, 0], [3, 7, 0], [-3, 6, 0], [-3, 7, 0], [0, 6, -3], [0, 7, -3], [0, 9, 3], [0, 10, 3]], "block": "air"}
]}
```

- `hollow` keeps the cylinder's bottom and top layers: y = 1 is the floor and y = 12 the floor of the top room
  under the roof. It also leaves the inside as it was, so the air cylinder clears whatever stood there (ground,
  trees) from y = 2 to 11.
- The cone's `base` is the centre of its lowest layer, so it starts on the layer above the tower's top.
- The door and slits are carved after the walls, since a later op overwrites an earlier one.
- Check the reply: `min` is [36, 70, -16] (the plinth's radius of 4), the size is [9, 19, 9], and the blocks are
  stone bricks, mossy cobblestone and dark oak planks with nothing else. Render `se` and `south`: the door should be
  centred on the south side.
