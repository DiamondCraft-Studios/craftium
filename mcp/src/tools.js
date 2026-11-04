// The tools the server offers: their descriptions, the schemas clients see, and the checks arguments pass before anything
// is sent to the game. Only the keys a tool names are forwarded, so nothing else a client sends reaches the bridge.

export class ArgumentError extends Error {}

const relative = "Coordinates are [x, y, z] whole numbers RELATIVE to the minimum corner of the selection (so [0,0,0] is its lowest corner and y is up).";

export const BUILD_SPEC_GUIDE = `A build spec is JSON: {"palette": {...}, "ops": [...]}.
${relative}
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
"hollow": true keeps only the outer shell (all shapes except line/set/replace).
Limits: at most 256 ops, 64 repeats, radius 128, 256 blocks along each side of the selection, ~131k blocks in total. Cells outside the selection are dropped.
Orientation-sensitive blocks (stairs, doors, logs) take properties like "minecraft:sandstone_stairs[facing=north,half=bottom]".`;

export const INSTRUCTIONS = `You can build in the player's Minecraft world through the Craftium mod. Builds are shown as a ghost preview in the game and only change the world when applied.

Workflow:
1. Call get_state. It reports the player's position and facing and the current selection. If the game is not reachable, tell the player to open a world and turn on AI building (in the editor: AI menu, then Turn on AI building; or type /craftium ai on).
2. Builds go inside the selection. If there is none (or it is the wrong size), choose an area in front of the player sized for the build and call set_selection. Mention the area you chose.
3. Call preview_build with a build spec. If it returns an error, fix the spec and try again; the error names the operation at fault. A new preview replaces the previous one.
4. Tell the player to look at the ghost in the game (it is violet). You can look too: call look_at on the middle of the selection and then get_screenshot, and judge proportions, gaps and style yourself before showing the player. Only turn the player's view when it helps, and turn it back afterwards. Adjust with another preview_build if the result or the player wants changes.
5. Call apply_preview only once the player is happy (or if they asked you to just build it). undo reverses the last applied build. cancel_preview discards a preview.
Use get_blocks to look at what is already in an area before building around or replacing it, and get_surface to find the ground height; both read the server's world, so they work away from the player too. Reading ground nobody has visited generates it, so a single read may touch at most 16 chunks that are not already loaded: if one is refused for that reason, read a smaller area, or read it in pieces. Reads stay in the dimension the player is in. Prefer a few well-chosen operations over many small ones; Minecraft builds read best with layered shapes, varied blocks and details like slabs, stairs and walls.`;

// ---- argument checks ------------------------------------------------------------------------------------------------

function whole(value, name) {
  if (!Array.isArray(value) || value.length !== 3 || !value.every(Number.isInteger)) throw new ArgumentError(`"${name}" must be [x, y, z] as whole numbers`);
  return value;
}

function view(value) {
  if (!Array.isArray(value) || value.length !== 2 || !value.every((n) => typeof n === "number" && Number.isFinite(n))) throw new ArgumentError('"view" must be [yaw, pitch] as numbers');
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
    description: "Where the player is and which way they face, the current selection (corners and dimensions), how many blocks the current preview holds, and undo/redo availability. Call this first.",
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
      "In the Nether the surface is the ceiling; use get_blocks to look below it. Columns past the world border read as -2147483648.",
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
      "Use it to check your own work after preview_build or apply_preview. If the build is out of frame, use look_at to face it, and if it is still too close or too big, ask the player to step back or fly up. " +
      "The reply says if a menu is open (screenOpen), in which case the picture shows the menu and not the world.",
    inputSchema: nothing,
    annotations: READ,
    args: () => ({}),
  },
  {
    name: "look_at",
    title: "Turn the player's view",
    description:
      "Turns the player's view (not their position) toward a block so get_screenshot can capture it, for example the middle of the selection. " +
      "The reply gives the previous view as [yaw, pitch]; pass it back as view to turn the player back when you are done.",
    inputSchema: {
      type: "object",
      properties: { target: coordinate("The block to face, [x, y, z]"), view: { type: "array", items: { type: "number" }, minItems: 2, maxItems: 2, description: "Or an exact [yaw, pitch] in degrees, such as a previousView to restore" } },
    },
    annotations: CHANGE,
    args: (a) => {
      if (a.target === undefined && a.view === undefined) throw new ArgumentError("Give a target block or a view.");
      return a.view !== undefined ? { view: view(a.view) } : { target: whole(a.target, "target") };
    },
  },
  {
    name: "set_selection",
    title: "Select an area",
    description: "Selects the box between two corners (world coordinates, inclusive), replacing the current selection. Builds are placed inside the selection, at most 256 blocks along each side.",
    inputSchema: box,
    annotations: CHANGE,
    args: (a) => ({ from: whole(a.from, "from"), to: whole(a.to, "to") }),
  },
  {
    name: "clear_selection",
    title: "Clear the selection",
    description: "Deselects everything.",
    inputSchema: nothing,
    annotations: CHANGE,
    args: () => ({}),
  },
  {
    name: "preview_build",
    title: "Preview a build",
    description:
      "Shows a build as a ghost preview inside the current selection without changing the world, replacing any earlier preview. Describe the build as a spec of primitives.\n\n" + BUILD_SPEC_GUIDE,
    inputSchema: {
      type: "object",
      properties: {
        spec: {
          type: "object",
          description: "The build spec",
          properties: {
            palette: { type: "object", additionalProperties: { type: "string" }, description: "Short names for block ids" },
            ops: { type: "array", minItems: 1, items: { type: "object" }, description: "Operations, run in order" },
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
    description: "Builds the previewed blocks in the world as one undoable step. Do this only once the player has seen the preview and is happy with it.",
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
    title: "Undo the last edit",
    description: "Undoes the player's most recent edit in Craftium, such as the last applied build.",
    inputSchema: nothing,
    annotations: DESTRUCTIVE,
    args: () => ({}),
  },
];
