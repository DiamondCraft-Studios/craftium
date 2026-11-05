# Changelog

## 0.5.0-beta.2

Changes since 0.5.0-beta.1.

### Manipulation tools
- Smooth has a smooth strength, a block ratio, Stable, Melt and Grow modes, and Fix edges.
- Distort has a distance, optionally per axis, with Smooth edges, a scale and a seed.
- Roughen has a Faces slider and a Roughening ratio.
- Shatter has a crack width, 3D or single-axis noise, and Use active block to fill the cracks.
- Extrude changes the whole face you click, with a layer count from 1 to 64.
- Extrude and Floodfill outline what a click would change, and a right click builds it. A click that reaches as many blocks as the hover preview limit asks first.
- Sculpt strokes are built when the button is released, without Enter.

### Brush and selection display
- The wireframe sphere is replaced by a blocky preview of the blocks the brush reaches. It is depth-tested: faces buried in solid blocks are coloured, and the part in open air is outlined.
- Each tool shows the brush it uses: a shape picker and radius, a disc or ball of the radius, or none. The options panel closes up around what a tool does not have.
- The selection draws its silhouette stronger than its edges, and All edges is depth-tested, with the edges behind blocks at 25%.
- Every selection-like outline, including the builder's and the previews, is drawn the same way and follows the selection's display style.
- `;` cycles the selection display style, and the builder selection options show the style too.

### Interface
- A Large selection warnings option turns the pop-up on or off. A Magic Wand click that already had its hover preview no longer asks.
- The block picker focuses its search field when it opens.

### Development
- Tests cover which brush each tool uses and the shared Extrude and Floodfill searches.

## 0.5.0-beta.1

Changes since 0.4.0-beta.1.

### Modify tool
- Rotate and scale drags work out the blocks they land in, and the preview draws those blocks.
- Faces that would land against solid blocks are flagged, with a halved tint and a Preferences switch for it.
- The wheel zooms when the active tool has no scroll input, and the tooltip lists each tool's shortcuts.
- Copies, offsets, angle and translation no longer have limits.
- Rulers and guides in the preview are depth-tested.
- The tool's icon is now a wrench.

### Ruler
- Each ruler is its own object with a knob at both ends, listed in a Rulers panel.
- Rulers are kept per world and come back when the world is loaded again.
- A ruler measures its span, counting both end blocks.
- Its length is drawn on the line, larger, with a label size setting and selectable colours.
- The line is coloured by the world axis it runs along, and the block a knob lands on is outlined.
- The tool previews the block under the pointer.
- Measurements show as whole numbers or at most two decimals.

### Builder
- The builder tools no longer have range limits.
- Magic select picks individual blocks, selects the found blocks rather than a box, and keeps middle clicks as magic selects once the builder has started with one.
- Ctrl turns magic add into magic subtract.
- The middle button can be set to magic select or to extend the box.
- Selection options are in Preferences, and the builder flags are in the menu held open with Alt.
- The stack preview is smoother, and stacking can target a middle-clicked block.
- Left click sets the first corner again during a preview.
- Undo and redo restore the builder box, Box Select handles, pivot, selected entity and a built shape (back to waiting to be built).
- The undo and redo chords work on builder edits made outside the builder.

### Movement
- A Block step toggle steps the player up a whole block.
- 100% flight momentum is the game's own flight, and Instant flight is removed.
- 0% flight momentum stops at once.
- No clip applies only while flying.

### Interface
- Tooltips show a tool's name and key above its description, and none appears over an open context menu or pop-up.
- Colour opacity sliders read 0% to 100%.
- The cursor is hidden while orbiting with the arcball camera.
- Overlay labels can sit at an exact point.

### Fixes
- Bucket fills apply at once instead of waiting for Enter.
- The capabilities are sent again when Block step is toggled.
- The no clip change no longer comments out its own closing braces.

### Development and docs
- All Minecraft versions and worktrees share one `options.txt`.
- Tests run the mod on a dedicated server with a connected client, and the docs describe these tests and the manual checks.
- The tick-lag warnings are documented in their own section as unexplained.
- Added a Ko-fi support link.
