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
  replace_selection: {"op":"replace_selection","find":B,"block":B,"keep":["facing","half","shape"]}   (works on the blocks the player has SELECTED: see below)
  roof:     {"op":"roof","from":[x,y,z],"to":[x,y,z],"style":"gable","material":"spruce","overhang":1}   (see below)
  stairs:   {"op":"stairs","from":[x,y,z],"to":[x,y,z],"material":"oak" | "block":"oak_stairs","width":1,"fill":B}   (a 45 degree flight: from and to differ by as much in height as along x or z; each step faces the way you climb; width runs toward +x or +z; fill puts a solid column under each step)
  group:    {"op":"group","ops":[...],"rotate":90,"mirror":"x","at":[x,y,z]}   (see below)
Any op may add "repeat": {"count": N, "step": [dx,dy,dz]} to place N copies, e.g. a row of columns.
roof covers the footprint from..to (the outer corners of the walls, both at the y of the lowest course of stairs, usually one above the wall top) and builds every stair with the right facing and corner shape. style: gable (two slopes; "ridge": "x" or "z", default along the longer side; the gable ends are filled with the block), hip (four slopes with corner stairs) or shed (one slope; "rises": north, east, south or west). overhang 0-4 (default 1) sticks out past the walls, and the walls are built up to meet it. Blocks come from "material" (its @stairs, @block and @slab) or "stairs", "block" and "slab"; "cap": false leaves the slab off the ridge. A roof rises one block per block in, so a wide footprint makes a tall roof: for a lower one, roof a narrower footprint or use "shed".
group builds its ops (in the same coordinates as the rest) and then turns them in place: "rotate" 0, 90, 180 or 270 clockwise seen from above, "mirror" "x" (east and west swap) or "z" (north and south swap), mirrored first. Stairs, logs, doors and every other block with a facing turn with it. "at" moves the group's lowest corner there. Use it to write a part once (a window bay, a wing, a tower) and place turned or mirrored copies; with "repeat" each copy is placed whole. Groups go at most 4 deep and replace_selection cannot be in one.
replace_selection edits the blocks the player has selected, without you giving a position: use it when they say "change THIS to deepslate" with something selected (call read_selection first to see what it is). "find" is optional (omit it to change every block that is not air; give "air" to fill the air in it). The new block keeps the properties of the old one that both have, so a stair keeps which way it faces and which half it is; "keep" limits that to the names you list, and [] keeps none. It needs a selection (it says so if there is none), never changes the selection itself, can cover up to a million blocks, and needs no other ops. It runs after the shapes, so it must be listed after all of them (a spec that lists it before a shape is refused).
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
Each tool's description gives its limits, and a request that goes past one is refused with what to do instead. Reads and builds stay in the dimension the player is in. Prefer a few well-chosen operations over many small ones; Minecraft builds read best with layered shapes, varied blocks and details like slabs, stairs and walls.`;

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
const CHANGE = { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false };
const DESTRUCTIVE = { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false };

/** Each tool: what clients see, and `args` to check what they sent and pick out what the game is told. */
export const TOOLS = [
  {
    name: "get_state",
    title: "Get Craftium state",
    description: "Where the player is and which way they face, the area they have selected (corners and dimensions only, so call read_selection to see what is in it; theirs, and never changed by you), how many blocks your waiting preview holds (previewBlocks) and how many of them the game can draw (previewBlocksDrawn; previewTruncated and a previewWarning appear when a big preview is only partly drawn), and how many of your builds can be undone. " +
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
      "The selection is the player's: you never change it. They may not have one, and they may also just give you coordinates, so it is a help and never a requirement. It works for any selection the editor allows, up to 1,048,576 blocks, and answers in a few lines whatever its size.",
    inputSchema: nothing,
    annotations: READ,
    args: () => ({}),
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
      "A box may hold up to 1,048,576 blocks, but the answer is held to about 60 KB, so read a big build a part at a time. Reading ground nobody visited makes it, so one call touches at most 16 chunks that are not already loaded. " +
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
    name: "preview_build",
    title: "Preview a build",
    description:
      "Shows a build as a ghost preview at the world coordinates its spec names, without changing the world, replacing any earlier preview (or adding to it, with mode add). Nothing needs to be selected, and the build can be anywhere in the world the player's dimension reaches. " +
      'The reply\'s "preview" says what the spec made: blocks placed and cleared, the box they fill (min, max, size), the chunks it reaches, and the commonest blocks with how the ones that face a way are turned (a gable roof has two facings in equal numbers). Check it first: it often shows a mistake without a render. ' +
      'Its "warnings", when present, name what will look broken or go missing: a door or bed without its other half, a torch, lantern, button, ladder or sign with nothing to hold it (the game drops it), or an operation whose blocks later operations replace entirely. Fix those before applying. ' +
      "If the spec is refused, the error names what to fix and your earlier preview stays waiting (previewKept). " +
      'With "mode": "add" the spec goes on top of the waiting preview instead of replacing it, so a detail pass sends only the new operations: its replace and find see the preview\'s blocks, the reply\'s "preview" describes only what was added, and the two are applied and undone as one build. ' +
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
      },
      required: ["spec"],
    },
    annotations: CHANGE,
    args: (a) => {
      if (a.mode !== undefined && a.mode !== "replace" && a.mode !== "add") throw new ArgumentError('"mode" must be "replace" or "add"');
      return { spec: spec(a.spec), ...(a.mode !== undefined ? { mode: a.mode } : {}) };
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
