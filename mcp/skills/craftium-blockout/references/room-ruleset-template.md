# Room ruleset template

A ruleset is a markdown file the user writes (or pastes, or attaches). It says what the rooms are and what is in
them. This is a sample to copy. The agent uses the user's ruleset when there is one, and this template's values only
when there is none.

How the rooms connect is a different ruleset. Do not put connection rules here.

````markdown
---
name: crypt
type: dungeon
---

## Measurements
Sizes are the inside of the room, in blocks.
- Small room: 5 to 7 wide, 5 to 7 deep, 4 high
- Large room: 9 to 13 wide, 9 to 13 deep, 5 high
- Entrances: 3 wide, 3 high

## Legend
Same as the default legend, except:
- spawner: red_concrete
- boss: purple_concrete

## Rooms
### Guard room (small)
- 1 entrance
- 1 spawner in the middle
- 2 loot markers against the far wall

### Burial chamber (large)
- 1 entrance on the shortest wall
- 3 loot markers
- 1 secret space behind a wall, 1 wide, 1 deep

### Boss room (large)
- 1 entrance
- 1 boss marker covering the middle 3 by 3
- No loot

## Rules
- Every room has a floor, a ceiling and four walls; nothing else is built.
- No windows.
- Loot is never in the centre of a room.
````

## Notes for the agent

- The front matter is a label. Read the sections that follow, not the keys.
- A missing section means "no preference": use the default and say you did.
- Sizes given as a range are the room's limits, not a request for the biggest: pick one and say which.
- A rule you cannot meet is reported to the user, not quietly bent.
