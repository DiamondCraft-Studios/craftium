import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { BridgeError } from "./bridge.js";

const coordinate = z.array(z.number().int()).length(3).describe("[x, y, z] in whole world coordinates");
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
1. Call get_state. It reports the player's position and facing and the current selection. If the game is not reachable, tell the player to open a world and type /craftium ai on in the game chat.
2. Builds go inside the selection. If there is none (or it is the wrong size), choose an area in front of the player sized for the build and call set_selection. Mention the area you chose.
3. Call preview_build with a build spec. If it returns an error, fix the spec and try again; the error names the operation at fault. A new preview replaces the previous one.
4. Tell the player to look at the ghost in the game. Adjust with another preview_build if they want changes.
5. Call apply_preview only once the player is happy (or if they asked you to just build it). undo reverses the last applied build. cancel_preview discards a preview.
Use get_blocks to look at what is already in an area before building around or replacing it. Prefer a few well-chosen operations over many small ones; Minecraft builds read best with layered shapes, varied blocks and details like slabs, stairs and walls.`;

/** Wraps a bridge reply as an MCP tool result: ok replies as JSON text, ok:false as an error the model can act on. */
function result(reply) {
  if (reply && reply.ok === false) return { isError: true, content: [{ type: "text", text: String(reply.error ?? "Failed") }] };
  const { ok, ...rest } = reply ?? {};
  return { content: [{ type: "text", text: JSON.stringify(rest) }] };
}

function guarded(bridge, tool, build = (args) => args) {
  return async (args) => {
    try {
      return result(await bridge.call(tool, build(args)));
    } catch (error) {
      if (error instanceof BridgeError) return { isError: true, content: [{ type: "text", text: error.message }] };
      throw error;
    }
  };
}

export function createServer(bridge, version = "0.1.0") {
  const server = new McpServer({ name: "craftium", version }, { instructions: INSTRUCTIONS });
  const read = { readOnlyHint: true, openWorldHint: false };

  server.registerTool(
    "get_state",
    {
      title: "Get Craftium state",
      description:
        "Where the player is and which way they face, the current selection (corners and dimensions), how many blocks the current preview holds, and undo/redo availability. Call this first.",
      inputSchema: {},
      annotations: read,
    },
    guarded(bridge, "get_state"),
  );

  server.registerTool(
    "get_blocks",
    {
      title: "Read blocks",
      description:
        "Reads the blocks in a box of the world the game has loaded, as a palette plus run-length runs [paletteIndex, count] (x fastest, then z, then y, from the box's minimum corner). At most 32,768 blocks per call.",
      inputSchema: { from: coordinate, to: coordinate },
      annotations: read,
    },
    guarded(bridge, "get_blocks"),
  );

  server.registerTool(
    "get_palette",
    {
      title: "Get block palette",
      description: "The player's current block and their palette of recently used blocks, which hints at the style they want.",
      inputSchema: {},
      annotations: read,
    },
    guarded(bridge, "get_palette"),
  );

  server.registerTool(
    "set_selection",
    {
      title: "Select an area",
      description:
        "Selects the box between two corners (world coordinates, inclusive), replacing the current selection. Builds are placed inside the selection, at most 256 blocks along each side.",
      inputSchema: { from: coordinate, to: coordinate },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    guarded(bridge, "set_selection"),
  );

  server.registerTool(
    "clear_selection",
    {
      title: "Clear the selection",
      description: "Deselects everything.",
      inputSchema: {},
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    guarded(bridge, "clear_selection"),
  );

  server.registerTool(
    "preview_build",
    {
      title: "Preview a build",
      description:
        "Shows a build as a ghost preview inside the current selection without changing the world, replacing any earlier preview. " +
        "Describe the build as a spec of primitives.\n\n" +
        BUILD_SPEC_GUIDE,
      inputSchema: {
        spec: z
          .object({
            palette: z.record(z.string(), z.string()).optional().describe("Short names for block ids"),
            ops: z.array(z.record(z.string(), z.unknown())).min(1).describe("Operations, run in order"),
          })
          .describe("The build spec"),
      },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    guarded(bridge, "preview_build"),
  );

  server.registerTool(
    "apply_preview",
    {
      title: "Apply the preview",
      description: "Builds the previewed blocks in the world as one undoable step. Do this only once the player has seen the preview and is happy with it.",
      inputSchema: {},
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false },
    },
    guarded(bridge, "apply_preview"),
  );

  server.registerTool(
    "cancel_preview",
    {
      title: "Cancel the preview",
      description: "Discards the preview. The world is not changed.",
      inputSchema: {},
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    guarded(bridge, "cancel_preview"),
  );

  server.registerTool(
    "undo",
    {
      title: "Undo the last edit",
      description: "Undoes the player's most recent edit in Craftium, such as the last applied build.",
      inputSchema: {},
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false },
    },
    guarded(bridge, "undo"),
  );

  return server;
}
