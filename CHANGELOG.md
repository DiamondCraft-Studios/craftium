# Changelog

## 1.6.0-beta.1

Changes since 1.5.0-beta.1.

### Interface
- Ctrl+= and Ctrl+- (and the keypad + and -) change the interface size on every Craftium screen, not only in the editor.
- New Hierarchy window at the top of the right column, where the Selections window was: it lists your saved selections. The Block palette and Tool masks sit together below it and take 3/8 of the column.
- The Hierarchy window has a "+" button along the bottom that creates a BuildObject from what is selected, replacing the Save selection button. New ones are named BuildObject, BuildObject (1), BuildObject (2) and so on, and double-clicking a row renames it where it stands (Enter keeps the name, Esc drops it). The colour moved to the right of each row, beside the show, edit and delete buttons.
- BuildObjects form a hierarchy: drag a row onto the middle of another to put it under that one, to any depth, or onto the top or bottom edge of a row to place it before or after it. The row you carry follows the pointer, a line or frame shows where it will land, and an arrow folds what is under a BuildObject. Looking at a parent shows everything under it; editing one changes only its own blocks; deleting one deletes what is under it, and Ctrl+Z brings the group back. Hovering a row shows no tooltip any more.
- Saved selections are BuildObjects everywhere: in the window, in messages and in what the AI is told. Two BuildObjects may now share a name; each has a unique GUID, which the AI tools list and accept in place of the name.
- The outline of a BuildObject only shows in the world while its eye is open or while it is selected, and every new BuildObject starts with its eye closed.
- BuildObjects follow their blocks: when you move, rotate, flip or scale the selected blocks (the Edit menu, the Move, Rotate and Scale tools and nudging), every BuildObject that holds those blocks takes them along, and Ctrl+Z puts the blocks and the BuildObjects back together. Scaling up keeps a BuildObject whole; scaling down loses the blocks it drops.
- Creating a BuildObject from a selection that touches existing ones takes them in whole, as children of the new one, even where the selection only reaches part of them. The new one holds only the blocks that no taken BuildObject holds. A selection made while one is shown makes a parent around it.
- Everything you do in the Hierarchy is in the history: Ctrl+Z and Ctrl+Y take back and redo making a BuildObject with +, renaming, dragging it to a new place or parent, and deleting it, in order with your block edits.
- Ctrl + click on a row of the Hierarchy adds or removes that BuildObject from the ones shown, and Shift + Ctrl + click adds every row from the last one clicked to this one. The ones shown together are one selection, so any tool works on all of them.
- F, with exactly one BuildObject shown, moves the view to face the middle of it from far enough back that all of it fits between the panels. It is bound in the controls like the other shortcuts.
- Right-clicking a row of the Hierarchy opens a menu: Select, Select children only, Edit blocks; Create empty parent (or Group selected for several), Create child from selection; Rename, Delete (or Delete selected); Move out of parent (Ungroup, at the top); Show or Hide outline, Change colour, Focus. Every item that changes the hierarchy is in the undo history.
- Ctrl + G makes an empty parent around the BuildObjects shown, in the place of the first of them. It is in the controls like the other shortcuts, and in the right-click menu.
- F2 renames the BuildObject shown, in its row.
- An open Hierarchy menu takes the pointer: nothing behind it is lit up or clicked through it, and a click anywhere else closes it.
- Duplicate (Ctrl + D, or in the Hierarchy menu) copies the blocks of the BuildObjects shown, with everything under them, and starts a placement where they were copied from; Enter pastes them as the selection and makes a new BuildObject of them, after the original. Move the placement first to put the copy somewhere else. The menu also has Copy, Cut (which takes the BuildObjects along with their blocks), Paste (a placement that makes a BuildObject) and Export, which exports the blocks shown from each BuildObject down, however they are spread over the hierarchy.
- Hovering a row of the Hierarchy draws that BuildObject and everything under it in the world, whatever their eyes say. Ctrl + click on an eye opens or closes it for everything under it too.
- A right click on another row while a Hierarchy menu is open opens that row's menu.
- BuildObjects are saved with the world (`craftium/buildobjects/<dimension>.nbt`, written every few seconds and when the server stops), so they are there after a reload or a rejoin. In multiplayer they are shared: everyone who builds in a dimension sees and changes the same BuildObjects, changes reach the others at once, and what each player is looking at stays their own. A BuildObject somebody else deletes while you look at it is let go of. Colours are shared and saved too; eyes and folds stay your own and are not saved yet.
- Who may change the BuildObjects is set in `config/craftium/hierarchy-permissions.json`, written the first time with everything allowed: `create`, `rename`, `move`, `delete`, `recolour` and `edit` can each be switched off for everyone or for a player by name or UUID, and operators bypass it unless `operatorsBypass` is off. It is read again when it changes. This is in the Fabric mod only so far.
- Undo and Redo of a Hierarchy change are dropped, with a message, when someone else changed the BuildObjects since, so one player's Undo never undoes another's work.
- A block that is deleted, cut or otherwise turned into air leaves the BuildObjects that held it, and Ctrl+Z puts it back into them. Replacing a block with another keeps it. Edit > Move selection takes the BuildObjects along with the blocks; Clone and Stack make copies that belong to no BuildObject. Deleting a BuildObject in the Hierarchy only removes the grouping: every block stays.
- The eye and the fold of each BuildObject are kept for you by GUID in `config/craftium/hierarchy-view.json`, so they are as you left them after a rejoin or a restart, in any world.
- Dragging one of several shown rows carries all of them together, in their order, and the drag shows how many.
- The Hierarchy has groups: flat headings, never nested, that the BuildObjects at the top level sit under (one inside another is in the group of the one at the top). Every world starts with "New Group" and everything is in it. A group's name is drawn in #D9E3EC on a #212832 band. Drag a heading to put the group, with what is in it, in another place, drag a BuildObject onto a heading to put it at the end of that group, or onto a row to place it before or after that one; double-click a heading to rename it, and its arrow folds it. The eye on a heading opens or closes the eyes of everything in the group.
- Right-clicking a group's heading opens a menu (Rename, Create empty BuildObject here, Create new Group, Fold, Delete group, which puts its BuildObjects in the group before it), and right-clicking the empty space in the Hierarchy opens one with Create new Group and Create empty BuildObject. A BuildObject's menu lists the other groups to move it to.
- Groups are shared like BuildObjects: they are saved with the world, in Undo and Redo, and sent to everyone building there. Creating, renaming, moving and deleting them ask for the `create`, `rename`, `move` and `delete` permissions of the BuildObjects. A server from before groups shows the Hierarchy as it was.
- The fold arrows of the Hierarchy are drawn in pixels, one row taller than before and one pixel higher.
- Grouping BuildObjects together (Ctrl + G, or dragging one onto another) keeps each in the group it was in, and Ungroup in the right-click menu (it says Move out of parent for one that is deeper) takes it back out into that group. Moving a parent to another group takes what is under it along.
- Which BuildObjects and groups are folded in the Hierarchy is kept by the server with the BuildObjects, so it is as you left it after a rejoin, a restart or on another computer. A fold is shared by everyone building there, is not in the undo history, and needs no permission. A server from before this leaves the folds to your own file as before.
- Window places in the editor are kept with the canvas and the edges they hang from, not worked out from the last size the editor was laid out at, so windows at the right or bottom stay there every time the game starts, at any interface size. Saved places are reset once for this.
- The selection mode icon on the pointer is hidden with the pointer, while looking round, turning the arcball or dragging a handle, and when the pointer is outside the window.
- The editor's interface size is set in Options, on the new UI tab, from Auto through 1x to 8x in half steps, and Ctrl + = and Ctrl + - change it from the editor. Every Craftium screen follows it, drawn a whole number of screen pixels to each pixel of the text so it stays sharp; a half step is exact in the editor and rounded up in the other screens. The default is 1x.
- The Tools window opens tall enough to show every tool, with Tool options below it, and the right-hand column is 50% wider. Panels that were at the right or bottom edge of the window stay there when the interface size or the window changes. Saved window places are reset once for this.
- Duplicate makes the whole hierarchy again: the same names, order, parents and colours, with the top one marked "(copy)", each holding its own blocks at the new place. If the copy is turned or something else is copied before it is placed, it falls back to one BuildObject.
- A click on empty space in the Hierarchy, and Alt + D, let go of the BuildObjects shown (Alt + D also clears the selection).
- "Delete blocks" in the Hierarchy menu (next to Delete, which still only removes the grouping) deletes the BuildObject, everything under it and all the blocks they hold, as one step: Ctrl+Z brings back the blocks and the BuildObjects together. It asks the first time, with a "Don't show this message again" tick box.
- After a duplicate or a paste from the Hierarchy the new BuildObjects are the ones shown, not the originals.
- The Hierarchy rows keep only the eye: Edit blocks and Delete are in the right-click menu (which also has Stop editing), and the eye stays clear of the scrollbar, which now gets its own space when the list scrolls.
- BuildObjects are all drawn in your selection colour, with no colour taken from a wheel; the colour swatch on the rows is gone. Change colour and Reset colour are in the right-click menu.
- Ctrl + X with BuildObjects shown takes them along with the blocks, like Cut in the menu. Ctrl + V with no block under the pointer (the pointer is over a panel, or the sky) starts the placement where the blocks were copied from instead of doing nothing. A click with any mouse button now takes the keyboard back from a text box, so the shortcuts work after a right click.
- The Rulers window starts closed and comes up the first time a ruler is used; after that it stays as you leave it.
- Target info moved to the bottom of the left column, under Tool options.
- The Clipboard window is off by default; open it from Window > Toggle clipboard. Copy, cut and paste work without it.
- Your saved window layout is reset once, to the new places.
- Dragging a gizmo handle (an arrow, a plane square or the centre) is now worked out from each step of the pointer, so flying about while you drag no longer slows it, stops it or sends the selection back towards you, even when you look along the arrow. The selection stays where it is while only the camera moves.
- The Move tool's gizmo now has the free move handle in the middle: drag it to move the selection anywhere on the screen's plane.
- New Inspect tool, in Utility: point at a block to see its name and the first few of its block tags floating above it, and use the tool (right-click by default) to list every block and fluid tag in chat. It changes nothing in the world and sends nothing to the server.
- The Builder's destination preview (where a move, clone, stack or smear would land) is drawn like its selection: dim faces on the ground and an outline in open air, hidden by terrain in front of it.

### Missing blocks
- A block that no installed mod provides (a mod you removed, a build from another modpack) is no longer turned into air when its chunk loads or when a blueprint, structure or schematic is pasted. It becomes a Missing Block that remembers the block it stood for, with its properties and any block entity data it had.
- /craftium missing list shows what the world is missing and how many of each are in your selection; /craftium missing replace <number> <block> swaps one for any block you choose, as an edit you can undo. The numbers are kept in craftium/missing-blocks.txt in the world folder, which only ever grows.
- Saving a blueprint, structure or schematic writes a Missing Block back out under its original name, so the file stays portable.
- Chunks that were loaded and saved without Craftium before this version already turned their missing blocks into air, and those cannot be recovered.
- A world saved with Missing Blocks needs Craftium to be installed when it is opened again: without it, they are air as before.

## 1.5.0-beta.1

Changes since 1.4.0-beta.1.

### Interface
- Craftium's chat messages start with a dark aqua [Craftium] tag, are written in sentence case and split over lines.
- The world rule commands moved under /craftium gamerule.
- The intro screen and the docs chat messages no longer mention Axiom.

## 1.4.0-beta.1

Changes since 1.3.1-beta.1.

### Interface
- New 2D colour picker, with a window to open it in.
- Turning off AI building in the editor asks first.
- The fold arrow of editor windows folds them.
- New Selections window. Save selection keeps what is selected as an entry named Selection 1, Selection 2 and so on (the count starts again at 1 when none are left), beside your selection and without changing it. Each entry has a colour square (the selection colour, then turned round the hue wheel for each next one; click it to change it), an eye to hide its outline, a pencil to edit it and a cross to delete it. Clicking an entry shows it in place of your selection and clicking it again gives yours back. Editing an entry lets any selection tool add to it or take from it, keeping the changes. Deleting asks nothing, and Ctrl+Z brings the entry back with its colour. Saved selections that are not shown are drawn like the selection, in their own colour, while the editor is open.

### AI building
- Claude can take showcase pictures of your builds. capture_batch takes real in-game pictures (textures, lighting, sky, interface hidden) of any number of rooms from several angles each, and saves them to screenshots/craftium/<job>/<room>/ with a manifest.json. Angles are presets (hero, compass sides, corners, top, ground) or a target with azimuth, elevation and distance, or a placed camera; the lens is 30 to 110 degrees; pictures are 1920 by 1080 by default, up to 4K. They are drawn from a camera of their own, so you are never moved or turned. Pictures are framed on the blocks really in the room's box, a shot with terrain or trees in the way is retaken once from higher up, and capture_status can return a room's pictures as one labelled contact sheet. A room can be one of your saved selections, by name. capture_status and capture_cancel follow and stop the job. A craftium-showcase skill works out the angles from what you say, takes the pictures, looks at the contact sheets and retakes the weak ones. A room outside the loaded chunks fails with a message saying so.
- Panes, bars, fences and walls in AI builds now connect to their neighbours.
- Claude can tell your saved selections apart. get_state lists them by name, read_selection takes a name to read one, and replace_selection takes a "selection" name, so one build can do "on Selection 1 do this, then on Selection 2 do that". An unknown name refuses the build and lists the names there are.

### Internal
- A request can name a plugin's tool and carry the values of its settings, and the server runs it through the same gate as the editor's own tools, holding the settings to what the tool declared and its plan to the block limit. A tool can show a ghost of what it would build before the player clicks, which arrives on its own and never changes the status line. These are new optional fields, so older clients and servers ignore them, and a build that has them says so with the `plugin-tools` capability. Nothing registers a plugin tool yet, so nothing a player sees changes.
- The server tells a client which tools its plugins have added, in the first status after they change and as a push of its own when they do, with each tool's settings. The client keeps the tools it understands and leaves out any it does not, and nothing lists them yet.

## 1.3.1-beta.1

Changes since 1.3.0-beta.1.

### AI building
- AI world reads include the selection corners again.

### Tools
- Two new flat shapes, Irregular polygon and Star. Irregular polygon keeps its number of Sides but gives each corner a random distance and spacing; the Seed slider picks which one, and the same seed always gives the same shape. Star has Points and an Inner radius slider for how deep the notches between the points go. Both work with every tool that picks a shape, hollow, outline and the scale and turn controls.
- The Magic Wand has a Select liquids switch. Off, which is the default, it aims through water and lava and no longer selects them with the Any select type; on, it can aim at them and select them.
- New Paint Bucket tool under Paint. Click a block to fill everything connected to it with your foreground block. It outlines what it would fill, asks first when that is a lot, and follows the Magic Wand's Up, Down, Horizontal, Corners and select type settings.
- A Script Brush or mask script that calls itself without end no longer crashes the server. It stops with "Lua call depth exceeded; operation cancelled", as a script that runs out of instructions does.

### Interface
- Sliders for an angle that can go round more than once, such as Modify's Angle, show whole degrees (567°), and a whole number of turns as 360°×2 once past one turn.
- Pasting with Ctrl+V and a click or Enter makes the pasted blocks the selection and lets go of the original, handles included, so Move, Rotate and Scale work on what you pasted. Undo gives the old selection back. Paste a copy keeps the selection as it was.
- Box Select shows its hover cube for every first point, not only the first box: after a box is made, the block a new box would start on is outlined as before.
- The selection tools show an icon on the pointer's bottom-right, as big as the pointer, for how the next selection combines with the current one: replace, add, subtract or intersect.
- Sliders for an angle that can go round more than once, such as Modify's Angle, show whole degrees (567°), and a whole number of turns past one as 360°×2.
- Pasting with Ctrl+V and a click or Enter makes the pasted blocks the selection and lets go of the original, handles included, so Move, Rotate and Scale work on what you pasted. Undo gives the old selection back. Paste a copy keeps the selection as it was.
- The block palette has a transparency toggle under the foreground slot, also on T. While it is on, every tool paints the foreground as air, and the slot keeps showing the block, faded, that it goes back to when you turn it off.
- The Magic Wand's directions, selection mode and select type are now one widget that other tool panels can reuse. The Paint Bucket shows its directions and select type.
- Every tool has an icon of its own. Smear, Annotation, Shape, Line, Extrude, Distort, Roughen, Modelling, Biome Painter and Clentaminator were drawn the same as another tool, and now have new icons.

### Internal
- Tools, their icons and the Lua sandbox were reorganised in preparation for plugins. Nothing else about how the editor behaves has changed.
- A tool can declare its own settings (a number, a whole number, a switch or a choice), and the options panel draws them as a form. The tools the editor ships with declare none, and their panels are unchanged.
- What a tool draws in the world, and the handles it shows, are drawn by the tool's own input handler instead of by the editor screen checking for each tool by name.
- On the server, every edit passes one gate before it is built or held for confirmation, each tool's click is handled by its own behaviour instead of a switch on the tool, and actions with a namespaced name (`plugin_id:action`) can be registered for plugins. None of this changes what the editor's tools do; on a Paper server a plugin's action needs the new `craftium.plugins.use` permission.

## 1.3.0-beta.1

Changes since 1.2.0-beta.1.

### AI building
- Several Claude sessions can build at once. Each has its own id, label, preview, undo list and selection, a tinted ghost, and a Build sessions gallery with a detail screen and a teleport to its build.
- The AI bridge protocol is 3 (session ids); an oversized status trims session previews instead of failing.
- A craftium-blockout skill blocks out rooms for mod and add-on landmarks.

### Editor
- Select outline in the Select menu.
- The Options screen is a scroll view, and window titles drop the "Craftium /" prefix.

## 1.2.0-beta.1

Changes since 1.1.0-beta.1.

### AI building
- Build specs can generate roofs (gable, hip or shed) and flights of stairs with every stair facing the right way, so the AI no longer writes roofs stair by stair.
- Build specs can name blocks by material (`spruce@stairs`, or `@stairs` with a material set once), mix blocks by weight for weathered walls and paths, and group operations to rotate, mirror and move them, turning stairs and logs with them.
- One read_blocks tool replaces get_blocks and get_preview_blocks: the world, the world with the AI's preview applied, or the preview alone, up to a million blocks at a time.
- render_view can draw up to three angles from one read of the world, about half the time of separate calls.
- The AI can add to its waiting preview instead of resending the whole build, and the result is still applied and undone as one build.
- The reply to a preview warns about doors and beds missing a half, torches, lanterns, signs and the like with nothing to hold them, and operations hidden entirely by later ones.
- The AI plugin tells you when it and the mod are out of step, and which to update; a server whose Craftium is older gets a clear message when it cannot read a build.
- A build that the server cannot draw no longer stops the server: the request fails, or the preview is dropped and logged.
- An AI script is cancelled for the memory it allocated itself, not for garbage or other threads' growth, so the same spec behaves the same on a busy server; getHighestBlockYAt in it sees what the build has planned, and setting one block twice counts once against its cap.
- Adding to a preview with a spec that only carves (air through a wall in open air) is kept and reported instead of dropped; an AI preview that cannot be drawn is refused with the reason when it is made; the warnings no longer miss two stacked lower door halves or warn about blocks backed by unloaded chunks or a grindstone; a world read on an older server says it is the smaller, older read.
- Build specs can hold a Lua script that places blocks inside a box, for natural ground, rocks and trees. It runs in the same sandbox as the Lua brushes, only places inside its box, and builds the same each time from its seed.

## 1.1.0-beta.1

Changes since 1.0.0-beta.1.

### Editor
- A world axis gizmo shows in the editor, so you can see which way X, Y and Z point.
- Reopening the editor on a box select puts the gizmo back on a corner of the box.

### Performance
- The editor draws faster: block icons are built once, line icons are baked into textures, measured and wrapped text is remembered between frames, and options rows scrolled out of view are skipped.

## 1.0.0-beta.1

Changes since 0.10.0-beta.1.

### AI building
- The reply to a preview tells the AI what it made: blocks placed and cleared, where they are, and the commonest blocks with how stairs and other facing blocks are turned, so a roof built the wrong way round is caught before it is drawn.
- A build spec the server refuses no longer clears the preview that was waiting, so your ghost stays while the AI fixes its spec. The error names the operation that used a block the game does not have and the nearest real ones ("spruce_stair": minecraft:spruce_stairs), or the properties a block takes.
- Replies to the AI are held to about 60 KB, which fits in what an AI app passes on. Reading a wide area of ground gives a summary (height range, how level it is, what it is made of, a coarse map) instead of every column.
- get_state also gives the dimension, height limits, sea level, the biome you stand in and your recent blocks; get_palette is gone from the AI tools.
- The building skill has worked examples (a gable cottage and a round tower) and renders only where the look matters, which saves time and tokens on every build.
- The AI tab has a Redo AI build button (and a menu item), and HOW IT WORKS describes installing the Claude plugin in Claude Code or Cowork.

### Preferences
- The options screen is split into General, Selection, Builder, Gizmos & Rulers and AI tabs. Reset applies to the tab shown.
- "Warn at (blocks)" sets when the editor and the Builder ask before keeping a big selection (it was a fixed 1,024, with an exception below the hover limit). It defaults to 50,000. The hover preview limit now defaults to 10,000, and both are typed into number boxes. A saved hover limit is kept.
- "Builder magic select limit" (default 1,000) caps the Builder's magic select when it does not follow the editor's Magic Wand.
- "Bulldozer same blocks" (on by default): a full Bulldozer sweep only breaks blocks equal to the first one, block state included, and a different block stops it until the button is let go.

### Builder
- Dragging the flight speed slider, or using any other control of the Builder widget, no longer breaks the block behind it.
- Letting go of Alt before the mouse button no longer leaves the flight speed unapplied; the last value is saved and sent when the button is released.

### Editor
- Dragging a block out of the palette is carried in a cell over the windows, as before, and only turns into the crosshair out over the world.
- Holding Ctrl and then dragging a gizmo handle moves the handle instead of starting the arcball camera.
- With a gizmo showing, X, Y and Z lock it to those axes, as in the Builder, instead of being the editor's hotkeys.

### Connection
- Flying fast, or turning the arcball quickly, no longer gets a player thrown back on a Fabric server: the server skips the game's "moved too quickly" check for a Craftium player who is flying, and lists a new `fast-movement` capability. Against a server without it (Paper/Purpur, older builds) the client keeps flight and the arcball under nine blocks a tick, so there is no rubber-banding, only a speed limit.

## 0.10.0-beta.1

Changes since 0.9.1-beta.1.

### Chunk leasing
- Moving, cloning or stacking a selection or a Builder box between far-away chunks no longer asks you to walk there. Craftium loads the chunks at both ends for as long as the edit takes, then lets them go. Undo and redo of such an edit do the same.
- Chunks nobody has visited are generated for the edit. An edit that needs more than 128 chunks that are not loaded is refused.

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
