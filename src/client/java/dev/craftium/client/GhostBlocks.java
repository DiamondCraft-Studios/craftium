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
    private record Data(List<String> states,int[] blocks,int[] removed,int total){}

    private final Data data;
    private List<BlockState> states;
    private List<Point> relative;
    private OutlineShape placedShape,removedShape;
    private List<BlockPos> cachedPlaces,removedPlaces=List.of();private Point cachedOrigin;
    /** The tint of blocks an edit is about to remove, over the original blocks that are still in the world. */

    /** Set on a preview an AI app made, which is drawn in violet so it is never taken for one of the player's own edits. */
    boolean ai;
    private static final int AI_OUTLINE=0xffc9a0ff,AI_FILL=0x22a070ff,AI_TINT=0x90b57cf0;

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
        placedShape=OutlineShape.of(points);
        var gone=new ArrayList<Point>();int[] r=data.removed();
        for(int i=0;i+2<r.length;i+=3)gone.add(new Point(r[i],r[i+1],r[i+2]));
        removedShape=OutlineShape.of(gone);
        removedPlaces=gone.stream().map(p->new BlockPos(p.x(),p.y(),p.z())).toList();
        return true;
    }

    /** Draws the ghost with its blocks moved by {@code origin}. */
    void draw(Point origin,boolean outline){
        if(data==null||!resolve())return;
        Vec3 at=new Vec3(origin.x(),origin.y(),origin.z());
        if(outline){
            int line=ai?AI_OUTLINE:ClientState.options.hoverColor,fill=ai?AI_FILL:ClientState.options.hoverFill;
            if(!placedShape.isEmpty())placedShape.drawEmphasised(at,line,(float)ClientState.options.selectionWidth,fill,true,false);
            if(!removedShape.isEmpty())removedShape.drawEmphasised(at,ClientState.options.removedColor,(float)ClientState.options.selectionWidth,ClientState.options.removedFill,true,false);
        }
        if(!removedPlaces.isEmpty()&&outline)Overlay.blocks(removedPlaces,ClientState.options.removedTint);
        if(states.isEmpty())return;
        if(cachedPlaces==null||!origin.equals(cachedOrigin)){
            var places=new ArrayList<BlockPos>(relative.size());
            for(Point p:relative)places.add(new BlockPos(p.x()+origin.x(),p.y()+origin.y(),p.z()+origin.z()));
            cachedPlaces=places;cachedOrigin=origin;
        }
        Overlay.blockStates(states,cachedPlaces,ai?AI_TINT:ClientState.options.previewTint);
    }
}
