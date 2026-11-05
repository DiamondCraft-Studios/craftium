# Changelog

## Unreleased

### Builder
- With a selection, Ctrl + scroll switches the middle button between Magic select (adds blocks, Ctrl-click takes them away) and Extend (grows the box to the block clicked). A magic selection starts in Magic select, a box in Extend.
- Nudging a clone or move by the selection's own size moved to Ctrl + Shift + scroll, to make room for that.
- The Builder's magic select follows the Large selection warnings switch in the preferences: with it off, a selection of 1,024 blocks or more is kept without asking.
- A Builder selection is drawn like the brush preview: where it lies in the ground, the faces you can see are coloured dimly, and its outline is hidden wherever blocks are in front of it.
- The selection is anchored by its centre: the gizmo sits there, and a middle click while moving or cloning puts the centre block on the spot clicked.
- Holding Alt in Builder mode shows the tool list with numbers, and pressing 1 to 7 picks that tool.
- Holding Ctrl with the Extrude tool is Smart extrude: the face takes in the stairs, slabs, walls and other variants of the clicked block's material, and each keeps its own shape as it is extruded.
- After the first click, the gizmo's ruler follows the block you aim at, with a thin line back to the first block (white, or in the axis colour when it runs along a world axis), to read distances off the grid. The Ruler switch in the Builder flags turns it off.
- After the first click the Builder draws the box to the block you aim at, and it follows your aim. The Live Builder selection box switch in the preferences turns it off.
- The Builder's Keep existing is its own setting and no longer follows the editor's, which the Builder no longer follows either.
- The block selector also lists blocks that have no item of their own, such as water and lava, as plain blocks.

### Editor
- The Paint tools build what a stroke staged as soon as the button is released, as the Sculpt tools do, without waiting for Enter.
- Block cells can be dragged between the Hand, Foreground, Background, Block palette and Tool masks. Cells in the block selector can be dragged out to them too (not back in), and the selector stays open.
- Enter to build is a switch in the preferences, on by default. Off, a stroke is built when you let go of the button and any other edit is built at once.

## 0.6.0-beta.1

Changes since 0.5.0-beta.2.

### Block limit
- Every tool that builds, removes or moves blocks has a Block limit slider at the bottom of its options, from 1 to 100,000. An edit that would change more blocks is refused.
- When an edit changes nothing, the message now says why: the height range, Only in selection, the mask, Keep existing, blocks that cannot stand there, or spots that already hold the block.

### Builder
- The Builder tools' boxes follow the selection style from the preferences (All edges, Outline or Silhouette).
- Holding the left or right button after setting a corner drags it, and the box updates as you go.
- After a clone, move or stack, the next left click starts a new selection.
- Ctrl while scrolling nudges a clone or move by the selection's own size, so it goes from cell to cell.
- A stack is shown as one selection instead of a box round each copy.
- A box selection is just its solid blocks, so the air inside an outlined shape is not selected, unless Copy air is on.
- Tool masks have a Use in Builder tools switch (a hard hat, off by default). While it is off the Builder tools ignore the tool masks.
- The builder's gizmo no longer shows the plane squares and the centre knob, which could not be grabbed.

### Flight
- Flying up and down is as fast as flying forward and sideways, and diagonals are no faster.
- Sticky flight (in the toolbox, the Builder tab and the Studio) slows you to a stop as you fly up to a wall. Let go and move again to bump into it, or pass through it with No clip.

### Gizmos
- Gizmo text is 250% of its old size, stays one size on screen at any distance, and is coloured by axis.
- While you drag a handle, the offset, angles or new size are shown above the gizmo, one line for each axis.
- Moving while holding the Move freely handle no longer offsets the blocks.

### Tools
- Floodfill has Up, Down, Horizontal and Corners switches for its spread, like the Magic Wand.
- Extrude can grow into replaceable blocks like grass and water (Into grass and water, on by default).
- Dropping a block from the palette onto the selection fills the whole selection, and a large match no longer gets a bucket fill preview (grass, a superflat floor).
- The palette has a Hand slot with the block in your hand, left of the foreground and background blocks.
- The Box Select and Magic Wand options show the selection bounds at the bottom.

### Interface
- History starts hidden. Tool masks is down to its buttons and one row of blocks, with the height range and Only in selection under a Limits fold, and the Block palette fills the space.
- The hover preview shows much larger brushes than can be built.
- The silhouette selection style is drawn without the thin edge lines, and keeps its contour when part of a wide shape lies beside the eye.

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
