// Not a unit test: an MCP client that starts the real shim over stdio and builds through a running game.
// Used by the game test (CRAFTIUM_TEST_SCOPE=ai) and handy by hand: node test/e2e-client.mjs <path to bridge.json>
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [path.join(here, "..", "src", "index.js")],
  env: { ...process.env, CRAFTIUM_BRIDGE_FILE: process.argv[2] },
  stderr: "inherit",
});
const client = new Client({ name: "craftium-e2e", version: "0" });
await client.connect(transport);

async function call(name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  const text = result.content[0].text;
  console.log(`E2E ${name} ${result.isError ? "ERROR" : "ok"} ${text.slice(0, 300)}`);
  if (result.isError) throw new Error(`${name}: ${text}`);
  return JSON.parse(text);
}

const { tools } = await client.listTools();
console.log(`E2E tools ${tools.length}`);
const state = await call("get_state");
const [px, py, pz] = state.player.block;
const x = px + 8, y = py, z = pz + 8;
await call("preview_build", {
  spec: {
    palette: { sand: "minecraft:sandstone", gold: "minecraft:gold_block" },
    ops: [
      { op: "pyramid", base: [x + 4, y, z + 4], radius: 4, height: 5, block: "sand" },
      { op: "set", at: [x + 4, y + 5, z + 4], block: "gold" },
    ],
  },
});
await call("apply_preview");
console.log(`E2E built ${x + 4} ${y} ${z + 4}`);
await client.close();
console.log("E2E_DONE");
