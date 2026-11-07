# Craftium for Claude

Build in Minecraft by chatting. Ask Claude for a build ("a small spruce cottage on the concrete in front of me"),
and Claude looks at your world, describes the build as a handful of shapes, and shows it as a **ghost preview** in
your game. Nothing changes in the world until you approve it, and every applied build is one undo step.

This plugin connects Claude to the [Craftium](https://github.com/DiamondCraft-Studios/craftium) Minecraft mod
running on your own computer. It needs no account and no API key. User guide:
[docs/AI.md](https://github.com/DiamondCraft-Studios/craftium/blob/main/docs/AI.md).
Privacy: [PRIVACY.md](https://github.com/DiamondCraft-Studios/craftium/blob/main/mcp/PRIVACY.md).

## What you need

- **The Craftium mod** for your Minecraft version, in the game you will build in. For multiplayer, Craftium must
  also be on the server.
- **AI building turned on** in the game: open the editor (Right Shift), open the **AI** menu and choose
  **Turn on AI building**. It is off until you do, and it listens on `127.0.0.1` only.
- **Node.js 18 or newer** on your PATH. The plugin's server is a small Node program with no dependencies.

## What's in it

- **The tools Claude uses:** read the world (`get_state`, `get_blocks`, `get_surface`, `get_palette`), look at it
  (`render_view`, `get_screenshot`), and build (`preview_build`, `apply_preview`, `cancel_preview`, `undo`).
- **The `craftium-builder` skill.** It teaches the order a builder works in (brief, site, an approved blockout, then
  shell, roof, openings, materials, detail and context), Minecraft-scale proportions, how to build in stages around
  the preview limit, and how to get stair and roof orientation right.

## Install

**Claude Code**

```
/plugin marketplace add DiamondCraft-Studios/craftium
/plugin install craftium@craftium
```

**Cowork** runs plugins too. **Claude Desktop's Chat tab** does not run a plugin's local server, so use the Craftium
extension there (`craftium.mcpb`, see the user guide). Install one or the other, not both, so the server is not
registered twice.

If your game does not use the default `.minecraft` folder (a Prism, Modrinth App or CurseForge profile, for
example), the plugin asks for a **Minecraft folder** when you enable it. Choose the profile's game folder, the one
that contains `config` and `mods`.

## Then

Open a world with AI building on, and ask Claude for a build. Look at the violet ghost in the game, ask for changes,
and say when to apply it. You can also apply, discard or undo from the game's **AI** menu.

## For developers

The server has **no run-time dependencies**: four small files in `src/` speak the part of MCP it needs. The official
MCP SDK is a dev dependency used only by the tests, and the packed extension carries no `node_modules`. What the code
may and may not do is pinned down in `test/contract.test.js`.

| File | Does |
| --- | --- |
| `src/index.js` | Reads JSON-RPC lines from stdin, writes them to stdout |
| `src/protocol.js` | `initialize`, `ping`, `tools/list`, `tools/call` |
| `src/tools.js` | The tools, their schemas and argument checks, and the guidance given to the model |
| `src/bridge.js` | Finds the bridge file and calls the game on `127.0.0.1` |
| `skills/craftium-builder/` | The building skill and its reference files |

```sh
npm ci             # once (installs the test-only SDK)
npm test           # protocol, end-to-end and contract tests
npm run pack       # builds craftium.mcpb for Claude Desktop (needs network for the packer)
node test/e2e-client.mjs <path to config/craftium/bridge.json>   # builds a pyramid through a running game
```

The server finds the bridge's port and token in `config/craftium/bridge.json` of the default Minecraft folder. Set
`CRAFTIUM_MINECRAFT_DIR` to a profile's game folder, or `CRAFTIUM_BRIDGE_FILE` to the file itself, for launchers with
their own folders. The plugin passes its **Minecraft folder** setting as `CRAFTIUM_MINECRAFT_DIR`.

`mcp/` is both the plugin and the source of the `.mcpb`: `.claude-plugin/`, `.mcp.json` and `skills/` belong to the
plugin and are left out of the extension (see `.mcpbignore`).

## License

GPL-3.0-only. See [LICENSE](https://github.com/DiamondCraft-Studios/craftium/blob/main/mcp/LICENSE).
