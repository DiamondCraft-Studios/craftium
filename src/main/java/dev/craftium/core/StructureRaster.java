package dev.craftium.core;

import java.util.Arrays;

/**
 * Draws a {@link StructurePreview} into a pixel buffer with an orthographic camera that orbits the structure's centre. It
 * is software rendering on purpose: the pop-up can show a texture of it on every Minecraft version, and nothing here
 * touches the game's render state. Faces are flat shaded by direction, with a thin darker edge so single blocks read.
 */
public final class StructureRaster {
    /** The camera ({@code yaw} and {@code pitch} in degrees, {@code zoom} 1 fits the structure) and what is drawn besides blocks. */
    public record View(float yaw,float pitch,float zoom,boolean bounds,boolean invisible){}
    public static final int BACKGROUND=0xff0d1219,AIR_MARK=0xff7fb8e6,VOID_MARK=0xffe0504a,BOUNDS=0xfff2f5f8;
    // Per direction (-X,+X,-Y,+Y,-Z,+Z): how bright a face looks.
    private static final float[] LIGHT={.70f,.82f,.50f,1f,.62f,.90f};
    private static final int[][] NORMALS={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    /** Fewer faces than this are drawn; a structure past it keeps working, only with its far side cut off. */
    public static final int MAX_FACES=900_000;
    private StructureRaster(){}

    private static final class Camera {
        final float cx,cy,scale,sinY,cosY,sinP,cosP,midX,midY,midZ;
        Camera(StructurePreview p,View v,int w,int h){
            midX=p.sizeX()/2f;midY=p.sizeY()/2f;midZ=p.sizeZ()/2f;
            float radius=.5f*(float)Math.sqrt((double)p.sizeX()*p.sizeX()+(double)p.sizeY()*p.sizeY()+(double)p.sizeZ()*p.sizeZ());
            scale=Math.min(w,h)*.46f/Math.max(radius,1f)*Math.clamp(v.zoom(),.05f,60f);cx=w/2f;cy=h/2f;
            double yaw=Math.toRadians(v.yaw()),pitch=Math.toRadians(Math.clamp(v.pitch(),-89f,89f));
            sinY=(float)Math.sin(yaw);cosY=(float)Math.cos(yaw);sinP=(float)Math.sin(pitch);cosP=(float)Math.cos(pitch);
        }
        /** Screen x, screen y and depth (larger is nearer) of a point given in block coordinates. */
        void project(float x,float y,float z,float[] out){
            x-=midX;y-=midY;z-=midZ;
            float x1=x*cosY+z*sinY,z1=-x*sinY+z*cosY,y2=y*cosP-z1*sinP,z2=y*sinP+z1*cosP;
            out[0]=cx+x1*scale;out[1]=cy-y2*scale;out[2]=z2;
        }
        float depthOfNormal(int nx,int ny,int nz){float z1=-nx*sinY+nz*cosY;return ny*sinP+z1*cosP;}
    }

    public static void render(StructurePreview p,int[] colours,View view,int[] pixels,int w,int h){
        if(pixels.length<w*h)throw new IllegalArgumentException("Pixel buffer too small");
        Arrays.fill(pixels,0,w*h,BACKGROUND);
        var depth=new float[w*h];Arrays.fill(depth,Float.NEGATIVE_INFINITY);
        var cam=new Camera(p,view,w,h);
        faces(p,colours,cam,pixels,depth,w,h);
        if(view.invisible())marks(p,cam,pixels,depth,w,h);
        if(view.bounds())box(p,cam,pixels,w,h);
    }

    private static boolean occludes(StructurePreview p,int x,int y,int z){
        if(x<0||y<0||z<0||x>=p.sizeX()||y>=p.sizeY()||z>=p.sizeZ())return false;
        int cell=p.cells()[p.index(x,y,z)];return cell>=0&&p.palette().get(cell).occludes();
    }

    private static void faces(StructurePreview p,int[] colours,Camera cam,int[] pixels,float[] depth,int w,int h){
        var a=new float[3];var b=new float[3];var c=new float[3];int drawn=0;
        float[] corners=new float[12];
        for(int y=0;y<p.sizeY();y++)for(int z=0;z<p.sizeZ();z++)for(int x=0;x<p.sizeX();x++){
            int cell=p.cells()[p.index(x,y,z)];if(cell<0)continue;
            var block=p.palette().get(cell);int[] bb=block.bounds();int base=cell<colours.length?colours[cell]:0xff9aa5b1;
            float x0=x+bb[0]/16f,y0=y+bb[1]/16f,z0=z+bb[2]/16f,x1=x+bb[3]/16f,y1=y+bb[4]/16f,z1=z+bb[5]/16f;
            for(int d=0;d<6;d++){
                int[] n=NORMALS[d];
                if(cam.depthOfNormal(n[0],n[1],n[2])<=0)continue;
                // A face that sits on the edge of its cell is hidden by an opaque neighbour.
                boolean onEdge=switch(d){case 0->bb[0]==0;case 1->bb[3]==16;case 2->bb[1]==0;case 3->bb[4]==16;case 4->bb[2]==0;default->bb[5]==16;};
                if(onEdge&&occludes(p,x+n[0],y+n[1],z+n[2]))continue;
                if(++drawn>MAX_FACES)return;
                // Three corners of the face and the one opposite: origin, along u, along v.
                switch(d){
                    case 0->corner(corners,x0,y0,z0,x0,y0,z1,x0,y1,z0);
                    case 1->corner(corners,x1,y0,z1,x1,y0,z0,x1,y1,z1);
                    case 2->corner(corners,x0,y0,z1,x0,y0,z0,x1,y0,z1);
                    case 3->corner(corners,x0,y1,z0,x0,y1,z1,x1,y1,z0);
                    case 4->corner(corners,x1,y0,z0,x0,y0,z0,x1,y1,z0);
                    default->corner(corners,x0,y0,z1,x1,y0,z1,x0,y1,z1);
                }
                cam.project(corners[0],corners[1],corners[2],a);cam.project(corners[3],corners[4],corners[5],b);cam.project(corners[6],corners[7],corners[8],c);
                parallelogram(a,b,c,shade(base,LIGHT[d]),pixels,depth,w,h);
            }
        }
    }
    private static void corner(float[] out,float... values){System.arraycopy(values,0,out,0,9);}

    /** Fills the parallelogram with corner {@code a} and edges {@code b-a} and c-a, nearest face winning at each pixel. */
    private static void parallelogram(float[] a,float[] b,float[] c,int colour,int[] pixels,float[] depth,int w,int h){
        float e1x=b[0]-a[0],e1y=b[1]-a[1],e2x=c[0]-a[0],e2y=c[1]-a[1],det=e1x*e2y-e1y*e2x;
        if(Math.abs(det)<.05f)return;
        float minX=Math.min(Math.min(a[0],b[0]),Math.min(c[0],b[0]+e2x)),maxX=Math.max(Math.max(a[0],b[0]),Math.max(c[0],b[0]+e2x));
        float minY=Math.min(Math.min(a[1],b[1]),Math.min(c[1],b[1]+e2y)),maxY=Math.max(Math.max(a[1],b[1]),Math.max(c[1],b[1]+e2y));
        int xs=Math.max(0,(int)Math.floor(minX)),xe=Math.min(w-1,(int)Math.ceil(maxX)),ys=Math.max(0,(int)Math.floor(minY)),ye=Math.min(h-1,(int)Math.ceil(maxY));
        float dz1=b[2]-a[2],dz2=c[2]-a[2],len1=(float)Math.hypot(e1x,e1y),len2=(float)Math.hypot(e2x,e2y);
        boolean edges=len1>=6&&len2>=6;int dark=shade(colour,.72f);
        for(int y=ys;y<=ye;y++)for(int x=xs;x<=xe;x++){
            float px=x+.5f-a[0],py=y+.5f-a[1];
            float u=(px*e2y-py*e2x)/det,v=(e1x*py-e1y*px)/det;
            if(u<0||u>1||v<0||v>1)continue;
            float z=a[2]+u*dz1+v*dz2;int at=y*w+x;
            if(z<=depth[at])continue;depth[at]=z;
            pixels[at]=edges&&(u*len1<1||(1-u)*len1<1||v*len2<1||(1-v)*len2<1)?dark:colour;
        }
    }

    private static void marks(StructurePreview p,Camera cam,int[] pixels,float[] depth,int w,int h){
        var out=new float[3];int size=cam.scale>=6?2:1;
        for(int y=0;y<p.sizeY();y++)for(int z=0;z<p.sizeZ();z++)for(int x=0;x<p.sizeX();x++){
            int cell=p.cells()[p.index(x,y,z)];if(cell>=0)continue;
            cam.project(x+.5f,y+.5f,z+.5f,out);int px=(int)out[0],py=(int)out[1];
            for(int dy=0;dy<size;dy++)for(int dx=0;dx<size;dx++){
                int sx=px+dx,sy=py+dy;if(sx<0||sy<0||sx>=w||sy>=h)continue;
                int at=sy*w+sx;if(out[2]+.6f<depth[at])continue; // behind a block
                pixels[at]=cell==StructurePreview.AIR?AIR_MARK:VOID_MARK;
            }
        }
    }

    private static void box(StructurePreview p,Camera cam,int[] pixels,int w,int h){
        var corner=new float[8][3];
        for(int i=0;i<8;i++)cam.project((i&1)*p.sizeX(),((i>>1)&1)*p.sizeY(),((i>>2)&1)*p.sizeZ(),corner[i]);
        for(int i=0;i<8;i++)for(int bit=1;bit<8;bit<<=1)if((i&bit)==0)line(corner[i],corner[i|bit],pixels,w,h);
    }
    private static void line(float[] a,float[] b,int[] pixels,int w,int h){
        int steps=(int)Math.min(4096,Math.max(Math.abs(b[0]-a[0]),Math.abs(b[1]-a[1])))+1;
        for(int i=0;i<=steps;i++){
            float t=i/(float)steps;int x=Math.round(a[0]+(b[0]-a[0])*t),y=Math.round(a[1]+(b[1]-a[1])*t);
            if(x>=0&&y>=0&&x<w&&y<h)pixels[y*w+x]=BOUNDS;
        }
    }

    private static int shade(int argb,float factor){
        int r=Math.min(255,(int)(((argb>>16)&255)*factor)),g=Math.min(255,(int)(((argb>>8)&255)*factor)),bl=Math.min(255,(int)((argb&255)*factor));
        return 0xff000000|r<<16|g<<8|bl;
    }
}
