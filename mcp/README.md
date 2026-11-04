# craftium-mcp

An [MCP](https://modelcontextprotocol.io) server that lets Claude Desktop (or any MCP client) build in Minecraft through the Craftium mod's local AI bridge. User guide: [docs/AI.md](../docs/AI.md).

```sh
npm install        # once
npm test           # unit tests against a fake bridge
npm run pack       # builds craftium.mcpb for Claude Desktop (needs network for the packer)
node test/e2e-client.mjs <path to config/craftium/bridge.json>   # builds a pyramid through a running game
```

The server finds the bridge's port and token in `config/craftium/bridge.json` of the default Minecraft folder. Set `CRAFTIUM_MINECRAFT_DIR` to a profile's game folder, or `CRAFTIUM_BRIDGE_FILE` to the file itself, for launchers with their own folders.
