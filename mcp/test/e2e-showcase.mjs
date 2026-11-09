// Not a unit test: an MCP client that starts the real shim over stdio and, the way an agent following the craftium-showcase skill
// would, builds a small castle through a running game, photographs it (outside and in) and fetches each room's contact sheet.
// Used by the game test (CRAFTIUM_TEST_SCOPE=showcase-live) and handy by hand:
//   node test/e2e-showcase.mjs <path to bridge.json> <folder to write the contact sheets to>
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const [bridgeFile, outDir] = [process.argv[2], process.argv[3] ?? "."];
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [path.join(here, "..", "src", "index.js")],
  env: { ...process.env, CRAFTIUM_BRIDGE_FILE: bridgeFile },
  stderr: "inherit",
});
const client = new Client({ name: "craftium-showcase-e2e", version: "0" });
await client.connect(transport);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function call(name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  const text = result.content.find((c) => c.type === "text")?.text ?? "";
  console.log(`LIVE ${name} ${result.isError ? "ERROR" : "ok"} ${text.slice(0, 240).replace(/\s+/g, " ")}`);
  if (result.isError) throw new Error(`${name}: ${text}`);
  return { json: text ? JSON.parse(text) : {}, content: result.content };
}

const { tools } = await client.listTools();
for (const name of ["capture_batch", "capture_status", "capture_cancel"]) if (!tools.some((t) => t.name === name)) throw new Error(`the server does not offer ${name}`);

// Site: the player's spot, and a castle a few blocks away on the flat ground.
const { json: state } = await call("get_state");
const [px, py, pz] = state.player.block;
const x0 = px + 10, y = py, z0 = pz + 10;
await call("preview_build", {
  label: "Showcase castle",
  spec: {
    palette: { wall: "minecraft:stone_bricks", floor: "minecraft:oak_planks", lamp: "minecraft:glowstone", tower: "minecraft:cobblestone", roof: "minecraft:spruce_planks" },
    ops: [
      { op: "box", from: [x0, y, z0], to: [x0 + 14, y + 6, z0 + 10], block: "wall", hollow: true },
      { op: "box", from: [x0 + 1, y, z0 + 1], to: [x0 + 13, y, z0 + 9], block: "floor" },
      { op: "box", from: [x0 + 7, y + 1, z0 + 10], to: [x0 + 7, y + 2, z0 + 10], block: "minecraft:air" },
      { op: "roof", from: [x0, y + 7, z0], to: [x0 + 14, y + 7, z0 + 10], style: "gable", material: "spruce", overhang: 1 },
      { op: "cylinder", base: [x0 + 18, y, z0 + 5], radius: 3, height: 12, block: "tower" },
      { op: "cone", base: [x0 + 18, y + 12, z0 + 5], radius: 4, height: 5, block: "roof" },
      { op: "set", at: [[x0 + 4, y + 5, z0 + 5], [x0 + 10, y + 5, z0 + 5]], block: "lamp" },
    ],
  },
});
await call("apply_preview");
await sleep(2500); // the build reaches the game's own copy of the world a moment after the server has it

// The batch an agent would send: an exterior set around the whole castle, and two cameras placed inside the hall.
const castleFrom = [x0 - 1, y, z0 - 1], castleTo = [x0 + 21, y + 16, z0 + 10];
const eye = y + 2.6;
const { json: started } = await call("capture_batch", {
  job: "live-castle",
  rooms: [
    { name: "castle", from: castleFrom, to: castleTo, shots: ["hero", "three_quarter", "south", "top", { name: "crown", target: [x0 + 18.5, y + 14, z0 + 5.5], azimuth: 150, elevation: 12, distance: 16, fov: 45 }] },
    {
      name: "hall", from: [x0 + 1, y + 1, z0 + 1], to: [x0 + 13, y + 5, z0 + 9],
      shots: [
        { name: "from-door", position: [x0 + 7.5, eye, z0 + 9.2], look_at: [x0 + 7.5, y + 2.5, z0 + 1.5], fov: 85 },
        { name: "from-corner", position: [x0 + 1.6, eye + 1, z0 + 8.4], look_at: [x0 + 11.5, y + 2, z0 + 2.5], fov: 85 },
      ],
    },
  ],
});
console.log(`LIVE folder ${started.folder}`);

let status = started;
for (let i = 0; i < 90 && status.state === "running"; i++) {
  await sleep(2000);
  status = (await call("capture_status")).json;
}
if (status.state !== "done") throw new Error(`the job ended as ${status.state}: ${JSON.stringify(status)}`);
if (status.failed !== 0) throw new Error(`pictures failed: ${JSON.stringify(status.shots.filter((s) => !s.ok))}`);

await mkdir(outDir, { recursive: true });
for (const room of ["castle", "hall"]) {
  const { content } = await call("capture_status", { contact_sheet: room });
  const image = content.find((c) => c.type === "image");
  if (!image) throw new Error(`no contact sheet image for ${room}`);
  await writeFile(path.join(outDir, `sheet-${room}.jpg`), Buffer.from(image.data, "base64"));
}
await writeFile(path.join(outDir, "status.json"), JSON.stringify(status, null, 2));
await client.close();
console.log("LIVE_DONE");
