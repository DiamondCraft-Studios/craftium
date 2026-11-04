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
    @Inject(method="travel",at=@At("HEAD"),cancellable=true)
    private void craftium$flight(Vec3 input,CallbackInfo ci){
        if(!((Object)this instanceof LocalPlayer player))return;var mc=Minecraft.getInstance();var o=ClientState.options;
        if(!player.isCreative()||!player.getAbilities().flying||player.isPassenger()||!CraftiumClient.supported()||ClientCompat.screen(mc)!=null||(!o.cameraFlight&&o.flightMomentumLevel>=1)){craftium$velocity=Vec3.ZERO;return;}
        double yaw=Math.toRadians(player.getYRot());Vec3 forward=o.cameraFlight?player.getLookAngle():new Vec3(-Math.sin(yaw),0,Math.cos(yaw)),right=new Vec3(Math.cos(yaw),0,Math.sin(yaw));double up=(mc.options.keyJump.isDown()?1:0)-(mc.options.keyShift.isDown()?1:0);
        // The game's own flight adds a little speed each tick and then keeps 91% of it sideways and 60% of it up and down. Here the same
        // speed is reached whatever the momentum is, so 100% flies exactly as the game does and less only shortens how long speed lingers.
        double acceleration=player.getAbilities().getFlyingSpeed()*(player.isSprinting()?2:1),climb=player.getAbilities().getFlyingSpeed()*3;
        double sideways=.91,vertical=.6;
        Vec3 flat=forward.scale(input.z).add(right.scale(input.x));if(flat.lengthSqr()>1)flat=flat.normalize();
        double topSide=sideways*acceleration/(1-sideways),topVertical=vertical*climb/(1-vertical);
        Vec3 wanted=new Vec3(flat.x*topSide,flat.y*topSide+up*topVertical,flat.z*topSide);
        double keepSide=sideways*o.flightMomentumLevel,keepVertical=vertical*o.flightMomentumLevel;
        craftium$velocity=new Vec3(craftium$velocity.x*keepSide+wanted.x*(1-keepSide),craftium$velocity.y*keepVertical+wanted.y*(1-keepVertical),craftium$velocity.z*keepSide+wanted.z*(1-keepSide));
        if(craftium$velocity.lengthSqr()<1e-8)craftium$velocity=Vec3.ZERO;
        player.setDeltaMovement(craftium$velocity);player.move(MoverType.SELF,craftium$velocity);player.resetFallDistance();ci.cancel();
    }
}
