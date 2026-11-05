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
        Vec3 wanted=wish.scale(top);
        double kept=keep*o.flightMomentumLevel;
        craftium$velocity=craftium$velocity.scale(kept).add(wanted.scale(1-kept));
        if(craftium$velocity.lengthSqr()<1e-8)craftium$velocity=Vec3.ZERO;
        player.setDeltaMovement(craftium$velocity);player.move(MoverType.SELF,craftium$velocity);player.resetFallDistance();ci.cancel();
    }
}
