# Dedicated-server game tests

Craftium's server half (edit planner, history, permissions, bridge frames, world rules) had been checked against an integrated server (singleplayer) and against an external Paper server. This suite adds the missing combination: **the Fabric mod running on a real dedicated server, with a real client connected over loopback TCP.**

## How it works

Fabric API's client game tests can start an in-process `DedicatedServer` (`worldBuilder().createServer()`) and connect the test client to it (`connect()`). The server loads the mod's `main` entrypoint exactly as a hosted server would, listens on a loopback port, and enforces permissions through `ops.json`. The client is the normal Craftium client driven through the same `CraftiumClient.send` path the editor uses, so every request crosses the actual network codec in both directions. Assertions on world state read the server's own level, not the client's copy.

## Running

```sh
CRAFTIUM_TEST_SCOPE=dedicated ./gradlew :versions:mc-1.21.10:runClientGameTest -Pcraftium.acceptEula=true
```

PowerShell: `$env:CRAFTIUM_TEST_SCOPE='dedicated'; .\gradlew.bat :versions:mc-1.21.10:runClientGameTest -Pcraftium.acceptEula=true`

A dedicated server refuses to start without an accepted Minecraft EULA. The build only writes `eula=true` for the test run directory when you pass `-Pcraftium.acceptEula=true`, which is your statement that you have read <https://aka.ms/MinecraftEULA>. Nothing accepts it for you.

## Coverage

| Stage | Checks (server state is read from the server) |
| --- | --- |
| Boot | Test server is a `DedicatedServer`; mod loaded; client channel negotiated |
| Permissions | A non-operator's select and fill are refused and the world is unchanged; a world-rule command from a non-operator is refused; `op` grants edits; `deop` revokes them |
| Selection and history | Box select, fill, undo, redo, outline, hollow, replace, delete |
| Clipboard | Copy, paste at, undo; chest contents (7 diamonds) survive delete then undo |
| Large payloads | A 16,000-position lasso travels as fragmented frames and is counted correctly |
| Builder | Move, clone, stack and erase commits with undo |
| Entities | Display entity spawn, delete, undo |
| World rules | `craftiumDoBlockGravity` changes server state and the sand-falling mixin obeys it |
| Libraries | Blueprint export is downloaded to the client; the client's copy uploads back and pastes |
| Client | Editor opens (screenshot `dedicated-editor`) and restores the game mode; client commands select |
| Persistence | History saved, client disconnects, reconnects, saved history reloads and undoes |

Not yet covered here (covered on 26.2 or Paper only): brushes and Lua, biomes, gizmos, Autoshade, animated rebuild, symmetry, structure import/export files, and the AI bridge on a Fabric server.

## Known log noise

The server logs "Can't keep up! ... 2 s behind" about three times per run, around the 16,000-position lasso stage and the stages after it. The test advances the server only through explicit `waitTicks` calls between wall-clock pauses, so held ticks alone may explain it. That has not been measured: the other candidate is reassembly of the fragmented lasso request. Timing the server's tick duration with the pauses removed would settle it. Until then, treat it as unexplained rather than as a known server stall.

## Needs a human

These cannot be settled by automation here:

1. **Two or more simultaneous players.** The test harness drives one client. Check that two operators editing at once keep separate selections, clipboards and history, and that one player's undo never reverts the other's edit.
2. **Visual quality.** Screenshots are captured to `versions/1.21.10/build/run/clientGameTest/screenshots/` for review; whether they look right is a judgement call.
3. **Real-world latency.** Loopback hides packet loss and slow links. Try a large paste and a 16,000-block lasso over a remote connection.
4. **Mouse and keyboard feel.** Gizmo dragging, camera flight and keybinds are covered by input simulation only on 26.2.
5. **Other mods and shaders** alongside Craftium on a dedicated server.
