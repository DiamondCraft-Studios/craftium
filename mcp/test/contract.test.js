// What this extension is allowed to do on the player's computer. It runs with the player's own permissions and Claude Desktop warns
// about that, so the limits are written down as tests: a change that gives the code more reach has to change this file too, where a
// reviewer will see it.
import test from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const sources = async () => {
  const dir = path.join(root, "src");
  return Promise.all((await readdir(dir)).filter((name) => name.endsWith(".js")).map(async (name) => ({ name, code: await readFile(path.join(dir, name), "utf8") })));
};

/** Code with its comments and string contents blanked, so the checks below see only what runs. */
const bare = (code) => code.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/.*$/gm, "$1").replace(/`(?:\\.|[^`\\])*`/g, "``").replace(/"(?:\\.|[^"\\\n])*"/g, '""');

test("it has no runtime dependencies", async () => {
  const pack = JSON.parse(await readFile(path.join(root, "package.json"), "utf8"));
  assert.equal(pack.dependencies, undefined);
});

test("it imports only these Node modules and its own files", async () => {
  const allowed = new Set(["node:fs/promises", "node:os", "node:path", "node:readline"]);
  for (const { name, code } of await sources()) {
    for (const match of code.matchAll(/(?:^|\n)\s*import\s[^;]*?from\s+"([^"]+)"/g)) {
      const target = match[1];
      assert.ok(allowed.has(target) || target.startsWith("./"), `${name} imports ${target}`);
    }
    assert.ok(!/\brequire\s*\(|\bimport\s*\(/.test(bare(code)), `${name} loads code dynamically`);
  }
});

test("it cannot run programs, evaluate text as code, open sockets or write files", async () => {
  const forbidden = [/\beval\b/, /\bnew Function\b/, /\bFunction\s*\(/, /child_process|spawn|exec(?:Sync|File)?\s*\(/, /\bnode:(?:net|http|https|dgram|dns|tls|worker_threads|vm|child_process)\b/, /writeFile|appendFile|createWriteStream|unlink|rmSync|rm\s*\(|mkdir/, /\bWebSocket\b|XMLHttpRequest/];
  for (const { name, code } of await sources()) for (const pattern of forbidden) assert.ok(!pattern.test(bare(code)), `${name} uses ${pattern}`);
});

test("its only network use is one fetch, to this computer's loopback address", async () => {
  let fetches = 0;
  for (const { name, code } of await sources()) {
    for (const match of code.matchAll(/\bfetch\s*\(/g)) { fetches++; void match; }
    for (const url of code.matchAll(/https?:\/\/[^\s"'`)]*/g)) assert.ok(url[0].startsWith("http://127.0.0.1:"), `${name} mentions ${url[0]}`);
  }
  assert.equal(fetches, 1, "the bridge client is the only caller of fetch");
  const bridge = await readFile(path.join(root, "src", "bridge.js"), "utf8");
  assert.match(bridge, /this\.fetch\(`http:\/\/127\.0\.0\.1:\$\{port\}\/\$\{tool\}`/);
});

test("it reads only the bridge file, found in the places listed in bridge.js", async () => {
  const reads = [];
  for (const { name, code } of await sources()) for (const m of bare(code).matchAll(/\b(readFile|readdir|stat|open|createReadStream)\s*\(/g)) reads.push(`${name}:${m[1]}`);
  assert.deepEqual(reads.sort(), ["bridge.js:readFile", "bridge.js:stat"]);
});

test("it reads only these environment variables", async () => {
  const names = new Set();
  for (const { code } of await sources()) for (const m of code.matchAll(/\benv\.([A-Z_]+)/g)) names.add(m[1]);
  assert.deepEqual([...names].sort(), ["APPDATA", "CRAFTIUM_BRIDGE_FILE", "CRAFTIUM_MINECRAFT_DIR"]);
});
