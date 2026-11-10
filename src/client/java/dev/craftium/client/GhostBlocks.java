package dev.craftium.client;

import com.mojang.brigadier.StringReader;
import dev.craftium.client.compat.Overlay;
import dev.craftium.core.Point;
import dev.craftium.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Blocks the server describes but has not placed: an edit waiting for Enter, or the clipboard about to be pasted.
 * They are drawn as the real block models, tinted, with one outline around them.
 */
final class GhostBlocks {
    static final GhostBlocks EMPTY=new GhostBlocks(null);
    /** The shape of what the server sends; see the server's GhostCodec. */
    private record Data(List<String> states,int[] blocks,int[] removed,int total,int[] layout,List<String> names,boolean large){}

    private final Data data;
    private List<BlockState> states;
    private List<Point> relative;
    private OutlineShape placedShape,removedShape;
    private List<BlockPos> cachedPlaces,removedPlaces=List.of();private Point cachedOrigin;
    private List<BlockPos> drawnPlaces,drawnFor;private List<BlockState> drawnStates;
    /** The tint of blocks an edit is about to remove, over the original blocks that are still in the world. */

    /** Set on a preview an AI app made, which is drawn in violet so it is never taken for one of the player's own edits. */
    boolean ai;
    /** Which of the player's AI sessions made it (see AiSessions#shade): each is drawn in a colour of its own. */
    int slot;
    /** Set on another player's AI preview, which is drawn in teal so it is told apart from the player's own violet one. */
    boolean shared;
    private int[] box; // x0, y0, z0, x1, y1, z1 of the blocks it places, worked out when first asked for

    private int[] box(){
        if(box!=null)return box;
        int[] b=data==null||data.blocks()==null?new int[0]:data.blocks();
        int[] out={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        for(int i=0;i+3<b.length;i+=4)for(int k=0;k<3;k++){out[k]=Math.min(out[k],b[i+k]);out[k+3]=Math.max(out[k+3],b[i+k]);}
        return box=out;
    }

    /** Whether any block it places is within {@code range} blocks of the point: its box, a cheap test that never says no to something in range. */
    boolean within(double x,double y,double z,double range){
        int[] b=box();if(b.length==0||b[0]==Integer.MAX_VALUE)return false;
        double dx=Math.max(Math.max(b[0]-x,0),x-(b[3]+1)),dy=Math.max(Math.max(b[1]-y,0),y-(b[4]+1)),dz=Math.max(Math.max(b[2]-z,0),z-(b[5]+1));
        return dx*dx+dy*dy+dz*dz<=range*range;
    }

    /** Above the middle of what it places, for a name tag, or null when it places nothing. */
    net.minecraft.world.phys.Vec3 labelAt(){
        int[] b=box();if(b.length==0||b[0]==Integer.MAX_VALUE)return null;
        return new net.minecraft.world.phys.Vec3((b[0]+b[3]+1)/2.0,b[4]+2.5,(b[2]+b[5]+1)/2.0);
    }

    private GhostBlocks(Data data){this.data=data;}

    static GhostBlocks parse(String json){
        if(json==null||json.isBlank())return EMPTY;
        try{var data=Packets.JSON.fromJson(json,Data.class);return data==null||data.total()==0?EMPTY:new GhostBlocks(data);}
        catch(RuntimeException e){return EMPTY;}
    }

    /** For a picture of the preview: the block states it uses, the blocks it places as x, y, z, state-index quads, and the ones it removes as x, y, z triples. */
    List<String> stateNames(){return data==null||data.states()==null?List.of():data.states();}
    int[] placedCells(){return data==null||data.blocks()==null?new int[0]:data.blocks();}
    int[] removedCells(){return data==null||data.removed()==null?new int[0]:data.removed();}

    /** How many blocks the edit or clipboard really holds, which can be more than are drawn. */
    int total(){return data==null?0:data.total();}

    /** How many of those blocks the server described, and so how many can be drawn. */
    int described(){return data==null?0:(data.blocks()==null?0:data.blocks().length/4)+(data.removed()==null?0:data.removed().length/3);}

    /** True when the server cut the list short, so some blocks of the edit are held but never drawn. */
    boolean truncated(){return described()<total();}

    /** Parses the palette once there is a world to look block names up in. */
    private boolean resolve(){
        if(states!=null)return true;
        var level=Minecraft.getInstance().level;
        if(level==null||data==null)return false;
        var lookup=level.registryAccess().lookupOrThrow(Registries.BLOCK);
        var palette=new ArrayList<BlockState>();
        for(String name:data.states()){
            try{palette.add(BlockStateParser.parseForBlock(lookup,new StringReader(name),false).blockState());}
            catch(Exception e){palette.add(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());}
        }
        var list=new ArrayList<BlockState>();var points=new ArrayList<Point>();
        int[] b=data.blocks();
        for(int i=0;i+3<b.length;i+=4){points.add(new Point(b[i],b[i+1],b[i+2]));list.add(palette.get(b[i+3]));}
        relative=points;states=list;
        placedShape=OutlineShape.ofLater(points);
        var gone=new ArrayList<Point>();int[] r=data.removed();
        for(int i=0;i+2<r.length;i+=3)gone.add(new Point(r[i],r[i+1],r[i+2]));
        removedShape=OutlineShape.ofLater(gone);
        removedPlaces=gone.stream().map(p->new BlockPos(p.x(),p.y(),p.z())).toList();
        return true;
    }

    /** The blocks it places by offset, or null when it cannot say exactly (nothing to read yet, or more blocks than it carries). */
    Map<Point,BlockState> placed(){
        if(data==null||!resolve()||relative.size()!=data.total())return null;
        var out=new LinkedHashMap<Point,BlockState>();
        for(int i=0;i<relative.size();i++)out.put(relative.get(i),states.get(i));
        return out;
    }

    /** Whether Escape leaves this preview alone: a jigsaw preview stays until it is built or cancelled in the planner, so the editor can be left to look at it. */
    boolean keepsOnEscape(){return data!=null&&data.large();}

    /** Whether this is a jigsaw preview, which carries the outline of each piece. */
    boolean hasLayout(){return data!=null&&data.layout()!=null&&data.layout().length>=7;}

    /** A jigsaw preview's pieces: a box each, coloured by the template it comes from (white for the start piece), named when the camera is near. */
    private void drawLayout(Vec3 origin){
        if(!hasLayout())return;
        int[] l=data.layout();var names=data.names();
        var player=Minecraft.getInstance().player;var camera=player==null?null:player.getEyePosition();
        for(int i=0,piece=0;i+6<l.length;i+=7,piece++){
            int hue=l[i+6];
            int line=hue<0?0xffffffff:rainbow(hue,0xff),fill=hue<0?0x22ffffff:rainbow(hue,0x22);
            var box=new net.minecraft.world.phys.AABB(l[i]+origin.x,l[i+1]+origin.y,l[i+2]+origin.z,l[i+3]+1+origin.x,l[i+4]+1+origin.y,l[i+5]+1+origin.z);
            Overlay.box(box,line,2f,fill);
            if(camera!=null&&names!=null&&piece<names.size()){
                var top=new Vec3((box.minX+box.maxX)/2,box.maxY+.6,(box.minZ+box.maxZ)/2);
                double distance=Math.sqrt(top.distanceToSqr(camera));
                // A label is as big in the world as it is told to be, so close up it would fill the screen: its size follows the distance, which keeps it readable at every range.
                if(distance<64)Overlay.text(names.get(piece),top,line,0xbb151c26,(float)Math.clamp(distance*.0016,.008,.04));
            }
        }
    }
    /** A bright colour for a hue in degrees, with the given alpha. */
    private static int rainbow(int hue,int alpha){
        float h=(hue%360)/60f;int sector=(int)h;float f=h-sector;
        float q=1-f;float[] rgb=switch(sector){case 0->new float[]{1,f,0};case 1->new float[]{q,1,0};case 2->new float[]{0,1,f};case 3->new float[]{0,q,1};case 4->new float[]{f,0,1};default->new float[]{1,0,q};};
        return alpha<<24|(int)(rgb[0]*220+35)<<16|(int)(rgb[1]*220+35)<<8|(int)(rgb[2]*220+35);
    }

    /** Draws the ghost with its blocks moved by {@code origin}. */
    void draw(Point origin,boolean outline){
        if(data==null||!resolve())return;
        Vec3 at=new Vec3(origin.x(),origin.y(),origin.z());
        if(outline)drawLayout(at);
        if(outline){
            var o=ClientState.options;
            int line=shared?o.sharedAiColor:ai?dev.craftium.core.AiSessions.shade(o.aiColor,slot):o.hoverColor,fill=shared?o.sharedAiFill:ai?dev.craftium.core.AiSessions.shade(o.aiFill,slot):o.hoverFill;
            if(!placedShape.isEmpty())placedShape.drawPlain(at,line,(float)ClientState.options.selectionWidth,fill,true,false);
            if(!removedShape.isEmpty())removedShape.drawFill(at,ClientState.options.removedFill);
        }
        if(!removedPlaces.isEmpty()&&outline)Overlay.blocks(removedPlaces,ClientState.options.removedTint);
        if(states.isEmpty())return;
        if(cachedPlaces==null||!origin.equals(cachedOrigin)){
            var places=new ArrayList<BlockPos>(relative.size());
            for(Point p:relative)places.add(new BlockPos(p.x()+origin.x(),p.y()+origin.y(),p.z()+origin.z()));
            cachedPlaces=places;cachedOrigin=origin;
        }
        // An AI preview can hold far more blocks than a frame can draw as models; the server sends the visible ones first, so the first few are the ones that show.
        // Another player's AI preview is one too, so it is held to the same limit.
        int models=ai||shared||data.large()?Math.min(states.size(),ClientState.options.aiGhostDrawLimit):states.size();
        if(drawnPlaces==null||drawnPlaces.size()!=models||drawnFor!=cachedPlaces){ // the same list objects every frame, so the renderer can keep what it worked out about them
            drawnPlaces=models<states.size()?cachedPlaces.subList(0,models):cachedPlaces;
            drawnStates=models<states.size()?states.subList(0,models):states;drawnFor=cachedPlaces;
        }
        Overlay.cachedBlockStates(drawnStates,drawnPlaces,shared?ClientState.options.sharedAiTint:ai?dev.craftium.core.AiSessions.shade(ClientState.options.aiTint,slot):ClientState.options.previewTint);
    }
}
