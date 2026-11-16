# Handoff: pack the undo history file (unfinished, 3 files)

Saved 2026-11-16 from the worktree `../craftium-more-limits` (branch `feat/raise-more-limits`, HEAD `edb9e404`, since deleted). The three files were edited on 2026-11-13 about 20:40, right after PR #110 (raise edit/preview/chunk limits). They were never committed, built or run as far as anyone can tell.

## The problem

PR #110 made edits of about 2 million blocks possible. `HistoryStore` writes the undo history file with one NBT compound per block change, so such an edit is over the 96 MiB `NbtAccounter` a read may take and the history cannot be loaded again.

## What the draft does

- `history-packing.patch` (apply with `git apply`, it was made against `edb9e404`, which is before the Spotless reformat, so expect a hand merge on `HistoryStore.java` and `CraftiumGameTest.java`):
  - `HistoryStore`: new `packBlocks` / `unpackBlocks` store an edit's changes as a palette of distinct blocks plus int arrays (x, y, z, before index, after index). File format goes 1 -> 2; load accepts both, and an old per-change `blocks` list still loads. The load budget check uses `e.packed().estimatedBytes()` and `Selection.HISTORY_BYTES` instead of the hard-coded 34 MiB.
  - `CraftiumGameTest` (26.2): registers the `historyfile` scope.
- `HistoryFileGameTest.java` (new, goes in `versions/26.2/src/gametest/java/dev/craftium/server/`): pastes a 125x125x128 volume (about 2 million blocks), saves the history, reloads it, asserts every change is equal and the load is whole. Run it with `CRAFTIUM_TEST_SCOPE=historyfile`.

## Checked

- `Session.Edit.packed()` and `Selection.HISTORY_BYTES` exist on `origin/main`, so the budget line should still compile in principle.

## Not checked

- Whether it compiles on current `origin/main`, whether `spotlessCheck` passes (written before Spotless), whether the game test was ever run, and how `Edit.packed()` (a different "packed", for in-memory changes) interacts with the new on-disk `packed` tag.

## Suggested next steps

1. New branch from `origin/main` (`--no-track`). Re-apply the patch by hand in the formatted style.
2. `./gradlew compileClientJava spotlessCheck`.
3. Run the `historyfile` game test on 26.2; the file should save and reload whole.
4. Commit separately: format change, test, then PR to origin main.
