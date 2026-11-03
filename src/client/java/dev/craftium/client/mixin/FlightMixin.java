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
        if(!player.isCreative()||!player.getAbilities().flying||player.isPassenger()||!CraftiumClient.supported()||ClientCompat.screen(mc)!=null||(!o.cameraFlight&&!o.enhancedFlight&&o.flightMomentum==0)){craftium$velocity=Vec3.ZERO;return;}
        double yaw=Math.toRadians(player.getYRot());Vec3 forward=o.cameraFlight?player.getLookAngle():new Vec3(-Math.sin(yaw),0,Math.cos(yaw)),right=new Vec3(Math.cos(yaw),0,Math.sin(yaw));double up=(mc.options.keyJump.isDown()?1:0)-(mc.options.keyShift.isDown()?1:0);
        Vec3 wanted=forward.scale(input.z).add(right.scale(input.x)).add(0,up,0);if(wanted.lengthSqr()>1)wanted=wanted.normalize();double speed=player.getAbilities().getFlyingSpeed()*10*(player.isSprinting()?2.5:1);wanted=wanted.scale(speed);craftium$velocity=craftium$velocity.scale(o.flightMomentum).add(wanted.scale(1-o.flightMomentum));if(craftium$velocity.lengthSqr()<1e-8)craftium$velocity=Vec3.ZERO;
        player.setDeltaMovement(craftium$velocity);player.move(MoverType.SELF,craftium$velocity);player.resetFallDistance();ci.cancel();
    }
}
