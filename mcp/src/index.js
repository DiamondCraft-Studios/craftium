#!/usr/bin/env node
// Reads one JSON-RPC message per line from stdin and writes one per line to stdout, as MCP clients expect of a local server.
import { createInterface } from "node:readline";
import { Bridge } from "./bridge.js";
import { createHandler } from "./protocol.js";

const handle = createHandler(new Bridge());
const send = (message) => process.stdout.write(JSON.stringify(message) + "\n");
const pending = new Set();

createInterface({ input: process.stdin }).on("line", (line) => {
  if (!line.trim()) return;
  let message;
  try {
    message = JSON.parse(line);
  } catch {
    send({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } });
    return;
  }
  const work = handle(message)
    .then((response) => { if (response) send(response); })
    .catch((error) => { console.error("craftium-mcp: unexpected failure:", error); })
    .finally(() => pending.delete(work));
  pending.add(work);
}).on("close", async () => {
  await Promise.allSettled([...pending]); // the client has gone; finish what it asked for, then stop
  process.exit(0);
});

// stdout carries the protocol, so anything for a human goes to stderr.
console.error("craftium-mcp ready; waiting for an MCP client");
