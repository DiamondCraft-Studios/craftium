# Axiom research and Craftium design references

Researched 20–21 October 2026. Sources below are the official Axiom documentation and official Fabric material, plus the two UI reference images supplied with the request. The documentation site's update marker is 31 July 2025; it may not describe every subsequent Axiom change.

## Workflow and UI

[Editor Mode](https://axiomdocs.moulberry.com/editor/intro.html) describes a 3D editing workspace, configurable windows, a top menu, contextual options and a bottom control strip. Familiar controls include Right Shift, Ctrl+C/X/V, Ctrl+Z/Y, Ctrl+R/F and Enter/Delete. Craftium adopts these interaction conventions and the reference images' left-tool/right-inspector arrangement.

[Windows](https://axiomdocs.moulberry.com/editor/windows/intro.html) and the documentation navigation identify Tools, Tool Options, Palette, Inventory, Active Block, History, World Properties, Clipboard, Target Info, Text Annotations, Keybinds, Blueprint Browser, Tool Masks, Style Editor and Views. Craftium implements core panels, movable/resizable layouts and a paged inspector; full arbitrary tab docking is still absent.

## Complete documented tool inventory

| Category | Tools in the official documentation |
| --- | --- |
| [Selection](https://axiomdocs.moulberry.com/tools/selection/intro.html) | Magic Wand, Box Select, Freehand Select, Lasso Select |
| [Utility](https://axiomdocs.moulberry.com/tools/utility/intro.html) | Ruler, Annotation |
| [Painting](https://axiomdocs.moulberry.com/tools/painting/intro.html) | Painter, Noise Painter, Biome Painter, Script Brush, Gradient Painter |
| [Drawing](https://axiomdocs.moulberry.com/tools/drawing/intro.html) | Freehand Draw, Sculpt Draw, Rock, Weld, Melt, Modelling, Stamp, Text, Shape, Path |
| [Fluid](https://axiomdocs.moulberry.com/tools/fluid/intro.html) | Floodfill, Fluid Ball |
| [Heightmap](https://axiomdocs.moulberry.com/tools/heightmap/intro.html) | Elevation, Slope; flattening is also an elevation mode |
| [Manipulation](https://axiomdocs.moulberry.com/tools/manipulation/intro.html) | Smooth, Distort, Roughen, Shatter, Extrude, Modify |

[Generic brush options](https://axiomdocs.moulberry.com/tools/intro.html) include sphere, cube and octahedron shapes. [Selection operations](https://axiomdocs.moulberry.com/tools/selection/intro.html) include addition, subtraction, replacement and intersection. Craftium implements these, plus a cylinder brush.

[Noise Painter](https://axiomdocs.moulberry.com/tools/painting/noisepainter.html) documents several noise families and material distributions. Craftium uses FastNoiseLite families, fractal controls and ordered weighted materials. [Rock](https://axiomdocs.moulberry.com/tools/drawing/rock.html), [Weld](https://axiomdocs.moulberry.com/tools/drawing/weld.html), [Melt](https://axiomdocs.moulberry.com/tools/drawing/melt.html) and [Smooth](https://axiomdocs.moulberry.com/tools/manipulation/smooth.html) motivated the independent volume/noise/density algorithms; they are not algorithmic replicas. Craftium's Gaussian brush smoothing conserves the eligible solid count, but its boundary and material choices are independent approximations.

[Biome Painter](https://axiomdocs.moulberry.com/tools/painting/biomepainter.html) documents Minecraft's four-block biome grid. Craftium stores changes at that same native granularity and includes them in history.

## Builder and context capabilities

[Builder Tools](https://axiomdocs.moulberry.com/builder/intro.html) cover Move, Clone, Stack, Smear, Extrude, Erase and Symmetry. The documentation also describes marker entities, a colour picker and a gradient helper. Craftium now exposes all seven in-game builder tools, half-block symmetry pivots, entity/passenger copying, colour matching and multi-stop palette gradients. Grid stacking and complete textured previews remain unfinished.

[Capabilities](https://axiomdocs.moulberry.com/contextmenu/capabilities/intro.html) include Tinker, No Updates, Force Place, Replace Mode, Bulldozer, Infinite Reach, Fast Place, Angel Placement and No Clip. [Tinker](https://axiomdocs.moulberry.com/contextmenu/capabilities/tinker.html) has many block-specific behaviors beyond simple property cycling. Craftium implements the capabilities requested in the prompt, with the narrower scope described in FEATURES.md. Craftium provides a game-mode switch and an operator-controlled Creative no-clip capability.

[Tool Masks](https://axiomdocs.moulberry.com/editor/windows/toolmasks.html) document boolean logic, spatial conditions, source-state rules and Lua scripting. Craftium provides nested boolean/spatial rules, a visual rule-block editor, Lua masks and typed script variables. Scripts run under instruction and memory limits without host file/network/Java access.

Other documented features include placements, transform gizmos, presets, blueprints, display-entity and block-attribute editing, multiple views, custom fonts and heightmaps. These were considered during research and are recorded as future work, rather than exposed as nonfunctional controls.

## Fabric 26.2

[Fabric's 26.2 notes](https://www.fabricmc.net/2026/06/15/262.html) describe the Java 25 / unobfuscated Minecraft development environment, updated GUI ownership and renderer backend changes. [Fabric's official example project](https://github.com/FabricMC/fabric-example-mod/tree/26.2) informed the split client/common setup. Version availability was checked against Fabric's live metadata and Maven repositories.

Pinned project dependencies: Minecraft 26.2, Fabric Loader 0.20.1, Fabric API 0.161.0+26.2 and Loom 1.17.21. The checked-in wrapper uses Gradle 9.7.1. All game-facing code was compiled against these actual artifacts.

## Expanded audit

All 95 linked index pages were retrieved for the 0.2 work. [PARITY-AUDIT.md](PARITY-AUDIT.md) and [FEATURES.md](FEATURES.md) distinguish functional equivalents, partial implementations and outstanding work. The product-specific license and whitelist infrastructure is outside this independent mod.

## Current expansion

The 0.2 development build adds the virtual tenth slot, world-persistent history, entity/passenger clipboards, animated rebuild, CIELAB fields, bounded ray-lit Autoshade, the documented Lua helpers and typed variables, visual mask rule blocks, weighted palettes, client/server blueprint transfer, viewing controls and expanded editor gizmos. The audit and feature list remain authoritative for unfinished parity.
