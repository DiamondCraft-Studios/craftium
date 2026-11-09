---
name: craftium-showcase
description: Take showcase screenshots of builds in the player's Minecraft world with the Craftium MCP tools (capture_batch, capture_status, capture_cancel). Use when the user asks for screenshots, hero shots, gallery or README pictures, a photo set of rooms or buildings, or "show off" a build from several angles. Picks angles, lens and framing from what the user says, takes the pictures, looks at a contact sheet and retakes the bad ones. Not for checking a build's shape while building (use render_view).
---

# Showcase pictures

`capture_batch` has the game take real pictures (textures, lighting, sky) from a camera of its own and save them to the
player's disk. You never move, turn or touch the player. The tool descriptions hold the syntax and limits; this skill is
the way of working: what to ask, which shots to pick, and how to judge the result before saying it is done.

For the shape of a build while you are building it, `render_view` is cheaper. Use this skill when the pictures are the
product. To build something first, use `craftium-builder`.

- [references/shots.md](references/shots.md): before you pick shots. Shot lists by subject, lens and height guides,
  interiors, and example batches.

## The player's numbers win

The player may give any of: where (rooms, boxes, coordinates), angles, field of view, size, distance, a list of shots.
Use exactly what they give. Fill only the gaps, from the defaults below and `references/shots.md`, and say in one line
what you filled in. The ranges in this skill are usual starting points, not rules: adapt them to the build.

## The loop

1. **Brief.** State in a line or two: which builds or rooms, how many pictures, the angles and lens, the size (1920 by
   1080 unless they say more; 3840 by 2160 is the most). Ask only when the answer would change the pictures, for
   example "outside the castle, or the rooms inside?". Pictures are files on their disk and never change the world, so
   a clear request needs no confirmation before you shoot.
2. **Site.** `get_state` for where the player is, then find each build's box: `read_selection` if they selected it,
   otherwise `get_surface` and `read_blocks` or `render_view` (top) to find its real corners, roof and all. If
   `get_state` lists `savedSelections`, the player has marked their builds for you: "photograph my selections" means one
   room per saved selection, each given as `"selection": "<name>"` instead of corners (its name is the room's name). A box that is
   too big frames the build too small; too small crops it. Say the boxes you chose.
3. **Range.** The game only holds the chunks near the player, so a build far from them cannot be photographed
   (its pictures fail with "not loaded"). Group builds that are close together into one job, and for a far one tell the
   player to stand near it and run it as its own job. Never try to work around this by moving anything.
4. **Pick the shots** (see the reference). Exterior: a hero shot first, then the sides and a top view as needed.
   Interior: a placed camera inside, because a camera outside only sees walls. Name shots for what they show
   (`hero`, `gate`, `dais`).
5. **Take them.** One `capture_batch` per cluster of rooms. It returns at once; poll `capture_status` every few
   seconds until `state` is `done`. Tell the player the game is taking pictures and to leave it alone with no menu
   open until it ends; if they need the game back, `capture_cancel` and carry on later with `resume`.
6. **Check the status.** Failed pictures (read each error), `chunksReady: false` (taken before the chunks were drawn,
   may have gaps) and `occlusion.hidden` (terrain, trees or another build still in the way after one raise).
7. **Look.** Call `capture_status` with `contact_sheet` for each room and judge every angle on it (checklist below).
   Do not say a picture is good without having looked at it.
8. **Retake only the bad ones**, with a changed angle, distance or lens, as a new job (or new shot names): a job with
   `resume` skips pictures that already exist. At most two rounds, then report what is still weak and why.
9. **Report.** The folder, the files, which shots you retook, and anything not right (a hidden angle, a far room).
   Do not paste base64 or describe every file; the player opens the folder.

## Judging a contact sheet

- **Framing:** is the whole build in the picture, with a little air around it? Cropped edges or a tiny build in a lot
  of ground and sky both mean change the distance, the box, or the lens.
- **Blocked view:** a tree, hill or neighbouring build in front. Raise the elevation, move the azimuth, or aim at a
  nearer target.
- **Readability:** dark faces with no detail, or a flat head-on wall with no depth. Turn the camera so two sides show
  (the corner views and `hero` do), and prefer a light side to a dark one.
- **Clutter:** foliage, water or terrain crossing the subject. Move the camera, not the world.
- **Variety:** a set where every shot is the same height and distance is a bad set. Mix a wide establishing shot, a
  corner view, and a close detail.
- **Sameness across rooms:** keep the same lens and elevation family for a set meant to sit together.

## Limits and honest edges

- No camera roll, no time of day or weather control. If they want golden hour, rain or night, ask them to set it in the
  game before the job; do not promise it.
- The pictures are the game's own render. Shader packs or mods that change rendering may change how they look.
- Never change the player's world to improve a shot (clearing trees, moving blocks) without asking first.
- Interior lighting is whatever the room has. A dark room gives a dark picture: say so and suggest light before retaking.
- 4K is for final pictures; a 100-shot job at 4K is a lot of disk. Prefer 1080p for drafts and a retake at 4K.
