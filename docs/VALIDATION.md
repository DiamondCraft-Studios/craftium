# Craftium 0.3.1-dev validation

Test environment: 21 October 2026, Minecraft 26.2, Fabric Loader 0.20.1, Fabric API 0.161.0+26.2 and Java 25 on Windows. Rendering uses OpenGL on Intel UHD Graphics 620.

## Build and unit tests

`gradlew.bat build` compiles both editions and runs **53 JUnit tests** (46 core/client and 7 server), with zero failures or errors in the latest build. Coverage includes geometry, noise, selections, masks, convex hulls, colour conversion, Lua sandboxing, typed variables, weighted palettes, builder transform bijections and rule-tree serialization/cycle prevention. New checks cover bounded bridge fragmentation, corruption and expansion limits, lazy biome protection coverage, event cancellation and interrupted undo/redo recovery.

## Fabric Minecraft integration

The test mod creates a disposable Creative world and uses the actual Fabric networking channel. The final complete runtime scenario is run with:

`gradlew.bat build :versions:mc-26.2:runClientGameTest`

**0.2 baseline full-run status: passed.** The combined build and Minecraft run completed successfully in 3 minutes 27 seconds. All scenario markers, including gizmos, vertical block-state rotation and UI checks, passed. Minecraft closed normally. An additional focused builder regression passed in 1 minute 11 seconds, including overlapping movement with Keep Existing enabled.

**0.3 full regression status: passed.** The complete scenario ran again after the shared-engine and companion changes, including every tool, history/rebuild, clipboard/entity, scripting, gizmo, rotation and UI marker. Minecraft shut down normally. The log includes a long overnight pause; its 7h 23m elapsed walltime is not a performance measurement.

The runtime scenario checks:

- Selection/fill/undo/redo, masks, overlapping movement, cloning, stacking, limits and later-edit conflicts.
- Original brush tools plus lasso, curves, modelling, stamp, smear, modify, gradients, scripts, floodfill and fluids. Algorithm checks cover selected examples, not every terrain shape.
- Biomes, undo and continuous stroke merging.
- Chest clipboard/rotation/paste/undo preserving 17 diamonds; moving entity/passenger copy/cut/paste/undo/redo; NBT and schematic interchange; legacy chest and numeric item upgrades.
- World rules, no-clip/reach, flight speed, hotbars and game modes.
- History rewind/redo, disk save and session eviction/reload restoring saved edits.
- Rebuild completion/cancellation, recovery history and chest inventory restoration.
- Lua native block-state IDs, properties, tags, collision, noise, RGB matching, variables, once-per-dab and independent mask variables.
- Unique CIELAB colour-field placement and undo.
- Tenth-slot activation through the actual 0 key; movement, clone, rotation, stack, erase, half-block and rotational symmetry.
- Custom-light and automatic-palette Autoshade, camera flight, momentum decay, and renderer refresh for opacity/brightness changes.
- Client export receipt, direct local blueprint transfer, world-rule/client commands and position visualization.
- Actual axis dragging and selection scale handles; fractional display edits composing the existing matrix; vertical piston-facing and log-axis rotations.
- Editor shortcuts, menu/modal input, inspector/palette/mask screens and HUD restoration.

## Controls audit regression

**0.3.1 full Fabric regression passed in 2 minutes 48 seconds**, including all editing, builder, clipboard, entity, history, scripting, gizmo, rotation and UI markers. The final focused controls run passed in 1 minute 9 seconds, including clipboard flips facing X, Y and Z, selected-entity copy/paste/delete/undo in the editor and game, entity Cut/Duplicate placements and entity-only blueprint save/load. It exercises all 13 native tool keys, E point extrusion versus Elevation, Ctrl-drag pan/orbit/radius without painting, biome toggle, saved custom chords, camera-relative arrow/Page Up nudges, contextual fill/rotate/flip, Copy/Save Blueprint/Duplicate/Cut, held-Alt menus, tenth-slot reselection and exact stair-property copying. The dedicated controls screen was captured and visually checked.

Default modifier matching is also tested independently, including lock-key flags and exclusion of unrelated modifiers. Camera-relative nudge checks cover cardinal directions and opposite/perpendicular steps across multiple rotations. These checks compare Craftium against documented behavior; Axiom itself was not launched for runtime comparison. Remaining gizmo-interaction/configuration differences are listed in [KEYBINDS.md](KEYBINDS.md).

## Paper and Purpur runtime

**Paper 1.21.10 build 130 passed** with the 1.21.10 companion (`:paper-1.21.10`) and a 1.21.10 Fabric client connecting over loopback. Paper accepted `api-version: '1.21.10'`, the plugin enabled with no errors, and the live scenario passed: channel negotiation, a fragmented 16,000-position request and selection response, fill/undo/redo, clipboard placement, chest contents surviving delete/undo, denied edits after `deop`, and opening the real editor. This is a smaller scenario than the 26.2 one below: biome editing, blueprint transfer, Lua brushes, world commands, WorldGuard and the public edit event were not exercised on 1.21.10, and Purpur was not tested. Run it with a Paper 1.21.10 server on `127.0.0.1:25575` (RCON on 25675, password `craftium-local-test-only`, offline mode) and `CRAFTIUM_TEST_SCOPE=paper ./gradlew :versions:mc-1.21.10:runClientGameTest`.

**Previous 0.3.0 baseline passed on both Paper 26.2 build 134 and Purpur 26.2 build 2633**, with the Fabric 0.3 client connecting over actual loopback TCP connections. The final companion scenario completed in 1 minute 49 seconds and exited normally. WorldGuard 7.0.19 and WorldEdit 7.4.6 were installed for protection checks.

The scenario verifies channel negotiation, a fragmented 16,000-position request and selection response, fill/undo/redo, clipboard placement, seven diamonds surviving chest delete/undo, biome replication and undo, blueprint export and actual client file receipt, blueprint reload, bundled Lua brushes, fixed world commands, denied unprivileged edits, explicit non-operator edit grants, separate world permissions, cancellation through the public edit event, and WorldGuard block flags protecting edits and redo. It opens and captures the real editor on each server. Every test server and RCON endpoint binds only to 127.0.0.1; these are disposable fixtures.

The optional test entrypoint uses `CRAFTIUM_TEST_SCOPE=paper` and `CRAFTIUM_TEST_PROTECTION=true` with both prepared loopback fixtures running. `:paper-26.2:liveTestPluginJar` builds a fixture plugin used only by this suite; it is excluded from release bundles. The suite ends by disconnecting and returning to the title screen. Server shutdown saves history normally.

## AI building on Paper

**The AI bridge passed against Paper 1.21.10 build 130** with the 1.21.10 companion and a 1.21.10 Fabric client over loopback, in a flat world, with `CRAFTIUM_TEST_SCOPE=paper-ai` (the server setup is the same as for the scenario above: offline mode, RCON on port 25675 with the fixed local test password). It drives the bridge over HTTP, as the MCP extension does, and checks what only a dedicated server shows: reads and builds answered over the plugin's bridge frames (`ai_reply`), the world's layers and surface read from the server, a pyramid previewed, applied and undone 2000 blocks from the player in ground nobody had loaded, the player's own selection left alone by all of it, a build that needs too much new ground refused with a message, and reads and builds refused once operator status is removed. The server logged no errors and no lag warnings.

The first run, which generated new ground, took about 12 seconds for the far preview and render; a rerun over the same ground took about a second. Minecraft 26.2 and Purpur have not been run for the AI scenario.

## Visual checks

The shared Craftium mark was added to the mod icon, editor header and builder HUD. Both JARs were checked for identical PNG/SVG assets. A focused Minecraft UI run passed in 51 seconds, including editor, builder, inspector, palette, masks, blueprint browser, reference images and HUD restoration. The new editor capture was visually inspected at 1440 × 900.

Screenshots are produced by Minecraft at 1440 x 900, not by a web mockup. Captures of the inspector, colour studio, blueprint browser, reference windows, weighted palette and visual masks were inspected. Clipped mask-library labels were shortened. The gizmo test caught and corrected an old display selection taking priority over the box selection.

The current release includes a preview of the keybind screen. Diagnostic-only captures are excluded.

## Practical limits

This is a development preview. These checks establish specific behaviors, not complete Axiom parity. Dedicated multi-user stress tests, full WorldEdit interoperability, other claim-plugin integrations, shader renderers, Vulkan and broad modpacks remain unverified. Large planning operations can stall a tick; alternate renderers may bypass water rendering. Remaining resampling, docking, views, annotation and feature gaps are listed in [FEATURES.md](FEATURES.md) and [PARITY-AUDIT.md](PARITY-AUDIT.md).
