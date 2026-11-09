// End to end: the real server process, started as Claude Desktop starts it, spoken to by the official MCP client over stdio.
import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdtemp, writeFile, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { BRIDGE_PROTOCOL, candidateFiles, NOT_RUNNING } from "../src/bridge.js";

const ENTRY = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "index.js");
const TOKEN = "t0ken";

/** Every call carries the id of the Claude session that made it; tests look at the rest of the body, and the id on its own. */
function splitSession({ session, ...body }) {
  return { body, session };
}

/** A stand-in for the game's bridge that records requests and answers from a table. */
async function fakeBridge(replies, info = { protocol: BRIDGE_PROTOCOL, version: "1.2.0-beta.1" }) {
  const seen = [];
  const server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => {
      seen.push({ method: req.method, url: req.url, auth: req.headers.authorization, host: req.headers.host, origin: req.headers.origin, ...splitSession(body ? JSON.parse(body) : {}) });
      if (req.headers.authorization !== `Bearer ${TOKEN}`) { res.writeHead(401); res.end("{}"); return; }
      const reply = replies[req.url.slice(1)];
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(reply ?? { ok: false, error: "Unknown tool" }));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const dir = await mkdtemp(path.join(os.tmpdir(), "craftium-mcp-"));
  const file = path.join(dir, "bridge.json");
  await writeFile(file, JSON.stringify({ port: server.address().port, token: TOKEN, ...info }));
  return {
    seen,
    file,
    async close() { await new Promise((resolve) => server.close(resolve)); await rm(dir, { recursive: true, force: true }); },
  };
}

const clients = [];
async function connect(file) {
  const transport = new StdioClientTransport({ command: process.execPath, args: [ENTRY], env: { CRAFTIUM_BRIDGE_FILE: file }, stderr: "ignore" });
  const client = new Client({ name: "test", version: "0" });
  await client.connect(transport);
  clients.push(client);
  return client;
}
test.after(async () => { await Promise.all(clients.map((client) => client.close())); });

const text = (result) => result.content[0].text;

test("lists every tool with its hints, and the server's instructions", async () => {
  const bridge = await fakeBridge({});
  const client = await connect(bridge.file);
  const { tools } = await client.listTools();
  assert.deepEqual(tools.map((t) => t.name).sort(), ["apply_preview", "cancel_preview", "capture_batch", "capture_cancel", "capture_status", "get_screenshot", "get_state", "get_surface", "preview_build", "read_blocks", "read_selection", "render_view", "undo"]);
  assert.equal(tools.find((t) => t.name === "get_state").annotations.readOnlyHint, true);
  assert.equal(tools.find((t) => t.name === "apply_preview").annotations.destructiveHint, true);
  assert.match(tools.find((t) => t.name === "preview_build").description, /cylinder/);
  assert.match(client.getInstructions(), /ghost preview/);
  assert.equal(client.getServerVersion().name, "craftium");
  await bridge.close();
});

test("a tool call reaches the bridge with the token and only its arguments", async () => {
  const bridge = await fakeBridge({ get_preview_blocks: { ok: true, palette: ["minecraft:air"], runs: [[0, 8]] } });
  const client = await connect(bridge.file);
  const result = await client.callTool({ name: "read_blocks", arguments: { from: [0, 0, 0], to: [1, 1, 1], sneaky: "ignored" } });
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

test("render_view passes its box, angle and preview flag through, and its picture comes back as an image", async () => {
  const bridge = await fakeBridge({ render_view: { ok: true, image: "QUJD", mime: "image/png", width: 800, height: 600, compass: { east: "right", north: "up" } } });
  const client = await connect(bridge.file);
  const result = await client.callTool({ name: "render_view", arguments: { from: [0, 0, 0], to: [9, 9, 9], angle: "top", preview: false, sneaky: 1 } });
  assert.equal(result.isError, undefined);
  assert.deepEqual(result.content[0], { type: "image", data: "QUJD", mimeType: "image/png" });
  assert.deepEqual(bridge.seen[0].body, { from: [0, 0, 0], to: [9, 9, 9], angle: "top", preview: false });
  await client.callTool({ name: "render_view", arguments: { from: [0, 0, 0], to: [1, 1, 1] } });
  assert.deepEqual(bridge.seen[1].body, { from: [0, 0, 0], to: [1, 1, 1] });
  await client.callTool({ name: "render_view", arguments: { from: [0, 0, 0], to: [1, 1, 1], angles: ["se", "top"] } });
  assert.deepEqual(bridge.seen[2].body, { from: [0, 0, 0], to: [1, 1, 1], angles: ["se", "top"] });
  for (const args of [{ angles: [] }, { angles: ["se", "sw", "ne", "nw"] }, { angles: ["above"] }, { angle: "se", angles: ["top"] }]) {
    const bad = await client.callTool({ name: "render_view", arguments: { from: [0, 0, 0], to: [1, 1, 1], ...args } });
    assert.equal(bad.isError, true, JSON.stringify(args));
  }
  assert.equal(bridge.seen.length, 3, "bad angles must not reach the game");
  await bridge.close();
});

test("capture_batch forwards only the keys it names, and bad requests never reach the game", async () => {
  const status = { ok: true, job: "castle", state: "running", total: 2, done: 0, failed: 0, folder: "C:/game/screenshots/craftium/castle" };
  const bridge = await fakeBridge({ capture_batch: status, capture_status: { ...status, state: "done", done: 2 }, capture_cancel: { ...status, state: "cancelled" } });
  const client = await connect(bridge.file);
  const room = { name: "Throne Room", from: [0, 64, 0], to: [20, 74, 30], shots: ["hero", { name: "dais", target: [5, 65, 5], azimuth: 90, fov: 45 }] };
  const ok = await client.callTool({ name: "capture_batch", arguments: { job: "castle", rooms: [room], defaults: { fov: 60, width: 3840, height: 2160 }, resume: false, path: "C:/evil", sneaky: 1 } });
  assert.equal(ok.isError, undefined);
  assert.match(text(ok), /castle/);
  assert.deepEqual(bridge.seen[0].body, { rooms: [room], job: "castle", defaults: { fov: 60, width: 3840, height: 2160 }, resume: false });
  await client.callTool({ name: "capture_batch", arguments: { rooms: [room] } });
  assert.deepEqual(bridge.seen[1].body, { rooms: [room] });
  const bad = [
    {}, { rooms: [] }, { rooms: "hero" }, { rooms: [null] },
    { rooms: [{ ...room, name: "" }] }, { rooms: [{ ...room, from: [0, 0] }] }, { rooms: [{ ...room, to: [0.5, 1, 1] }] },
    { rooms: [{ ...room, shots: [] }] }, { rooms: [{ ...room, shots: [5] }] }, { rooms: [{ ...room, shots: [["hero"]] }] },
    { rooms: [room], job: "" }, { rooms: [room], job: 7 }, { rooms: [room], defaults: [] }, { rooms: [room], resume: "yes" },
    { rooms: [room], resume: true },
    { rooms: Array.from({ length: 51 }, (_, i) => ({ ...room, name: `r${i}` })) },
  ];
  for (const args of bad) {
    const result = await client.callTool({ name: "capture_batch", arguments: args });
    assert.equal(result.isError, true, JSON.stringify(args).slice(0, 80));
  }
  assert.equal(bridge.seen.length, 2, "bad requests must not reach the game");
  const poll = await client.callTool({ name: "capture_status", arguments: { ignored: 1 } });
  assert.match(text(poll), /done/);
  assert.deepEqual(bridge.seen[2].body, {});
  const stop = await client.callTool({ name: "capture_cancel", arguments: {} });
  assert.match(text(stop), /cancelled/);
  const tools = (await client.listTools()).tools;
  assert.equal(tools.find((t) => t.name === "capture_status").annotations.readOnlyHint, true);
  assert.equal(tools.find((t) => t.name === "capture_batch").annotations.readOnlyHint, false);
  assert.equal(tools.find((t) => t.name === "capture_batch").annotations.destructiveHint, false, "pictures never change the world");
  await bridge.close();
});

test("capture_status asks for a room's contact sheet and gets it back as an image", async () => {
  const bridge = await fakeBridge({ capture_status: { ok: true, image: "QUJD", mime: "image/jpeg", job: "castle", room: "throne-room", thumbnails: [{ position: 1, shot: "hero" }] } });
  const client = await connect(bridge.file);
  const sheet = await client.callTool({ name: "capture_status", arguments: { contact_sheet: "throne-room", sneaky: 1 } });
  assert.equal(sheet.isError, undefined);
  assert.deepEqual(sheet.content[0], { type: "image", data: "QUJD", mimeType: "image/jpeg" });
  assert.match(text({ content: [sheet.content[1]] }), /throne-room/);
  assert.deepEqual(bridge.seen[0].body, { contact_sheet: "throne-room" });
  for (const bad of [{ contact_sheet: "" }, { contact_sheet: 3 }, { contact_sheet: "x".repeat(65) }]) {
    assert.equal((await client.callTool({ name: "capture_status", arguments: bad })).isError, true, JSON.stringify(bad));
  }
  assert.equal(bridge.seen.length, 1);
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

test("preview_build passes a spec's material and palette mixes through to the game", async () => {
  const bridge = await fakeBridge({ preview_build: { ok: true, message: "AI preview ready: 9 blocks" } });
  const client = await connect(bridge.file);
  const spec = { material: "spruce", palette: { wall: { mix: [["stone_bricks", 3], ["mossy_stone_bricks", 1]] }, step: "@stairs" }, ops: [{ op: "roof", from: [0, 4, 0], to: [6, 4, 4] }] };
  const result = await client.callTool({ name: "preview_build", arguments: { spec } });
  assert.equal(result.isError, undefined, text(result));
  assert.deepEqual(bridge.seen[0].body, { spec });
  const bad = await client.callTool({ name: "preview_build", arguments: { spec: { ...spec, material: 3 } } });
  assert.equal(bad.isError, true);
  await client.callTool({ name: "preview_build", arguments: { spec, mode: "add" } });
  assert.deepEqual(bridge.seen[1].body, { spec, mode: "add" });
  const badMode = await client.callTool({ name: "preview_build", arguments: { spec, mode: "merge" } });
  assert.equal(badMode.isError, true);
  assert.equal(bridge.seen.length, 2);
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

test("get_surface passes its corners and kind to the bridge and nothing else", async () => {
  const bridge = await fakeBridge({ get_surface: { ok: true, heights: [64], blocks: [0] } });
  const client = await connect(bridge.file);
  const result = await client.callTool({ name: "get_surface", arguments: { from: [0, 0, 0], to: [3, 9, 3], kind: "floor", sneaky: 1 } });
  assert.equal(result.isError, undefined);
  assert.deepEqual(bridge.seen[0].body, { from: [0, 0, 0], to: [3, 9, 3], kind: "floor" });
  await bridge.close();
});

test("preview_build passes the spec with its origin, and the only selection tool only reads", async () => {
  const bridge = await fakeBridge({ preview_build: { ok: true, message: "AI preview ready: 4 blocks" } });
  const client = await connect(bridge.file);
  const spec = { origin: [100, 64, -20], palette: { s: "stone" }, ops: [{ op: "set", at: [0, 0, 0], block: "s" }] };
  const result = await client.callTool({ name: "preview_build", arguments: { spec: { ...spec, sneaky: 1 } } });
  assert.equal(result.isError, undefined);
  assert.deepEqual(bridge.seen[0].body, { spec });
  const { tools } = await client.listTools();
  // The AI works in its own workspace and never changes the player's selection: the one tool that mentions it only reads.
  const selectionTools = tools.filter((t) => /selection/.test(t.name));
  assert.deepEqual(selectionTools.map((t) => t.name), ["read_selection"]);
  assert.equal(selectionTools[0].annotations.readOnlyHint, true);
  assert.match(tools.find((t) => t.name === "preview_build").description, /WORLD/);
  await bridge.close();
});

test("read_blocks sends each source as the game's view, with its box and format, and rejects bad ones before the game", async () => {
  const bridge = await fakeBridge({ get_preview_blocks: { ok: true, format: "boxes", summary: "1,000 blocks in 1 boxes: 1000 oak_log" } });
  const client = await connect(bridge.file);
  const result = await client.callTool({ name: "read_blocks", arguments: { from: [0, 0, 0], to: [9, 9, 9], source: "preview", format: "boxes", sneaky: 1 } });
  assert.equal(result.isError, undefined);
  assert.equal(JSON.parse(text(result)).format, "boxes");
  assert.deepEqual(bridge.seen[0].body, { from: [0, 0, 0], to: [9, 9, 9], view: "only", format: "boxes" });
  const plain = await client.callTool({ name: "read_blocks", arguments: { from: [0, 0, 0], to: [1, 1, 1] } });
  assert.equal(plain.isError, undefined);
  assert.deepEqual(bridge.seen[1].body, { from: [0, 0, 0], to: [1, 1, 1] }); // defaults are the game's to apply
  await client.callTool({ name: "read_blocks", arguments: { from: [0, 0, 0], to: [1, 1, 1], source: "world" } });
  assert.deepEqual(bridge.seen[2].body, { from: [0, 0, 0], to: [1, 1, 1], view: "world" });
  const badSource = await client.callTool({ name: "read_blocks", arguments: { from: [0, 0, 0], to: [1, 1, 1], source: "only" } });
  assert.equal(badSource.isError, true);
  const badFormat = await client.callTool({ name: "read_blocks", arguments: { from: [0, 0, 0], to: [1, 1, 1], format: "pretty" } });
  assert.equal(badFormat.isError, true);
  assert.equal(bridge.seen.length, 3, "bad arguments must not reach the game");
  const tool = (await client.listTools()).tools.find((t) => t.name === "read_blocks");
  assert.equal(tool.annotations.readOnlyHint, true);
  await bridge.close();
});

test("read_selection asks the game for the selection and forwards nothing the model adds", async () => {
  const bridge = await fakeBridge({ read_selection: { ok: true, selected: 4, summary: "4 blocks, flat" } });
  const client = await connect(bridge.file);
  const result = await client.callTool({ name: "read_selection", arguments: { sneaky: 1 } });
  assert.equal(result.isError, undefined);
  assert.equal(JSON.parse(text(result)).summary, "4 blocks, flat");
  assert.deepEqual(bridge.seen[0].body, {});
  await bridge.close();
});

test("read_selection forwards refresh only when it is true, and rejects anything else", async () => {
  const bridge = await fakeBridge({ read_selection: { ok: true, selected: 4 } });
  const client = await connect(bridge.file);
  await client.callTool({ name: "read_selection", arguments: { refresh: true } });
  assert.deepEqual(bridge.seen[0].body, { refresh: true });
  await client.callTool({ name: "read_selection", arguments: { refresh: false } });
  assert.deepEqual(bridge.seen[1].body, {});
  const bad = await client.callTool({ name: "read_selection", arguments: { refresh: "yes" } });
  assert.equal(bad.isError, true);
  assert.equal(bridge.seen.length, 2);
  await bridge.close();
});

test("read_selection reads a saved selection by name and keeps it apart from refresh", async () => {
  const bridge = await fakeBridge({ read_selection: { ok: true, selected: 4 } });
  const client = await connect(bridge.file);
  await client.callTool({ name: "read_selection", arguments: { name: "  Selection 2 " } });
  assert.deepEqual(bridge.seen[0].body, { name: "Selection 2" });
  await client.callTool({ name: "read_selection", arguments: { name: "Selection 1" } });
  assert.deepEqual(bridge.seen[1].body, { name: "Selection 1" }, "two reads stay two separate reads");
  for (const bad of [{ name: "" }, { name: 3 }, { name: "x".repeat(41) }, { name: "Selection 1", refresh: true }]) {
    const result = await client.callTool({ name: "read_selection", arguments: bad });
    assert.equal(result.isError, true, JSON.stringify(bad));
  }
  assert.equal(bridge.seen.length, 2, "refused before they reach the game");
  await bridge.close();
});

test("the build tool explains how to work on several saved selections in one spec", async () => {
  const bridge = await fakeBridge({});
  const client = await connect(bridge.file);
  const tools = (await client.listTools()).tools;
  const text = JSON.stringify(tools);
  assert.match(text, /\\"selection\\":\\"Selection 1\\"/);
  assert.match(text, /savedSelections/);
  await bridge.close();
});

test("bad arguments are rejected before they reach the game", async () => {
  const bridge = await fakeBridge({});
  const client = await connect(bridge.file);
  for (const [name, args] of [["read_blocks", { from: [0, 0, 0] }], ["read_blocks", { from: [0, 0], to: [1, 1, 1] }], ["get_surface", { from: [0, 0, 0], to: [1, 1, 1], kind: "water" }], ["render_view", { from: [0, 0, 0], to: [1, 1, 1], angle: "above" }], ["render_view", { from: [0, 0, 0], to: [1, 1, 1], preview: "yes" }], ["read_blocks", { from: [0.5, 0, 0], to: [1, 1, 1] }], ["preview_build", { spec: { ops: [] } }], ["preview_build", { spec: { ops: [{ op: "set" }], origin: [0, 0] } }], ["preview_build", { spec: "not an object" }]]) {
    const result = await client.callTool({ name, arguments: args });
    assert.equal(result.isError, true, `${name} ${JSON.stringify(args)}`);
  }
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
  const dir = await mkdtemp(path.join(os.tmpdir(), "craftium-mcp-"));
  const file = path.join(dir, "bridge.json");
  await writeFile(file, JSON.stringify({ port: 1, token: TOKEN, protocol: BRIDGE_PROTOCOL }));
  const client = await connect(file);
  const result = await client.callTool({ name: "get_state", arguments: {} });
  assert.equal(result.isError, true);
  assert.equal(text(result), NOT_RUNNING);
  await rm(dir, { recursive: true, force: true });
});

test("a refused token tells the player to restart the bridge", async () => {
  const bridge = await fakeBridge({});
  const { port } = JSON.parse(await readFile(bridge.file, "utf8"));
  await writeFile(bridge.file, JSON.stringify({ port, token: "wrong", protocol: BRIDGE_PROTOCOL }));
  const client = await connect(bridge.file);
  const result = await client.callTool({ name: "get_state", arguments: {} });
  assert.equal(result.isError, true);
  assert.match(text(result), /refused/);
  await bridge.close();
});

test("a mod older than the plugin is refused before any call, with what to update", async () => {
  for (const info of [{}, { protocol: 1, version: "0.9.1-beta.1" }]) {
    const bridge = await fakeBridge({ get_state: { ok: true } }, info);
    const client = await connect(bridge.file);
    const result = await client.callTool({ name: "get_state", arguments: {} });
    assert.equal(result.isError, true);
    assert.match(text(result), /older than this Craftium plugin.*1.3.0-beta.1/);
    if (info.version) assert.match(text(result), /0\.9\.1-beta\.1/);
    assert.equal(bridge.seen.length, 0);
    await bridge.close();
  }
});

test("a mod newer than the plugin still works, and get_state says the plugin is behind", async () => {
  const bridge = await fakeBridge({ get_state: { ok: true, inWorld: true }, undo: { ok: true } }, { protocol: BRIDGE_PROTOCOL + 1, version: "9.9.9" });
  const client = await connect(bridge.file);
  const state = await client.callTool({ name: "get_state", arguments: {} });
  assert.equal(state.isError, undefined);
  assert.match(JSON.parse(text(state)).pluginOutdated, /update the plugin/);
  const undo = await client.callTool({ name: "undo", arguments: {} });
  assert.equal(JSON.parse(text(undo)).pluginOutdated, undefined);
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

test("every call carries this server's own session id, and a label rides with a build", async () => {
  const bridge = await fakeBridge({ get_state: { ok: true }, preview_build: { ok: true } });
  const spec = { ops: [{ op: "set", at: [0, 0, 0], block: "stone" }] };
  const client = await connect(bridge.file);
  await client.callTool({ name: "get_state", arguments: {} });
  await client.callTool({ name: "preview_build", arguments: { spec, label: "  Egyptian pillar  " } });
  assert.match(bridge.seen[0].session, /^[0-9a-f]{12}$/);
  assert.equal(bridge.seen[1].session, bridge.seen[0].session, "one process is one session");
  assert.deepEqual(bridge.seen[1].body, { spec, label: "Egyptian pillar" });
  const other = await connect(bridge.file);
  await other.callTool({ name: "get_state", arguments: {} });
  assert.notEqual(bridge.seen[2].session, bridge.seen[0].session, "another process is another session");
  await bridge.close();
});

test("an apply that finds another session's build still being written waits and tries again", async () => {
  const { Bridge } = await import("../src/bridge.js");
  const bridge = await fakeBridge({});
  let calls = 0;
  const answers = [
    { ok: false, error: "Another edit or animated rebuild is applying; try again in a moment" },
    { ok: false, error: "Another edit or animated rebuild is applying; try again in a moment" },
    { ok: true, message: "Applying" },
  ];
  const real = globalThis.fetch;
  const scripted = async () => new Response(JSON.stringify(answers[calls++]), { status: 200, headers: { "Content-Type": "application/json" } });
  const instance = new Bridge({ files: [bridge.file], fetchImpl: scripted, retryMs: 1 });
  assert.deepEqual(await instance.call("apply_preview"), { ok: true, message: "Applying" });
  assert.equal(calls, 3);
  calls = 0;
  const refused = new Bridge({ files: [bridge.file], fetchImpl: async () => new Response(JSON.stringify({ ok: false, error: "You have no preview" }), { status: 200 }), retryMs: 1 });
  assert.equal((await refused.call("apply_preview")).error, "You have no preview", "another refusal is not retried");
  assert.equal(globalThis.fetch, real);
  await bridge.close();
});
