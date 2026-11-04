# Contributing to Craftium

Craftium is a vibe-coded, open-source competitor to Axiom. AI assistance is part of its development history. Contributions should be readable, independently implemented and checked against the actual Minecraft build.

## Build

Use Java 25 and the checked-in Gradle wrapper. Craftium builds for Minecraft Java 26.2 and 1.21.10; the Paper/Purpur companion targets 26.2 only.

```sh
./gradlew build compileGametestJava
```

`build` covers every Fabric module and every Paper plugin module. To work on one version, name its modules: `:versions:mc-26.2:build` (Fabric mod) or `:paper-26.2:build` (Paper/Purpur plugin), and likewise for 1.21.10.

On Windows, use `gradlew.bat`. Fabric mod JARs are written to `versions/<version>/build/libs/`; Paper/Purpur companion JARs are written to `versions/<version>/server-plugin/build/libs/`. Use the same version on the client and server.

## Layout

Shared code lives in `src/` and must not contain anything that differs between Minecraft versions. The Paper/Purpur companion follows the same rule: its shared code is in `server-plugin/src/`, and `versions/<version>/server-plugin/` holds that version's `plugin.yml` and dev bundle, built with the shared script `gradle/paper-plugin.gradle`. Each supported version is a module in `versions/<version>/` that adds the code and metadata specific to it: the `compat` adapter classes, mixins that target changed internals, `fabric.mod.json` and the mixin configs. A file in a module replaces the shared file at the same path.

When shared code needs a Minecraft API that differs between versions, call it through an adapter (`Compat`, `ClientCompat`, `Gfx`, `Overlay` and similar) and implement that adapter method in every `versions/<version>/` module. A change inside one module affects only that version.

## Branches and releases

- `main` is the trunk: shared code and every version module. Open pull requests against it.
- `release/<version>` (for example `release/26.2`) is cut from `main` to ship that version and take hotfixes. Cherry-pick fixes into it; do not develop on it.
- Tag releases `v<mod version>-mc<version>`, for example `v0.3.1-mc26.2`.
- CI builds only the versions a change touches. Shared code, the root build files and the workflows build all of them.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/) without a scope: `type: subject`, in the imperative, with the reason in the body. Types are `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci` and `chore`. Mark breaking changes with `!` after the type, for example `refactor!: rename the Gfx adapter`.

## Verify changes

The build runs the core and companion JUnit tests. Changes to world edits should preserve permissions, protection checks, block-entity data, history and operation limits.

The Minecraft integration scenario creates a disposable Creative world and opens a real game window:

```sh
./gradlew :versions:mc-26.2:runClientGameTest
```

The 1.21.10 module has a smaller smoke test that launches the client, applies every mixin, opens the editor and draws each overlay, and writes screenshots to `versions/1.21.10/build/run/clientGameTest/screenshots/`:

```sh
./gradlew :versions:mc-1.21.10:runClientGameTest
```

Set `CRAFTIUM_TEST_SCOPE` to `keybinds`, `builder`, `gizmos`, `gallery`, `viewport`, `selection` or `structures` for a focused scenario. `structures` builds a small structure, captures the export pop-up on both tabs, exports Java NBT, a schematic and Bedrock files, reads them back and imports the Bedrock file; it also runs on 1.21.10. `selection` builds an L-shaped volume of real blocks (with a slab and a stair), selects it, captures how the editor outlines and tints it for visual review, then selects a 4096-block cube and reports how many frames render with that selection against a small one. The 1.21.10 smoke test takes the same L-shaped screenshot. `viewport` checks that the editor camera updates on every rendered frame at an unchanged fly speed, and also runs on 1.21.10. Leave it unset for the full Fabric regression. The Paper/Purpur scenario needs separate local server fixtures; it is not part of the ordinary build. See [validation](docs/VALIDATION.md).

## Submit a change

- Explain the problem and resulting behavior in your pull request.
- Include relevant validation and actual screenshots for UI changes.
- Update the feature coverage and control guide when behavior changes.
- Cite official documentation when comparing Axiom behavior. Use original code and assets, and preserve third-party license notices.

AI-generated contributions are welcome when the contributor reviews and verifies them. Describe remaining limitations candidly; matching a workflow does not establish complete Axiom parity.
