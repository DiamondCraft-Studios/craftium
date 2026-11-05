package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.Point;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * A set of blocks drawn as the world hides it: where the blocks sit in solid ground, the faces you can see are coloured dimly, depth-tested,
 * with no edges (so nothing shows through terrain, and a selection lying flush on the ground reads as a surface); where they sit in open
 * air they are outlined in the selection's style. Shared by the brush preview and the Builder's selection.
 */
final class SceneShape {
    /** More exposed faces than this are not coloured one by one; {@link #tooLarge} is set and the caller draws a box. */
    static final int MAX_FACES=65_536;
    private static final float LIFT=.003f;
    /** Dim faces: the alpha byte of the colour they are drawn in. */
    static final int FACE_ALPHA=0x66000000;
    static final SceneShape EMPTY=new SceneShape(List.of(),OutlineShape.EMPTY,false);

    private final List<Vec3[]> faces;
    private final OutlineShape air;
    private final boolean tooLarge;

    private SceneShape(List<Vec3[]> faces,OutlineShape air,boolean tooLarge){this.faces=faces;this.air=air;this.tooLarge=tooLarge;}

    boolean tooLarge(){return tooLarge;}

    static SceneShape of(BlockGetter level,Collection<Point> points){return of(level,points,false);}
    /** With {@code outlineAll} the outline runs round every block, solid ones too (still hidden where blocks are in front of it), not only those in open air. */
    static SceneShape of(BlockGetter level,Collection<Point> points,boolean outlineAll){
        var open=new ArrayList<Point>();var solid=new HashSet<Point>();
        for(Point p:points){
            if(level.getBlockState(new BlockPos(p.x(),p.y(),p.z())).isAir())open.add(p);else solid.add(p);
        }
        var found=new ArrayList<Vec3[]>();
        for(Point p:solid)for(var d:Direction.values()){
            if(!level.getBlockState(new BlockPos(p.x()+d.getStepX(),p.y()+d.getStepY(),p.z()+d.getStepZ())).isAir())continue; // buried faces cannot be seen
            found.add(face(p,d));
            if(found.size()>MAX_FACES)return new SceneShape(List.of(),OutlineShape.EMPTY,true);
        }
        return new SceneShape(found,OutlineShape.of(outlineAll?points:open),false);
    }

    /** The dim faces, then the outline of the blocks in open air (with {@code airFill} on their faces, when given). */
    void draw(int faceFill,int stroke,float width,int airFill){
        for(var f:faces)Overlay.sceneFace(f[0],f[1],f[2],f[3],faceFill);
        air.drawPlain(Vec3.ZERO,stroke,width,airFill,airFill!=0,true);
    }

    /** The face of the block at {@code p} on the side {@code d} points to, lifted off the block so it does not fight it for depth, counter-clockwise from outside. */
    private static Vec3[] face(Point p,Direction d){
        int axis=d.getAxis().ordinal();boolean positive=d.getAxisDirection()==Direction.AxisDirection.POSITIVE;
        double[] base={p.x(),p.y(),p.z()};base[axis]+=positive?1+LIFT:-LIFT;
        int u=(axis+1)%3,v=(axis+2)%3;
        if(!positive){int t=u;u=v;v=t;} // the other way round faces the other way
        return new Vec3[]{at(base,0,0,u,v),at(base,1,0,u,v),at(base,1,1,u,v),at(base,0,1,u,v)};
    }
    private static Vec3 at(double[] base,int a,int b,int u,int v){double[] c=base.clone();c[u]+=a;c[v]+=b;return new Vec3(c[0],c[1],c[2]);}
}
