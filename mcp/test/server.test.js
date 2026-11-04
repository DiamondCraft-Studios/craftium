import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { Bridge, candidateFiles, NOT_RUNNING } from "../src/bridge.js";
import { createServer } from "../src/server.js";

const TOKEN = "t0ken";

/** A stand-in for the game's bridge that records requests and answers from a table. */
async function fakeBridge(replies) {
  const seen = [];
  const server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => {
      seen.push({ method: req.method, url: req.url, auth: req.headers.authorization, host: req.headers.host, origin: req.headers.origin, body: body ? JSON.parse(body) : {} });
      if (req.headers.authorization !== `Bearer ${TOKEN}`) { res.writeHead(401); res.end("{}"); return; }
      const reply = replies[req.url.slice(1)];
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(reply ?? { ok: false, error: "Unknown tool" }));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const dir = await mkdtemp(path.join(os.tmpdir(), "craftium-mcp-"));
  const file = path.join(dir, "bridge.json");
  await writeFile(file, JSON.stringify({ port: server.address().port, token: TOKEN }));
  return {
    seen,
    file,
    async close() { await new Promise((resolve) => server.close(resolve)); await rm(dir, { recursive: true, force: true }); },
  };
}

async function connect(file) {
  const server = createServer(new Bridge({ files: [file], timeoutMs: 2000 }));
  const client = new Client({ name: "test", version: "0" });
  const [a, b] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(a), client.connect(b)]);
  return client;
}

const text = (result) => result.content[0].text;

test("lists every tool with a read-only hint where it applies", async () => {
  const bridge = await fakeBridge({});
  const client = await connect(bridge.file);
  const { tools } = await client.listTools();
  assert.deepEqual(tools.map((t) => t.name).sort(), ["apply_preview", "cancel_preview", "clear_selection", "get_blocks", "get_palette", "get_screenshot", "get_state", "look_at", "preview_build", "set_selection", "undo"]);
  assert.equal(tools.find((t) => t.name === "get_state").annotations.readOnlyHint, true);
  assert.equal(tools.find((t) => t.name === "apply_preview").annotations.destructiveHint, true);
  assert.match(tools.find((t) => t.name === "preview_build").description, /cylinder/);
  await bridge.close();
});

test("a tool call reaches the bridge with the token and its arguments", async () => {
  const bridge = await fakeBridge({ get_blocks: { ok: true, palette: ["minecraft:air"], runs: [[0, 8]] } });
  const client = await connect(bridge.file);
  const result = await client.callTool({ name: "get_blocks", arguments: { from: [0, 0, 0], to: [1, 1, 1] } });
  assert.equal(result.isError, undefined);
  assert.deepEqual(JSON.parse(text(result)), { palette: ["minecraft:air"], runs: [[0, 8]] });
  const [request] = bridge.seen;
  assert.equal(request.method, "POST");
  assert.equal(request.auth, `Bearer ${TOKEN}`);
  assert.match(request.host, /^127\.0\.0\.1:\d+$/);
  assert.equal(request.origin, undefined);
  assert.deepEqual(request.body, { from: [0, 0, 0], to: [1, 1, 1] });
  await bridge.close();
});

test("a screenshot reaches the model as an image with the details beside it", async () => {
  const bridge = await fakeBridge({ get_screenshot: { ok: true, image: "QUJD", mime: "image/jpeg", width: 1024, height: 576, screenOpen: false } });
  const client = await connect(bridge.file);
  const result = await client.callTool({ name: "get_screenshot", arguments: {} });
  assert.equal(result.isError, undefined);
  assert.deepEqual(result.content[0], { type: "image", data: "QUJD", mimeType: "image/jpeg" });
  assert.deepEqual(JSON.parse(result.content[1].text), { width: 1024, height: 576, screenOpen: false });
  await bridge.close();
});

test("look_at needs a target or a view and passes either through", async () => {
  const bridge = await fakeBridge({ look_at: { ok: true, previousView: [0, 0], view: [90, 10] } });
  const client = await connect(bridge.file);
  const none = await client.callTool({ name: "look_at", arguments: {} });
  assert.equal(none.isError, true);
  assert.equal(bridge.seen.length, 0);
  await client.callTool({ name: "look_at", arguments: { target: [1, 2, 3] } });
  await client.callTool({ name: "look_at", arguments: { view: [45, 5] } });
  assert.deepEqual(bridge.seen.map((r) => r.body), [{ target: [1, 2, 3] }, { view: [45, 5] }]);
  await bridge.close();
});

test("preview_build passes the spec through untouched", async () => {
  const bridge = await fakeBridge({ preview_build: { ok: true, message: "Edit ready: 12 blocks" } });
  const client = await connect(bridge.file);
  const spec = { palette: { s: "minecraft:sandstone" }, ops: [{ op: "box", from: [0, 0, 0], to: [1, 1, 1], block: "s" }] };
  const result = await client.callTool({ name: "preview_build", arguments: { spec } });
  assert.equal(result.isError, undefined);
  assert.deepEqual(bridge.seen[0].body, { spec });
  await bridge.close();
});

test("an ok:false reply becomes an error the model can read", async () => {
  const bridge = await fakeBridge({ preview_build: { ok: false, error: 'ops[0] (cylinder): missing number "radius"' } });
  const client = await connect(bridge.file);
  const result = await client.callTool({ name: "preview_build", arguments: { spec: { ops: [{ op: "cylinder" }] } } });
  assert.equal(result.isError, true);
  assert.match(text(result), /ops\[0\] \(cylinder\)/);
  await bridge.close();
});

test("bad arguments are rejected before they reach the game", async () => {
  const bridge = await fakeBridge({});
  const client = await connect(bridge.file);
  const result = await client.callTool({ name: "set_selection", arguments: { from: [0, 0], to: [1, 1, 1] } }).catch((error) => ({ isError: true, content: [{ text: String(error) }] }));
  assert.equal(result.isError, true);
  assert.equal(bridge.seen.length, 0);
  await bridge.close();
});

test("without a bridge file the error says how to start it", async () => {
  const client = await connect(path.join(os.tmpdir(), "craftium-mcp-missing", "bridge.json"));
  const result = await client.callTool({ name: "get_state", arguments: {} });
  assert.equal(result.isError, true);
  assert.equal(text(result), NOT_RUNNING);
});

test("a stale file with nothing listening reads as the game not running", async () => {
  const bridge = await fakeBridge({});
  await bridge.close(); // the port is now closed but the file remains, as after a crash
  const dir = await mkdtemp(path.join(os.tmpdir(), "craftium-mcp-"));
  const file = path.join(dir, "bridge.json");
  await writeFile(file, JSON.stringify({ port: 1, token: TOKEN }));
  const client = await connect(file);
  const result = await client.callTool({ name: "get_state", arguments: {} });
  assert.equal(result.isError, true);
  assert.equal(text(result), NOT_RUNNING);
  await rm(dir, { recursive: true, force: true });
});

test("a refused token tells the player to restart the bridge", async () => {
  const bridge = await fakeBridge({});
  await writeFile(bridge.file, JSON.stringify({ port: JSON.parse(await (await import("node:fs/promises")).readFile(bridge.file, "utf8")).port, token: "wrong" }));
  const client = await connect(bridge.file);
  const result = await client.callTool({ name: "get_state", arguments: {} });
  assert.equal(result.isError, true);
  assert.match(text(result), /refused/);
  await bridge.close();
});

test("a blank or unfilled folder setting is ignored", () => {
  const home = path.join(path.sep, "home", "player");
  for (const value of ["", "${user_config.minecraft_dir}"]) {
    const files = candidateFiles({ CRAFTIUM_MINECRAFT_DIR: value, CRAFTIUM_BRIDGE_FILE: value }, "linux", home);
    assert.deepEqual(files, [path.join(home, ".minecraft", "config", "craftium", "bridge.json")]);
  }
});

test("candidate files honour the overrides and the platform's default folder", () => {
  const home = path.join(path.sep, "home", "player");
  assert.deepEqual(candidateFiles({ CRAFTIUM_BRIDGE_FILE: "/x/bridge.json" }, "linux", home)[0], "/x/bridge.json");
  assert.equal(candidateFiles({ CRAFTIUM_MINECRAFT_DIR: "/mc" }, "linux", home)[0], path.join("/mc", "config", "craftium", "bridge.json"));
  assert.equal(candidateFiles({}, "linux", home).at(-1), path.join(home, ".minecraft", "config", "craftium", "bridge.json"));
  assert.equal(candidateFiles({}, "darwin", home).at(-1), path.join(home, "Library", "Application Support", "minecraft", "config", "craftium", "bridge.json"));
  assert.equal(candidateFiles({ APPDATA: "C:\\Users\\p\\AppData\\Roaming" }, "win32", home).at(-1), path.join("C:\\Users\\p\\AppData\\Roaming", ".minecraft", "config", "craftium", "bridge.json"));
});
