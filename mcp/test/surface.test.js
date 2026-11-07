import test from "node:test";
import assert from "node:assert/strict";
import { NO_COLUMN, summarizeSurface } from "../src/surface.js";

/** A get_surface reply as the game sends it, for an sx by sz area whose heights come from fn(x, z). */
function reply(sx, sz, fn, block = () => 0) {
  const heights = [], blocks = [];
  for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) { const h = fn(x, z); heights.push(h); blocks.push(h === NO_COLUMN ? -1 : block(x, z)); }
  return { ok: true, source: "server", kind: "top", min: [100, -20], size: [sx, sz], palette: ["minecraft:grass_block[snowy=false]", "minecraft:water[level=0]"], heights, blocks, chunksLoadedForThisRead: 0 };
}

test("a level field summarises as level, with its height and what it is made of", () => {
  const out = summarizeSurface(reply(40, 40, () => 64));
  assert.equal(out.detail, "summary");
  assert.equal(out.columns, 1600);
  assert.deepEqual(out.heights, { lowest: 64, highest: 64, median: 64 });
  assert.equal(out.levelWithMedianPercent, 100);
  assert.deepEqual(out.surface, [{ block: "minecraft:grass_block", percent: 100 }]);
});

test("the map is at most 16 cells a side, keeps each cell's highest top, and runs north to south", () => {
  const out = summarizeSurface(reply(128, 64, (x, z) => (x === 127 && z === 0 ? 90 : 60 + Math.floor(z / 8))));
  assert.equal(out.map.cell, 8);
  assert.equal(out.map.rows.length, 8);
  assert.equal(out.map.rows[0].length, 16);
  assert.equal(out.map.rows[0][15], 90, "a tall column shows in its cell");
  assert.equal(out.map.rows[7][0], 67, "rows run with z");
});

test("water and empty columns are told apart from ground", () => {
  const out = summarizeSurface(reply(10, 10, (x) => (x === 0 ? NO_COLUMN : 62), (x) => (x < 5 ? 1 : 0)));
  assert.equal(out.emptyColumns, 10);
  assert.equal(out.surface.find((s) => s.block === "minecraft:water").percent, 44.4);
  assert.equal(out.heights.lowest, 62);
});

test("an area with no ground at all says so", () => {
  const out = summarizeSurface(reply(4, 4, () => NO_COLUMN));
  assert.equal(out.emptyColumns, 16);
  assert.ok(out.note);
  assert.equal(out.map, undefined);
});

test("a summary of the largest area is a few KB", () => {
  const out = summarizeSurface(reply(128, 128, (x, z) => 60 + ((x * 7 + z * 13) % 11)));
  assert.ok(JSON.stringify(out).length < 4000, `${JSON.stringify(out).length} characters`);
});
