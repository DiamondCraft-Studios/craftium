# AI building with Claude Desktop

Craftium can be driven from an AI app such as Claude Desktop. You chat in the AI app ("build me an Egyptian pillar here"), Claude looks at the world and describes the build as a handful of shapes, and Craftium shows it as a **ghost preview** in your game. Nothing changes in the world until the build is applied, and every applied build is one undo step.

It runs on your own Claude subscription through the AI app. Craftium never sees your account and needs no API key.

```
Claude Desktop ──stdio──▶ craftium-mcp (Node) ──127.0.0.1──▶ Craftium client mod ──▶ the editor's normal requests
```

## Set up

1. Install the Craftium mod for your Minecraft version as usual. For multiplayer, Craftium must also be on the server (Fabric, or the Paper/Purpur companion).
2. Get **craftium.mcpb** (see [Getting the extension](#getting-the-extension)) and install it in Claude Desktop: open the file with Claude Desktop (double-click it), or drag it into Settings → Extensions. It needs Node.js 18 or newer, which Claude Desktop supplies for extensions of this kind.
3. In Minecraft, open a world and the editor (Right Shift), open the **AI** menu in the top bar and choose **Turn on AI building**. The setting is remembered, and a coloured square appears beside the menu while it is on. (The commands `/craftium ai on`, `/craftium ai off` and `/craftium ai` do the same.)
4. If your game does not use the default `.minecraft` folder (a Prism, Modrinth App or CurseForge profile, for example), open the extension's settings in Claude Desktop and choose the profile's game folder, the one that contains `config` and `mods`.

A preview made by an AI app is drawn with a **violet** outline, its blocks at half opacity, so it is never mistaken for your own blue edit previews, and the marker reads **AI preview** while one is waiting. Its colours are in **Selection Options**, under **AI preview**.

While AI building is on, a small **AI** marker shows in the bottom right corner of the game: amber while it waits for the AI app, green once the app has connected, and blue while a request is being answered. The **AI** menu also has **Apply AI preview**, **Discard AI preview** and **Undo last AI build**, so you can approve a build without going back to the chat, and **AI status and setup…**, a page with the same status, the last request and the steps above.

Then ask Claude for a build while the game is open. Claude works on the world itself: it does not need you to select anything, never moves or turns you, and can build anywhere in the world (see [Claude's workspace](#claudes-workspace)). If you have selected an area it takes that as a hint of where you want the build, and it can read exactly what you selected: say "I have selected a platform for you to build on" after picking one with the box, lasso or magic wand, and it sees the shape, what it is made of and the room above it instead of hunting for the spot. Nothing makes you do this; you can give coordinates or just describe where. Look at the ghost in the game, ask for changes, and say when to apply it.

## Getting the extension

`craftium.mcpb` is built from the [mcp](../mcp) folder. Until it is attached to a release, you can get it two ways:

- **From CI:** the *Build craftium-mcp* workflow on GitHub packs it on every change to `mcp/`. Open a run and download the `craftium-mcpb` artifact.
- **Build it yourself** (needs Node.js 18 or newer and a network connection once, to fetch the packer):
  ```sh
  cd mcp
  npm ci
  npm run pack
  ```
  This writes `mcp/craftium.mcpb`. The `mcp` folder pins Node 24 in `.nvmrc` (run `nvm use` or `fnm use` there if your tool does not switch on its own). If `npx` stops with "Yallist is not a constructor", your npm is too old for your Node version (npm 10.8 with Node 25, for example): use Node 24, or update npm with `npm install -g npm@latest`.

## As a Claude plugin (Claude Code and Cowork)

The [mcp](../mcp) folder is also a Claude plugin: the same server plus the **craftium-builder** skill, which teaches Claude to build the way a person does (brief, site, an approved blockout, then shell, roof, openings, materials, detail), at Minecraft's scale rather than real-world scale, in stages that stay under the preview limit, and with stairs the right way round. In Claude Code:

```
/plugin marketplace add DiamondCraft-Studios/craftium
/plugin install craftium@craftium
```

The plugin needs Node.js 18 or newer on your PATH (an extension gets Node from Claude Desktop, a plugin does not), and asks for a **Minecraft folder** when you enable it if your game uses a launcher profile. Claude Desktop's Chat tab does not run a plugin's local server, so use the extension there; install one or the other, not both. Steps 2 and 3 of [Set up](#set-up) apply either way.

## What Claude can do

| Tool | Does |
| --- | --- |
| `get_state` | Your position and facing, the box around the area you have selected (yours, and never changed by Claude), whether Claude's preview is waiting, the world's height limits, sea level and the biome you stand in, and your current and recently used blocks |
| `read_selection` | What you have selected, as it really is: how many blocks, whether it is a solid box or an irregular shape (lasso or magic wand), whether it is flat, what blocks it is made of, and how much clear space is above it. Read only. It works for any selection, up to 1,048,576 blocks, and the answer is a few lines whatever its size |
| `read_blocks` | Reads the blocks in a box (up to 1,048,576 at a time), anywhere in the world, exactly, with their states: the world as it is, the world with Claude's waiting preview applied, or the preview alone, so it can check a site, or a roof before applying it. The server reads it, so ground your game has not loaded is real blocks and not air. The answer is as boxes (`[x1,y1,z1,x2,y2,z2]` per block, so a wall is a few entries) or as runs, whichever is shorter, in at most about 60 KB |
| `get_surface` | The ground of an area (up to 128 by 128 columns), to find ground level: every column for a small area or a strip, and for a wide one a summary (height range, how level it is, what it is made of, a coarse map) |
| `get_screenshot` | A picture of what you see in the game, including the preview |
| `render_view` | Claude's own camera: a picture of any box of the world from an angle it picks (see [Claude's own view](#claudes-own-view)), so it can check its work without touching your view |
| `preview_build` | Shows a build as a ghost, replacing any earlier preview, or adding to it (`mode: "add"`) so a build kept as a preview grows phase by phase and is still applied and undone as one |
| `apply_preview` | Builds the preview, as one undoable step, loading the ground first if nobody is near it |
| `cancel_preview`, `undo` | Discards the preview, or undoes the last build Claude applied (never your own edits) |

Claude cannot do anything the editor cannot. Every request goes through the same checks as your own edits: server permissions, protected areas, WorldGuard, the world border and size limits.

## Claude's workspace

Claude has a workspace of its own, apart from yours. It never changes your selection, your preview or your undo history, and it never moves or turns you. It can read your selection (`read_selection`), but only to see where you want a build.

- **Its preview** is held separately and outlined in violet. You apply or discard it from the **AI** menu (Enter and Esc still belong to your own edits). On a multiplayer server, other players who have Craftium and are in the same dimension see your AI preview too, in teal with your name above it, and you see theirs. They can only look: nobody else can apply or discard it. The **AI** tab has a **Show AI previews from other players** setting to hide them, and one is drawn only within 192 blocks of you. It is shown only while it is waiting: once applied or discarded, or when its owner leaves or changes dimension, it disappears.
- **Its undo** only covers builds Claude applied. **Undo last AI build** and the `undo` tool undo those, and nothing else; your own Ctrl+Z is unchanged. This list is not saved: it is dropped when you leave the world, reconnect or change dimension.
- **Building far away works.** A build is planned and applied by the server, so it can be anywhere in the dimension you are in, with nobody nearby. The ground it reaches is loaded for it, and ground nobody has visited is made (as flying there would). One build may reach at most **36 chunks that are not already loaded** (a square 96 blocks across); a larger one is refused with a message, so Claude builds closer to loaded ground or in pieces. Undoing a far build loads the ground again in the same way.
- **You still decide.** A build is always shown as a preview first and applied as a separate step. It goes through the same checks as your own edits: permissions, protected areas, WorldGuard and the world border.

## Claude's own view

`render_view` draws a box of the world as flat-shaded blocks, each in the average colour of its texture (so resource packs show), seen from an angle Claude chooses: four isometric corners (`se`, `sw`, `ne`, `nw`), straight down (`top`, north at the top), or level with one side (`north`, `south`, `east`, `west`, for judging heights). Up to three angles can be asked for at once (`angles`), drawn from one read of the world, which takes about half the time of separate calls. It never turns or moves you and does not need the box to be near you. Your waiting preview is drawn over the world tinted violet, so Claude sees what you see. The picture is for Claude only; you keep looking at the violet ghost in the game.

It has no textures, shadows or transparency (water and glass draw as solid colour), so it is for shape, proportion and layout, not for fine detail. The game describes an AI preview up to 32,768 blocks, the blocks that show first, and this picture draws all of those. The ghost in the game draws block models for the first 8,192 of them (the **AI preview models** setting in Preferences) and the rest as an outline. When a preview is bigger than 32,768 blocks, `get_state`, `preview_build` and `render_view` all say so (`previewTruncated`, with how many blocks are described and a warning), so Claude does not mistake the missing blocks for a mistake in the build; the whole preview is still built when it is applied. A box can be up to 128 blocks a side and 262,144 blocks (for example 64 by 64 by 64); it is read from the server in 32-block pieces, each within the chunk limit below.

## Reading the preview

`render_view` draws the preview in flat colour (it cannot show which way a stair faces) and, for a very big preview, only the part the game draws. `read_blocks` reads the preview itself. It answers in the fewest words: for each block, the boxes it fills, so a 10 x 10 x 10 block of wood is one entry wherever it sits in the box that was asked about, where run-length runs would need a run per row. Where boxes stop helping (noisy ground) it answers with runs instead, and says which. `source: "after"` (the default) shows the world as it would be once the preview is applied, which is the world itself when no preview is waiting; `source: "world"` leaves the preview out; `source: "preview"` shows just what the preview places and clears. The server reads the preview in one pass over the edit it already holds, copying and indexing nothing, so a read costs a walk of the build and the box, and a box may hold up to 1,048,576 blocks. The answer is held to about 60 KB, like every reply (see [Reading the world](#reading-the-world)); for a big build Claude reads it a part at a time.

## Reading the world

`read_blocks` and `get_surface` are answered by the **server's** world, not by what your game has loaded, so Claude can look at ground that is far from you. A position above or below the world's height limits, or past the world border, reads as `craftium:outside_world` rather than air. Reading ground nobody has visited makes it (as flying there would), so **one call may touch at most 16 chunks that are not already loaded**. A read that needs more is refused with a message saying so, and Claude is told to read a smaller area or read it in pieces. Chunks that are already loaded do not count towards the 16, and chunks past the world border are never read. The other limits are 1,048,576 blocks for `read_blocks` and 128 by 128 columns for `get_surface`. With a server whose Craftium predates 0.10.0-beta.1, a world read falls back to the older read of at most 32,768 blocks.

Every reply Claude gets is held to about **60 KB** (roughly 20,000 tokens): AI apps cut longer tool results without saying what was lost (Claude Code at 25,000 tokens). A longer answer is refused with what to ask for instead, such as a smaller box. So that a wide area still fits, `get_surface` sends every column only for up to 256 of them (a 16 by 16 area, or a strip up to 128 long) and otherwise a summary: the lowest, highest and median height, how much of it is level, what the ground is made of, and a map of at most 16 by 16 cells, each the highest top in it. Claude can ask for either with `detail`.

The reads are plain reads: they change nothing, and they need the same permission as editing (`craftium.edit` on Paper/Purpur, operator level on Fabric). They only read the **dimension you are in**; another dimension is not reachable from here.

## Build specs

Claude describes a build as JSON primitives with positions in **world coordinates**, the same numbers `read_blocks` and `get_surface` report, so it builds at the heights it found. An optional `origin` is added to every coordinate, for a build written near zero and placed anywhere. Nothing is selected first: the area a build covers is worked out from its operations. The primitives are: `box`, `sphere` (or ellipsoid), `cylinder`, `cone`, `pyramid`, `line`, `set` and `replace`. Any operation can be `hollow` (shapes) or `repeat` with a step (a row of columns). `hollow` keeps every outer cell, including the top and bottom layers of a box or cylinder, and leaves the inside as it was rather than clearing it. A later operation overwrites an earlier one, and `minecraft:air` carves. A palette maps short names to block ids, including states such as `minecraft:sandstone_stairs[facing=north]`.

```json
{
  "palette": { "sand": "minecraft:cut_sandstone", "cap": "minecraft:smooth_sandstone" },
  "ops": [
    { "op": "cylinder", "base": [102, 64, -38], "radius": 2, "height": 8, "block": "sand" },
    { "op": "box", "from": [100, 72, -40], "to": [104, 73, -36], "block": "cap" }
  ]
}
```

Beyond the primitives:

- **Materials.** A block can be named by material and kind, such as `spruce@stairs` or `stone_bricks@wall`, and the server spells the real id (`spruce_stairs`, `stone_brick_wall`). With `"material": "spruce"` on the spec or an operation, `@stairs` alone means spruce stairs, so one word restyles a build.
- **Mixes.** A block can be a weighted mix, such as `{"mix": [["stone_bricks", 6], ["mossy_stone_bricks", 3], ["cracked_stone_bricks", 1]]}`, scattered over the shape. The pick depends on the position and an optional `seed`, so the same spec always builds the same pattern.
- **Roofs.** `roof` covers a footprint with a gable, hip or shed roof, every stair facing the right way and the hip corners shaped as the game shapes them, the gable ends filled and the ridge capped.
- **Stairs.** `stairs` lays a straight 45° flight between two points, each step facing the way you climb, optionally on solid columns.
- **Scripts.** `script` runs a short Lua program over a box, for what shapes describe badly: natural ground, rocks, a tree, a pattern worked out block by block. It runs on the server in the same sandbox as Craftium's Lua brushes, after the shapes (whose blocks it can read), and may only place blocks inside its own box. It is given a fixed seed, so the same spec always builds the same, and it is limited to about 5 million Lua steps (around half a second) for all the scripts in a spec.
- **Groups.** `group` holds operations and turns them as one: rotated by quarter turns, mirrored, or moved with `at`. Stairs, logs, doors and other blocks with a facing turn with it, the same way the game turns them in a structure.

Limits: 256 operations (counting those inside groups, which go up to 4 deep), 64 palette entries, 16 blocks in a mix, 64 repeats, radius 128, a build that spans at most 256 blocks along each side, and 131,072 blocks in total; a spec that reaches further is refused with a message saying which side. The ground a build reaches is loaded for it (see [Claude's workspace](#claudes-workspace)). The server rejects unknown block ids with a message naming the operation at fault and the nearest real ids (`spruce_stair`: `minecraft:spruce_stairs`), and a state a block does not have with the properties it takes, so Claude can correct itself in one go. A refused spec leaves the preview that was waiting as it was.

The reply to a preview says what it made: blocks placed and cleared, the box they fill, the chunks reached and the commonest blocks, with how the ones that face a way are turned. A gable roof has two facings in equal numbers, so a roof with its stairs the wrong way round shows there before anything is drawn.

To change what you have selected, the build can include `replace_selection`: say you selected the stair part of a build and ask "can you change this to deepslate?". Claude reads your selection, then replaces every matching block in it (or every block, with no `find`), and each new block keeps the properties of the old one that both have, so stairs stay turned the way they were and slabs keep their half. It never changes your selection, can cover up to a million blocks, and is shown as a preview first like any build.

A build ignores your brush settings (mask, symmetry, height range) and places exactly what the spec says. It does not refuse a torch with nothing to hold it or half a door, but the reply to the preview warns Claude about them, and about an operation that later ones hide entirely. Primitives suit pillars, walls, towers, domes and similar shapes; organic shapes such as trees or terrain are better done with the brush tools.

## Safety

- On a server, an AI preview is visible to the other players with Craftium in your dimension while it waits (see [Claude's workspace](#claudes-workspace)). Nothing else of yours is shared: not your selection, your own edits or your requests.
- The bridge is **off until you turn it on**, and it listens on `127.0.0.1` only.
- Each launch writes a new random token to `config/craftium/bridge.json`. Requests without it are refused, as are requests that come from a web page (they carry an `Origin`) or name another host.
- `get_screenshot` sends a picture of your game window (at most 1024 pixels wide) to the AI app, so close anything private on screen first. It is only taken when the AI app asks, and the corner marker shows **AI working…** while it does. `render_view` does not use your screen at all: it draws from block data, so it shows nothing but blocks.
- Builds are always staged as a ghost first, even if you have turned confirmation off for your own edits. Applying is a separate step. The ghost is Claude's own, so it cannot be applied by accident with your own Enter key.
- The file is deleted when the bridge stops. If Minecraft crashes and leaves it behind, the extension reports that the game is not running.
- Anyone who can read your Minecraft folder or run programs as you can read the token, as with any local app. Turn the bridge off when you are not using it.

## What the extension can do on your computer

Claude Desktop warns that installing an extension "will grant this extension access to everything on your computer". That is true of every extension: it runs as an ordinary program with your permissions, and Claude Desktop does not sandbox it. The warning is generic, so here is what this one actually does, and how that is enforced.

- **It is tiny and has no dependencies.** The packed extension is 9 files and about 27 kB, most of it the licence text: four short scripts (`mcp/src`, around 390 lines), a package file, a manifest, an icon, a readme and the licence. It uses no npm packages at run time, so there is no supply chain behind it to audit. It speaks the small part of the MCP protocol it needs itself.
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
- **"The Craftium mod in Minecraft is older than this Craftium plugin"**: the extension or plugin needs a newer mod. Update the mod, or install the extension that came with your mod. When the mod is the newer one, Claude is told to suggest updating the plugin, and everything still works.
- **A build is refused with "This server runs Craftium …"**: the server's Craftium predates roofs, groups, mixes and material names. Claude can still build with the plain shapes; ask the server owner to update for the rest.
- **Running the game from source (`gradlew runClient`) or from a launcher profile**: the game does not use your default `.minecraft` folder, so the extension cannot find it on its own. Open **AI menu → AI status and setup…**, press **Copy game folder**, and paste it into the extension's **Minecraft folder** setting. (For a source run that is `versions/<version>/run` in the checkout you launched from, so make sure you launched from the checkout that has the AI feature.)
- **"Craftium refused the connection"**: Minecraft restarted and the token changed. Try again, or run `/craftium ai off` then `/craftium ai on`.
- **"Craftium on the server"**: the world needs Craftium on the server too, as for the editor. In singleplayer this is automatic.
- **Errors like `ops[2] (cylinder): missing number "radius"`** are the spec's fault, not yours. Claude gets the message and retries.
- **"That build reaches more than 36 chunks that are not loaded"**: the build is too large for ground nobody has visited. Claude builds closer to loaded ground, or in smaller pieces.
- Nothing builds on a server: you need the `craftium.edit` permission and Creative mode, as for normal editing.

## For developers

- Bridge: [AiBridge.java](../src/client/java/dev/craftium/client/AiBridge.java). A small loopback HTTP server (no JDK HTTP module, since Minecraft's bundled runtime may omit it) answering `POST /<tool>` with a JSON body and `Authorization: Bearer <token>`. Requests are spaced 120 ms apart because the server silently drops requests that follow another within 65 ms.
- Build specs: [BuildSpec.java](../src/main/java/dev/craftium/core/BuildSpec.java) (pure, unit tested) and [BuildPlanner.java](../src/main/java/dev/craftium/server/BuildPlanner.java), reached by the `ai_build_preview` request. `BuildSpec.place()` works out the area from the operations, so no selection is needed. The AI's workspace is a second `Session` per player in `EditorServer` (`AI_SESSIONS`), used through `ai_build_preview`, `ai_apply`, `ai_cancel` and `ai_undo`. Its statuses are described through the player's own session, with the AI's preview and undo count in the status fields `aiPendingData`, `aiUndo` and `aiUndoName`. Jobs for it load chunks as they go (`Job.load`), and `AiWorld.MAX_BUILD_CHUNKS` caps how many a build may make.
- Pictures: [VoxelView.java](../src/main/java/dev/craftium/core/VoxelView.java) is the pure renderer (unit tested, no game needed) and [AiView.java](../src/client/java/dev/craftium/client/AiView.java) gathers the blocks, block colours and preview for it. `render_view` reuses `ai_blocks` for the data, in 32-block tiles.
- Server reads: [AiWorld.java](../src/main/java/dev/craftium/server/AiWorld.java) answers the `ai_blocks`, `ai_surface` and `ai_surface_floor` requests with a `Packets.AiReply` (the `ai_reply` message on Paper). Their limits are the constants at the top of that file (`MAX_BLOCKS`, `MAX_COLUMNS`, `MAX_NEW_CHUNKS`, `MAX_REPLY`); change them there and in the tool descriptions in [mcp/src/tools.js](../mcp/src/tools.js), and update this page. These requests carry their two corners in `target` and `normal`, so `EditorServer` handles them before it checks `normal` as a direction.
- MCP server: [mcp/](../mcp), with no run-time dependencies. `npm test` starts the real server and talks to it with the official MCP client (a test-only dependency) against a fake bridge, and also checks the limits in `contract.test.js`. `npm run pack` builds the `.mcpb`.
- Game test: `CRAFTIUM_TEST_SCOPE=ai gradlew :versions:mc-1.21.10:runClientGameTest` enables the bridge in a real world, drives it over HTTP, then runs the real MCP server over stdio to build a pyramid.
