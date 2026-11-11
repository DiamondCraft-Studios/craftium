import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createHandler, REPLY_LIMIT, toResult, VERSION } from "../src/protocol.js";

const stub = (reply = { ok: true }) => ({ calls: [], async call(tool, args) { this.calls.push([tool, args]); return reply; } });

test("initialize answers with the version the client asked for, or the newest this server knows", async () => {
  const handle = createHandler(stub());
  const known = await handle({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18" } });
  assert.equal(known.result.protocolVersion, "2025-06-18");
  assert.deepEqual(known.result.capabilities, { tools: {} });
  assert.equal(known.result.serverInfo.name, "craftium");
  const future = await handle({ jsonrpc: "2.0", id: 2, method: "initialize", params: { protocolVersion: "2099-01-01" } });
  assert.equal(future.result.protocolVersion, "2025-11-25");
});

test("the server's version matches the package", async () => {
  const read = async (file) => JSON.parse(await readFile(new URL(`../${file}`, import.meta.url), "utf8"));
  assert.equal((await read("package.json")).version, VERSION);
});

test("ping is answered, notifications are not, unknown methods are refused", async () => {
  const handle = createHandler(stub());
  assert.deepEqual((await handle({ jsonrpc: "2.0", id: 7, method: "ping" })).result, {});
  assert.equal(await handle({ jsonrpc: "2.0", method: "notifications/initialized" }), null);
  assert.equal(await handle({ jsonrpc: "2.0", method: "something/else" }), null);
  const refused = await handle({ jsonrpc: "2.0", id: 8, method: "resources/list" });
  assert.equal(refused.error.code, -32601);
});

test("malformed messages get an error and never throw", async () => {
  const handle = createHandler(stub());
  assert.equal((await handle(null)).error.code, -32600);
  assert.equal((await handle([{ jsonrpc: "2.0", id: 1, method: "ping" }])).error.code, -32600);
  assert.equal((await handle({ jsonrpc: "2.0", id: 1 })).error.code, -32600);
  assert.equal(await handle({ jsonrpc: "2.0" }), null);
});

test("an unknown tool is a protocol error, and a call needs object arguments", async () => {
  const bridge = stub();
  const handle = createHandler(bridge);
  assert.equal((await handle({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "format_disk" } })).error.code, -32602);
  const bad = await handle({ jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "get_state", arguments: [1] } });
  assert.equal(bad.result.isError, true);
  assert.equal(bridge.calls.length, 0);
});

test("a tool with no arguments still works when none are given", async () => {
  const bridge = stub({ ok: true, inWorld: true });
  const handle = createHandler(bridge);
  const response = await handle({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "get_state" } });
  assert.deepEqual(JSON.parse(response.result.content[0].text), { inWorld: true });
  assert.deepEqual(bridge.calls, [["get_state", {}]]);
});

test("a failure inside the bridge call is reported to the model, not thrown", async () => {
  const handle = createHandler({ async call() { throw new Error("boom"); } });
  const response = await handle({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "get_state", arguments: {} } });
  assert.equal(response.result.isError, true);
  assert.match(response.result.content[0].text, /boom/);
});

test("toResult keeps ok:false as an error and splits a screenshot from its details", () => {
  assert.deepEqual(toResult({ ok: false, error: "no" }), { isError: true, content: [{ type: "text", text: "no" }] });
  const shot = toResult({ ok: true, image: "QQ==", width: 2 });
  assert.equal(shot.content[0].type, "image");
  assert.equal(shot.content[0].mimeType, "image/jpeg");
  assert.deepEqual(JSON.parse(shot.content[1].text), { width: 2 });
});

test("several pictures each come with their own details, then what they share", () => {
  const result = toResult({ ok: true, images: [{ image: "QQ", mime: "image/png", angle: "se" }, { image: "Qg", mime: "image/png", angle: "top" }], size: [3, 3, 3] });
  assert.deepEqual(result.content.map((c) => c.type), ["image", "text", "image", "text", "text"]);
  assert.equal(result.content[0].data, "QQ");
  assert.deepEqual(JSON.parse(result.content[3].text), { angle: "top" });
  assert.deepEqual(JSON.parse(result.content[4].text), { size: [3, 3, 3] });
});

test("a reply longer than the limit is refused with what to ask for instead, and a picture does not count", () => {
  const long = toResult({ ok: true, runs: "x".repeat(REPLY_LIMIT) }, "Read a smaller box.");
  assert.equal(long.isError, true);
  assert.match(long.content[0].text, /60 KB.*Read a smaller box\./);
  const picture = toResult({ ok: true, image: "Q".repeat(REPLY_LIMIT * 2), width: 2 });
  assert.equal(picture.isError, undefined);
});

test("get_surface summarises a wide area and gives a small one in full, as the model asked", async () => {
  const columns = (n) => ({ ok: true, source: "server", kind: "top", min: [0, 0], size: [n, n], palette: ["minecraft:stone"], heights: Array(n * n).fill(70), blocks: Array(n * n).fill(0) });
  const ask = async (n, args) => {
    const handle = createHandler(stub(columns(n)));
    const response = await handle({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "get_surface", arguments: { from: [0, 0, 0], to: [n - 1, 0, n - 1], ...args } } });
    return JSON.parse(response.result.content[0].text);
  };
  assert.equal((await ask(16, {})).heights.length, 256, "auto: 256 columns come in full");
  assert.equal((await ask(17, {})).detail, "summary", "auto: more are summarised");
  assert.equal((await ask(8, { detail: "summary" })).detail, "summary");
  assert.equal((await ask(40, { detail: "full" })).heights.length, 1600);
  const bridge = stub(columns(4));
  await createHandler(bridge)({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "get_surface", arguments: { from: [0, 0, 0], to: [3, 0, 3], detail: "summary" } } });
  assert.deepEqual(Object.keys(bridge.calls[0][1]).sort(), ["from", "to"], "detail is handled here and never sent to the game");
});
