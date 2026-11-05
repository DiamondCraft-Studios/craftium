# Craftium server companion — 0.6.0-beta.1

The companion runs Craftium's native editing engine on **Paper and Purpur**. There is one build per Minecraft version: **26.2** (Java 25) and **1.21.10** (Java 21). The player uses the Fabric Craftium client and the same editor, tools and shortcuts.

## Install

1. Stop the server.
2. Put the jar for your Minecraft version in the server's `plugins/` folder: `craftium-server-0.6.0-beta.1.jar` for 26.2, or `craftium-server-0.6.0-beta.1+mc1.21.10.jar` for 1.21.10. A build refuses to start on any other Minecraft version.
3. Start Paper or Purpur. Craftium creates `plugins/Craftium/config.yml`.
4. On a client for the same Minecraft version, install Fabric Loader, Fabric API and the matching Craftium mod jar in `mods/` (`craftium-0.6.0-beta.1.jar` for 26.2, `craftium-0.6.0-beta.1+mc1.21.10.jar` for 1.21.10).
5. Join in Creative mode. Operators can edit immediately. Press **Right Shift** for the editor or **Left Alt** for builder controls.

The plugin and client are different JARs. The server does not need Fabric or Fabric API. Older Craftium 0.2 clients do not support the Paper connection. Vanilla clients can join normally, but cannot use the Craftium editor.

Each build targets the internals of its own Minecraft version. Other Minecraft versions, Spigot, Folia and proxy routing are not validated. The 1.21.10 build has been validated on Paper only, not Purpur.

## What works

The plugin shares the Fabric edition's editing implementation: selections, brushes and terraforming, masks, scripting, shape and path creation, block transformations, builder placement, biome editing, display entities and annotations, clipboard and schematics, blueprint and font libraries, hotbars, persistent undo/redo and animated rebuilds. Existing development-preview limitations listed in [FEATURES.md](FEATURES.md) still apply.

Time, weather, vanilla gamerules, game mode and flight speed have separate permissions. Editor visuals and reference images run on the client.

**Current Paper/Purpur differences:** The custom block-gravity, farmland-trampling, block-drop and player-invulnerability rules require Fabric server mixins. The companion rejects those options with an explanation. Vanilla gamerules and ordinary enhanced flight work. Anti-cheat plugins may need their own configuration for extended reach or faster flight.

## Permissions

All permissions default to operators. Grant `craftium.edit` to trusted builders using your server's permission manager; grant additional permissions as needed. Editing still requires Creative mode.

| Permission | Allows |
| --- | --- |
| `craftium.edit` | Editor, brushes, selections, clipboard, undo/redo, entities, saved hotbars and views |
| `craftium.assets` | Asset upload, blueprint save and export |
| `craftium.world` | Time, weather and supported world rules |
| `craftium.gamemode` | Switch your own game mode |
| `craftium.capabilities` | Extended reach and flight controls |
| `craftium.rebuild` | Animated rebuild and cancellation |
| `craftium.admin` | `/craftiumserver status` and `/craftiumserver reload` |

Additional permissions require `craftium.edit`. No permission grants arbitrary server command execution. Craftium's fixed world/view commands use a limited native command source after authorization.

## Configuration and storage

`allowed-worlds: []` allows all worlds; otherwise list Bukkit world names. `/craftiumserver reload` reloads the file. `/craftiumserver status` reports connected clients and protection status. Outgoing messages are paced across ticks; byte and frame budgets are configurable with bounded values.

Shared assets live in `plugins/Craftium/blueprints`, `fonts`, `heightmaps` and `exports`. History and saved views live in the server world directory under `craftium/`. Client downloads go to the local `config/craftium` library when explicitly requested by that client. History is separated by player and dimension. Edits retain the existing 131,072-cell and 32 MiB history limits.

## Protection integration

Craftium checks world restrictions, Creative mode, edit permission, world border and spawn protection before writing. Undo, redo and rebuild also pass the protection preflight. Biome checks cover every block in each affected 4×4×4 cell. Entity checks include source and destination positions.

When WorldGuard 7 is installed and `worldguard: true`, the optional bridge checks build, block-place and block-break flags, including WorldGuard bypass permissions. A failed bridge initialization blocks edits and reports the error. WorldGuard itself is not bundled.

Native bulk edits **do not fire Bukkit BlockPlaceEvent or BlockBreakEvent per block**. Other claim/logging plugins need a Craftium integration. They can depend on `Craftium` and listen for the synchronous, cancellable `dev.craftium.paper.api.CraftiumEditEvent`. The event supplies immutable lists of block positions, biome cells (quart coordinates) and entity UUIDs. `getBiomeBlocks()` supplies an immutable lazy view of all 64 block positions per biome cell, avoiding a large additional allocation. Set a reason and cancel to reject the complete operation before changes begin. It is also fired for undo/redo and rebuild. Event cancellation is checked at the beginning; permission and WorldGuard access are checked again during block application. Rebuild cancellation restores previously removed original blocks as recovery.

## Build and verification

Run `./gradlew build` (Windows: `gradlew.bat`). Client output is in `versions/26.2/build/libs/`; plugin output is in `versions/26.2/server-plugin/build/libs/`. Paperweight uses the pinned official `26.2.build.134-stable` development bundle. LuaJ is bundled; Fabric classes and Minecraft server classes are not bundled in the plugin.

See [VALIDATION.md](VALIDATION.md) for actual tested builds and results.

References: [Paper development bundle](https://docs.papermc.io/paper/dev/userdev/), [Paper plugin messaging](https://docs.papermc.io/paper/dev/plugin-messaging/), [Purpur API](https://api.purpurmc.org/), [WorldGuard build queries](https://github.com/EngineHub/WorldGuard/blob/master/worldguard-core/src/main/java/com/sk89q/worldguard/protection/regions/RegionQuery.java).
