// The tools the server offers: their descriptions, the schemas clients see, and the checks arguments pass before anything
// is sent to the game. Only the keys a tool names are forwarded, so nothing else a client sends reaches the bridge.

import { FULL_COLUMNS, summarizeSurface } from "./surface.js";

export class ArgumentError extends Error {}

const coordinates = "Coordinates are [x, y, z] whole numbers in the WORLD (y is up), the same numbers read_blocks and get_surface use, so read the ground first and build at the heights you found. Nothing has to be selected: the area a build covers is worked out from its operations.";

export const BUILD_SPEC_GUIDE = `A build spec is JSON: {"material": "...", "palette": {...}, "ops": [...], "origin": [x,y,z]}.
${coordinates}
origin (optional): added to every coordinate, so a build can be written near [0,0,0] and placed anywhere.
Blocks (B below): a block id with optional [state=value] properties ("stone" means "minecraft:stone"), a palette name, a material@kind name, or a mix.
  material@kind names a kind of block by its material, spelled for you: "spruce@stairs" is spruce_stairs, "stone_bricks@stairs" is stone_brick_stairs, "stone_bricks@wall" is stone_brick_wall, "spruce@block" is spruce_planks. Kinds: block, planks, stairs, slab, wall, fence, fence_gate, door, trapdoor, button, pressure_plate, log, wood, stripped_log, stripped_wood, leaves, pillar, bricks, tiles, chiseled, cut, smooth, polished, mossy, cracked. A state may follow ("spruce@stairs[facing=east]"). A material without that kind is refused with the ids tried.
  "material" (optional, on the spec or on one op) lets "@kind" alone stand for it: with "material": "spruce", "@slab" is spruce_slab. Swapping the material restyles everything written that way.
  A mix scatters blocks by weight, the same way each time: {"mix": [["stone_bricks", 6], ["mossy_stone_bricks", 3], ["cracked_stone_bricks", 1]], "seed": 0}. Use it for weathered walls, paths and ground. A field that looks for one block ("find", a roof's or stairs' blocks) takes a single block, not a mix.
palette (optional): maps short names to blocks or mixes, e.g. {"wall": {"mix": [["cobblestone", 3], ["mossy_cobblestone", 1]]}, "slab": "minecraft:smooth_sandstone_slab[type=top]"}.
ops run in order; a later op overwrites earlier ones. Use "minecraft:air" to carve.
  box:      {"op":"box","from":[x,y,z],"to":[x,y,z],"block":B,"hollow":false}
  sphere:   {"op":"sphere","center":[x,y,z],"radius":R | "radii":[rx,ry,rz],"block":B,"hollow":false}   (radii makes an ellipsoid)
  cylinder: {"op":"cylinder","base":[x,y,z],"radius":R,"height":H,"axis":"y","block":B,"hollow":false}   (base is the centre of the first layer; axis x|y|z, default y)
  cone:     {"op":"cone","base":[x,y,z],"radius":R,"height":H,"axis":"y","block":B}   (narrows to a point)
  pyramid:  {"op":"pyramid","base":[x,y,z],"radius":R,"height":H,"axis":"y","block":B,"hollow":false}   (square layers, base is (2R+1) wide)
  line:     {"op":"line","from":[x,y,z],"to":[x,y,z],"thickness":1,"block":B}   (thickness 1-5)
  set:      {"op":"set","at":[x,y,z],"block":B}   ("at" may also be a list of positions)
  replace:  {"op":"replace","from":[x,y,z],"to":[x,y,z],"find":B,"block":B}   (only changes blocks that are "find")
  replace_selection: {"op":"replace_selection","find":B,"block":B,"keep":["facing","half","shape"],"selection":"Selection 1"}   (works on the blocks the player has SELECTED, or on one saved selection by its name: see below)
  roof:     {"op":"roof","from":[x,y,z],"to":[x,y,z],"style":"gable","material":"spruce","overhang":1}   (see below)
  stairs:   {"op":"stairs","from":[x,y,z],"to":[x,y,z],"material":"oak" | "block":"oak_stairs","width":1,"fill":B}   (a 45 degree flight: from and to differ by as much in height as along x or z; each step faces the way you climb; width runs toward +x or +z; fill puts a solid column under each step)
  group:    {"op":"group","ops":[...],"rotate":90,"mirror":"x","at":[x,y,z]}   (see below)
  script:   {"op":"script","from":[x,y,z],"to":[x,y,z],"seed":0,"code":"..."}   (Lua that places blocks inside a box: see below)
Any op may add "repeat": {"count": N, "step": [dx,dy,dz]} to place N copies, e.g. a row of columns.
roof covers the footprint from..to (the outer corners of the walls, both at the y of the lowest course of stairs, usually one above the wall top) and builds every stair with the right facing and corner shape. style: gable (two slopes; "ridge": "x" or "z", default along the longer side; the gable ends are filled with the block), hip (four slopes with corner stairs) or shed (one slope; "rises": north, east, south or west). overhang 0-4 (default 1) sticks out past the walls, and the walls are built up to meet it. Blocks come from "material" (its @stairs, @block and @slab) or "stairs", "block" and "slab"; "cap": false leaves the slab off the ridge. A roof rises one block per block in, so a wide footprint makes a tall roof: for a lower one, roof a narrower footprint or use "shed".
group builds its ops (in the same coordinates as the rest) and then turns them in place: "rotate" 0, 90, 180 or 270 clockwise seen from above, "mirror" "x" (east and west swap) or "z" (north and south swap), mirrored first. Stairs, logs, doors and every other block with a facing turn with it. "at" moves the group's lowest corner there. Use it to write a part once (a window bay, a wing, a tower) and place turned or mirrored copies; with "repeat" each copy is placed whole. Groups go at most 4 deep and replace_selection cannot be in one.
replace_selection edits the blocks the player has selected, without you giving a position: use it when they say "change THIS to deepslate" with something selected (call read_selection first to see what it is). "find" is optional (omit it to change every block that is not air; give "air" to fill the air in it). The new block keeps the properties of the old one that both have, so a stair keeps which way it faces and which half it is; "keep" limits that to the names you list, and [] keeps none. It needs a selection (it says so if there is none), never changes the selection itself, can cover up to a million blocks, and needs no other ops. It runs after the shapes, so it must be listed after all of them (a spec that lists it before a shape is refused).
The player can save several selections by name in the Selections window ("Selection 1", "Selection 2" and so on, which they may rename); get_state lists them as savedSelections, with each one's blocks and bounds, and read_selection with "name" reads one the way it reads the selection. When the player refers to one ("on selection 1 do this, then on selection 2 do that"), give replace_selection a "selection": "Selection 1" to work on that one instead of the selection you started with, and give each op its own name: one build can change several, e.g. [{"op":"replace_selection","selection":"Selection 1","block":"stone_bricks"},{"op":"replace_selection","selection":"Selection 2","block":"deepslate_bricks"}]. An op without "selection" works on the selection you started with. A name is not case sensitive. An unknown name refuses the whole build and lists the names there are. To build with shapes on or around a saved selection, read it with read_selection and its name, and use its bounds as coordinates. Saved selections are the player's: you never change them, and the player may edit one while you work, so read it again if it matters.
script runs Lua for what shapes describe badly: natural ground, a tree, rocks, a pattern worked out block by block. Prefer shapes for anything they can make. The code runs once, after every shape: list scripts after the shapes and before replace_selection, never in a group. It calls setBlock(x, y, z, block) with WORLD coordinates inside its box: x0, y0, z0 and x1, y1, z1 are the box's lowest and highest corners (origin already added), and seed is the op's "seed" (default 0). A block is an id ("minecraft:oak_log[axis=y]"; no palette names, materials or mixes) or blocks.oak_log. It can read getBlock(x, y, z) and getBlockState(x, y, z) (what the shapes before it placed, else the world), getHighestBlockYAt(x, z) (the highest block in that column: what your spec has built there if it stands above the ground, else the world's ground), getSimplexNoise(x, y, z, seed) and getVoronoiEdgeNoise(x, y, z, seed) (0 to 1; divide coordinates by 8 to 32 for smooth ground), withBlockProperty(block, "facing=east"), getBlockProperty(block, "facing"), isBlockTagged(block, "#minecraft:logs") and math. math.random follows the seed, so the same spec builds the same. There is no string library and no print. Blocks set outside the box are dropped and the reply warns. Limits: 20,000 characters of code, about 5 million Lua steps for all the scripts in a spec (about half a second: a loop over 64 x 64 columns setting a few blocks each fits), 131k different blocks per script (setting one block again does not count twice). An error names the op and the Lua line. Example, rolling grass over the box's floor: "for x = x0, x1 do for z = z0, z1 do local h = math.floor(getSimplexNoise(x / 16, 0, z / 16, seed) * 4) for y = y0, math.min(y0 + h, y1) do setBlock(x, y, z, y == y0 + h and 'grass_block' or 'dirt') end end end".
"hollow": true keeps only the outer shell (all shapes except line/set/replace). The shell includes the top and bottom layers of boxes and cylinders, and the inside is left as it was, not cleared: add an air op to empty it.
Limits: at most 256 ops (counting those inside groups), 64 entries in the palette, 16 blocks in a mix, 64 repeats, radius 128, a build that spans at most 256 blocks along each side, ~131k blocks in total. The ground a build reaches is loaded for it (at most 36 chunks that were not loaded already), so it can be far from the player.
Orientation-sensitive blocks (stairs, doors, logs) take properties like "minecraft:sandstone_stairs[facing=north,half=bottom]".`;

export const INSTRUCTIONS = `You can build in the player's Minecraft world through the Craftium mod. Builds are shown as a ghost preview in the game and only change the world when applied. You work on the world itself, in world coordinates: you never move, turn or select anything for the player.

Workflow:
1. Call get_state. It reports the player's position and facing, the area they have selected (if any: a hint of where they want a build, which you never change), whether you have a preview waiting, and the world (height limits, sea level, biome). If the game is not reachable, tell the player to open a world and turn on AI building (in the editor: AI menu, then Turn on AI building; or type /craftium ai on).
2. Decide where to build: in the player's selection if they made one (call read_selection to see what it is: its shape, its blocks and the space above it), otherwise near the player or where they asked. Look before you build: get_surface gives the ground of an area (a summary for a wide one) and read_blocks reads blocks; both work anywhere in the world, not just near the player. Say where you chose to build.
3. Call preview_build with a build spec in world coordinates. Its reply says what the spec made (where, how many blocks, of what, and which way stairs face): check it. If the spec is refused, the error names the operation at fault and what to use instead; fix it and try again, and your earlier preview stays waiting meanwhile. A new preview replaces the previous one.
4. Tell the player to look at the ghost in the game. You can look too, without touching the player's view: render_view draws the build's area from an angle you choose, with your waiting preview tinted violet. Render when the look matters (the first rough form, the roof, the finished build), from two angles in one call (angles: ["se", "top"]); for a small change the preview_build reply is usually enough. If previewTruncated is true the game drew only part of the preview, so build in smaller stages to see all of it. Adjust with another preview_build if the result or the player wants changes.
5. Show the player the rough form (size, shape and place) and get a yes before anything is built. After that, a build made in stages applies each stage once it checks out, so the player watches it grow; if they want to see it first ("just show me", "don't build it yet"), keep it as a preview until they say to build. If they asked you to just build it, apply it. undo reverses the last build you applied, never the player's own edits. cancel_preview discards a preview.
Each tool's description gives its limits, and a request that goes past one is refused with what to do instead. Reads and builds stay in the dimension the player is in. Prefer a few well-chosen operations over many small ones; Minecraft builds read best with layered shapes, varied blocks and details like slabs, stairs and walls.

The selection you first see is yours for the whole task. The player may select another place for another Claude session while you work, so get_state and read_selection keep reporting the selection you started with and tell you (playerSelectionChanged) when theirs has moved on. Keep building where you were, and use the new selection only when the player says to, by calling read_selection with refresh true.

Other Claude sessions may be building for the same player at the same time, each with a preview and an undo list of its own: your preview, apply_preview, cancel_preview and undo only ever touch yours. get_state lists the others as otherSessions (label, state, box): keep your build clear of those boxes, and when a preview_build reply has "conflicts" yours overlaps one, so move it or ask the player. Give each preview_build a short "label" (such as "Egyptian pillar") so the player can tell the sessions apart in the game. The player can stop a session: if a tool says the player stopped this session, stop at once, do not retry or build anything else, tell the player what you were doing and wait for them.`;

// ---- argument checks ------------------------------------------------------------------------------------------------

function whole(value, name) {
  if (!Array.isArray(value) || value.length !== 3 || !value.every(Number.isInteger)) throw new ArgumentError(`"${name}" must be [x, y, z] as whole numbers`);
  return value;
}

function plain(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function spec(value) {
  if (!plain(value)) throw new ArgumentError('"spec" must be an object with an "ops" list');
  if (!Array.isArray(value.ops) || value.ops.length === 0 || !value.ops.every(plain)) throw new ArgumentError('"spec.ops" must be a list of operation objects');
  // A palette entry is a block or a mix ({"mix": [...]}); the game checks what is inside.
  if (value.palette !== undefined && (!plain(value.palette) || !Object.values(value.palette).every((v) => typeof v === "string" || plain(v))))
    throw new ArgumentError('"spec.palette" must map names to blocks or mixes');
  if (value.material !== undefined && typeof value.material !== "string") throw new ArgumentError('"spec.material" must be a material such as "spruce"');
  const out = { ops: value.ops };
  if (value.material !== undefined) out.material = value.material;
  if (value.palette !== undefined) out.palette = value.palette;
  if (value.origin !== undefined) out.origin = whole(value.origin, "spec.origin");
  return out;
}

const coordinate = (description) => ({ type: "array", items: { type: "integer" }, minItems: 3, maxItems: 3, description });
const nothing = { type: "object", properties: {} };
const box = { type: "object", properties: { from: coordinate("One corner, [x, y, z] in world coordinates"), to: coordinate("The opposite corner, [x, y, z] in world coordinates") }, required: ["from", "to"] };
const READ = { readOnlyHint: true, openWorldHint: false };
// Saves pictures to the player's disk but never changes the world.
const SAVES_FILES = { readOnlyHint: false, destructiveHint: false, openWorldHint: false };
const CHANGE = { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false };
const DESTRUCTIVE = { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false };

/** Each tool: what clients see, and `args` to check what they sent and pick out what the game is told. */
export const TOOLS = [
  {
    name: "get_state",
    title: "Get Craftium state",
    description: "Where the player is and which way they face, the area they have selected (corners and dimensions only, so call read_selection to see what is in it; theirs, and never changed by you), the selections they saved by name (savedSelections, each with its blocks and bounds; read one with read_selection and its name), how many blocks your waiting preview holds (previewBlocks) and how many of them the game can draw (previewBlocksDrawn; previewTruncated and a previewWarning appear when a big preview is only partly drawn), and how many of your builds can be undone. Other Claude sessions building for the player at the same time are listed as otherSessions, so you can keep clear of them. " +
      "It also gives the world: dimension, the lowest and highest y a block can be at (minY, maxY), sea level and the biome the player stands in, a hint of the style that fits; and the player's current block and recently used blocks (playerBlocks), a hint of their taste. Call this first.",
    inputSchema: nothing,
    annotations: READ,
    args: () => ({}),
  },
  {
    name: "read_selection",
    title: "Read the player's selection",
    description:
      "Reads the area the player has selected, as it really is and not only the box around it: how many blocks, its exact bounds, whether it is a solid box or an irregular shape (a lasso or magic wand can select any shape, such as the top of a platform), whether it is flat, what blocks it is made of, and how much clear space there is above it with whatever is in the way. " +
      'The reply has a one-sentence "summary". Use it when the player says they have selected a place for you to build on, or when get_state shows a selection, so you do not have to hunt for the spot with get_surface. ' +
      "The selection is the player's: you never change it. They may not have one, and they may also just give you coordinates, so it is a help and never a requirement. It works for any selection the editor allows, up to 1,048,576 blocks, and answers in a few lines whatever its size. " +
      "The first selection you see is yours for the whole task: when the player selects something else meanwhile (they may be sending another Claude session to build there), you keep getting the one you started with, and the reply says playerSelectionChanged with where the new one is. " +
      "Switch to the new selection only when the player tells you to (a new request for a new place, \"use my new selection\"), with refresh: true. " +
      "The player can also save selections by name (get_state lists them as savedSelections): give \"name\" to read one of those, as often as you like, for as many as you like; each stays apart and is not affected by refresh or by which selection you started with.",
    inputSchema: { type: "object", properties: { refresh: { type: "boolean", description: "Take the player's selection as it is now as the one you work with from here on. Only when the player says to use their new selection" }, name: { type: "string", description: "Read the selection the player saved under this name in the Selections window (\"Selection 1\"; not case sensitive) instead of their selection. get_state lists the names. Use it whenever the player refers to a selection by name; you can read several, one call each, and they stay apart" } } },
    annotations: READ,
    args: (a) => {
      if (a.refresh !== undefined && typeof a.refresh !== "boolean") throw new ArgumentError('"refresh" must be true or false');
      if (a.name !== undefined) {
        if (typeof a.name !== "string" || !a.name.trim() || a.name.length > 40) throw new ArgumentError('"name" must be the name of a saved selection, such as "Selection 1"');
        if (a.refresh === true) throw new ArgumentError('"refresh" is for the player\'s own selection: leave it out when you give a "name"');
        return { name: a.name.trim() };
      }
      return a.refresh === true ? { refresh: true } : {};
    },
  },
  {
    name: "read_blocks",
    title: "Read blocks",
    // The game's bridge answers every source through its preview read; "preview" is what it calls "only".
    bridge: "get_preview_blocks",
    description:
      "Reads the blocks in a box of the world, with their states (which way each stair faces, a slab's half), anywhere in the world and whether or not the player is near. Use it to see what is on a site before building, and to check your build, which a picture cannot show exactly. " +
      'source: "after" (default) is the world as it will be once your waiting preview is applied, so it is the world itself when no preview is waiting; "world" is the world as it is now, leaving your preview out; "preview" is only what your preview places, with air where it clears a block. ' +
      "The answer is written in the fewest words. format \"boxes\" lists, for each block, the boxes it fills as [x1, y1, z1, x2, y2, z2] (world coordinates, both corners included), so a wall or a roof is a few boxes; " +
      "format \"runs\" is a palette plus run-length runs [paletteIndex, count] (x fastest, then z, then y, from the box's minimum corner), shorter for noisy ground; \"auto\" (default) picks the shorter and says which it chose. Air is left out, and previewBlocks says how big your waiting preview is. " +
      "A box may hold up to 1,048,576 blocks (up to 32,768 for the world on a server whose Craftium is older than this plugin: the reply says so, and gives runs), but the answer is held to about 60 KB, so read a big build a part at a time. Reading ground nobody visited makes it, so one call touches at most 16 chunks that are not already loaded. " +
      'The palette entry "craftium:outside_world" marks positions past the height limits or the world border.',
    inputSchema: {
      type: "object",
      properties: {
        ...box.properties,
        source: { type: "string", enum: ["after", "world", "preview"], description: '"after" (default): the world with your preview applied. "world": the world as it is. "preview": only what your preview places' },
        format: { type: "string", enum: ["auto", "boxes", "runs"], description: '"auto" (default): the shorter of boxes and runs' },
      },
      required: ["from", "to"],
    },
    annotations: READ,
    args: (a) => {
      const views = { after: "after", world: "world", preview: "only" };
      if (a.source !== undefined && !(a.source in views)) throw new ArgumentError('"source" must be "after", "world" or "preview"');
      if (a.format !== undefined && !["auto", "boxes", "runs"].includes(a.format)) throw new ArgumentError('"format" must be "auto", "boxes" or "runs"');
      return { from: whole(a.from, "from"), to: whole(a.to, "to"), ...(a.source !== undefined ? { view: views[a.source] } : {}), ...(a.format !== undefined ? { format: a.format } : {}) };
    },
    tooBig: "Read a smaller box: part of the build at a time, such as one wall or the roof, or a few layers of ground.",
  },
  {
    name: "get_surface",
    title: "Read the ground",
    description:
      "The ground of a rectangle (corners [x, y, z]; y is ignored), up to 128 by 128 columns. Use it to find ground level before building on or into terrain. " +
      "Like read_blocks it reads the server's world anywhere, and reading ground nobody visited makes it, so one call touches at most 16 chunks that are not already loaded (16 chunks cover at most 64 by 64 blocks); if a call is refused for that, read a smaller area or read it in pieces. " +
      'detail "summary" gives the lowest, highest and median height, how much of the area is level with the median, what the ground is made of, and a coarse map of at most 16 by 16 cells: a few hundred words for any size, so start a wide search with it. ' +
      'detail "full" gives every column: heights[i] is the y of the top block and blocks[i] indexes the palette, x fastest then z from the rectangle\'s minimum corner. "auto" (default) is full up to 256 columns (a 16 by 16 area, or a strip up to 128 long) and a summary beyond. ' +
      '"top" (default) is the highest solid or liquid block ignoring leaves, so water counts and trunks and buildings count; "floor" looks under water. ' +
      "In the Nether the surface is the ceiling; use read_blocks to look below it. A column past the world border, or with no block in it, has heights[i] = -2147483648 and blocks[i] = -1. A height of -1 is real ground at y = -1 (as in a superflat world), not an empty column.",
    inputSchema: {
      type: "object",
      properties: {
        ...box.properties,
        kind: { type: "string", enum: ["top", "floor"], description: '"top" (default) or "floor" (under water)' },
        detail: { type: "string", enum: ["auto", "summary", "full"], description: '"auto" (default): every column up to 256 of them, else a summary' },
      },
      required: ["from", "to"],
    },
    annotations: READ,
    args: (a) => {
      if (a.kind !== undefined && a.kind !== "top" && a.kind !== "floor") throw new ArgumentError('"kind" must be "top" or "floor"');
      if (a.detail !== undefined && !["auto", "summary", "full"].includes(a.detail)) throw new ArgumentError('"detail" must be "auto", "summary" or "full"');
      return { from: whole(a.from, "from"), to: whole(a.to, "to"), ...(a.kind !== undefined ? { kind: a.kind } : {}) };
    },
    // The game always sends every column; the summary is made here, so the game needs nothing new for it.
    post: (reply, a) => {
      const detail = a.detail ?? "auto";
      if (!Array.isArray(reply.heights) || !Array.isArray(reply.size)) return reply;
      if (detail === "full" || (detail === "auto" && reply.heights.length <= FULL_COLUMNS)) return reply;
      return summarizeSurface(reply);
    },
    tooBig: 'Ask for detail "summary", or read a smaller area: about 64 by 64 columns fit in full.',
  },
  {
    name: "get_screenshot",
    title: "See the game",
    description:
      "A screenshot of what the player currently sees in Minecraft, including your ghost preview (outlined in violet, its blocks at half opacity) and the game's interface. " +
      "Use it to check your own work after preview_build or apply_preview. It shows only what is in the player's view, so for your own look at a build use render_view, which needs no one's view. " +
      "The reply says if a menu is open (screenOpen), in which case the picture shows the menu and not the world.",
    inputSchema: nothing,
    annotations: READ,
    args: () => ({}),
  },
  {
    name: "render_view",
    title: "Look at the world",
    description:
      "Draws a picture of a box of the world from an angle you choose, without moving or turning the player and wherever the box is. " +
      "The picture is flat-shaded blocks in each block's average colour (no textures), with the box's edges drawn faintly; the player's waiting preview, if there is one, is drawn over the world tinted violet. " +
      "Angles: se, sw, ne, nw look down at the box from that corner (a three-quarter view); top looks straight down with north at the top; north, south, east, west look at that side of the box from level height, which is the way to judge heights and profiles. " +
      'To see a build from more than one side, give "angles" (up to 3, such as ["se", "top"]) instead of "angle": the box is read once and drawn from each, which is faster than separate calls. Each picture costs as much of your context as one call, so ask only for the angles you will use. ' +
      "The reply says which way east and north point in the picture and lists the commonest blocks with the colour each is drawn in, so you can tell materials apart. " +
      "The game draws only part of a very big preview: when that happens the reply has previewTruncated and a previewWarning, and the picture is missing blocks that will still be built, so do not judge the build from it. " +
      "The box can be at most 128 blocks on a side and 262,144 blocks (64 by 64 by 64, for example); it is read from the server in pieces, like read_blocks.",
    inputSchema: {
      type: "object",
      properties: {
        ...box.properties,
        angle: { type: "string", enum: ["se", "sw", "ne", "nw", "top", "north", "south", "east", "west"], description: "Where the camera is (default se)" },
        angles: { type: "array", items: { type: "string", enum: ["se", "sw", "ne", "nw", "top", "north", "south", "east", "west"] }, minItems: 1, maxItems: 3, description: "Up to 3 angles drawn from one read, in place of angle" },
        preview: { type: "boolean", description: "Draw the player's waiting preview over the world (default true)" },
      },
      required: ["from", "to"],
    },
    annotations: READ,
    args: (a) => {
      const angles = ["se", "sw", "ne", "nw", "top", "north", "south", "east", "west"];
      if (a.angle !== undefined && !angles.includes(a.angle)) throw new ArgumentError(`"angle" must be one of ${angles.join(", ")}`);
      if (a.angles !== undefined) {
        if (a.angle !== undefined) throw new ArgumentError('Give "angle" or "angles", not both');
        if (!Array.isArray(a.angles) || a.angles.length < 1 || a.angles.length > 3 || !a.angles.every((x) => angles.includes(x)))
          throw new ArgumentError(`"angles" must list 1 to 3 of ${angles.join(", ")}`);
      }
      if (a.preview !== undefined && typeof a.preview !== "boolean") throw new ArgumentError('"preview" must be true or false');
      return {
        from: whole(a.from, "from"), to: whole(a.to, "to"),
        ...(a.angle !== undefined ? { angle: a.angle } : {}), ...(a.angles !== undefined ? { angles: a.angles } : {}),
        ...(a.preview !== undefined ? { preview: a.preview } : {}),
      };
    },
  },
  {
    name: "capture_batch",
    title: "Take showcase pictures",
    description:
      "Takes real in-game pictures (textures, lighting, sky, with the interface hidden) of one or more rooms or builds, each from as many angles as you list, and saves them to the player's disk. " +
      "The game draws them from its own camera without moving, turning or touching the player, and the job runs in the background: this returns at once, then call capture_status until its state is done. " +
      "Pictures go to screenshots/craftium/<job>/<room>/<room>_<shot>.png in the game folder, beside a manifest.json that records every camera and result; you never choose a path, and an earlier job's folder is never overwritten. " +
      'Give {"job": "castle", "rooms": [{"name": "throne-room", "from": [x,y,z], "to": [x,y,z], "shots": ["hero", "south", {"name": "dais", "target": [x,y,z], "azimuth": 90, "elevation": 10, "distance": 12, "fov": 45}]}]}. ' +
      "from and to are the corners of the room or build to frame (world coordinates, as read_selection and get_surface give). " +
      "A shot is a preset name or an object. Presets: hero (south-east, looking down), three_quarter, front/south, back/north, east, west, se, sw, ne, nw (raised corner views), top (straight down), ground (low, looking up). " +
      'A shot object may set "preset", or aim the camera at a "target" (default: the middle of the room) from an "azimuth" (the side the camera stands on: 0 north, 90 east, 180 south, 270 west), an "elevation" in degrees above level (negative looks up) and a "distance" (default: worked out so the room fills most of the picture). ' +
      'Or place the camera yourself with "position" [x,y,z] and either "look_at" [x,y,z] or "yaw" and "pitch" (yaw 0 faces south, 90 west; pitch positive looks down). ' +
      '"fov" is the lens in degrees, 30 to 110 (default 70); "width" and "height" default to 1920 by 1080 and go up to 3840 by 2160 (4K); "hide_hud" (default true) hides the interface. ' +
      'Any of fov, width, height and hide_hud can go on a shot, on a room, or in "defaults" for the whole job; the shot wins, then the room, then the defaults. There is no camera roll. ' +
      "At most 50 rooms and 100 pictures per job. Names become lowercase file names; two rooms, or two shots of one room, cannot share a name. " +
      'Set "resume": true with the same job name to carry on an earlier job, skipping pictures already saved. ' +
      "The game only holds the chunks near the player, so a room outside them fails with a message saying so (the other pictures still go ahead): ask the player to stand near it. The player's own screen must have no menu open. " +
      "The reply is the job's status; the pictures themselves are files, not images in this reply.",
    inputSchema: {
      type: "object",
      properties: {
        job: { type: "string", maxLength: 64, description: 'A name for this set of pictures, such as "castle" (default: showcase-<date and time>)' },
        rooms: {
          type: "array", minItems: 1, maxItems: 50,
          description: "The rooms or builds to photograph, each with its box and its shots",
          items: {
            type: "object",
            properties: {
              name: { type: "string", maxLength: 64, description: "The room's name, used in the file names" },
              from: coordinate("One corner of the room or build, [x, y, z]"),
              to: coordinate("The opposite corner, [x, y, z]"),
              shots: { type: "array", minItems: 1, items: { anyOf: [{ type: "string" }, { type: "object" }] }, description: 'Presets such as "hero", or shot objects (see above)' },
            },
            required: ["name", "from", "to", "shots"],
          },
        },
        defaults: { type: "object", description: "fov, width, height and hide_hud for every shot that does not set its own" },
        resume: { type: "boolean", description: "Carry on the job of this name, skipping pictures already saved" },
      },
      required: ["rooms"],
    },
    annotations: SAVES_FILES,
    args: (a) => {
      if (!Array.isArray(a.rooms) || a.rooms.length < 1) throw new ArgumentError('"rooms" must list at least one room');
      if (a.rooms.length > 50) throw new ArgumentError(`A job can have at most 50 rooms; this has ${a.rooms.length}`);
      a.rooms.forEach((room, i) => {
        if (room === null || typeof room !== "object" || Array.isArray(room)) throw new ArgumentError(`rooms[${i}] must be an object`);
        if (typeof room.name !== "string" || room.name.trim() === "") throw new ArgumentError(`rooms[${i}] needs a "name"`);
        whole(room.from, `rooms[${i}].from`);
        whole(room.to, `rooms[${i}].to`);
        if (!Array.isArray(room.shots) || room.shots.length < 1) throw new ArgumentError(`rooms[${i}] needs "shots": a list of presets or shot objects`);
        if (!room.shots.every((x) => typeof x === "string" || (x !== null && typeof x === "object" && !Array.isArray(x)))) throw new ArgumentError(`rooms[${i}].shots must hold preset names or shot objects`);
      });
      if (a.job !== undefined && (typeof a.job !== "string" || a.job.trim() === "")) throw new ArgumentError('"job" must be a name');
      if (a.defaults !== undefined && (a.defaults === null || typeof a.defaults !== "object" || Array.isArray(a.defaults))) throw new ArgumentError('"defaults" must be an object');
      if (a.resume !== undefined && typeof a.resume !== "boolean") throw new ArgumentError('"resume" must be true or false');
      if (a.resume === true && a.job === undefined) throw new ArgumentError('"resume" needs the "job" name of the job to carry on');
      return {
        rooms: a.rooms,
        ...(a.job !== undefined ? { job: a.job } : {}),
        ...(a.defaults !== undefined ? { defaults: a.defaults } : {}),
        ...(a.resume !== undefined ? { resume: a.resume } : {}),
      };
    },
  },
  {
    name: "capture_status",
    title: "Check the showcase job",
    description:
      "How the running showcase job, or else the last one, is going: its state (running, done, cancelled or failed), how many pictures are done out of the total, how many failed, the folder and manifest.json on the player's disk, and for each picture so far its file, the camera it asked for and the one the game drew from, and why it failed if it did. " +
      "A picture with chunksReady false was taken before every chunk in view was drawn and may have gaps; retake it with another capture_batch if it matters. Poll every few seconds; a picture takes about a second, a little more at 4K. " +
      "Before each picture the game checks for terrain, trees or another build between the camera and the room, and retakes it once from higher up if so: each picture's occlusion says how many of 9 sample rays were blocked, and whether it was raised; occlusion.hidden means it is still hidden, so choose another angle or distance. " +
      'Give "contact_sheet" with a room\'s name to get ONE image holding all of that room\'s saved pictures as labelled thumbnails (also saved as contact-sheet.jpg in the room\'s folder): look at it to judge every angle at once instead of opening each file, then retake only the ones that are wrong. ' +
      "Take the sheet once the room's pictures are done.",
    inputSchema: {
      type: "object",
      properties: { contact_sheet: { type: "string", maxLength: 64, description: "A room's name: returns a contact sheet image of that room's saved pictures instead of the status" } },
    },
    annotations: READ,
    args: (a) => {
      if (a.contact_sheet === undefined) return {};
      if (typeof a.contact_sheet !== "string" || a.contact_sheet.trim() === "" || a.contact_sheet.length > 64) throw new ArgumentError('"contact_sheet" must be a room name');
      return { contact_sheet: a.contact_sheet };
    },
  },
  {
    name: "capture_cancel",
    title: "Stop the showcase job",
    description: 'Stops the running showcase job after the picture being saved, and gives the player their view back. Pictures already saved stay on disk and in manifest.json; carry on later with capture_batch and "resume": true.',
    inputSchema: nothing,
    annotations: SAVES_FILES,
    args: () => ({}),
  },
  {
    name: "preview_build",
    title: "Preview a build",
    description:
      "Shows a build as a ghost preview at the world coordinates its spec names, without changing the world, replacing any earlier preview (or adding to it, with mode add). Nothing needs to be selected, and the build can be anywhere in the world the player's dimension reaches. " +
      'The reply\'s "preview" says what the spec made: blocks placed and cleared, the box they fill (min, max, size), the chunks it reaches, and the commonest blocks with how the ones that face a way are turned (a gable roof has two facings in equal numbers). Check it first: it often shows a mistake without a render. ' +
      'Its "warnings", when present, name what will look broken or go missing: a door or bed without its other half, a torch, lantern, button, ladder or sign with nothing to hold it (the game drops it), or an operation whose blocks later operations replace entirely. Fix those before applying. ' +
      "If the spec is refused, the error names what to fix and your earlier preview stays waiting (previewKept). " +
      'With "mode": "add" the spec goes on top of the waiting preview instead of replacing it, so a detail pass sends only the new operations: its replace and find see the preview\'s blocks, the reply\'s "preview" describes only what was added ("takenBack" counts preview blocks it removed, such as a doorway carved with air through a preview wall in open air), and the two are applied and undone as one build. ' +
      "The game draws only part of a very big preview. The reply's state says so (previewTruncated, previewWarning): the rest is still built when you apply it, so build in smaller stages if you need to see all of it. Describe the build as a spec of primitives.\n\n" + BUILD_SPEC_GUIDE,
    inputSchema: {
      type: "object",
      properties: {
        spec: {
          type: "object",
          description: "The build spec",
          properties: {
            material: { type: "string", description: 'The build\'s material, for blocks written "@kind" (such as "@stairs"); optional' },
            palette: { type: "object", additionalProperties: { anyOf: [{ type: "string" }, { type: "object" }] }, description: "Short names for blocks or mixes" },
            origin: coordinate("Added to every coordinate in the spec, [x, y, z]; optional"),
            ops: { type: "array", minItems: 1, items: { type: "object" }, description: "Operations, run in order, in world coordinates" },
          },
          required: ["ops"],
        },
        mode: { type: "string", enum: ["replace", "add"], description: '"replace" (default): this spec is the whole preview. "add": its blocks go on top of the waiting preview' },
        label: { type: "string", maxLength: 60, description: 'A short name for this build, such as "Egyptian pillar": the player sees it on the preview and in the list of AI sessions, so they can tell sessions apart. Optional; the player may rename it' },
      },
      required: ["spec"],
    },
    annotations: CHANGE,
    args: (a) => {
      if (a.mode !== undefined && a.mode !== "replace" && a.mode !== "add") throw new ArgumentError('"mode" must be "replace" or "add"');
      if (a.label !== undefined && typeof a.label !== "string") throw new ArgumentError('"label" must be a short text such as "Egyptian pillar"');
      return { spec: spec(a.spec), ...(a.mode !== undefined ? { mode: a.mode } : {}), ...(a.label !== undefined && a.label.trim() ? { label: a.label.trim().slice(0, 60) } : {}) };
    },
  },
  {
    name: "apply_preview",
    title: "Apply the preview",
    description: "Builds your previewed blocks in the world as one undoable step, loading the ground if no one is near it. Do this only once the player has seen the preview and is happy with it.",
    inputSchema: nothing,
    annotations: DESTRUCTIVE,
    args: () => ({}),
  },
  {
    name: "cancel_preview",
    title: "Cancel the preview",
    description: "Discards the preview. The world is not changed.",
    inputSchema: nothing,
    annotations: CHANGE,
    args: () => ({}),
  },
  {
    name: "undo",
    title: "Undo your last build",
    description: "Undoes the last build you applied. Only your own builds are on this list: the player's edits are never undone by it.",
    inputSchema: nothing,
    annotations: DESTRUCTIVE,
    args: () => ({}),
  },
];
