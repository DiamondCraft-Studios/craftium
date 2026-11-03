package dev.craftium.client;

import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;

/**
 * Tells the player a large selection is still being worked out: a toast, and a spinning cursor in place of the arrow.
 * A selection that comes back at once never shows either; only one still pending after a moment does.
 */
final class SelectingIndicator {
    private static final int SIZE=32,FRAMES=12,FRAME_MILLIS=70;
    private static final int RING=0x6DC8FF,SHADOW=0x141d29;
    private static long[] cursors;
    private static boolean shown;
    private SelectingIndicator(){}

    static void tick(Minecraft mc){
        boolean on=ClientState.selecting()||BuilderMode.selecting();
        if(on){
            ClientState.notify("Selected large area...");
            if(frames().length>0)GLFW.glfwSetCursor(mc.getWindow().handle(),cursors[(int)(System.currentTimeMillis()/FRAME_MILLIS%FRAMES)]);
            shown=true;
        }else if(shown){
            shown=false;ClientState.noticeUntil=0; // the toast would otherwise outlast the work
            GLFW.glfwSetCursor(mc.getWindow().handle(),0);
        }
    }

    /** A turn of the spinner in the HUD, for the builder, where the pointer is hidden. */
    static String spinner(){return "|/-\\".substring((int)(System.currentTimeMillis()/FRAME_MILLIS%4),(int)(System.currentTimeMillis()/FRAME_MILLIS%4)+1);}

    /** The spinner's frames, drawn once: a ring with a bright head and a fading tail, turning a twelfth each frame. */
    private static long[] frames(){
        if(cursors!=null)return cursors;
        var made=new long[FRAMES];
        try{
            for(int frame=0;frame<FRAMES;frame++){
                var pixels=BufferUtils.createByteBuffer(SIZE*SIZE*4);
                double head=frame*2*Math.PI/FRAMES;
                for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){
                    double dx=x-SIZE/2d+.5,dy=y-SIZE/2d+.5,radius=Math.hypot(dx,dy);
                    int rgb=SHADOW,alpha=0;
                    if(radius>=7&&radius<=14)alpha=170; // a dark disc under the ring, so it reads on any background
                    if(radius>=9&&radius<=12){
                        double behind=Math.floorMod((long)((head-Math.atan2(dy,dx))*1000),(long)(2*Math.PI*1000))/(2*Math.PI*1000);
                        rgb=RING;alpha=(int)(255*Math.max(.15,1-behind));
                    }
                    pixels.put((byte)(rgb>>16)).put((byte)(rgb>>8)).put((byte)rgb).put((byte)alpha);
                }
                pixels.flip();
                var image=GLFWImage.malloc();
                try{image.set(SIZE,SIZE,pixels);made[frame]=GLFW.glfwCreateCursor(image,SIZE/2,SIZE/2);}finally{image.free();}
                if(made[frame]==0)throw new IllegalStateException("no cursor");
            }
        }catch(RuntimeException e){made=new long[0];} // an arrow that does not spin is better than none
        return cursors=made;
    }
}
