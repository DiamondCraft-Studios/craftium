# Craftium

[![Support me on Ko-fi](https://img.shields.io/badge/Ko--fi-Support%20Craftium-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/deviank)

Craftium is free and open source. If you find it useful, you can [support the Claude usage it burns on Ko-fi](https://ko-fi.com/deviank).

**A vibe-coded, open-source building studio for Minecraft. It's an early development preview, so expect rough edges, missing features and the occasional bug.**

Craftium is an AI-assisted, independently implemented Fabric building studio with a Paper/Purpur server companion for **Minecraft Java 26.2** and **1.21.10**. Inspired by Axiom's building workflows, it adopts its documented default keybinds, with original code and icons under the GNU GPL v3 license.

![Craftium editor with a selected watchtower, clipboard and material palette](docs/gallery/01-editor-overview.png)

[Gallery](docs/GALLERY.md) · [Controls](docs/KEYBINDS.md) · [Server companion](docs/SERVER-PLUGIN.md) · [AI building](docs/AI.md) · [Contributing](CONTRIBUTING.md) · [GPL-3.0 license](LICENSE)

**1.4.0-beta.1 is a development preview and doesn't yet cover everything Axiom offers.** This release aligns controls with Axiom's documented defaults and adds configurable editor chords and mouse gestures. It includes the Paper/Purpur companion and a versioned client connection. The editor expands the original 26 tools to 37, adding tenth-slot building, persistent history, entity clipboards, animated rebuild, lighting, scripting, visual masks, weighted palettes and asset transfer. [Feature coverage](docs/FEATURES.md) and the [95-page documentation audit](docs/PARITY-AUDIT.md) identify remaining differences.

## Install

1. Install Fabric Loader for Minecraft **26.2** (tested with **0.20.1**), or for **1.21.10**.
2. Put the matching jar and Fabric API in the instance's `mods` folder: `craftium-1.4.0-beta.1.jar` with **Fabric API 0.161.0+26.2**, or `craftium-1.4.0-beta.1+mc1.21.10.jar` with **Fabric API 0.138.4+1.21.10** or newer.
3. Run Minecraft with **Java 25** (26.2) or **Java 21** (1.21.10). Open a Creative world with cheats enabled.
4. Press **Right Shift** to open Craftium; **View → Studio inspector** opens the detailed inspector.

For multiplayer, choose a Fabric server with the same mod, or install the companion on **Paper/Purpur**: `craftium-server-1.4.0-beta.1.jar` for 26.2, or `craftium-server-1.4.0-beta.1+mc1.21.10.jar` for 1.21.10. Players use the updated Fabric client in both cases. The companion defaults to operators and supports permission grants, protected edits and large transfers. Read [Server installation and permissions](docs/SERVER-PLUGIN.md).

On Fabric servers, editing requires operator permission level 2 and Creative mode. On Paper/Purpur, grant `craftium.edit` and use Creative mode. Four custom Fabric world rules are currently unavailable on Paper/Purpur.

The editor scales to Minecraft GUI settings. A 1280 × 720 or larger window is recommended for readable controls.

## Controls

| Control | Action |
| --- | --- |
| Right Shift | Open / close editor |
| Hold Left Alt | Open builder context menu; release to close |
| 0; Alt + wheel | Select tenth slot; choose builder tool |
| M / B / N / L | Magic / Box / Freehand / Lasso Select |
| P / O / G / H / J / K / E / U / Z | Painter / Noise / Draw / Rock / Weld / Melt / Elevation / Smooth / Extrude |
| Left / middle / right mouse | Camera / pick block / use tool |
| Ctrl + left / right / middle drag | Orbit / pan / adjust radius |
| Minecraft movement keys | Editor flight (normally WASD, Space / Shift) |
| Ctrl + Z / Y | Undo / redo |
| Ctrl + C / X / V / J | Copy / cut / paste / duplicate |
| Ctrl + P / B | Save blueprint dialog / show biome borders |
| Ctrl + F / R | Fill / replace selection; flip / rotate placement |
| E with a Path or Modelling point | Extrude previous point |
| Arrows / Page Up / Page Down | Camera-relative horizontal gizmo nudges / +Y / −Y |
| Enter | Confirm placement or geometry, otherwise clear selection |
| Delete / Backspace | Cancel placement, otherwise delete selected blocks |
| R outside editor | Toggle Replace Mode |

Defaults follow Axiom's published documentation. Other tools and Craftium-only shortcuts start unbound. **View → Keybinds** edits editor chords and mouse gestures; **Tools & Minecraft controls** edits native mappings. The inspector and panel controls remain accessible through View. See the [controls audit](docs/KEYBINDS.md) for upgrade behavior, sources and remaining differences. **View → Colour picker / gradient** opens texture-colour matching and the nine-slot gradient helper.

## Getting started

Choose Box Select and right-click two opposite corners. Pick a block in the palette or type a block identifier, then press **Ctrl+F** to fill. Ctrl+Z reverses the change. The inspector's Selection tab supports coordinate entry, comparison modes and selection modification.

Copy with Ctrl+C, point at the destination and press Ctrl+V. A translucent voxel outline appears without placing blocks. Drag its axis lines or use the camera-relative arrow keys, or right-click another surface to reposition it. Enter confirms; Delete or Escape cancels. The inspector's Placement tab provides snap-to-ground, paste-copy, paste-and-select, shape merging and nearest-neighbour rotation/scaling. Representable facing, axis, half and connection properties turn with the geometry. Vanilla blocks still have only their legal discrete orientations; unsupported variants use a legal approximation.

For Path or Modelling, right-click control points and press Enter to build. Select a point with left-click, drag its gizmo or use the arrow keys to adjust it. Choose algorithms and settings under Geometry. For Gradient Painter, Shift-right-click the endpoints, then paint normally.

For terrain, use Rock, Weld, Melt, Smooth, Elevation and Slope. The inspector exposes noise, smoothing, surface, symmetry and mask settings. Imported PNG heightmaps are used by Elevation. Floodfill has a count limit independent of brush radius; Fluid Ball uses a bounded flow approximation.

## AI building (experimental)

Craftium can be driven from an AI app such as Claude Desktop. You describe a build in chat ("build me an Egyptian pillar here"), Claude reads the world and answers with a list of shapes, and Craftium shows the result as a violet **ghost preview** in your game. Nothing changes until you apply it, and each applied build is one undo step.

**What it is:** Craftium runs a local bridge (off by default, `127.0.0.1` only, random token per launch), and a small, dependency-free MCP extension (`craftium.mcpb`) connects your AI app to it. It uses the Claude subscription you already have in that app. Craftium never sees your account, has no sign-in of its own and needs no API key. There is no AI built into the mod itself.

**What Claude can do:** read blocks and surface heights anywhere in the world, look at your screen on request, render its own camera views to check its work, stage a build as a preview, and apply or undo it. Builds are made from primitives: boxes, spheres, cylinders, cones, pyramids, lines, single blocks and replacements, with hollowing and repeats. Everything goes through the same permission, protected-area, WorldGuard, world-border and size checks as your own edits.

**What to expect:**
- It suits pillars, walls, towers, domes and similar geometric structures. Organic shapes such as trees and terrain are better done with the brush tools, and Claude's results depend on how well it plans the shapes. Expect to iterate on the preview.
- It is an early preview. The extension is not yet attached to a release; you get it from CI or build it yourself. It is not signed or verified by Anthropic, and Claude Desktop will show its generic extension warning.
- It needs Claude Desktop (or another MCP client, including Claude Code through the plugin in [mcp/](mcp)) and Node.js 18 or newer for the extension. The automated game test for the bridge runs on 1.21.10.
- A build is limited to 256 operations and 131,072 blocks, and screenshots you allow are sent to the AI app, so close anything private first.

Setup, the full tool list, limits and the safety model are in [AI building](docs/AI.md).

## Inspector and assets

- **Library:** named tool presets, palettes and clipboard blueprints; authors/tags and a searchable browser with top-down thumbnails and nested folders.
- **Assets:** native file import for `.schem`, legacy `.schematic`, structure `.nbt`, PNG heightmaps and TTF/OTF fonts; schematic, structure and selection CSV export.
- **Script / Masks:** multiline Lua and boolean predicates. Example brush: `if y % 2 == 0 then return blocks.stone end`. Example mask: `all(surface,not(block(minecraft:bedrock)),y(0,100))`.
- **Entities:** create/select/edit/duplicate/delete block, item and text displays and marker entities, fractional transforms, named groups and undo.
- **Annotations:** text, lines, boxes, freehand strokes and small image annotations. Import an image, select Annotation, then right-click a surface. Image annotations are sampled to at most 32 pixels on the longest side.
- **World:** time, weather, tick settings, block gravity, farmland trampling, block/inventory drops, invulnerability and saved views.
- **Hotbars:** named hotbars, automatic saved slots and an inventory picker. Slots and item components are saved with each world/player; up to 4096 automatic slots.
- **Style:** editor colours; drag panel headers and resize their lower-right corners. Window contains panel toggles and saved/copied layouts. File opens local reference-image windows; right-click one for filtering, opacity, borderless and in-game display options.

The blueprint browser switches between Client and Server libraries. Exports up to 1 MiB download into your local `config/craftium/blueprints/` or `exports/`; larger files stay on the server. Only requested files are accepted. Local blueprints transfer directly into the server clipboard. Fonts, heightmaps and server assets live under `config/craftium/` on Fabric or `plugins/Craftium/` on Paper/Purpur. Local reference windows support images up to 2048 × 2048 and 8 MiB.

## Builder tools and lighting

Press **0** in Creative mode for the virtual tenth slot. Hold **Alt** and scroll to choose Move, Clone, Stack, Smear, Extrude, Erase or Symmetry. Left-click the first corner, right-click the second, and middle-click faces to adjust bounds. Scroll starts a preview and nudges it; hold X/Y/Z to constrain direction. Right-click confirms; Escape cancels. Ctrl+R rotates, Ctrl+F mirrors, and Ctrl+Z/Y undo/redo. Stack repeats the configured count along the offset; full multi-axis grid stacking is pending.

Extrude uses right-click to grow a face and left-click to shrink it. Erase uses right-click for up to 128 connected blocks, or Delete for a selected cuboid. Symmetry uses right-click to set a node with half-block pivot support, Ctrl+F for mirror axes and Ctrl+R for fourfold rotation. Keep Existing, Copy Air and Copy Entities are available in the inspector.

**Lighting** provides Autoshade with player, custom-position or sun-angle sources, voxel shadows, hemisphere ambient occlusion, diffuse surface-light propagation, dithering and weighted or automatic texture-colour palettes. This is a bounded approximation. Paint → Weighted palette reorders material stops and adjusts their influence; Colour Studio can interpolate between multiple palette stops.

**Rebuild** animates selected blocks with delay and propagation controls. It persists a recovery history entry before clearing the structure. Ctrl+Z or Cancel restores cleared cells; conflict checks still apply.

## Commands and scripts

Use `/craftium intro`, `editor`, `builder`, `docs`, `status`, `handshake`, `toggle <capability>`, and `pos1` / `pos2 [x y z]` (including `~` coordinates). `/craftium enablecheats` works only in your local singleplayer world. WorldEdit's `//pos1` and `//pos2` are observed without consuming their commands; other WorldEdit selection changes are not synchronized.

Operators can use `/craftium gamerule <rule> <true|false>` with `doBlockGravity`, `doBlockDrops`, `doTrampleFarmland` or `playerInvulnerability`. The rules are saved with the world and sit beside Minecraft's own `/gamerule` list, not in it.

Lua includes numeric block-state IDs, properties, tags, collision tests, seeded noise and resource-pack colour matching. Editable variables use `$int(Height,5,1,20)$`, `$float(Strength,0.5,0,1)$`, `$boolean(Enabled,true)$` and `$blockState(Material,stone)$`. `$once$` runs a brush once per dab. Brush and mask variables have separate controls. The sandbox limits instructions and memory and excludes host file, network and Java access.

## History and limits

World edits are planned on the server, then applied in batches with an 8 ms application budget. Targets must be within 256 blocks, in loaded chunks and within the world border. Selections/operations are limited to 131,072 cells, the ordinary brush radius to 24 and scaled shape extent to 64. Large planning operations can still stall a server tick.

History holds up to 32 entries and an estimated 32 MiB per player; clipboard has its own 32 MiB limit. Block entities, biomes, entities and annotations participate in undo. Conflicting subsequent edits are skipped. Continuous block/biome strokes merge; metadata edits retain separate history. Undo and redo are saved per world/player/dimension and restored when reconnecting. Click a history entry to rewind through it, or use Edit → History browser. Clipboard and selections reset on disconnect; blueprints, annotations, views, world rules and hotbars are saved.

No Updates suppresses neighbour/shape updates during Craftium placement; it does not cancel all pending vanilla ticks. The Paper/Purpur companion integrates WorldGuard and exposes a cancellable edit event; other claim plugins require an integration. Compatibility with large shared servers, Vulkan, shader packs and broad modpacks is unverified.

## Build and validation

Requires JDK 25. Run `./gradlew.bat build`, or `./gradlew.bat :versions:mc-26.2:runClientGameTest` for the disposable Minecraft integration scenario. The 26.2 client jar goes to `versions/26.2/build/libs/`; server plugin output goes to `versions/26.2/server-plugin/build/libs/`. `scripts/package.ps1 -Version 1.4.0-beta.1` creates the distribution bundle.

[Validation](docs/VALIDATION.md) records checks and practical limits. Screenshots in the bundle show the actual Minecraft mod. Craftium is licensed under the [GNU GPL v3](LICENSE) (GPL-3.0-only); bundled component notices are in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). Craftium is an independent project, unaffiliated with Axiom and its authors, and contains no Axiom code, textures or icons. If you can, support the original at [Axiom's site](https://axiomdocs.moulberry.com).
