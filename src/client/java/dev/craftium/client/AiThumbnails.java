package dev.craftium.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.craftium.client.compat.ReferenceTexture;
import dev.craftium.client.compat.Tex;
import dev.craftium.core.VoxelView;
import net.minecraft.client.Minecraft;
import java.util.*;

/**
 * A small picture of what each AI session has previewed, for the Build sessions screens. It is drawn in software from the preview's own
 * blocks, once when the preview is new, and kept after the preview is applied or discarded so a session still shows what it last made.
 */
final class AiThumbnails {
    static final int WIDTH = 128, HEIGHT = 88;
    /** Pictures are drawn from boxes up to this many blocks; a bigger preview is shown without one. */
    private static final long MAX_VOLUME = 2_000_000L;
    record Entry(Tex tex, ReferenceTexture texture, long previewId) {}
    private static final Map<String, Entry> CACHE = new HashMap<>();
    /** The preview of each session that could not be drawn, so a long thin one is not tried again every frame. */
    private static final Map<String, Long> UNDRAWABLE = new HashMap<>();
    private static boolean builtThisFrame;
    private AiThumbnails() {}

    /** Called once per frame by the screens that draw thumbnails, so at most one is made in a frame. */
    static void newFrame() { builtThisFrame = false; }

    /** The picture for this session, or null when it has none (it never previewed anything, or the preview is too big to draw). */
    static Tex of(ClientState.AiSessionView view) {
        var entry = CACHE.get(view.id());
        var ghost = view.ghost();
        long previewId = view.info().previewId();
        if (ghost.total() > 0 && (entry == null || entry.previewId() != previewId) && !builtThisFrame && !Long.valueOf(previewId).equals(UNDRAWABLE.get(view.id()))) {
            builtThisFrame = true;
            var fresh = build(view);
            if (entry != null) release(entry);
            if (fresh != null) { CACHE.put(view.id(), fresh); UNDRAWABLE.remove(view.id()); }
            else { CACHE.remove(view.id()); UNDRAWABLE.put(view.id(), previewId); }
            entry = fresh;
        }
        return entry == null ? null : entry.tex();
    }

    /** Lets go of the pictures of sessions that are no longer listed. */
    static void keepOnly(Set<String> ids) {
        for (var it = CACHE.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!ids.contains(e.getKey())) { release(e.getValue()); it.remove(); }
        }
        UNDRAWABLE.keySet().retainAll(ids);
    }

    static void releaseAll() {
        for (var entry : CACHE.values()) release(entry);
        CACHE.clear();
        UNDRAWABLE.clear();
    }

    /** Texture work belongs to the render thread; a disconnect arrives on the network thread, so it is handed over (and runs at once when already there). */
    static void release(Entry entry) {
        var minecraft = Minecraft.getInstance();
        var id = entry.tex().id();
        minecraft.execute(() -> minecraft.getTextureManager().release(id));
    }

    private static Entry build(ClientState.AiSessionView view) {
        var ghost = view.ghost();
        int[] cells = ghost.placedCells();
        if (cells.length < 4) return null;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int i = 0; i + 3 < cells.length; i += 4) {
            x0 = Math.min(x0, cells[i]); x1 = Math.max(x1, cells[i]);
            y0 = Math.min(y0, cells[i + 1]); y1 = Math.max(y1, cells[i + 1]);
            z0 = Math.min(z0, cells[i + 2]); z1 = Math.max(z1, cells[i + 2]);
        }
        int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
        if ((long) sx * sy * sz > MAX_VOLUME) return null;
        var names = ghost.stateNames();
        var cache = new HashMap<String, Integer>();
        int[] palette = new int[names.size()];
        for (int i = 0; i < palette.length; i++) palette[i] = AiView.colour(names.get(i), cache);
        int[] colours = new int[sx * sy * sz];
        for (int i = 0; i + 3 < cells.length; i += 4)
            colours[(cells[i] - x0) + sx * ((cells[i + 2] - z0) + sz * (cells[i + 1] - y0))] = palette[cells[i + 3]];
        // A box can be small in volume and still too long for the picture (a road, a wall): that is drawn without one, not as a crash in the screen.
        try { return draw(sx, sy, sz, colours, view.info().previewId()); }
        catch (IllegalArgumentException tooLarge) { return null; }
    }

    /** The picture of a box of block colours (0 for nothing), registered as a texture; shared with the blueprint browser. */
    static Entry draw(int sx, int sy, int sz, int[] colours, long previewId) { return draw(sx, sy, sz, colours, colours, colours, previewId); }

    /** As above, with a colour for the top, the sides and the underside of each block. */
    static Entry draw(int sx, int sy, int sz, int[] top, int[] colours, int[] bottom, long previewId) {
        var picture = VoxelView.render(sx, sy, sz, top, colours, bottom, VoxelView.Angle.SE, WIDTH, HEIGHT).image();
        var image = new NativeImage(WIDTH, HEIGHT, false);
        int ox = (WIDTH - picture.getWidth()) / 2, oy = (HEIGHT - picture.getHeight()) / 2;
        for (int y = 0; y < HEIGHT; y++)
            for (int x = 0; x < WIDTH; x++) {
                int px = x - ox, py = y - oy;
                image.setPixel(x, y, px < 0 || py < 0 || px >= picture.getWidth() || py >= picture.getHeight() ? 0xff2a3441 : 0xff000000 | picture.getRGB(px, py));
            }
        var texture = new ReferenceTexture(image);
        texture.filtering(true);
        var tex = Tex.of("craftium", "ai_session/" + UUID.randomUUID());
        Minecraft.getInstance().getTextureManager().register(tex.id(), texture);
        return new Entry(tex, texture, previewId);
    }
}
