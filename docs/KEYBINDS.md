# Craftium controls audit

Checked 21 October 2026 against Axiom's official [keybind table](https://axiomdocs.moulberry.com/editor/windows/keybinds.html), [editor introduction](https://axiomdocs.moulberry.com/editor/intro.html), [builder introduction](https://axiomdocs.moulberry.com/builder/intro.html), [placements](https://axiomdocs.moulberry.com/editor/placement.html) and [gizmos](https://axiomdocs.moulberry.com/editor/gizmos.html).

This matches published defaults. A running copy of Axiom was not used for comparison; undocumented behavior and every interaction detail are not verified. See [feature coverage](FEATURES.md) for broader differences.

## Defaults

| Context | Input | Action |
| --- | --- | --- |
| Game | Right Shift | Editor |
| Game | Hold Left Alt | Context menu; release to close |
| Game | 0 | Select tenth slot |
| Tenth slot | Hold Alt + wheel | Choose builder tool |
| Game | R | Replace Mode |
| Editor | M / B / N / L | Magic / Box / Freehand / Lasso Select |
| Editor | P / O | Painter / Noise Painter |
| Editor | G / H / J / K | Freehand Draw / Rock / Weld / Melt |
| Editor | E / U / Z | Elevation / Smooth / Extrude |
| Editor | LMB / MMB / RMB | Camera / pick block / tool |
| Editor | Ctrl + LMB / RMB / MMB drag | Orbit / pan / radius |
| Editor | Ctrl + Z / Y | Undo / redo |
| Editor | Ctrl + C / X / V / J | Copy / cut / paste / duplicate |
| Editor | Ctrl + Shift + V | Paste in place: start a placement where the blocks were copied from |
| Editor | Ctrl + P / B | Save blueprint / biome borders |
| Selection | Ctrl + F / R | Fill / replace |
| Placement | Ctrl + R / F | Rotate / flip along facing axis |
| Path / Modelling point | E | Extrude previous point |
| Gizmo | Arrows / Page Up / Page Down | Camera-relative XZ / +Y / −Y |

Enter confirms a placement or geometry operation, otherwise clears the selection. Delete and Backspace cancel a placement, otherwise delete the selection. Editor flight uses your Minecraft movement bindings (normally WASD, Space and Shift). With no active tool, RMB rotates the camera. Click the selected toolbar tool to deactivate it.

Other tools, view toggles, Copy With Air, Paste and Select, last-tool switching and Adjust Speed start unbound. Craftium's inspector, help, panel visibility and gizmo mode/local-axis shortcuts also start unbound. Use the View menu or assign them yourself. The old F4, F1, Tab, 1/2/3, local-axis L, Q/E flight, Ctrl+wheel speed and Z hotbar defaults were removed.

## Rebinding and upgrades

**View → Keybinds** edits editor action chords and mouse gestures, plus separate ingame copy/delete bindings. Choose an entry, press its new combination, use Backspace to unbind, or Reset to restore its default. Camera/tool gestures accept mouse buttons. The Tools & Minecraft controls button opens Minecraft's native screen for tool letters, editor toggle, context menu, Replace Mode and the optional hotbar swapper.

Editor bindings live in `config/craftium-controls.json`; native mappings live in `options.txt`. The first upgrade replaces recognizable former factory mappings and backs up the previous `options.txt` to `config/craftium-controls-before-update-options.txt`. Other custom keys are retained. Former unbound tool mappings cannot be distinguished from explicit user unbinds and receive the new defaults. Assignments shared by unrelated actions can conflict; reset the affected entries if necessary.

## Interaction details and limits

- Placement controls take priority over fill/replace. Point extrusion takes priority over the Elevation tool only when Path or Modelling has a previous point. Typing into a focused field does not trigger editor commands.
- Ctrl-drag camera gestures do not paint. Plain scrolling no longer changes brush radius. Use Ctrl+MMB drag or the radius control; use the flight-speed slider or assign Adjust Speed.
- Cut and Duplicate produce a pending placement. Ctrl+P opens a naming dialog. Ctrl+B renders nearby quart-biome cells; this is a bounded local visualization.
- 0 reselects the tenth slot. Choose 1–9 to return to the ordinary hotbar. Alt in the tenth slot opens the tool picker without opening the editor. Clone retains its selection for further copies; left-click finishes.
- Ingame Ctrl+C copies a targeted block with its properties and block-entity data into the held hotbar slot. Ingame Delete removes a targeted block. With a selected display/marker gizmo, Ctrl+C copies that entity and Delete/Backspace deletes it; Ctrl+V pastes its clipboard. Editor Cut and Duplicate also produce entity placements. Entity deletion participates in Craftium history. In the tenth builder slot, builder selections take priority.
- Entity selection and handles still differ from Axiom's complete ingame gizmo workflow. Middle-click export of a display as a placeable item is not implemented; the inspector remains available for entity setup.
- Invert-camera/pick-drag/arcball snap and other configuration toggles are not all implemented. Matching the documented bindings does not establish full feature or camera-algorithm parity.

Update both client and server to the accompanying build for exact-state block copying, facing-axis clipboard flips and entity-only clipboard metadata.
