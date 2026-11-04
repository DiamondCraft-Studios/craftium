# AI building with Claude Desktop

Craftium can be driven from an AI app such as Claude Desktop. You chat in the AI app ("build me an Egyptian pillar here"), Claude picks an area and describes the build as a handful of shapes, and Craftium shows it as a **ghost preview** in your game. Nothing changes in the world until the build is applied, and every applied build is one undo step.

It runs on your own Claude subscription through the AI app. Craftium never sees your account and needs no API key.

```
Claude Desktop ──stdio──▶ craftium-mcp (Node) ──127.0.0.1──▶ Craftium client mod ──▶ the editor's normal requests
```

## Set up

1. Install the Craftium mod for your Minecraft version as usual. For multiplayer, Craftium must also be on the server (Fabric, or the Paper/Purpur companion).
2. Get **craftium.mcpb** (see [Getting the extension](#getting-the-extension)) and install it in Claude Desktop: open the file with Claude Desktop (double-click it), or drag it into Settings → Extensions. It needs Node.js 18 or newer, which Claude Desktop supplies for extensions of this kind.
3. In Minecraft, open a world and the editor (Right Shift), open the **AI** menu in the top bar and choose **Turn on AI building**. The setting is remembered, and a coloured square appears beside the menu while it is on. (The commands `/craftium ai on`, `/craftium ai off` and `/craftium ai` do the same.)
4. If your game does not use the default `.minecraft` folder (a Prism, Modrinth App or CurseForge profile, for example), open the extension's settings in Claude Desktop and choose the profile's game folder, the one that contains `config` and `mods`.

A preview made by an AI app is drawn in **violet**, so it is never mistaken for your own blue edit previews, and the marker reads **AI preview** while one is waiting.

While AI building is on, a small **AI** marker shows in the top right corner of the game: amber while it waits for the AI app, green once the app has connected, and blue while a request is being answered. The **AI** menu also has **Apply AI preview**, **Discard AI preview** and **Undo last edit**, so you can approve a build without going back to the chat, and **AI status and setup…**, a page with the same status, the last request and the steps above.

Then ask Claude for a build while the game is open. Claude can select an area in front of you, or you can select one yourself first. Look at the ghost in the game, ask for changes, and say when to apply it.

## Getting the extension

`craftium.mcpb` is built from the [mcp](../mcp) folder. Until it is attached to a release, you can get it two ways:

- **From CI:** the *Build craftium-mcp* workflow on GitHub packs it on every change to `mcp/`. Open a run and download the `craftium-mcpb` artifact.
- **Build it yourself** (needs Node.js 18 or newer and a network connection once, to fetch the packer):
  ```sh
  cd mcp
  npm ci
  npm run pack
  ```
  This writes `mcp/craftium.mcpb`. If `npx` stops with "Yallist is not a constructor", your npm is too old for your Node version (npm 10.8 with Node 25, for example): update npm with `npm install -g npm@latest`, or use Node 22 or 24.

## What Claude can do

| Tool | Does |
| --- | --- |
| `get_state` | Your position and facing, the selection, and whether a preview is waiting |
| `get_blocks` | Reads the blocks in a box (up to 32,768 at a time) |
| `get_palette` | Your current block and recently used blocks |
| `get_screenshot` | A picture of what you see in the game, including the preview, so Claude can check its own work |
| `look_at` | Turns your view (not your position) toward a block so the picture shows the build, and turns it back afterwards |
| `set_selection`, `clear_selection` | Chooses the area builds go in |
| `preview_build` | Shows a build as a ghost, replacing any earlier preview |
| `apply_preview` | Builds the preview, as one undoable step |
| `cancel_preview`, `undo` | Discards the preview, or undoes your last edit |

Claude cannot do anything the editor cannot. Every request goes through the same checks as your own edits: server permissions, protected areas, WorldGuard, the world border and size limits.

## Build specs

Claude describes a build as JSON primitives with positions relative to the selection's lowest corner: `box`, `sphere` (or ellipsoid), `cylinder`, `cone`, `pyramid`, `line`, `set` and `replace`. Any operation can be `hollow` (shapes) or `repeat` with a step (a row of columns). A later operation overwrites an earlier one, and `minecraft:air` carves. A palette maps short names to block ids, including states such as `minecraft:sandstone_stairs[facing=north]`.

```json
{
  "palette": { "sand": "minecraft:cut_sandstone", "cap": "minecraft:smooth_sandstone" },
  "ops": [
    { "op": "cylinder", "base": [2, 0, 2], "radius": 2, "height": 8, "block": "sand" },
    { "op": "box", "from": [0, 8, 0], "to": [4, 9, 4], "block": "cap" }
  ]
}
```

Limits: 256 operations, 64 repeats, radius 128, at most 256 blocks along each side of the selection and 131,072 blocks in total. Blocks that fall outside the selection are dropped. The server rejects unknown block ids, with a message naming the operation at fault, so Claude can correct itself.

A build ignores your brush settings (mask, symmetry, height range) and places exactly what the spec says. It does not check that blocks such as torches have support. Primitives suit pillars, walls, towers, domes and similar shapes; organic shapes such as trees or terrain are better done with the brush tools.

## Safety

- The bridge is **off until you turn it on**, and it listens on `127.0.0.1` only.
- Each launch writes a new random token to `config/craftium/bridge.json`. Requests without it are refused, as are requests that come from a web page (they carry an `Origin`) or name another host.
- `get_screenshot` sends a picture of your game window (at most 1024 pixels wide) to the AI app, so close anything private on screen first. It is only taken when the AI app asks, and the corner marker shows **AI working…** while it does. `look_at` only turns your view, never moves you.
- Builds are always staged as a ghost first, even if you have turned confirmation off for your own edits. Applying is a separate step.
- The file is deleted when the bridge stops. If Minecraft crashes and leaves it behind, the extension reports that the game is not running.
- Anyone who can read your Minecraft folder or run programs as you can read the token, as with any local app. Turn the bridge off when you are not using it.

## What the extension can do on your computer

Claude Desktop warns that installing an extension "will grant this extension access to everything on your computer". That is true of every extension: it runs as an ordinary program with your permissions, and Claude Desktop does not sandbox it. The warning is generic, so here is what this one actually does, and how that is enforced.

- **It is tiny and has no dependencies.** The packed extension is 8 files and about 11 kB: four short scripts (`mcp/src`, around 375 lines), a manifest, an icon and a readme. It uses no npm packages at run time, so there is no supply chain behind it to audit. It speaks the small part of the MCP protocol it needs itself.
- **It reads one file:** `config/craftium/bridge.json`, in the default Minecraft folder or the folder you choose in its settings. That file holds the port and token the game wrote for this launch.
- **It talks to one place:** `127.0.0.1`, the game's bridge on your own computer. It has no code that contacts the internet, starts programs, evaluates text as code, opens listening sockets or writes files.
- **It forwards only what each tool names.** A tool call is checked, and only the keys that tool defines are sent to the game, so extra data a model adds goes nowhere.
- **It does not enlarge what the game allows.** The game applies its own checks to everything it receives (see Safety).

These limits are written as tests in [mcp/test/contract.test.js](../mcp/test/contract.test.js): the build fails if the code gains a dependency, imports another Node module, reads another file or environment variable, makes any network call other than the one loopback `fetch`, or can run a program. A change that widens the extension's reach has to change that file as well, where a reviewer will see it.

You do not have to trust the packed file either: it is built from the source in [mcp](../mcp), and you can read it in a few minutes and build it yourself (see Getting the extension). The extension is not signed or verified by Anthropic, which is what the "developer information has not been verified" line in the install dialog means.

**Fence it in further (optional).** Node can restrict a program to the folders you name. If you set the server up by hand instead of installing the `.mcpb`, use Node 22.13 or newer and list only the extension folder and your Minecraft `config` folder, in `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "craftium": {
      "command": "node",
      "args": [
        "--permission",
        "--allow-fs-read=C:\\path\\to\\craftium\\mcp\\src",
        "--allow-fs-read=C:\\Users\\you\\AppData\\Roaming\\.minecraft\\config\\craftium",
        "C:\\path\\to\\craftium\\mcp\\src\\index.js"
      ]
    }
  }
}
```

Under `--permission` the process can read only those two folders and cannot start programs or load native add-ons. (Node 24 does not restrict network access, so the loopback-only guarantee still comes from the code and its tests.) Use absolute paths, and if you use a launcher profile, point the second folder at that profile's `config\craftium`. The packed extension does not use this mode, because it cannot know which Node version Claude Desktop runs it with.

## Troubleshooting

- **"Craftium is not reachable"**: open a world, turn on AI building (AI menu in the editor), and check the Minecraft folder setting in the extension if you use a launcher profile.
- **"Craftium refused the connection"**: Minecraft restarted and the token changed. Try again, or run `/craftium ai off` then `/craftium ai on`.
- **"Craftium on the server"**: the world needs Craftium on the server too, as for the editor. In singleplayer this is automatic.
- **Errors like `ops[2] (cylinder): missing number "radius"`** are the spec's fault, not yours. Claude gets the message and retries.
- Nothing builds on a server: you need the `craftium.edit` permission and Creative mode, as for normal editing.

## For developers

- Bridge: [AiBridge.java](../src/client/java/dev/craftium/client/AiBridge.java). A small loopback HTTP server (no JDK HTTP module, since Minecraft's bundled runtime may omit it) answering `POST /<tool>` with a JSON body and `Authorization: Bearer <token>`. Requests are spaced 120 ms apart because the server silently drops requests that follow another within 65 ms.
- Build specs: [BuildSpec.java](../src/main/java/dev/craftium/core/BuildSpec.java) (pure, unit tested) and [BuildPlanner.java](../src/main/java/dev/craftium/server/BuildPlanner.java), reached by the `ai_build_preview` request. Apply and cancel reuse `pending_confirm` and `pending_cancel`.
- MCP server: [mcp/](../mcp), with no run-time dependencies. `npm test` starts the real server and talks to it with the official MCP client (a test-only dependency) against a fake bridge, and also checks the limits in `contract.test.js`. `npm run pack` builds the `.mcpb`.
- Game test: `CRAFTIUM_TEST_SCOPE=ai gradlew :versions:mc-1.21.10:runClientGameTest` enables the bridge in a real world, drives it over HTTP, then runs the real MCP server over stdio to build a pyramid.
