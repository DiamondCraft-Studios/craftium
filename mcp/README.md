# craftium-mcp

An [MCP](https://modelcontextprotocol.io) server that lets Claude Desktop (or any MCP client) build in Minecraft through the Craftium mod's local AI bridge. User guide: [docs/AI.md](../docs/AI.md).

It has **no run-time dependencies**: four small files in `src/` speak the part of MCP it needs. The official MCP SDK is a dev dependency used only by the tests, and the packed extension carries no `node_modules`. What the code may and may not do is pinned down in `test/contract.test.js`.

| File | Does |
| --- | --- |
| `src/index.js` | Reads JSON-RPC lines from stdin, writes them to stdout |
| `src/protocol.js` | `initialize`, `ping`, `tools/list`, `tools/call` |
| `src/tools.js` | The tools, their schemas and argument checks, and the guidance given to the model |
| `src/bridge.js` | Finds the bridge file and calls the game on `127.0.0.1` |

```sh
npm ci             # once (installs the test-only SDK)
npm test           # protocol, end-to-end and contract tests
npm run pack       # builds craftium.mcpb for Claude Desktop (needs network for the packer)
node test/e2e-client.mjs <path to config/craftium/bridge.json>   # builds a pyramid through a running game
```

The server finds the bridge's port and token in `config/craftium/bridge.json` of the default Minecraft folder. Set `CRAFTIUM_MINECRAFT_DIR` to a profile's game folder, or `CRAFTIUM_BRIDGE_FILE` to the file itself, for launchers with their own folders.

## As a Claude Code plugin

`mcp/` is also a Claude Code plugin (`.claude-plugin/plugin.json` and `.mcp.json`), listed by the repo's `.claude-plugin/marketplace.json`:

```
/plugin marketplace add <this repo>
/plugin install craftium@craftium
```

For a launcher with its own game folder, set `CRAFTIUM_MINECRAFT_DIR` in the environment Claude Code runs in.
