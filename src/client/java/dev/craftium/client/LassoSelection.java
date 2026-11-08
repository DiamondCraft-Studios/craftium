package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public final class LassoSelection {
    public record Pixel(double x,double y){}
    private LassoSelection(){}
    public static List<Point> project(List<Pixel> polygon) {
        if(polygon.size()<3)return List.of();var mc=Minecraft.getInstance();if(mc.level==null)return List.of();var o=ClientState.options;
        double minX=polygon.stream().mapToDouble(Pixel::x).min().orElse(0),maxX=polygon.stream().mapToDouble(Pixel::x).max().orElse(0),minY=polygon.stream().mapToDouble(Pixel::y).min().orElse(0),maxY=polygon.stream().mapToDouble(Pixel::y).max().orElse(0);
        int step=Math.max(1,(int)Math.ceil(Math.sqrt((maxX-minX)*(maxY-minY)/8192)));
        var result=new LinkedHashSet<Point>();Vec3 camera=ClientCompat.camera(mc).position();
        for(double x=minX;x<=maxX;x+=step)for(double y=minY;y<=maxY;y+=step){
            if(!contains(polygon,x,y))continue;var hit=CraftiumClient.raycast(x,y);if(hit==null)continue;
            Vec3 direction=hit.getLocation().subtract(camera).normalize();
            for(int depth=0;depth<=o.lassoDepth;depth++){
                Vec3 at=hit.getLocation().add(direction.scale(depth+.05));if(at.distanceToSqr(camera)>256*256)break;
                BlockPos pos=BlockPos.containing(at);var state=mc.level.getBlockState(pos);
                if(state.isAir()||(!o.includeNonSolid&&state.getCollisionShape(mc.level,pos).isEmpty()))continue;
                result.add(CraftiumClient.point(pos));if(result.size()>=32768)return List.copyOf(result);
            }
        }return List.copyOf(result);
    }
    static boolean contains(List<Pixel> polygon,double x,double y){boolean inside=false;for(int i=0,j=polygon.size()-1;i<polygon.size();j=i++){
        Pixel a=polygon.get(i),b=polygon.get(j);if((a.y>y)!=(b.y>y)&&x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)inside=!inside;
    }return inside;}
}
