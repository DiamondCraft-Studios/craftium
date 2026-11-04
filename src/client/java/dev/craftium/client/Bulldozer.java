package dev.craftium.client;

import net.minecraft.client.Minecraft;

/**
 * Bulldozer works through the game's own creative breaking, which already repeats while the button is held. After a
 * short warm-up it only takes away the wait between two blocks, so sounds, particles, the arm swing, server checks and
 * any other mod's hooks all happen as they do for a normal break.
 */
public final class Bulldozer {
    private static final long WARMUP_MS=350,GAP_MS=150;
    private static long since,last;
    private Bulldozer(){}
    /** Whether the game should skip its wait before breaking the next block. Asked once per tick of a held break. */
    public static boolean skipDelay(){
        var mc=Minecraft.getInstance();long now=System.currentTimeMillis();
        boolean on=ClientState.options.bulldozer&&mc.player!=null&&mc.player.isCreative()&&CraftiumClient.supported()&&!BuilderMode.active();
        if(!on||now-last>GAP_MS)since=now; // a new hold, or the first ask after the button was let go
        last=now;
        return on&&now-since>WARMUP_MS;
    }
}
