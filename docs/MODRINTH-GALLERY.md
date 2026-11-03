# Craftium Modrinth gallery

Eight unedited **1920 × 1080 PNG** screenshots captured from Craftium **0.3.1-dev** in Minecraft Java **26.2**. The scene is an original sample world. Images use Minecraft's renderer and the actual mod UI, without shaders, added text or image retouching.

Upload in the order below. Use **01-editor-overview.png** as the featured image.

| File | Gallery title | Caption |
| --- | --- | --- |
| `01-editor-overview.png` | Editor workspace | Select and transform structures with the toolbar, gizmos, clipboard and palette in one workspace. |
| `02-clipboard-placement.png` | Clipboard placement | Preview a copied watchtower before placing it in the world. |
| `03-terrain-tools.png` | Rock and terrain tools | The Rock tool with a live brush outline, radius controls and undo history. |
| `04-builder-tools.png` | In-game builder tools | Access Move, Clone, Stack, Smear, Extrude, Erase and Symmetry from the tenth hotbar slot. |
| `05-colour-studio.png` | Colour Studio | Match colours to block textures and create gradients between chosen materials. |
| `06-weighted-palettes.png` | Weighted palettes | Build a material palette, adjust weights and search available blocks. |
| `07-visual-masks.png` | Visual masks | Combine surface, block and height rules to control which blocks a tool can change. |
| `08-keybindings.png` | Configurable controls | Edit shortcuts and mouse gestures, with Axiom's documented defaults. |

All eight are mod gallery screenshots from Fabric singleplayer. They are not presented as Paper/Purpur runtime captures. The companion uses the same client UI, but the plugin listing should explain that players need the Fabric mod.

To reproduce the captures, set `CRAFTIUM_TEST_SCOPE=gallery` and run `gradlew.bat :versions:mc-26.2:runClientGameTest`. The showcase fixture only belongs to the test source set; it is not included in the published mod or server plugin.
