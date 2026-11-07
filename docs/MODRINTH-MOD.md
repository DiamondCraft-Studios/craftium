# Craftium

Craftium is a creative building editor for **Minecraft Java 26.2 on Fabric**. Select and transform structures, sculpt terrain, paint materials and manage blueprints from a dedicated workspace.

## Editor tools

- **Selections and transforms:** Box, Magic, Freehand and Lasso selection; fill, replace, copy, cut, rotate and mirror, with placement previews and gizmos.
- **Terrain and painting:** Rock, Weld, Melt, Smooth, Elevation, Slope, noise painting and biome editing.
- **Shapes and geometry:** Primitives, paths, modelling, text, stamps and extrusion.
- **Materials:** Weighted palettes, colour matching, gradients, visual masks and sandboxed Lua brushes.
- **Clipboards and libraries:** Schematics, structure NBT, Bedrock `.mcstructure` import and export with a 3D export preview (Structure menu), legacy schematic import, PNG heightmaps, custom fonts and named blueprints. Copy and paste display and marker entities, including entity-only blueprints.
- **History:** Undo and redo for supported edits, saved per player, world and dimension.

## Building in game

Use the tenth hotbar slot for Move, Clone, Stack, Smear, Extrude, Erase and Symmetry. Builder controls also include enhanced flight, Replace Mode, Force Place, No Updates, Tinker, Bulldozer and saved hotbars.

## Familiar controls

Defaults follow Axiom's published keybinds. Press **Right Shift** for the editor, hold **Left Alt** for the context menu, or press **0** to select the tenth slot.

In the editor, left-drag rotates the camera, middle-click picks a block and right-click uses the tool. Hold Ctrl while dragging with the left, right or middle mouse button to orbit, pan or adjust the brush radius. Standard copy, paste and undo shortcuts are supported. Placement shortcuts take priority over fill and replace while a placement is active.

Change editor chords and mouse gestures under **View → Keybinds**. Tool letters and global bindings use Minecraft's Controls screen.

## Installation

1. Install Fabric Loader and Fabric API for Minecraft **26.2**, using **Java 25**.
2. Put the Craftium mod in your client's `mods` folder.
3. Open a Creative world with cheats enabled, then press Right Shift.

Multiplayer requires Craftium on both sides: the same mod on a Fabric server, or the **Craftium server companion** on Paper/Purpur. Editing requires Creative mode and the appropriate server permissions.

**Current release: 1.0.0-beta.1.** This is a development preview with independent implementations and documented limits. Craftium is independently developed; it does not provide complete Axiom feature or interaction parity.
