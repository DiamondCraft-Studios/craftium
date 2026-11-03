package dev.craftium.client;

import dev.craftium.client.compat.Overlay;
import dev.craftium.core.SelectionCodec;
import dev.craftium.net.Packets;
import net.minecraft.world.phys.Vec3;

/**
 * Flashes the blocks an undo removes in red, and the blocks a redo puts back in lime, as one pulse that eases out.
 * The server sends the blocks along with the status; this only draws them.
 */
final class HistoryPulse {
    private static final int RED=0xff4a4a,LIME=0x9dff3d;
    private static OutlineShape shape=OutlineShape.EMPTY;
    private static boolean undo;
    private static long start,duration;
    /** When the latest status arrived, so the age of the next edit it reports can be carried forward. */
    static long receivedAt=System.currentTimeMillis();
    private HistoryPulse(){}

    static void receive(Packets.Status status){
        receivedAt=System.currentTimeMillis();
        if(!ClientState.options.historyPulse)return;
        String pulse=status.pulse();if(pulse==null||pulse.isBlank())return;
        String[] parts=pulse.split(":",3);if(parts.length<3)return;
        try{undo=parts[0].equals("undo");duration=Long.parseLong(parts[1]);shape=OutlineShape.of(SelectionCodec.decode(parts[2]));start=System.currentTimeMillis();}
        catch(RuntimeException e){shape=OutlineShape.EMPTY;}
    }
    /** How long ago the next edit to undo or redo was made, now; -1 when there is none. */
    static long age(boolean forUndo){
        long age=forUndo?ClientState.status.undoAge():ClientState.status.redoAge();
        return age<0?-1:age+(System.currentTimeMillis()-receivedAt);
    }
    static void draw(){
        if(shape.isEmpty())return;
        long elapsed=System.currentTimeMillis()-start;
        if(elapsed>=duration){shape=OutlineShape.EMPTY;return;}
        // One flash at full strength that dies away exponentially (ease out expo).
        double t=elapsed/(double)duration,level=1-(1-Math.pow(2,-10*t));
        int stroke=((int)(level*255)<<24)|(undo?RED:LIME);
        shape.draw(Vec3.ZERO,(a,b)->Overlay.line(a,b,stroke,2),stroke,0,false); // outlines only, no fill
    }
}
