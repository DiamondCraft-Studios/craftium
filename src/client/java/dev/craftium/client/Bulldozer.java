package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.Options;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Bulldozer: after the break button has been held a moment, every new block the crosshair comes to rest on is broken at
 * once, on every frame, with no wait between blocks. Each one is broken by the game's own creative break, so sounds,
 * particles, server checks and other mods' hooks all happen as for a normal click. Holding still breaks nothing more.
 */
public final class Bulldozer {
    private static final long WARMUP_MS=350;
    private static long since;
    private static BlockPos lastSeen;
    private Bulldozer(){}
    /** Called after every pick of the crosshair target. */
    public static void afterPick(Minecraft mc){
        if(!mc.options.keyAttack.isDown()){since=0;lastSeen=null;return;}
        long now=System.currentTimeMillis();if(since==0)since=now;
        var o=ClientState.options;
        // Symmetry and infinite reach break through the editor instead, which does its own repeating.
        if(!o.bulldozer||mc.player==null||mc.level==null||mc.gameMode==null||!mc.player.isCreative()||!CraftiumClient.supported()||ClientCompat.screen(mc)!=null||BuilderMode.active()||ClientState.symmetry()||o.reach==Options.ReachMode.INFINITE)return;
        if(!(mc.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK){lastSeen=null;return;}
        var pos=hit.getBlockPos();
        if(now-since>WARMUP_MS&&!pos.equals(lastSeen)&&mc.gameMode.startDestroyBlock(pos,hit.getDirection()))mc.player.swing(InteractionHand.MAIN_HAND);
        lastSeen=pos;
    }
}
