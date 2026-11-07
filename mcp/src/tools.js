// The tools the server offers: their descriptions, the schemas clients see, and the checks arguments pass before anything
// is sent to the game. Only the keys a tool names are forwarded, so nothing else a client sends reaches the bridge.

export class ArgumentError extends Error {}

const coordinates = "Coordinates are [x, y, z] whole numbers in the WORLD (y is up), the same numbers get_blocks and get_surface use, so read the ground first and build at the heights you found. Nothing has to be selected: the area a build covers is worked out from its operations.";

export const BUILD_SPEC_GUIDE = `A build spec is JSON: {"palette": {...}, "ops": [...], "origin": [x,y,z]}.
${coordinates}
origin (optional): added to every coordinate, so a build can be written near [0,0,0] and placed anywhere.
palette (optional): maps short names to block ids, e.g. {"sand": "minecraft:cut_sandstone", "slab": "minecraft:smooth_sandstone_slab[type=top]"}. Any block field may use a palette name or a block id with optional [state=value] properties. A bare id like "stone" means "minecraft:stone".
ops run in order; a later op overwrites earlier ones. Use "minecraft:air" to carve.
  box:      {"op":"box","from":[x,y,z],"to":[x,y,z],"block":B,"hollow":false}
  sphere:   {"op":"sphere","center":[x,y,z],"radius":R | "radii":[rx,ry,rz],"block":B,"hollow":false}   (radii makes an ellipsoid)
  cylinder: {"op":"cylinder","base":[x,y,z],"radius":R,"height":H,"axis":"y","block":B,"hollow":false}   (base is the centre of the first layer; axis x|y|z, default y)
  cone:     {"op":"cone","base":[x,y,z],"radius":R,"height":H,"axis":"y","block":B}   (narrows to a point)
  pyramid:  {"op":"pyramid","base":[x,y,z],"radius":R,"height":H,"axis":"y","block":B,"hollow":false}   (square layers, base is (2R+1) wide)
  line:     {"op":"line","from":[x,y,z],"to":[x,y,z],"thickness":1,"block":B}   (thickness 1-5)
  set:      {"op":"set","at":[x,y,z],"block":B}   ("at" may also be a list of positions)
  replace:  {"op":"replace","from":[x,y,z],"to":[x,y,z],"find":B,"block":B}   (only changes blocks that are "find")
Any op may add "repeat": {"count": N, "step": [dx,dy,dz]} to place N copies, e.g. a row of columns.
"hollow": true keeps only the outer shell (all shapes except line/set/replace). The shell includes the top and bottom layers of boxes and cylinders, and the inside is left as it was, not cleared: add an air op to empty it.
Limits: at most 256 ops, 64 repeats, radius 128, a build that spans at most 256 blocks along each side, ~131k blocks in total. The ground a build reaches is loaded for it (at most 36 chunks that were not loaded already), so it can be far from the player.
Orientation-sensitive blocks (stairs, doors, logs) take properties like "minecraft:sandstone_stairs[facing=north,half=bottom]".`;

export const INSTRUCTIONS = `You can build in the player's Minecraft world through the Craftium mod. Builds are shown as a ghost preview in the game and only change the world when applied. You work on the world itself, in world coordinates: you never move, turn or select anything for the player.

Workflow:
1. Call get_state. It reports the player's position and facing, the area they have selected (if any: a hint of where they want a build, which you never change), and whether you have a preview waiting. If the game is not reachable, tell the player to open a world and turn on AI building (in the editor: AI menu, then Turn on AI building; or type /craftium ai on).
2. Decide where to build: in the player's selection if they made one (call read_selection to see what it is: its shape, its blocks and the space above it), otherwise near the player or where they asked. Look before you build: get_surface gives the ground height of an area and get_blocks reads blocks; both work anywhere in the world, not just near the player. Say where you chose to build.
3. Call preview_build with a build spec in world coordinates. If it returns an error, fix the spec and try again; the error names the operation at fault. A new preview replaces the previous one.
4. Tell the player to look at the ghost in the game (it is violet). You can look too, without touching the player's view: call render_view on the build's area from a couple of angles, for example se for a three-quarter view and top for the plan, and judge proportions, gaps and style yourself before showing the player. Your waiting preview is drawn tinted violet. If previewTruncated is true the game drew only part of the preview, so build in smaller stages to see all of it. Adjust with another preview_build if the result or the player wants changes.
5. Call apply_preview only once the player is happy (or if they asked you to just build it). undo reverses the last build you applied, never the player's own edits. cancel_preview discards a preview.
Reading ground nobody has visited generates it, so a single read may touch at most 16 chunks that are not already loaded: if one is refused for that reason, read a smaller area, or read it in pieces. A build may reach 36 such chunks. Reads and builds stay in the dimension the player is in. Prefer a few well-chosen operations over many small ones; Minecraft builds read best with layered shapes, varied blocks and details like slabs, stairs and walls.`;

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
  if (value.palette !== undefined && (!plain(value.palette) || !Object.values(value.palette).every((v) => typeof v === "string"))) throw new ArgumentError('"spec.palette" must map names to block ids');
  const out = { ops: value.ops };
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
    description: "Where the player is and which way they face, the area they have selected (corners and dimensions only, so call read_selection to see what is in it; theirs, and never changed by you), how many blocks your waiting preview holds (previewBlocks) and how many of them the game can draw (previewBlocksDrawn; previewTruncated and a previewWarning appear when a big preview is only partly drawn), and how many of your builds can be undone. Call this first.",
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
    name: "get_blocks",
    title: "Read blocks",
    description:
      "Reads the blocks in a box of the world, anywhere in it and whether or not the player is near, as a palette plus run-length runs [paletteIndex, count] (x fastest, then z, then y, from the box's minimum corner). At most 32,768 blocks per call. " +
      "The server reads it, so areas the player's game has not loaded are real blocks, not air; reading far-off ground that was never visited makes it, so one call touches at most 16 chunks that are not already loaded. " +
      'The palette entry "craftium:outside_world" marks positions above or below the height limits or past the world border.',
    inputSchema: box,
    annotations: READ,
    args: (a) => ({ from: whole(a.from, "from"), to: whole(a.to, "to") }),
  },
  {
    name: "get_surface",
    title: "Read the ground",
    description:
      "The highest block of every column in a rectangle (corners [x, y, z]; y is ignored), up to 128 by 128 columns: heights[i] is the y of the top block and blocks[i] indexes the palette, x fastest then z from the rectangle's minimum corner. " +
      'Use it to find ground level before building on or into terrain. "top" (default) is the highest solid or liquid block ignoring leaves, so water counts and trunks and buildings count; "floor" looks under water. ' +
      "In the Nether the surface is the ceiling; use get_blocks to look below it. A column past the world border, or with no block in it, has heights[i] = -2147483648 and blocks[i] = -1. A height of -1 is real ground at y = -1 (as in a superflat world), not an empty column.",
    inputSchema: { type: "object", properties: { ...box.properties, kind: { type: "string", enum: ["top", "floor"], description: '"top" (default) or "floor" (under water)' } }, required: ["from", "to"] },
    annotations: READ,
    args: (a) => {
      if (a.kind !== undefined && a.kind !== "top" && a.kind !== "floor") throw new ArgumentError('"kind" must be "top" or "floor"');
      return { from: whole(a.from, "from"), to: whole(a.to, "to"), ...(a.kind !== undefined ? { kind: a.kind } : {}) };
    },
  },
  {
    name: "get_palette",
    title: "Get block palette",
    description: "The player's current block and their palette of recently used blocks, which hints at the style they want.",
    inputSchema: nothing,
    annotations: READ,
    args: () => ({}),
  },
  {
    name: "get_screenshot",
    title: "See the game",
    description:
      "A screenshot of what the player currently sees in Minecraft, including a ghost preview (shown in violet) and the game's interface. " +
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
      "The reply says which way east and north point in the picture and lists the commonest blocks with the colour each is drawn in, so you can tell materials apart. " +
      "The game draws only part of a very big preview: when that happens the reply has previewTruncated and a previewWarning, and the picture is missing blocks that will still be built, so do not judge the build from it. " +
      "The box can be at most 128 blocks on a side and 262,144 blocks (64 by 64 by 64, for example); it is read from the server in pieces, like get_blocks.",
    inputSchema: {
      type: "object",
      properties: {
        ...box.properties,
        angle: { type: "string", enum: ["se", "sw", "ne", "nw", "top", "north", "south", "east", "west"], description: "Where the camera is (default se)" },
        preview: { type: "boolean", description: "Draw the player's waiting preview over the world (default true)" },
      },
      required: ["from", "to"],
    },
    annotations: READ,
    args: (a) => {
      const angles = ["se", "sw", "ne", "nw", "top", "north", "south", "east", "west"];
      if (a.angle !== undefined && !angles.includes(a.angle)) throw new ArgumentError(`"angle" must be one of ${angles.join(", ")}`);
      if (a.preview !== undefined && typeof a.preview !== "boolean") throw new ArgumentError('"preview" must be true or false');
      return { from: whole(a.from, "from"), to: whole(a.to, "to"), ...(a.angle !== undefined ? { angle: a.angle } : {}), ...(a.preview !== undefined ? { preview: a.preview } : {}) };
    },
  },
  {
    name: "preview_build",
    title: "Preview a build",
    description:
      "Shows a build as a ghost preview at the world coordinates its spec names, without changing the world, replacing any earlier preview. Nothing needs to be selected, and the build can be anywhere in the world the player's dimension reaches. " +
      "The game draws only part of a very big preview. The reply's state says so (previewTruncated, previewWarning): the rest is still built when you apply it, so build in smaller stages if you need to see all of it. Describe the build as a spec of primitives.\n\n" + BUILD_SPEC_GUIDE,
    inputSchema: {
      type: "object",
      properties: {
        spec: {
          type: "object",
          description: "The build spec",
          properties: {
            palette: { type: "object", additionalProperties: { type: "string" }, description: "Short names for block ids" },
            origin: coordinate("Added to every coordinate in the spec, [x, y, z]; optional"),
            ops: { type: "array", minItems: 1, items: { type: "object" }, description: "Operations, run in order, in world coordinates" },
          },
          required: ["ops"],
        },
      },
      required: ["spec"],
    },
    annotations: CHANGE,
    args: (a) => ({ spec: spec(a.spec) }),
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
