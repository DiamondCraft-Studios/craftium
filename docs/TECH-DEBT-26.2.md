# Minecraft 26.2 technical debt

Where the 26.2 module (`versions/26.2`, Gradle `:versions:mc-26.2`, `:paper-26.2`) trails 1.21.10 or the shared code. Surveyed 2026-11-13 from the tree on `main` (commit `6656a311`) by comparing the two version modules, plus the docs and release state. Each item says how it was found; **[unverified]** means it was inferred, not run on 26.2.

The 1.21.10 module has been the one developed and played first for several weeks, so most of this is "built on 1.21.10, 26.2 left for later".

## 1. Features that do not exist on 26.2

| Item | Evidence | What is missing |
|---|---|---|
| **Jigsaw planner** | `versions/26.2/src/main/java/dev/craftium/server/JigsawCommand.java` is an empty stub ("the command is not there yet"); [FEATURES.md](FEATURES.md) line 75 says "1.21.10 only for now"; [JIGSAW-PLANNER-PLAN.md](JIGSAW-PLANNER-PLAN.md) says "26.2: the tab's actions are not registered there" | `JigsawPlanner`, `JigsawRuns`, `JigsawEditor`, `JigsawDiagnostics`, `RecordingLevel` exist only in `versions/1.21.10/src/main`. The Studio Jigsaw tab, the pool editor and the planner screen are in shared client code, so on 26.2 they are present but their `craftium:jigsaw_*` actions are not registered **[unverified how the screen behaves]** |
| **Jigsaw Bedrock pack export** | `JigsawBedrockExport.java` is 1.21.10-only | The drag-and-drop Bedrock behavior-pack export of a jigsaw set |
| **Jigsaw agent tools (MCP)** | `jigsaw_read` / `jigsaw_edit` tools sit on the planner above | Not available when the server is 26.2 **[unverified]** |
| **GPU vertex-buffer ghost** | `versions/1.21.10/.../compat/GhostMesh.java` (PR #115) | 26.2 still draws every ghost block by block, which costs about 11 ms a frame for 32,768 scattered blocks (measured on 1.21.10 before the mesh). The 26.2 render API (`LevelRenderContext`, `QuadInstance`, `rendertype`) is different, so it needs its own implementation |
| **Fluid/opacity handling** | `OpacityConsumer.java` and `FovMixin.java` are 1.21.10-only; 26.2 `ClientCompat.fov` just returns `camera.getFov()` | Not a gap if 26.2 gets the same result by other means. **[unverified]** whether translucent ghosts and fluid opacity look the same on 26.2 |

## 2. Test coverage gaps

The two modules have different test sets, so a regression in the shared code can pass on one version and go unseen on the other.

- **1.21.10 has these game tests; 26.2 does not:** `AiBridgeGameTest`, `BedrockPackGameTest`, `DedicatedServerGameTest` (a real dedicated server with a connected client), `GhostDrawCostGameTest` (the frame-cost measurement), `PaperAiGameTest`, `PaperLiveGameTest`, `UiDrawCostGameTest`, `SmokeGameTest`.
- **26.2 has many that 1.21.10 lacks:** builder queue, gizmo drag, handshake, interaction, keybinds, overlay, panels, prediction, replace ghost, saved selections, selection outline, tool hints, history and memory, plugin tools, golden tool output.
- **AI bridge on 26.2:** [VALIDATION.md](VALIDATION.md) says "Minecraft 26.2 and Purpur have not been run for the AI scenario" (the AI-on-Paper run was 1.21.10 build 130 only).
- **Paper 26.2 live run:** the recorded Paper 26.2 build 134 and Purpur 26.2 build 2633 pass is a **0.3.0 baseline**. The mod is now 2.0.0-beta.1, so it is more than a year of features behind in what has been run on that server.
- **Frame-cost measurement:** the `ghostperf` numbers in PR #115 and the earlier 32,768-model figures are 1.21.10 only. There is no 26.2 baseline, so no way to tell whether 26.2 has the same cost.
- [SERVER-TESTS.md](SERVER-TESTS.md): "Not yet covered here (covered on 26.2 or Paper only): brushes and Lua, biomes, gizmos, Autoshade, animated rebuild, symmetry, structure import/export files, and the AI bridge on a Fabric server." That list is about the dedicated-server test, not 26.2 itself, but it shows the coverage is split by version rather than shared.

## 3. Documentation that contradicts the code

- [CONTRIBUTING.md](../CONTRIBUTING.md) line 7 says "the Paper/Purpur companion targets 26.2 only", but there is a `:paper-1.21.10` module and [VALIDATION.md](VALIDATION.md) records a passing 1.21.10 Paper run. One of them is out of date.
- [GALLERY.md](GALLERY.md) and [MODRINTH-GALLERY.md](MODRINTH-GALLERY.md) are "Craftium 0.3.1-dev screenshots from Minecraft Java 26.2". The mod is at 2.0.0-beta.1, so the gallery shows an interface that no longer exists.
- [VALIDATION.md](VALIDATION.md) test environment is dated 21 October 2026 with Fabric Loader 0.20.1 and Fabric API 0.161.0; `versions/26.2/gradle.properties` still pins those. Whether they are current is not checked.

## 4. Release and branch drift

- Latest 26.2 tag is `v1.6.0-beta.1-mc26.2`; the mod version on `main` is `2.0.0-beta.1`. The 1.21.10 tags also stop at `v1.6.0-beta.1-mc1.21.10`, so both are behind `main`, not just 26.2.
- `release/26.2` and `release/1.21.10` are both **1,065 commits behind `main`**. Per [CONTRIBUTING.md](../CONTRIBUTING.md) fixes are cherry-picked into them. At this distance any release needs a new cut from `main`, not a cherry-pick.
- One known fix not yet on `release/26.2`: the Lua recursion crash fix (`d3b6cca`), recorded in the plugin-ecosystem plan as "not yet on release/26.2".

## 5. Code-level divergence

Different APIs force different code, and the per-version copies must be kept in step by hand:

- **`Overlay.java` is written twice** (1.21.10: 409 lines, `WorldRenderContext`, immediate vertex consumers; 26.2: 303 lines, `LevelRenderContext`, `QuadInstance`, submit-based). Every ghost change (cached ghosts, `cachedBlocks`, the mesh) has to be done twice, and the 26.2 side is the one that gets skipped.
- **26.2-only workarounds:** `AbstractTextureAccessor` (an accessor mixin to set a font texture's sampler, used by `FontTextures`) and `SpeleothemMixin` in main. Both are reaching into game internals and may break on any 26.2 update. 26.2's `EditBox` also has no input filter (noted in the structure-export gotchas).
- **Shared code carries `Compat` indirection** (`versions/<mc>/.../compat/`: `Compat`, `FabricCompat`, `ClientCompat`, `CommandCompat`, `Gfx`, `Tex`, `Overlay`, and the Craftium widgets). Anything new that touches the game API needs a method added to both copies.
- **Deprecated API in the build:** both modules compile with "uses or overrides a deprecated API" notes (for example `EditorServer`). Not specific to 26.2, but nothing tracks them.
- 26.2 is a development target (Java 25, Loom 1.17.21, Sodium `mc26.2-0.9.2-fabric`, Paperweight pin `26.2.build.134-stable`). Each is a pin to keep an eye on when 26.2 updates **[unverified whether any is stale]**.

## 6. Suggested order

1. **Decide what 26.2 is for.** If it is the version players run, the Jigsaw planner, the vertex-buffer ghost and the AI bridge run need porting. If 1.21.10 is the primary, say so in [CONTRIBUTING.md](../CONTRIBUTING.md) and the README, and stop 26.2 from silently trailing.
2. **Fix the docs** (section 3). Cheap and removes the contradictions.
3. **Re-run the Paper 26.2 and AI scenarios** against the current build, to find out what has actually broken rather than guess.
4. **Port the ghost mesh** to the 26.2 `Overlay` (the largest performance gap), then measure with `ghostperf` on 26.2 (the test needs porting too).
5. **Port the Jigsaw planner**: the planner and runs first, then the editor and the Bedrock export.
6. **Cut new releases** from `main` for both versions, so the tags catch up.
7. **Share more of the tests** between the two modules so a regression shows on both.
