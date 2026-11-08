# Worked examples

Two blockouts written out, with what to check in each `preview_build` reply. Copy the method, not the numbers: sizes,
roles and colours come from the user's ruleset or the brief.

Both are written near [0,0,0] and placed with `origin`. `y = 0` is the first layer above the ground, so if
`get_surface` says the ground top is at y = 63 where the room goes, the origin's y is 64. The foundation goes below
`y = 0` and shows how the room meets the ground.

## 1. One room: a crypt burial chamber

Brief: "a burial chamber for my crypt, 9 by 7 inside, entrance on the south wall, two chests at the back, a
spawner". The player described one room, so this is one room. Inside 9 wide (x), 7 deep (z), 4 high. The entrance is
3 wide and 3 high in the middle of the south wall (z = 0). Chests are against the north wall, away from the centre.

All three phases are previews. Send the first one plain, the next two with `"mode": "add"`.

### Footprint (preview, get a yes)

```json
{"origin": [100, 64, 200],
 "palette": {"foundation": "brown_concrete", "wall": "light_gray_concrete", "floor": "gray_concrete", "entrance": "orange_concrete"},
 "ops": [
  {"op": "box", "from": [0, -2, 0], "to": [10, -1, 8], "block": "foundation"},
  {"op": "box", "from": [0, 0, 0], "to": [10, 0, 8], "block": "wall"},
  {"op": "box", "from": [1, 0, 1], "to": [9, 0, 7], "block": "floor"},
  {"op": "box", "from": [4, 0, 0], "to": [6, 0, 0], "block": "entrance"}
]}
```

Check: the reply's `min` is [100, 62, 200] and `size` is [11, 3, 9]. Render `top` and show the player: the grey
floor with its wall ring, the orange threshold on the south edge. Ask for a yes on size, shape and position.

### Massing (preview, mode "add")

```json
{"origin": [100, 64, 200],
 "palette": {"wall": "light_gray_concrete"},
 "ops": [
  {"op": "box", "from": [0, 1, 0], "to": [10, 5, 8], "block": "wall"},
  {"op": "box", "from": [1, 1, 1], "to": [9, 4, 7], "block": "air"},
  {"op": "box", "from": [4, 1, 0], "to": [6, 3, 0], "block": "air"}
]}
```

Check: the reply describes the added part: `min` [100, 65, 200], `size` [11, 5, 9], 234 wall blocks. The inside is
9 by 7 by 4 and the doorway is 3 wide and 3 high. Render `se` and `top`.

### Function pass (preview, mode "add")

```json
{"origin": [100, 64, 200],
 "palette": {"loot": "yellow_concrete", "spawner": "red_concrete"},
 "ops": [
  {"op": "set", "at": [[2, 1, 7], [8, 1, 7]], "block": "loot"},
  {"op": "set", "at": [5, 1, 4], "block": "spawner"}
]}
```

Check: two yellow and one red block, none in the middle of the north wall. Render two or three angles in one call,
check with `read_blocks`, then report the legend (a table of role and block), the inside size, and ask whether to
build it, change it or do another room. Do not apply without the yes.

## 2. A set: a guard room and a burial chamber

Brief: "the guard room and the burial chamber from my ruleset, side by side". The player asked for these two rooms,
so build both and nothing else. How they connect is another ruleset, so a 3-block gap is left between them with no
corridor. Each room has its own entrance on the wall facing the gap.

The guard room is 5 by 5 inside, the burial chamber 9 by 7, both 4 high. This sample leaves out the secret space from
the template, to keep it short.

```json
{"origin": [100, 64, 200],
 "palette": {"foundation": "brown_concrete", "wall": "light_gray_concrete", "floor": "gray_concrete",
             "entrance": "orange_concrete", "loot": "yellow_concrete", "spawner": "red_concrete"},
 "ops": [
  {"op": "box", "from": [0, -2, 0], "to": [6, -1, 6], "block": "foundation"},
  {"op": "box", "from": [10, -2, 0], "to": [20, -1, 8], "block": "foundation"},
  {"op": "box", "from": [0, 0, 0], "to": [6, 5, 6], "block": "wall"},
  {"op": "box", "from": [10, 0, 0], "to": [20, 5, 8], "block": "wall"},
  {"op": "box", "from": [1, 1, 1], "to": [5, 4, 5], "block": "air"},
  {"op": "box", "from": [11, 1, 1], "to": [19, 4, 7], "block": "air"},
  {"op": "box", "from": [1, 0, 1], "to": [5, 0, 5], "block": "floor"},
  {"op": "box", "from": [11, 0, 1], "to": [19, 0, 7], "block": "floor"},
  {"op": "box", "from": [6, 1, 2], "to": [6, 3, 4], "block": "air"},
  {"op": "box", "from": [10, 1, 3], "to": [10, 3, 5], "block": "air"},
  {"op": "box", "from": [6, 0, 2], "to": [6, 0, 4], "block": "entrance"},
  {"op": "box", "from": [10, 0, 3], "to": [10, 0, 5], "block": "entrance"},
  {"op": "set", "at": [3, 1, 3], "block": "spawner"},
  {"op": "set", "at": [[1, 1, 2], [1, 1, 4]], "block": "loot"},
  {"op": "set", "at": [[19, 1, 1], [19, 1, 4], [19, 1, 7]], "block": "loot"}
]}
```

Check: `min` [100, 62, 200] and `size` [21, 8, 9]; the gap from x = 7 to 9 stays empty. In a real run this is three
phases like the first example (footprint, massing, function), each previewed. Here the whole set is one spec to
show the layout.
