# Craftium Server

The server companion for the Craftium building mod. It runs Craftium's editing engine on **Paper and Purpur for Minecraft Java 26.2 and 1.21.10**, while players use the Fabric client for the editor and building controls.

## Features

- Selections, bulk block editing, terrain brushes, painting, masks and Lua brushes.
- Clipboard placements, schematics, blueprints, display and marker entities, and entity-only clipboards.
- Biome editing, persistent undo/redo, saved hotbars and animated rebuilds.
- Separate permissions for editing, assets, world controls, game modes, capabilities and rebuilds.
- World restrictions, optional WorldGuard integration and a cancellable Craftium edit event for other integrations.

The editor UI, previews and keybinds run on the client. Vanilla clients can join the server, but cannot use Craftium's editor.

## Installation

1. Stop your Paper or Purpur server. Use the **26.2** build on Java 25, or the **1.21.10** build on Java 21.
2. Put the jar for your Minecraft version in `plugins/` (`craftium-server-0.9.1-beta.1.jar` for 26.2, `craftium-server-0.9.1-beta.1+mc1.21.10.jar` for 1.21.10), then start the server.
3. Players install the matching **Craftium Fabric mod** and **Fabric API** on their clients.
4. Join in Creative mode. Operators can edit immediately; grant `craftium.edit` to other builders.

The server does not need Fabric or Fabric API. Keep the client and companion versions matched.

## Permissions and configuration

Permissions default to operators. Additional grants include `craftium.assets`, `craftium.world`, `craftium.gamemode`, `craftium.capabilities` and `craftium.rebuild`; these also require `craftium.edit`.

Configure allowed worlds and connection budgets in `plugins/Craftium/config.yml`. Use `/craftiumserver status` and `/craftiumserver reload` with `craftium.admin`.

## Compatibility

WorldGuard integration checks protected edits, including undo and redo. Bulk edits use **CraftiumEditEvent** rather than individual Bukkit block-place and block-break events; other protection and logging plugins need a Craftium integration.

Craftium's custom gravity, farmland, block-drop and invulnerability rules require a Fabric server. They are unavailable in this companion. Spigot, Folia, other Minecraft versions and proxy routing are not validated.

**Current release: 0.9.1-beta.1.** This is a development preview. Editing is limited to 1,048,576 cells per selection/operation, and large planning operations can still stall a server tick.
