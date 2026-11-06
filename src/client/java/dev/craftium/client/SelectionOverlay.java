package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.client.compat.Overlay;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import java.util.*;

final class SelectionOverlay {
    /** Past this many blocks the real models are not drawn, because a frame would have to draw all of them. */
    private static final int MAX_GHOST_BLOCKS=4096;
    private static String encoded="";
    private static OutlineShape shape=OutlineShape.EMPTY;
    /**
     * The outline is worked out on a worker thread, because for a selection of a hundred thousand blocks it takes a couple of
     * hundred milliseconds, which would freeze the game. Until it is ready the previous outline stays on screen.
     */
    private static final LatestResult<OutlineShape> building=new LatestResult<>("Craftium selection outline");
    /** Whether the outline of the latest selection is still being worked out. */
    static boolean building(){return building.busy();}
    /** Takes over a finished outline. Called wherever the outline is read, so it always shows the newest one that is ready. */
    private static void adopt(){var ready=building.poll();if(ready!=null)shape=ready;}
    private static List<BlockPos> blocks=List.of(),moved=List.of();
    private static Point movedBy;
    private static List<Point> selected=List.of();
    /** The selected blocks as last received; replaced, never changed, so the same list means the same selection. */
    static List<Point> points(){return selected;}
    private static Set<BlockPos> blockSet=Set.of();
    private static Set<Point> pointSet=Set.of();
    /** Whether the block is one of the selected blocks, however many there are. */
    static boolean contains(BlockPos at){return !pointSet.isEmpty()&&pointSet.contains(new Point(at.getX(),at.getY(),at.getZ()));}
    /** The outline of the whole selection. */
    static OutlineShape outline(){adopt();return shape;}
    private static List<Vec3[]> contacts=List.of();
    private static long contactsAt;
    private SelectionOverlay(){}
    static void update(String data){
        if(Objects.equals(encoded,data))return;encoded=data;
        var points=SelectionCodec.decode(data);selected=List.copyOf(points);
        if(points.isEmpty()){building.cancel();shape=OutlineShape.EMPTY;} // nothing to wait for: an empty selection must not linger on screen
        else building.submit(()->OutlineShape.of(points)); // the decoded set is not touched again here: the lists below are copies
        blocks=points.size()>MAX_GHOST_BLOCKS?List.of():points.stream().map(p->new BlockPos(p.x(),p.y(),p.z())).toList();
        blockSet=new HashSet<>(blocks);pointSet=new HashSet<>(selected);contactsAt=0;
    }
    /**
     * The faces of the selection that touch a solid block outside it, so it is clear whether the selection sits inside a solid
     * block or beside it. Worked out again a few times a second, because the world can change under a still selection.
     */
    private static List<Vec3[]> contacts(){
        long now=System.nanoTime();
        if(now-contactsAt<250_000_000L)return contacts;
        contactsAt=now;
        var level=Minecraft.getInstance().level;
        if(level==null||blocks.isEmpty())return contacts=List.of();
        var found=new ArrayList<Vec3[]>();
        for(BlockPos at:blocks)for(Direction d:Direction.values()){
            BlockPos next=at.relative(d);
            if(blockSet.contains(next)||level.getBlockState(next).getCollisionShape(level,next).isEmpty())continue;
            found.add(quad(at,d));
        }
        return contacts=found;
    }
    /** The face of the block on its {@code d} side, wound counter-clockwise as seen from outside. */
    private static Vec3[] quad(BlockPos at,Direction d){
        int axis=d.getAxis().ordinal(),u=(axis+1)%3,v=(axis+2)%3,sign=d.getAxisDirection().getStep();
        int[] base={at.getX(),at.getY(),at.getZ()};
        double[][] ring={{0,0},{1,0},{1,1},{0,1}};
        Vec3[] c=new Vec3[4];
        for(int i=0;i<4;i++){
            double[] p=new double[3];p[axis]=base[axis]+(sign>0?1:0);p[u]=base[u]+ring[i][0];p[v]=base[v]+ring[i][1];
            c[sign>0?i:3-i]=new Vec3(p[0],p[1],p[2]);
        }
        return c;
    }
    /** A faint wash over the whole screen while the camera is inside the selection, like the one under water. Drawn with the interface, not in the world. */
    static void tint(dev.craftium.client.compat.Gfx g){
        adopt();
        int colour=ClientState.options.selectionInsideTint;
        if((colour>>>24)==0||shape.isEmpty()||!shape.cameraInside())return;
        g.fill(0,0,g.guiWidth(),g.guiHeight(),colour);
    }
    /**
     * Draws the selection as one outline around its outer surface, with a fill on each exterior face. While the
     * transform gizmo is being dragged only the outline is drawn, moved by the transform being applied.
     */
    static void draw(boolean transforming){
        adopt();
        if(shape.isEmpty())return;
        int colour=ClientState.options.selectionColor;float width=(float)ClientState.options.selectionWidth;
        var landing=transforming?TransformGizmo.landing(selected):null;
        if(landing!=null)landing.drawPlain(Vec3.ZERO,colour,width,ClientState.options.selectionFill,true,false); // a rotate or scale: the blocks it will leave
        else if(transforming)shape.draw(Vec3.ZERO,TransformGizmo::line,colour,ClientState.options.selectionFill,false);
        else shape.drawPlain(Vec3.ZERO,colour,width,ClientState.options.selectionFill,ClientState.options.selectionInsideFill,true,false);
        int contact=ClientState.options.selectionContact;
        if(!transforming&&ClientState.options.showSelectionContact&&(contact>>>24)!=0)for(var q:contacts())Overlay.face(q[0],q[1],q[2],q[3],contact);
        if(blocks.isEmpty())return;
        Point moving=TransformGizmo.moveDelta();
        if(moving!=null){ // the blocks being dragged, shown where they would land
            if(!moving.equals(movedBy)){movedBy=moving;moved=blocks.stream().map(b->b.offset(moving.x(),moving.y(),moving.z())).toList();}
            Overlay.blocks(blocks,moved,ClientState.options.previewTint);
        }else if(!transforming&&!ClientState.hasPending()) // a waiting edit shows its own blocks
            Overlay.blocks(blocks,ClientState.options.selectionTint);
    }
}
