package dev.craftium.client;

import dev.craftium.core.BrushMath;
import dev.craftium.core.Options;
import dev.craftium.core.Tool;
import dev.craftium.client.compat.Gfx;

/** Small original line icons, drawn at UI resolution without Axiom assets. */
final class ToolIcons {
    private ToolIcons() {}
    static void draw(Gfx g,Tool tool,int x,int y,int color) {
        switch(tool) {
            case LASSO_SELECT -> {line(g,x+3,y+7,x+11,y+3,color);line(g,x+11,y+3,x+21,y+9,color);line(g,x+21,y+9,x+17,y+18,color);line(g,x+17,y+18,x+5,y+18,color);line(g,x+5,y+18,x+3,y+7,color);line(g,x+11,y+18,x+7,y+23,color);}
            case PATH,MODELLING -> {line(g,x+3,y+19,x+9,y+5,color);line(g,x+9,y+5,x+20,y+17,color);for(int[] p:new int[][]{{3,19},{9,5},{20,17}})g.fill(x+p[0]-2,y+p[1]-2,x+p[0]+3,y+p[1]+3,color);}
            case GRADIENT_PAINTER -> {for(int i=0;i<5;i++)g.fill(x+3+i*4,y+3,x+6+i*4,y+22,(color&0xffffff)|((80+i*40)<<24));}
            case SCRIPT_BRUSH -> {line(g,x+8,y+5,x+2,y+12,color);line(g,x+2,y+12,x+8,y+19,color);line(g,x+17,y+5,x+23,y+12,color);line(g,x+23,y+12,x+17,y+19,color);line(g,x+15,y+3,x+10,y+22,color);}
            case FLOODFILL,FLUID_BALL -> {line(g,x+12,y+2,x+3,y+15,color);line(g,x+12,y+2,x+21,y+15,color);line(g,x+3,y+15,x+8,y+22,color);line(g,x+8,y+22,x+17,y+22,color);line(g,x+17,y+22,x+21,y+15,color);}
            case MOVE -> {line(g,x+12,y+2,x+12,y+22,color);line(g,x+2,y+12,x+22,y+12,color);line(g,x+12,y+2,x+8,y+7,color);line(g,x+12,y+2,x+16,y+7,color);line(g,x+22,y+12,x+17,y+8,color);line(g,x+22,y+12,x+17,y+16,color);}
            case ROTATE -> { // most of a circle, with an arrowhead where it ends
                for(int i=0;i<20;i++){double a=Math.toRadians(45+i*15),b=Math.toRadians(45+(i+1)*15);line(g,x+12+(int)Math.round(Math.cos(a)*8),y+12+(int)Math.round(Math.sin(a)*8),x+12+(int)Math.round(Math.cos(b)*8),y+12+(int)Math.round(Math.sin(b)*8),color);}
                double end=Math.toRadians(345),px=x+12+Math.cos(end)*8,py=y+12+Math.sin(end)*8,tx=-Math.sin(end),ty=Math.cos(end),nx=Math.cos(end),ny=Math.sin(end);
                int tipX=(int)Math.round(px+tx*4),tipY=(int)Math.round(py+ty*4);
                line(g,tipX,tipY,(int)Math.round(px-tx*2+nx*4),(int)Math.round(py-ty*2+ny*4),color);line(g,tipX,tipY,(int)Math.round(px-tx*2-nx*4),(int)Math.round(py-ty*2-ny*4),color);
            }
            case SCALE -> {g.outline(x+3,y+9,11,11,color);g.outline(x+9,y+3,12,12,color);line(g,x+14,y+10,x+21,y+3,color);}
            case PIVOT -> {g.outline(x+7,y+7,10,10,color);g.fill(x+11,y+11,x+14,y+14,color);line(g,x+12,y+2,x+12,y+7,color);line(g,x+12,y+17,x+12,y+22,color);line(g,x+2,y+12,x+7,y+12,color);line(g,x+17,y+12,x+22,y+12,color);}
            case STAMP,SMEAR,ANNOTATION -> {g.outline(x+3,y+11,19,10,color);g.fill(x+9,y+3,x+16,y+13,color);}
            case MODIFY -> { // a wrench: a handle running to the lower left, and an open jaw at the upper right
                line(g,x+4,y+20,x+13,y+11,color);line(g,x+5,y+21,x+14,y+12,color);line(g,x+3,y+19,x+12,y+10,color);
                line(g,x+11,y+9,x+13,y+4,color);line(g,x+13,y+4,x+16,y+7,color);line(g,x+16,y+7,x+18,y+5,color);line(g,x+18,y+5,x+21,y+8,color);
                line(g,x+21,y+8,x+19,y+10,color);line(g,x+19,y+10,x+22,y+13,color);line(g,x+22,y+13,x+17,y+15,color);line(g,x+17,y+15,x+14,y+12,color);
            }
            case MAGIC_SELECT -> {line(g,x+3,y+19,x+17,y+5,color);star(g,x+6,y+5,color);star(g,x+19,y+13,color);}
            case BOX_SELECT -> {dotted(g,x+3,y+3,18,18,color);g.fill(x+6,y+6,x+18,y+18,color&0xffffff|0x50000000);}
            case FREEHAND_SELECT -> {line(g,x+3,y+14,x+8,y+4,color);line(g,x+8,y+4,x+20,y+7,color);line(g,x+20,y+7,x+16,y+20,color);line(g,x+16,y+20,x+3,y+14,color);}
            case RULER,LINE -> {line(g,x+3,y+19,x+19,y+3,color);for(int i=0;i<4;i++)line(g,x+5+i*4,y+17-i*4,x+8+i*4,y+20-i*4,color);}
            case PAINTER,NOISE_PAINTER,BIOME_PAINTER,CLENTAMINATOR -> {
                line(g,x+3,y+19,x+13,y+9,color);line(g,x+4,y+20,x+14,y+10,color);g.fill(x+11,y+3,x+20,y+10,color);
                if(tool!=Tool.PAINTER)for(int i=0;i<3;i++)g.fill(x+15+i*2,y+13+i*2,x+16+i*2,y+15+i*2,color);
            }
            case FREEHAND_DRAW,SCULPT_DRAW -> {line(g,x+4,y+19,x+17,y+6,color);line(g,x+6,y+21,x+19,y+8,color);line(g,x+17,y+6,x+19,y+8,color);line(g,x+4,y+19,x+3,y+22,color);if(tool==Tool.SCULPT_DRAW)star(g,x+5,y+5,color);}
            case ROCK,SHAPE -> {line(g,x+3,y+8,x+13,y+3,color);line(g,x+13,y+3,x+21,y+9,color);line(g,x+21,y+9,x+19,y+20,color);line(g,x+19,y+20,x+7,y+22,color);line(g,x+7,y+22,x+3,y+8,color);line(g,x+3,y+8,x+12,y+12,color);line(g,x+12,y+12,x+21,y+9,color);line(g,x+12,y+12,x+12,y+21,color);}
            case TEXT -> {g.fill(x+3,y+4,x+21,y+7,color);g.fill(x+10,y+6,x+14,y+21,color);g.fill(x+6,y+20,x+18,y+22,color);}
            case WELD,MELT -> {line(g,x+3,y+5,x+5,y+18,color);line(g,x+5,y+18,x+19,y+18,color);line(g,x+19,y+18,x+21,y+5,color);g.fill(x+10,y+5,x+14,y+14,color);line(g,x+8,y+(tool==Tool.WELD?10:8),x+12,y+(tool==Tool.WELD?6:12),color);line(g,x+12,y+(tool==Tool.WELD?6:12),x+16,y+(tool==Tool.WELD?10:8),color);}
            case ELEVATION,EXTRUDE -> {line(g,x+3,y+19,x+12,y+15,color);line(g,x+12,y+15,x+21,y+19,color);line(g,x+21,y+19,x+12,y+23,color);line(g,x+12,y+23,x+3,y+19,color);g.fill(x+11,y+5,x+14,y+18,color);line(g,x+7,y+10,x+12,y+4,color);line(g,x+12,y+4,x+18,y+10,color);}
            case FLATTEN,SLOPE -> {line(g,x+2,y+20,x+22,y+(tool==Tool.SLOPE?5:20),color);line(g,x+5,y+8,x+12,y+15,color);line(g,x+12,y+15,x+19,y+8,color);g.fill(x+11,y+3,x+14,y+13,color);}
            case SMOOTH,DISTORT -> {for(int i=0;i<3;i++){line(g,x+2,y+6+i*6,x+8,y+3+i*6,color);line(g,x+8,y+3+i*6,x+15,y+8+i*6,color);line(g,x+15,y+8+i*6,x+22,y+5+i*6,color);}}
            case ROUGHEN,SHATTER -> {for(int i=0;i<8;i++){double a=i*Math.PI/4;line(g,x+12,y+12,x+12+(int)(Math.cos(a)*10),y+12+(int)(Math.sin(a)*10),color);}star(g,x+12,y+12,color);}
            case ERASE -> {line(g,x+3,y+14,x+14,y+3,color);line(g,x+14,y+3,x+22,y+11,color);line(g,x+22,y+11,x+11,y+22,color);line(g,x+11,y+22,x+3,y+14,color);line(g,x+7,y+10,x+15,y+18,color);}
            case TINKER -> {line(g,x+4,y+21,x+17,y+8,color);g.fill(x+14,y+3,x+18,y+9,color);g.fill(x+18,y+7,x+22,y+12,color);}
        }
    }
    /** The Magic Wand's comparison choices, on the same 24 pixel grid as the tool icons. */
    static void compare(Gfx g,Options.Compare compare,int x,int y,int color) {
        switch(compare) {
            case ANY -> {for(int i=0;i<4;i++)g.outline(x+3+(i%2)*10,y+3+(i/2)*10,8,8,color);g.fill(x+5,y+5,x+9,y+9,color);g.fill(x+15,y+15,x+19,y+19,color&0xffffff|0x80000000);}
            case BLOCK -> {g.outline(x+4,y+4,16,16,color);g.fill(x+8,y+8,x+16,y+16,color);}
            case STATE -> {g.outline(x+3,y+3,18,18,color);line(g,x+6,y+9,x+17,y+9,color);line(g,x+6,y+16,x+17,y+16,color);g.fill(x+8,y+7,x+11,y+12,color);g.fill(x+13,y+14,x+16,y+19,color);}
            case TAG -> {line(g,x+3,y+12,x+12,y+3,color);line(g,x+12,y+3,x+21,y+3,color);line(g,x+21,y+3,x+21,y+12,color);line(g,x+21,y+12,x+12,y+21,color);line(g,x+12,y+21,x+3,y+12,color);g.fill(x+16,y+6,x+19,y+9,color);}
            case NBT -> {line(g,x+9,y+3,x+6,y+6,color);line(g,x+6,y+6,x+6,y+10,color);line(g,x+6,y+10,x+3,y+12,color);line(g,x+3,y+12,x+6,y+14,color);line(g,x+6,y+14,x+6,y+18,color);line(g,x+6,y+18,x+9,y+21,color);
                line(g,x+15,y+3,x+18,y+6,color);line(g,x+18,y+6,x+18,y+10,color);line(g,x+18,y+10,x+21,y+12,color);line(g,x+21,y+12,x+18,y+14,color);line(g,x+18,y+14,x+18,y+18,color);line(g,x+18,y+18,x+15,y+21,color);}
        }
    }
    /** The Shape tool's solids, on the same 24 pixel grid as the tool icons. */
    static void shape(Gfx g,BrushMath.Shape shape,int x,int y,int color) {
        switch(shape) {
            case SPHERE -> {ring(g,x+12,y+12,9,9,color);ring(g,x+12,y+12,9,3,color);line(g,x+12,y+3,x+12,y+21,color);}
            case CUBE -> {line(g,x+12,y+2,x+21,y+7,color);line(g,x+21,y+7,x+21,y+17,color);line(g,x+21,y+17,x+12,y+22,color);line(g,x+12,y+22,x+3,y+17,color);line(g,x+3,y+17,x+3,y+7,color);line(g,x+3,y+7,x+12,y+2,color);line(g,x+3,y+7,x+12,y+12,color);line(g,x+21,y+7,x+12,y+12,color);line(g,x+12,y+12,x+12,y+22,color);}
            case OCTAHEDRON -> {line(g,x+12,y+2,x+21,y+12,color);line(g,x+21,y+12,x+12,y+22,color);line(g,x+12,y+22,x+3,y+12,color);line(g,x+3,y+12,x+12,y+2,color);line(g,x+3,y+12,x+21,y+12,color);line(g,x+12,y+2,x+12,y+22,color);}
            case CYLINDER -> {ring(g,x+12,y+6,8,3,color);line(g,x+4,y+6,x+4,y+18,color);line(g,x+20,y+6,x+20,y+18,color);arc(g,x+12,y+18,8,3,0,Math.PI,color);}
            case PLANE -> {line(g,x+2,y+17,x+7,y+8,color);line(g,x+7,y+8,x+22,y+8,color);line(g,x+22,y+8,x+17,y+17,color);line(g,x+17,y+17,x+2,y+17,color);}
            case DISK -> {ring(g,x+12,y+12,10,5,color);g.fill(x+11,y+11,x+14,y+14,color);}
            case POLYGON -> {line(g,x+12,y+3,x+21,y+9,color);line(g,x+21,y+9,x+18,y+20,color);line(g,x+18,y+20,x+6,y+20,color);line(g,x+6,y+20,x+3,y+9,color);line(g,x+3,y+9,x+12,y+3,color);}
            case PYRAMID -> {line(g,x+12,y+2,x+3,y+17,color);line(g,x+12,y+2,x+13,y+22,color);line(g,x+12,y+2,x+22,y+16,color);line(g,x+3,y+17,x+13,y+22,color);line(g,x+13,y+22,x+22,y+16,color);line(g,x+3,y+17,x+10,y+14,color);line(g,x+10,y+14,x+22,y+16,color);}
            case CONE -> {line(g,x+12,y+2,x+3,y+18,color);line(g,x+12,y+2,x+21,y+18,color);ring(g,x+12,y+18,9,3,color);}
            case HEMISPHERE -> {arc(g,x+12,y+16,10,12,Math.PI,Math.PI*2,color);ring(g,x+12,y+16,10,3,color);}
            case TORUS -> {ring(g,x+12,y+12,10,6,color);ring(g,x+12,y+12,4,2,color);}
            case TUBE -> {ring(g,x+12,y+6,9,3,color);ring(g,x+12,y+6,4,1,color);line(g,x+3,y+6,x+3,y+18,color);line(g,x+21,y+6,x+21,y+18,color);arc(g,x+12,y+18,9,3,0,Math.PI,color);}
        }
    }
    private static void ring(Gfx g,int cx,int cy,int rx,int ry,int c){arc(g,cx,cy,rx,ry,0,Math.PI*2,c);}
    /** The part of an ellipse from angle {@code from} to {@code to} (radians, y grows downward), as dots two pixels wide. */
    private static void arc(Gfx g,int cx,int cy,int rx,int ry,double from,double to,int c){
        int steps=Math.max(16,(int)((to-from)/(Math.PI*2)*40));
        for(int i=0;i<=steps;i++){double a=from+(to-from)*i/steps;int px=cx+(int)Math.round(Math.cos(a)*rx),py=cy+(int)Math.round(Math.sin(a)*ry);g.fill(px,py,px+2,py+2,c);}
    }
    static void line(Gfx g,int x1,int y1,int x2,int y2,int c) {
        int steps=Math.max(Math.abs(x2-x1),Math.abs(y2-y1));
        for(int i=0;i<=steps;i++){float t=steps==0?0:(float)i/steps;int x=Math.round(x1+(x2-x1)*t),y=Math.round(y1+(y2-y1)*t);g.fill(x,y,x+2,y+2,c);}
    }
    private static void star(Gfx g,int x,int y,int c){g.fill(x-3,y,x+4,y+1,c);g.fill(x,y-3,x+1,y+4,c);}
    private static void dotted(Gfx g,int x,int y,int w,int h,int c){for(int i=0;i<w;i+=3){g.fill(x+i,y,x+i+2,y+1,c);g.fill(x+i,y+h,x+i+2,y+h+1,c);}for(int i=0;i<h;i+=3){g.fill(x,y+i,x+1,y+i+2,c);g.fill(x+w,y+i,x+w+1,y+i+2,c);}}
}
