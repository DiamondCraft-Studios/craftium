package dev.craftium.client.mixin;

import dev.craftium.client.compat.ClientCompat;import dev.craftium.client.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Player.class)
abstract class FlightMixin {
    @Unique private Vec3 craftium$velocity=Vec3.ZERO;
    /** Flying DoF: the wall the player has been slowed by in this hold of the movement keys, and whether that braking has been used up for now. */
    @Unique private boolean craftium$braked,craftium$spent;
    /** How far ahead a wall starts to slow the player, and how close they stop. */
    @Unique private static final double DOF_RANGE=6,DOF_STOP=.6;
    /** The free distance, up to the range, from the middle of the player to the first block in the way along {@code direction}. */
    @Unique private static double craftium$wallAhead(LocalPlayer player,Vec3 direction){
        Vec3 from=player.getBoundingBox().getCenter(),to=from.add(direction.scale(DOF_RANGE));
        var hit=player.level().clip(new net.minecraft.world.level.ClipContext(from,to,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,player));
        return hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS?DOF_RANGE:hit.getLocation().distanceTo(from);
    }
    @Inject(method="travel",at=@At("HEAD"),cancellable=true)
    private void craftium$flight(Vec3 input,CallbackInfo ci){
        if(!((Object)this instanceof LocalPlayer player))return;var mc=Minecraft.getInstance();var o=ClientState.options;
        if(!player.isCreative()||!player.getAbilities().flying||player.isPassenger()||!CraftiumClient.supported()||ClientCompat.screen(mc)!=null){craftium$velocity=Vec3.ZERO;return;}
        double yaw=Math.toRadians(player.getYRot());Vec3 forward=o.cameraFlight?player.getLookAngle():new Vec3(-Math.sin(yaw),0,Math.cos(yaw)),right=new Vec3(Math.cos(yaw),0,Math.sin(yaw));double up=(mc.options.keyJump.isDown()?1:0)-(mc.options.keyShift.isDown()?1:0);
        // The game's own flight reaches about 10 blocks a second sideways but under half of that up and down (it keeps 60% of the speed
        // vertically, against 91% sideways, and climbs at 3 times the flying speed), so going up felt slow beside going forward. Here every
        // direction reaches the same speed and keeps the same share of it, so up and down fly exactly as fast as forward and sideways, and
        // diagonals are no faster. Sprinting speeds all of them up alike. The momentum setting only changes how long speed lingers.
        double acceleration=player.getAbilities().getFlyingSpeed()*(player.isSprinting()?2:1);
        double keep=.91;
        Vec3 wish=forward.scale(input.z).add(right.scale(input.x)).add(0,up,0);if(wish.lengthSqr()>1)wish=wish.normalize();
        double top=keep*acceleration/(1-keep);
        double focus=1;
        if(o.flyingDof){
            if(wish.lengthSqr()<1e-6){ // let go: a brake that was used is spent, so the next hold bumps into the wall, or passes through it with No clip
                if(craftium$braked)craftium$spent=true;
                craftium$braked=false;
            }else{
                double room=craftium$wallAhead(player,wish.normalize());
                if(room>=DOF_RANGE)craftium$spent=false; // clear of walls again: the next approach brakes afresh
                else if(!craftium$spent){
                    double t=Math.clamp((room-DOF_STOP)/(DOF_RANGE-DOF_STOP),0,1);
                    focus=t*t*(3-2*t); // eases in and out, like a lens racking focus
                    craftium$braked=true;
                }
            }
        }else{craftium$braked=craftium$spent=false;}
        Vec3 wanted=wish.scale(top*focus);
        double kept=keep*o.flightMomentumLevel;
        craftium$velocity=craftium$velocity.scale(kept).add(wanted.scale(1-kept));
        if(focus<1){double allowed=top*focus,speed=craftium$velocity.length();if(speed>allowed)craftium$velocity=craftium$velocity.scale(allowed/speed);} // momentum may not carry past where the wall slows
        if(craftium$velocity.lengthSqr()<1e-8)craftium$velocity=Vec3.ZERO;
        player.setDeltaMovement(craftium$velocity);player.move(MoverType.SELF,craftium$velocity);player.resetFallDistance();ci.cancel();
    }
}
