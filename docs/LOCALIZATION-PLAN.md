# Localization plan

Status: proposed 2026-11-10, awaiting answers to the open questions and a go-ahead.

## Where the code stands

- Only keybinds are translated. `src/main/resources/assets/craftium/lang/en_us.json` has 49 keys, all keybind names and categories.
- About 590 string literals in the client screens are hardcoded English. `Component.translatable` is never used. The widgets draw raw `String`s and measure them through `TextCache`, which already resets when the language changes.
- Server and plugin text is hardcoded too: `ServerCommands`, `HandshakeServer`, and the Paper plugins under `versions/*/server-plugin`.
- Other text is outside the lang system: `docs/`, the Modrinth pages, the `mcp` tools and the AI skill text.

## Phases

### Phase 0: Decisions

See the open questions below.

### Phase 1: Plumbing, no visible change

- Add a `Lang` helper in the client: `tr("key", args...)` returns a `String` for the string-based widgets, and `component("key", args...)` returns a `Component` for chat and tooltips.
- Resolve keys through Minecraft's `I18n`, so the game's language setting and resource-pack overrides work. `TextCache` already handles language changes.
- Fall back to `en_us` for a missing key, never show a raw key, and log each missing key once.
- Keep `en_us.json` as the single source of truth. Name keys by screen, e.g. `craftium.studio.button.apply`.

### Phase 2: Extract the English strings

- Move the ~590 literals into `en_us.json` one screen at a time, in small commits. Start with the shared widgets (`FlatButton`, `FlatCheckbox`), then Studio, Editor, the AI screens and the rest.
- Move chat and command messages in `ClientCommands` and `ServerCommands` too.
- Use positional placeholders (`%1$s`) instead of concatenation, so translators can reorder words.
- Check for layout overflow. Many widgets use fixed pixel widths, and German and Russian run about 30% longer. Use `TextCache.fit` and tooltips where text gets cut off.

### Phase 3: Language files on GitHub

- Put community translations in `src/main/resources/assets/craftium/lang/<code>.json` (e.g. `de_de.json`). Minecraft loads them automatically, so no extra loader is needed.
- Add `scripts/check_lang.py`, run in CI, that catches:
  - keys missing from a language file
  - keys that no longer exist in `en_us`
  - placeholder mismatches
  - invalid JSON
- Update CONTRIBUTING.md with a "Translating" section, add a PR template for translations, and add a coverage table (e.g. `de_de: 92%`) to the README.
- Don't ship partial translations. A language only goes in the jar once it passes the checker, and missing keys fall back to English.

### Phase 4: Optional extras

- A translator credits section in the About screen.
- Server plugin messages: the Paper plugins can't use the client's lang system, so give them their own `lang/*.yml` or `.json` files with the same checker.
- Docs and Modrinth pages: leave in English, or translate later in a separate effort.
- Translation platform: Crowdin or Weblate can sync with the GitHub repo if the community grows. Plain PRs are enough to start.

## Open questions

1. **Languages:** which come first? Recommendation: `en_us` only, plus one or two real translations to prove the pipeline.
2. **Scope:** localize the server plugin and MCP/AI skill text in the first release, or only the mod's client UI? Recommendation: client only first.
3. **Loading from GitHub:** files stored in the repo and bundled in the jar (recommended), or downloaded at runtime so translations can update without a mod release? Runtime download adds network and trust concerns and goes against Minecraft's normal resource system.
4. **Versions:** both `1.21.10` and `26.2` share `src/`, so one set of lang files covers both. Confirm.

## Delivery

Phase 2 is the largest part. Do it as a stacked series of small PRs, one per group of screens.
