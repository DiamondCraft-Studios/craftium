# Formatting plan

Drafted 2026-11-14. Status 2026-11-15: user chose the full reformat (option 3). Done on local
branch chore/spotless-formatting (worktree ../craftium-spotless), 4 commits, not pushed, no PR:
Spotless + Palantir + .editorconfig, the Spotless reformat, .git-blame-ignore-revs, spotlessCheck in CI.
Waiting on the user to say when to push/PR. Known: `:paper-1.21.10:compileJava` already fails on
origin/main (missing Fabric API in Compat.java), unrelated to this work.

## Goal

Make the code consistently formatted and keep it that way, without wrecking
`git blame` or in-flight branches.

## Findings so far

- 528 of about 600 tracked files are Java. The rest: 37 md, 16 json, 10 yml, 9 js, 8 gradle, 6 py.
- No `.editorconfig`, Spotless or Checkstyle config exists.
- No `package.json`, so Prettier would add a Node toolchain and needs a
  community plugin for Java. Rejected in favor of Spotless.

## Measurements (2026-11-15, dry runs in worktree ../craftium-spotless, branch chore/spotless-formatting)

- Existing style is already consistent: 4-space indent, no tabs, K&R braces, no trailing
  whitespace, LF endings. The only real "mess" is line length: of 60,197 lines, 14,567 exceed
  100 columns, 10,354 exceed 120 and 6,619 exceed 140. 324 files use wildcard imports.
- Full-tree dry runs through Spotless (reverted afterwards, nothing committed):
  - google-java-format AOSP: 526 files, +101,118 / -29,935
  - palantir-java-format: 525 files, +72,354 / -24,711
  - Eclipse (160 cols, no joining of wrapped lines, comments untouched, other settings at Eclipse defaults): 525 files, +57,384 / -24,553
- Spotless needs `repositories { mavenCentral() }` in the root build.gradle to resolve a formatter.
- Correction: `ratchetFrom` formats whole files that changed since the ref, not only the changed
  lines. A PR touching a file would reformat all of it, so the ratchet is not the low-churn
  option first described.

## Open decisions (ask before starting)

1. Scope: full reformat in one commit, or ratchet (`ratchetFrom 'origin/main'`, only touched lines)?
   Recommendation: ratchet, unless the user wants everything fixed at once.
2. Style: google-java-format (AOSP, 4-space) or Eclipse formatter rules matched to the
   dominant existing style. Recommendation: pick whichever produces the smaller dry-run diff.
3. Non-Java files (md, json, yml, gradle): include in Spotless or leave alone?
4. What does "a mess" mean concretely (indent, braces, wrapping, imports)? It decides how much config is needed.

## Phases

### 0. Preconditions
- Working tree clean. Currently `EditorScreen.java` and `EditorControls.java` are modified and
  `docs/TECH-DEBT-26.2.md` is untracked, so commit or stash those first.
- List open PRs and live branches/worktrees (`gh` as DevianKeno). A full reformat only goes
  ahead once these are merged or the user accepts the conflicts.

### 1. Measure (read-only)
- Survey indent, brace and import styles to find the dominant style.
- Dry-run each candidate formatter in a scratch copy (scratchpad dir) and report
  `git diff --stat` size per candidate. No change to the repo.

### 2. Tooling commit
- Add Spotless to the Gradle build, covering all `versions/<mc>` modules and the shared source.
- Add a minimal `.editorconfig`.
- Commit separately from any reformat. Stage by explicit path.

### 3. Reformat commit (skip when using the ratchet)
- Run `spotlessApply` on its own commit with no logic changes.
- Verify: the build compiles for every Minecraft version module and tests pass.

### 4. Blame protection
- Add `.git-blame-ignore-revs` with the reformat commit hash (a follow-up commit,
  since the hash is only known afterward). Document `git config blame.ignoreRevsFile`.

### 5. Enforce
- Add `spotlessCheck` to the CI workflow in `.github/workflows` (yml).

### 6. Rollout
- Tell the user how to rebase live branches over the reformat
  (`git rebase -X theirs` is not safe, so give per-branch steps instead).
- Private `priv/` branches stay unpushed (never push them). They get the same rebase locally.

## Rules for the work

- Branch from main with `--no-track`; PR goes to origin (DiamondCraft-Studios) main only.
- One commit per logical change; commit messages use Conventional Commits, no scope.
- Never push without the user's say-so. No worktree unless asked.
