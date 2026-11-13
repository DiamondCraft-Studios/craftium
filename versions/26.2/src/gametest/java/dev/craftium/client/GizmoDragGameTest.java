package dev.craftium.client;

import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Tool;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * The Move tool's arrow dragged by the pointer: only the pointer moves it (flying about leaves it where it is, even looking straight along the
 * axis), it follows the pointer in proportion across the view, and an axis looked along gives a finite answer instead of a leap or a turn back.
 */
public final class GizmoDragGameTest {
    private GizmoDragGameTest(){}

    public static void run(ClientGameTestContext c,TestSingleplayerContext w){
        c.runOnClient(mc->{ClientState.options.tool=Tool.MOVE;ClientState.options.confirmEdits=false;ClientState.options.gizmoMode=Options.GizmoMode.MOVE;ClientState.options.gizmoTarget=Options.GizmoTarget.AUTO;
            ClientState.options.position1=new Point(55,30,50);ClientState.options.position2=new Point(56,31,51);CraftiumClient.send("select_box",null,null);});
        w.getConnection().waitForServerboundPackets();w.getConnection().waitForClientboundPackets();
        // Looking along the Z axis, at the selection, from 20 blocks south.
        w.getServer().runCommand("tp @a 55.5 31 71 180 0");c.waitTicks(8);
        c.runOnClient(mc->{mc.player.getAbilities().flying=true;mc.player.onUpdateAbilities();mc.getWindow().setWindowed(1200,800);mc.options.guiScale().set(2);mc.resizeGui();CraftiumClient.open(false);});
        c.waitTicks(10);
        require(c.computeOnClient(mc->ClientState.status.selectionSize())==8,"the selection was not made");

        // 1. Looking along the arrow: grab it, move the pointer, then fly towards it with the pointer still. Nothing may move.
        double[] grab=c.computeOnClient(mc->grab(mc,2));
        require(grab!=null,"could not grab the Z arrow");
        c.runOnClient(mc->TransformGizmo.drag(grab[0]+20,grab[1]+10));
        Point before=c.computeOnClient(mc->TransformGizmo.moveDelta());
        require(before!=null,"the drag was not a move");
        for(int i=0;i<6;i++){
            c.runOnClient(mc->{mc.player.setPos(mc.player.getX(),mc.player.getY(),mc.player.getZ()-2);mc.player.setOldPosAndRot();});
            c.waitTicks(1);
            c.runOnClient(mc->TransformGizmo.drag(grab[0]+20,grab[1]+10)); // the same pointer, a camera that moved
        }
        Point after=c.computeOnClient(mc->TransformGizmo.moveDelta());
        require(before.equals(after),"flying along the axis moved the selection: "+before+" -> "+after);
        // A pointer that keeps going gives a finite, small answer, not a leap.
        c.runOnClient(mc->TransformGizmo.drag(grab[0]+400,grab[1]+300));
        Point far=c.computeOnClient(mc->TransformGizmo.moveDelta());
        require(Math.abs(far.z())<200,"a pointer far across an axis looked along leapt to "+far);
        c.runOnClient(mc->TransformGizmo.release((EditorScreen)mc.gui.screen()));
        w.getConnection().waitForServerboundPackets();w.getConnection().waitForClientboundPackets();c.waitTicks(5);
        System.out.println("CRAFTIUM_GIZMO_DRAG_PASS flying_leaves_selection looked_along_finite");

        // 2. Across the view (looking along X): the drag follows the pointer, twice the way is twice the blocks.
        w.getServer().runCommand("tp @a 80 31 50.5 90 0");c.waitTicks(10);
        c.runOnClient(mc->{ClientState.options.position1=new Point(55,30,50);ClientState.options.position2=new Point(56,31,51);CraftiumClient.send("select_box",null,null);});
        w.getConnection().waitForServerboundPackets();w.getConnection().waitForClientboundPackets();c.waitTicks(5);
        double[] g2=c.computeOnClient(mc->grab(mc,2));
        require(g2!=null,"could not grab the Z arrow across the view");
        c.runOnClient(mc->TransformGizmo.drag(g2[0]+60,g2[1]));
        int one=c.computeOnClient(mc->TransformGizmo.moveDelta().z());
        c.runOnClient(mc->TransformGizmo.drag(g2[0]+120,g2[1]));
        int two=c.computeOnClient(mc->TransformGizmo.moveDelta().z());
        require(one!=0&&Math.abs(two-2*one)<=1,"across the view the drag was not in proportion: "+one+" then "+two);
        c.runOnClient(mc->{mc.player.setPos(mc.player.getX()-15,mc.player.getY(),mc.player.getZ());mc.player.setOldPosAndRot();});c.waitTicks(1);
        c.runOnClient(mc->TransformGizmo.drag(g2[0]+120,g2[1]));
        int flown=c.computeOnClient(mc->TransformGizmo.moveDelta().z());
        require(flown==two,"flying across the view moved the selection: "+two+" -> "+flown);
        c.runOnClient(mc->TransformGizmo.cancel());
        System.out.println("CRAFTIUM_GIZMO_DRAG_PASS across_in_proportion flying_leaves_selection");
    }

    /** Presses the pointer on the arrow of {@code axis}, found by walking out from the gizmo's centre on the screen; its pixel, or null. */
    private static double[] grab(Minecraft mc,int axis){
        var screen=(EditorScreen)mc.gui.screen();
        Vec3 anchor=TransformGizmo.pivot();
        double[] from=project(mc,anchor),to=project(mc,anchor.add(axis==0?1:0,axis==1?1:0,axis==2?1:0));
        double dx=to[0]-from[0],dy=to[1]-from[1],length=Math.hypot(dx,dy);
        if(length<1e-6){dx=0;dy=-1;length=1;} // looking along the axis: the arrow is a point, so walk from the centre in any direction
        else{dx/=length;dy/=length;}
        for(double r=0;r<400;r+=1.5){
            double x=from[0]+dx*r,y=from[1]+dy*r;
            if(TransformGizmo.click(screen,x,y)&&TransformGizmo.dragging()&&TransformGizmo.moveDelta()!=null)return new double[]{x,y};
            TransformGizmo.cancel();
        }
        return null;
    }
    private static double[] project(Minecraft mc,Vec3 point){
        var camera=mc.gameRenderer.mainCamera();var near=camera.getNearPlane(camera.getFov());var center=near.getPointOnPlane(0,0);var offset=point.subtract(camera.position());
        double depth=offset.dot(center.normalize()),focal=mc.getWindow().getGuiScaledHeight()/(2*Math.tan(Math.toRadians(camera.getFov())/2));
        return new double[]{mc.getWindow().getGuiScaledWidth()/2d+offset.dot(near.getPointOnPlane(1,0).subtract(center).normalize())*focal/depth,mc.getWindow().getGuiScaledHeight()/2d-offset.dot(near.getPointOnPlane(0,1).subtract(center).normalize())*focal/depth};
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
