# Privacy: the Craftium plugin

This describes what the Craftium plugin for Claude (the MCP server in this folder) does with data. It is a plain
statement of what the code does, so you can check it against the source. It is not legal advice.

## What the plugin is

The plugin is a small program that runs on your own computer. When you ask Claude to build something in Minecraft,
Claude uses the plugin to talk to the Craftium mod running in your game, on the same computer. Nothing here needs
an account, and the plugin has no server of its own.

## What it can see and send

When Claude calls a tool, the plugin asks your game and passes the answer to Claude as the tool's result. Depending
on the tool, that can include:

- Block data: the blocks in an area you or Claude chose, and the height of the ground there.
- Your position and facing in the world, the dimension and biome you are in, the area you have selected, and your recent block palette.
- A picture of your game window, only when Claude asks for one (`get_screenshot`). It is at most 1,024 pixels wide
  and includes whatever is on your screen in the game window, so close anything private first.
- A picture of a box of the world drawn from block data (`render_view`). It shows only blocks, not your screen.

That data goes to Claude, as part of your conversation, and is handled under your agreement with Anthropic and the
settings of the Claude app you use. The plugin does not control what the AI app does with it.

## What it does not do

- It does not contact the internet. Its only network use is requests to `127.0.0.1`, the game on your own computer,
  and this is checked by tests in `test/contract.test.js` that fail if it changes.
- It reads one file: `config/craftium/bridge.json` in your Minecraft folder, which holds the port and a random token
  the game wrote for that launch. It writes no files.
- It starts no programs and has no analytics, telemetry or crash reporting.
- It keeps nothing between runs.

## What stays on your control

- The game side is off until you turn it on (AI menu, then Turn on AI building), and it listens on `127.0.0.1` only.
- Every build is shown as a preview first and applied only as a separate step. Applied builds can be undone.
- The token file is deleted when the bridge stops. Anyone who can read your Minecraft folder or run programs as you
  can read it while it exists, as with any local app, so turn the bridge off when you are not using it.

## Contact

Questions or concerns: open an issue at https://github.com/DiamondCraft-Studios/craftium/issues.
