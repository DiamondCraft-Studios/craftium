# Contributing to Craftium

Craftium is a vibe-coded, open-source building studio inspired by Axiom. AI assistance is part of its development history. Contributions should be readable, independently implemented and checked against the actual Minecraft build.

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
- Tag releases `v<mod version>-mc<version>`, for example `v0.8.0-beta.1-mc26.2`.
- CI builds only the versions a change touches. Shared code, the root build files and the workflows build all of them.

## Shipping a hotfix

A client and a server meet with a hello: the build's version, a protocol number (`Handshake.PROTOCOL`) and a set of capability names. Only the protocol number can refuse a connection, so a fix that stays inside one side ships as a plain patch release and the other side does not have to update.

- **Backend only, same wire format:** fix it, tag a patch release, update only the side that changed. Nothing else is needed.
- **Adding a field, an option or an enum constant:** compatible, no protocol bump. Older peers ignore what they do not know and see the default for what they do not send.
- **A feature that behaves differently with and without the other side:** give it a capability name in `Handshake.CAPABILITIES` and let the side that lacks it fall back. A name means one thing for good; when the behaviour changes, add a new name and keep the old one.
- **Removing, renaming or retyping a field or enum constant, or changing what one means:** raise `Handshake.PROTOCOL`, then record the new wire shape (see below). Every client and server must now update together, so avoid this for a hotfix.
- **Logic both sides run** (`ModifyMath` is one): the client's preview and the server's edit agree only when both have the change, so treat it as a change to both and say so in the release notes.

`ProtocolCompatibilityTest` enforces the second and fourth rules: it fails when something recorded for the current protocol disappears or changes type. To record the shape for a new protocol number, run the tests with `CRAFTIUM_RECORD_PROTOCOL` set to a file path, then paste that file into `ProtocolSnapshot` as the next version.

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

Set `CRAFTIUM_TEST_SCOPE` to `keybinds`, `builder`, `gizmos`, `gallery`, `viewport`, `selection`, `structures` or `handshake` for a focused scenario. `handshake` (26.2) checks the hello with the server it joined, then plays an older server, a newer protocol and a refused client that recovers. `structures` builds a small structure, captures the export pop-up on both tabs, exports Java NBT, a schematic and Bedrock files, reads them back and imports the Bedrock file; it also runs on 1.21.10. `selection` builds an L-shaped volume of real blocks (with a slab and a stair), selects it, captures how the editor outlines and tints it for visual review, then selects a 4096-block cube and reports how many frames render with that selection against a small one. The 1.21.10 smoke test takes the same L-shaped screenshot. `viewport` checks that the editor camera updates on every rendered frame at an unchanged fly speed, and also runs on 1.21.10. `dedicated` (1.21.10) runs the mod on a real dedicated server with a connected client; it needs `-Pcraftium.acceptEula=true` and is described in [server tests](docs/SERVER-TESTS.md). Leave it unset for the full Fabric regression. The Paper/Purpur scenario needs separate local server fixtures; it is not part of the ordinary build. See [validation](docs/VALIDATION.md).

## Submit a change

- Explain the problem and resulting behavior in your pull request.
- Include relevant validation and actual screenshots for UI changes.
- Update the feature coverage and control guide when behavior changes.
- Cite official documentation when comparing Axiom behavior. Use original code and assets, and preserve third-party license notices.

By submitting a contribution you agree that it is licensed under the project's [GPL-3.0 license](LICENSE).

AI-generated contributions are welcome when the contributor reviews and verifies them. Describe remaining limitations candidly; matching a workflow does not establish complete Axiom parity.
