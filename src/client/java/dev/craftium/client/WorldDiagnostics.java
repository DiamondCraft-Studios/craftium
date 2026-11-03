package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.client.compat.ClientCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import java.util.*;

final class WorldDiagnostics {
    private record Mark(AABB box,int color){}
    private static final List<Mark> marks=new ArrayList<>();
    private static int ticks;
    private WorldDiagnostics(){}
    private static void drawBiomes(Minecraft mc){
        var center=mc.player.blockPosition();int cx=center.getX()>>2,cy=center.getY()>>2,cz=center.getZ()>>2;
        for(int x=cx-3;x<=cx+3;x++)for(int y=cy-2;y<=cy+2;y++)for(int z=cz-3;z<=cz+3;z++){
            if(y*4<mc.level.getMinY()||y*4>=mc.level.getMaxY()||!mc.level.hasChunk(x>>2,z>>2))continue;
            String biome=mc.level.getNoiseBiome(x,y,z).unwrapKey().map(k->dev.craftium.compat.Compat.keyName(k)).orElse("unknown");int rgb=(biome.hashCode()&0x7f7f7f)+0x404040;Overlay.box(new AABB(x*4,y*4,z*4,x*4+4,y*4+4,z*4+4),0xff000000|rgb,1,0x16000000|rgb);
        }
    }
    static void draw(Minecraft mc){
        var o=ClientState.options;if(o.showBiomes&&(ClientCompat.screen(mc) instanceof EditorScreen||ClientCompat.screen(mc) instanceof StudioScreen))drawBiomes(mc);if(!o.showCollisionMesh&&!o.showLightBlocks&&!o.showStructureVoid){marks.clear();return;}
        if(++ticks%10==1){
            marks.clear();BlockPos center=mc.player.blockPosition();
            for(int x=-7;x<=7;x++)for(int y=-5;y<=5;y++)for(int z=-7;z<=7;z++){
                BlockPos at=center.offset(x,y,z);if(!mc.level.hasChunk(at.getX()>>4,at.getZ()>>4))continue;var state=mc.level.getBlockState(at);
                if(o.showLightBlocks&&state.is(Blocks.LIGHT))marks.add(new Mark(new AABB(at).deflate(.3),0xffffe28a));
                if(o.showStructureVoid&&state.is(Blocks.STRUCTURE_VOID))marks.add(new Mark(new AABB(at).deflate(.3),0xffe6a5ff));
                if(o.showCollisionMesh&&!state.isAir()&&marks.size()<2048)for(var box:state.getCollisionShape(mc.level,at).toAabbs())marks.add(new Mark(box.move(at),0xff78dfa6));
            }
        }
        for(var mark:marks)Overlay.box(mark.box,mark.color,1,0);
    }
}
