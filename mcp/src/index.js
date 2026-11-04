#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { Bridge } from "./bridge.js";
import { createServer } from "./server.js";

// stdout carries the protocol, so anything for a human goes to stderr.
const server = createServer(new Bridge());
await server.connect(new StdioServerTransport());
console.error("craftium-mcp ready; waiting for an MCP client");
