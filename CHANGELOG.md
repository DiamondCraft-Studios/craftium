# Changelog

## 0.9.1-beta.1

Changes since 0.9.0-beta.1.

### Editor
- Modify's Translate Scale is now Expansion (0.1 to 8): with Add Translation on, a revolve widens its radius over the sweep and each rotated copy sits further out than the last, instead of the offset being stretched. This also stops a large scale from crashing the client.
- Replace mode shows a block smaller than the one it replaces: a slab, fence or pressure plate held against a full block used to show nothing, as its ghost was hidden inside the block. The part that is kept now gets a faint white tint.

### Connection
- The client and the server greet each other with their version and a protocol number, on both Fabric and Paper/Purpur. The protocol only goes up when the request format breaks, so a hotfix on one side keeps working with the other; a real mismatch is named to the player ("Update the client…") and edits are refused until they reconnect updated. `/craftium status` shows the server's version.
- The client looks at the project's GitHub releases once per game session and shows a line when a newer build exists for its Minecraft version. `/craftium update` shows the link and `/craftium update off` stops the check. The Paper/Purpur companion logs the same at startup; `check-for-updates: false` in its config.yml turns that off. Nothing is downloaded.
- The hello also lists the optional features each build supports (capabilities, starting with `modify-expansion`), so a client and a server on different patch versions use a feature only when both have it. Against a server without Expansion the slider says "Expansion needs a server update", and the preview and the request leave the sweep as the server would build it. `/craftium status` names what the server lacks.

## 0.9.0-beta.1

Changes since 0.8.0-beta.1.

### Editor
- The block chooser draws water and lava as an isometric cube, and searches the way JEI does: `@mod`, `#tooltip`, `$tag`, `%tab`, `&id`, `^colour`, `-` to leave a word out, `|` for alternatives, quotes for exact words, and `*` and `?` as wildcards.
- Paste in place (Ctrl + Shift + V) starts a placement where the blocks were copied from, to nudge and build with Enter, instead of building at once.
- Dragging a block to the Hand slot works in the editor, where the player is a spectator.
- With the pipette, Ctrl + click adds the block to the palette and keeps the pipette in hand; a plain click still takes it as the foreground. The palette keeps 64 blocks.
- A dragged block is carried the way the pipette shows one: the pointer is the crosshair and the block rides above and to the right of it.
- Edits and selections go up to 1,048,576 blocks, and each shape's largest radius, up to 999, is worked out from the blocks it makes: a flat disk or a hollow ring can be far wider than a filled sphere. The radius slider names the limit when a shape has one, and sizes shrink to stay within it. The undo history budget, the clipboard limit and the packet sizes grew with the limit.
- Undo normal building is a preference, on by default: off, the blocks you place and break by hand stay out of the undo history. A held place button is one undo step, as a held break already was.
- Builder follows Editor magic select is a preference, on by default. Off, the Builder matches by the block comparison chosen next to it (the same block unless changed), selects up to the largest selection, and uses the wand's plain defaults.

### Reach
- Clicks in the world (infinite reach breaks, Tinker, angel and fast placement, the delete key, the mirrors of a placement) ignore the tool masks unless Use in Builder tools is on.
- Breaking with infinite reach or symmetry plays the game's break sound and particles again.
- The click that changes the reach no longer breaks or places a block.

## 0.8.0-beta.1

Changes since 0.7.0-beta.1.

### Builder
- Smart extrude is also an option on the editor's Extrude tool.
- Extrude and Floodfill stay inside the selection they are clicked in.
- Floodfill has a paint bucket icon, grows as a ball, follows the Block limit, closes gaps in thin walls, and its default limit is 10,000.
- Rotating a selection with the gizmo turns stairs and other oriented blocks with it, and the preview ghosts are drawn with the turn and flip applied.
- A selection fills its inside faces in their own colour, and the screen is washed while the camera is inside it.
- All selection colours are in one screen, grouped by use. The landing colours became Destination blocks, the moved blocks tint joined Remove blocks, and blocks about to be removed are filled instead of outlined.
- Each shape has its own size controls in the tool options, with a Chain toggle.
- The brush has a Flow setting for how often a stroke applies it.
- The builder draws a white bounding box when Copy air is off, shows a live size readout, and `;` cycles the selection style.
- The ruler highlights the first block hit in white while an end is moved.
- Builder move, clone, stack and erase, and pasting the clipboard, show their result at once instead of waiting for the server. If the server turns the edit down, the blocks go back. This is off through the Paper companion, with Copy air, Merge blocks, symmetry or Keep existing on, and when entities are copied.
- A builder edit sent while another is still being applied waits its turn instead of being refused.

### Editor
- Edit anchor turns the Modify anchor into a knob that the move gizmo carries. The preview follows the cursor until it is anchored, and then follows the knob while the gizmo drags it.
- While the gizmo holds one handle, the other handles are hidden. A preference turns this off, and it is on by default.
- Tool masks can have include and exclude both switched off, and a new tool mask starts that way.
- The invert / carve toggle is gone from the editor.
- Slider values can be typed like a text box (Ctrl-click). Modify sliders are capped at 64 while typing, and Angle drags past 360.
- Modify has Translate Scale and the gizmo shows drag hints.
- Dropping a block on the Hand keeps the item you are holding.
- Keep existing is ignored while Replace mode is on.

## 0.7.0-beta.1

Changes since 0.6.0-beta.1.

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
