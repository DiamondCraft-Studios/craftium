package dev.craftium.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * In-world overlay primitives (lines, boxes, points, labels) drawn on top of the scene.
 * Colours are ARGB; a stroke width or fill colour of 0 means "none". Primitives submitted between
 * {@link #begin} and {@link Scope#close()} stay visible until the next tick's batch replaces them.
 * This is the 26.2 implementation, which hands everything to the game's gizmo system.
 */
public final class Overlay {
    private Overlay(){}

    /** One tick's batch of primitives. */
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    /** One-time setup. 26.2 draws gizmos itself, so there is nothing to install. */
    public static void install(){}

    public static Scope begin(Minecraft mc){var collector=mc.collectPerTickGizmos();return collector::close;}

    public static void line(Vec3 from,Vec3 to,int color,float width){Gizmos.line(from,to,color,width).setAlwaysOnTop();}
    public static void box(AABB box,int strokeColor,float strokeWidth,int fillColor){Gizmos.cuboid(box,new GizmoStyle(strokeColor,strokeWidth,fillColor)).setAlwaysOnTop();}
    /** A filled quad. Corners are in order, counter-clockwise when seen from the side it faces. */
    public static void face(Vec3 a,Vec3 b,Vec3 c,Vec3 d,int color){Gizmos.rect(a,b,c,d,GizmoStyle.fill(color)).setAlwaysOnTop();}
    public static void point(Vec3 at,int color,float size){Gizmos.point(at,color,size).setAlwaysOnTop();}
    /**
     * A label that always faces the camera, floating over the block at {@code pos}. {@code scale} is world
     * units per font pixel, like a name tag (0.025 is name-tag size). The game's gizmo text has no
     * background and divides its scale by 16, so the background is ignored here and the scale is multiplied back.
     */
    public static void text(String text,BlockPos pos,int color,int background,float scale){Gizmos.billboardTextOverBlock(text,pos,0,color,scale*16f).setAlwaysOnTop();}
}
