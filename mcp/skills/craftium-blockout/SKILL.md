---
name: craftium-blockout
description: Block out rooms for world-generated landmarks (dungeons, ruins, towers, temples, village buildings) for mods and add-ons with the Craftium MCP tools. Use when the user asks for a blockout, greybox or whitebox of a room or a set of rooms, or gives a room ruleset. Cubes only, colour-coded placeholder blocks, preview only until they say to build.
---

# Blocking out rooms

A blockout is rough, colour-coded geometry that answers "is this the right size, shape and layout?" before anyone
spends time on detail. Here it is for the rooms of a landmark that a mod or add-on will generate. The syntax and hard
limits live in the `preview_build` tool description. For anything that needs real building (roofs, detail, final
materials), use the `craftium-builder` skill.

## Read these when

- [references/legend.md](references/legend.md): before the first spec. The default placeholder blocks, one per role.
- [references/room-ruleset-template.md](references/room-ruleset-template.md): when the user gives no ruleset, or
  gives one that leaves gaps.
- [references/landmark-sizes.md](references/landmark-sizes.md): when you have to choose a size the ruleset does not.
- [references/examples.md](references/examples.md): before your first spec. One room phase by phase, and a set.

## Scope: one room or a set

- The user describes ONE room: block out that room only. No neighbouring rooms, no set, no extra rooms from the
  template or from a ruleset that lists many.
- Build a set only when they ask for one ("the whole dungeon", "all the rooms in this ruleset", "these four rooms").
- If it is unclear, assume one room, say so in a line, and offer the set as the next step.
- A ruleset that lists many rooms is a reference, not an order. Take the rules for the room asked for.
- Rooms only: how rooms connect is a separate ruleset and is not this skill's job. In a set, place the rooms where
  the user's layout says, or leave a plain gap where a connection will go. Do not invent corridors or doors between
  rooms. A room's own doorways (its entrances) are part of the room.

## Rulesets

The user supplies the room rules: pasted, attached as markdown, or as a file path you read. A ruleset from the user
always wins over this skill's defaults, including over the legend. The template shows the format. If there is none,
say you are using the template's defaults and ask whether to go on with them.

Start by echoing the rules back in a few lines (sizes, legend, constraints) and say which parts you filled in
yourself. Then follow them. If a rule cannot be met, or two rules clash, say so and ask. Do not quietly bend it.

## Preview only

The blockout is a preview until the player says to build it ("build it", "apply it"). Do not call `apply_preview`
before that. Send each phase with `"mode": "add"` so it goes on top of the waiting preview and you send only the new
part; to redo a phase, preview the whole thing again without `mode`. If a player asks you to build everything at
once, still preview first and ask for the yes.

## Cubes only

- Allowed ops: `box`, `set`, `line`, `replace`, `group`, `replace_selection`, and `repeat` on any of them.
- Not allowed: `roof`, `sphere`, `cylinder`, `cone`, `pyramid`, `script`, `hollow` detail, windows, trim.
- Stairs and slabs are allowed only as ramps (a `ramp` role in the legend). Everything else is a full block.
- Write rooms with a solid `box` for the mass and an `air` box to carve the inside. Use the palette role names
  (`wall`, `floor`, `loot`) in the ops, so a change of colour is one palette edit.

## The loop

1. **Brief and rules.** State in a line or two: which room(s), the size, the ruleset used, the legend. Ask only if the
   answer would change the build.
2. **Site.** `get_state`, then `get_surface` where it goes (or `read_selection` if the player selected the spot). Say
   where the room's floor sits relative to the ground.
3. **Footprint.** The floor, the foundation and the entrance marker, nothing else. Render `top`. This is the commit
   point: get a yes on size, shape and position before building up.
4. **Massing.** Walls and ceiling as solid volumes, the inside carved, the doorways opened. Render `se` and `top`.
5. **Function pass.** Place the contents by role: loot, spawners, the boss marker, traps, secrets, the ramp.
6. **Check.** Render two or three angles in one call (`"angles"`). Check with `read_blocks` that the room has the
   inside size the rules ask for and that every entrance is open to head height. Then report.

The report names the legend used (a short table of role and block), the room's size, anything that departs from the
rules, and asks whether to build it, change it, or do the next room.

## Do not

- Do not detail. A blockout with windows and trim is the wrong tool.
- Do not apply without a yes.
- Do not build rooms nobody asked for.
- Do not export or save the structure unless the user asks. The agent builds in the game.
- Do not treat the legend as a standard. It is this skill's default; the user's rules replace it.
