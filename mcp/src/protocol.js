// The small part of the Model Context Protocol this server speaks: JSON-RPC 2.0 messages for initialize, ping,
// tools/list and tools/call. Written out here, instead of pulled in as a library, so the whole extension can be read in a few minutes.
import { BridgeError } from "./bridge.js";
import { ArgumentError, INSTRUCTIONS, TOOLS } from "./tools.js";

export const VERSION = "0.4.0";
const PROTOCOLS = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];

const reply = (id, result) => ({ jsonrpc: "2.0", id, result });
const failure = (id, code, message) => ({ jsonrpc: "2.0", id, error: { code, message } });
const problem = (text) => ({ isError: true, content: [{ type: "text", text }] });

/** A bridge reply as a tool result: ok:false as an error the model can act on, a screenshot as an image beside its details. */
export function toResult(bridgeReply) {
  if (bridgeReply && bridgeReply.ok === false) return problem(String(bridgeReply.error ?? "Failed"));
  const { ok, image, mime, ...rest } = bridgeReply ?? {};
  if (typeof image === "string") return { content: [{ type: "image", data: image, mimeType: mime ?? "image/jpeg" }, { type: "text", text: JSON.stringify(rest) }] };
  return { content: [{ type: "text", text: JSON.stringify(rest) }] };
}

/** Returns a function that takes one parsed message and gives back the response to send, or null when none is due. */
export function createHandler(bridge) {
  const tools = new Map(TOOLS.map((tool) => [tool.name, tool]));
  const listed = TOOLS.map(({ name, title, description, inputSchema, annotations }) => ({ name, title, description, inputSchema, annotations }));

  async function call(params) {
    const tool = tools.get(params?.name);
    if (!tool) return { error: failure(null, -32602, `Unknown tool: ${params?.name}`).error };
    const given = params.arguments ?? {};
    if (given === null || typeof given !== "object" || Array.isArray(given)) return { result: problem("Arguments must be an object") };
    let sent;
    try {
      sent = tool.args(given);
    } catch (error) {
      if (error instanceof ArgumentError) return { result: problem(error.message) };
      throw error;
    }
    try {
      return { result: toResult(await bridge.call(tool.name, sent)) };
    } catch (error) {
      if (error instanceof BridgeError) return { result: problem(error.message) };
      return { result: problem(`Internal error: ${error?.message ?? error}`) };
    }
  }

  return async function handle(message) {
    if (message === null || typeof message !== "object" || Array.isArray(message)) return failure(null, -32600, "Invalid request");
    const { id, method, params } = message;
    const request = typeof id === "string" || typeof id === "number";
    if (typeof method !== "string") return request ? failure(id, -32600, "Invalid request") : null;
    switch (method) {
      case "initialize": {
        if (!request) return null;
        const asked = params?.protocolVersion;
        return reply(id, {
          protocolVersion: PROTOCOLS.includes(asked) ? asked : PROTOCOLS[0],
          capabilities: { tools: {} },
          serverInfo: { name: "craftium", version: VERSION },
          instructions: INSTRUCTIONS,
        });
      }
      case "ping":
        return request ? reply(id, {}) : null;
      case "tools/list":
        return request ? reply(id, { tools: listed }) : null;
      case "tools/call": {
        if (!request) return null;
        const outcome = await call(params);
        return outcome.error ? { jsonrpc: "2.0", id, error: outcome.error } : reply(id, outcome.result);
      }
      default:
        // Notifications (initialized, cancelled and the like) need no answer; anything else asked for is not offered.
        return request ? failure(id, -32601, `Method not found: ${method}`) : null;
    }
  };
}
