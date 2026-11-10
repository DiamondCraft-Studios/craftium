# Choosing shots

Everything here is a starting point. The player's own angles, lens and distances override it.

## How the camera is described

- **Presets** aim at the middle of the room's box and pick the distance so the whole box fits: `hero` (south-east, 28
  degrees up), `three_quarter` (south-west, 22 up), `front`/`south`, `back`/`north`, `east`, `west` (level, a little
  above), `se`, `sw`, `ne`, `nw` (raised corners, 30 up), `top` (straight down), `ground` (low, looking up).
- **Azimuth** is the side the camera stands on: 0 north, 90 east, 180 south, 270 west. Elevation is degrees above level
  (negative looks up). Distance is optional; leave it out and the game frames the box.
- **A placed camera** is `position` plus `look_at`, or `position` plus `yaw` and `pitch`. Yaw 0 faces south, 90 faces
  west; positive pitch looks down. The position is the camera's eye, in blocks (a player's eyes are 1.62 above their
  feet).
- Lens: `fov` 30 to 110, default 70. No roll.

## Lens and height guide (usual ranges)

| Look | fov | elevation | Notes |
| --- | --- | --- | --- |
| Hero of a building | 55 to 70 | 20 to 35 | Two faces visible, ground in front, a little sky |
| Flat, even documentation | 60 | 5 to 10 | `south`, `east`... one face, true proportions |
| Close detail | 35 to 50 | 0 to 15 | Give `target` and a small `distance` |
| Tall tower or spire | 60 to 75 | 5 to 20 | A low camera makes it tower; watch the box height |
| Wide landscape or village | 70 to 90 | 25 to 45 | Raise the camera so buildings do not hide each other |
| Interior room | 80 to 95 | pitch 0 to 10 | Placed camera; wide lens or the room feels small |
| Plan view | 60 | `top` | North is up; good for layouts |

Vanilla shades faces differently (tops lightest, then north and south, then east and west), so a corner view shows two
different tones and reads as solid, where a head-on wall looks flat. Prefer it for heroes.

## Shot lists by subject

- **A single building outside:** `hero`, `three_quarter`, `south` (or the face with the door), `top` if the layout
  matters, and one `ground` for scale on a tall one. 3 to 5 shots.
- **A tower or monument:** `hero`, `ground`, a close `detail` of the top (`target` at the crown, small `distance`,
  `fov` 45), `top`.
- **Rooms inside a landmark:** per room, one placed-camera shot from a doorway or corner at eye height looking at the
  room's focal point (altar, stairs, treasure), plus one from the opposite corner. 2 shots each.
- **A village or large area:** one or two wide raised shots of the whole box (`fov` 80, elevation 40), then a hero per
  important building as its own room.
- **A set meant to sit together (gallery, README):** same lens and elevation for every hero, one hero per build.

## Interior camera

A camera outside a closed room sees its walls. Put it inside, at the entrance or in a corner:

1. Take the room's floor y, then eye height = floor + 1.6 (add a block or two for a high camera).
2. Stand it at a doorway or corner, well back from the focal point, and `look_at` that point.
3. `fov` 80 to 95; `pitch` comes out of `look_at`.
4. The room must be lit. Check with `read_blocks` that light sources exist, or ask the player.

```json
{"name": "altar", "position": [102.5, 71.6, 40.5], "look_at": [110.5, 70.0, 52.5], "fov": 85}
```

## Example batches

A castle: exterior hero set plus two interior rooms, 1080p, one job. (`job` names the folder; room names are the file
names.)

```json
{
  "job": "castle",
  "defaults": {"fov": 65},
  "rooms": [
    {"name": "castle", "from": [90, 64, 30], "to": [130, 96, 70],
     "shots": ["hero", "three_quarter", "south", "top",
               {"name": "crown", "target": [110, 94, 50], "azimuth": 150, "elevation": 12, "distance": 14, "fov": 45}]},
    {"name": "throne-room", "from": [100, 70, 40], "to": [120, 82, 60],
     "shots": [{"name": "from-door", "position": [100.5, 71.6, 50.5], "look_at": [118.5, 72.0, 50.5], "fov": 88},
               {"name": "from-dais", "position": [118.5, 72.6, 50.5], "look_at": [102.5, 71.5, 50.5], "fov": 88}]}
  ]
}
```

Photographing the player's BuildObjects (`get_state` lists them as `savedSelections`): one room each, by name, no
corners needed. An irregular selection is framed by its bounding box and the blocks inside it.

```json
{"job": "my-builds", "rooms": [
  {"selection": "BuildObject", "shots": ["hero", "south"]},
  {"selection": "BuildObject (1)", "name": "windmill", "shots": ["hero", "top"]}
]}
```

A retake of one weak shot, after the sheet showed a tree in front of the south view:

```json
{"job": "castle-retake", "rooms": [{"name": "castle", "from": [90, 64, 30], "to": [130, 96, 70],
  "shots": [{"name": "south-high", "preset": "south", "elevation": 28}]}]}
```

A 4K final of just the hero:

```json
{"job": "castle-4k", "defaults": {"width": 3840, "height": 2160},
 "rooms": [{"name": "castle", "from": [90, 64, 30], "to": [130, 96, 70], "shots": ["hero"]}]}
```
