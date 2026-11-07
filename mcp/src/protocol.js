// The small part of the Model Context Protocol this server speaks: JSON-RPC 2.0 messages for initialize, ping,
// tools/list and tools/call. Written out here, instead of pulled in as a library, so the whole extension can be read in a few minutes.
import { BridgeError } from "./bridge.js";
import { ArgumentError, INSTRUCTIONS, TOOLS } from "./tools.js";

export const VERSION = "0.6.0";
const PROTOCOLS = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];

const reply = (id, result) => ({ jsonrpc: "2.0", id, result });
const failure = (id, code, message) => ({ jsonrpc: "2.0", id, error: { code, message } });
const problem = (text) => ({ isError: true, content: [{ type: "text", text }] });

/**
 * The most text one reply carries. A model reads about 3 characters of this JSON per token, so this is about 20,000 tokens: clients cut
 * longer tool results (Claude Code at 25,000 tokens by default) without saying what was lost, so a longer answer is refused with what to
 * ask for instead. Pictures are not counted.
 */
export const REPLY_LIMIT = 60_000;

/** A bridge reply as a tool result: ok:false as an error the model can act on, a screenshot as an image beside its details. */
export function toResult(bridgeReply, tooBig) {
  if (bridgeReply && bridgeReply.ok === false) return problem(String(bridgeReply.error ?? "Failed"));
  const { ok, image, mime, images, ...rest } = bridgeReply ?? {};
  const text = JSON.stringify(rest);
  if (text.length > REPLY_LIMIT)
    return problem(`The answer is ${Math.round(text.length / 1000)} KB (about ${Math.round(text.length / 3000) * 1000} tokens), more than the ${REPLY_LIMIT / 1000} KB a reply is held to so it fits in your context. ${tooBig ?? "Ask for less at a time."}`);
  if (Array.isArray(images)) {
    // Several pictures of one box: each followed by its own details (angle, compass), then what they share.
    const content = [];
    for (const { image: data, mime: type, ...details } of images) {
      if (typeof data === "string") content.push({ type: "image", data, mimeType: type ?? "image/png" });
      content.push({ type: "text", text: JSON.stringify(details) });
    }
    content.push({ type: "text", text });
    return { content };
  }
  if (typeof image === "string") return { content: [{ type: "image", data: image, mimeType: mime ?? "image/jpeg" }, { type: "text", text }] };
  return { content: [{ type: "text", text }] };
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
      const answer = await bridge.call(tool.bridge ?? tool.name, sent);
      // A tool may reshape what the game sent (get_surface summarises it) from what the model asked; an error is passed on as it is.
      const shaped = tool.post && answer && answer.ok !== false ? tool.post(answer, given) : answer;
      return { result: toResult(shaped, tool.tooBig) };
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
