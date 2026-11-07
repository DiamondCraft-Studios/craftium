// A summary of the ground the game reported, so a wide area costs a model a few hundred words instead of thousands of numbers.

/** The y the game uses for a column with no block in it (past the world border, or empty). */
export const NO_COLUMN = -2147483648;

/** At most this many columns come back one by one when the model leaves the choice to us ("auto"); more are summarised. */
export const FULL_COLUMNS = 256;

/** The coarse map in a summary is at most this many cells along each side. */
const GRID = 16;

const baseId = (state) => {
  const bracket = state.indexOf("[");
  return bracket < 0 ? state : state.slice(0, bracket);
};

const percent = (part, whole) => Math.round((part * 1000) / whole) / 10;

/**
 * The game's full get_surface reply, summarised: the range and middle of the heights, how much of the area is level with the middle,
 * what the ground is made of, and a coarse map whose cells give the highest top in each, rows running north to south.
 */
export function summarizeSurface(reply) {
  const [sx, sz] = reply.size;
  const heights = reply.heights;
  const known = [];
  for (const h of heights) if (h !== NO_COLUMN) known.push(h);
  const out = { ok: true, source: reply.source, kind: reply.kind, detail: "summary", min: reply.min, size: reply.size, columns: heights.length };
  if (reply.chunksLoadedForThisRead !== undefined) out.chunksLoadedForThisRead = reply.chunksLoadedForThisRead;
  if (known.length < heights.length) out.emptyColumns = heights.length - known.length;
  if (known.length === 0) {
    out.note = "No column here has a block in it (past the world border, or empty).";
    return out;
  }
  const sorted = [...known].sort((a, b) => a - b);
  const median = sorted[Math.floor(sorted.length / 2)];
  let level = 0;
  for (const h of known) if (Math.abs(h - median) <= 1) level++;
  out.heights = { lowest: sorted[0], highest: sorted[sorted.length - 1], median };
  out.levelWithMedianPercent = percent(level, known.length);

  const counts = new Map();
  for (let i = 0; i < heights.length; i++) {
    if (heights[i] === NO_COLUMN || reply.blocks[i] < 0) continue;
    const id = baseId(reply.palette[reply.blocks[i]]);
    counts.set(id, (counts.get(id) ?? 0) + 1);
  }
  out.surface = [...counts].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1)).slice(0, 8).map(([block, n]) => ({ block, percent: percent(n, known.length) }));

  const cell = Math.max(1, Math.ceil(Math.max(sx, sz) / GRID));
  const rows = [];
  for (let cz = 0; cz < sz; cz += cell) {
    const row = [];
    for (let cx = 0; cx < sx; cx += cell) {
      let top = null;
      for (let z = cz; z < Math.min(cz + cell, sz); z++)
        for (let x = cx; x < Math.min(cx + cell, sx); x++) {
          const h = heights[x + sx * z];
          if (h !== NO_COLUMN && (top === null || h > top)) top = h;
        }
      row.push(top);
    }
    rows.push(row);
  }
  out.map = {
    cell,
    rows,
    reading: `Each number is the highest top in a ${cell} by ${cell} cell (null: no block). Rows run north to south (z rising) from the minimum corner, and each row runs west to east (x rising).`,
  };
  out.next = 'For every column, call get_surface again with detail "full" on a smaller area (a 1-wide strip, or the part you will build on).';
  return out;
}
