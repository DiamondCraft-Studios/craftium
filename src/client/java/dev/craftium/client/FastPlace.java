package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.Map;

/**
 * Fast place: while the use key is held, a block is placed the moment the crosshair rests on a block it has not just
 * been on, with no cooldown at all. The check runs on every frame, so sweeping the view places as fast as it moves.
 * Holding still places nothing more, and a block is not placed twice against the same target.
 *
 * Adapted from the approach of Accurate Block Placement: Reborn (MIT, see THIRD-PARTY-NOTICES.md).
 */
public final class FastPlace {
    private FastPlace(){}
    private static BlockPos lastSeen,lastPlaced;
    private static Vec3 lastPlayer;
    private static boolean wasDown,grace,fresh;

    /** Tracks the use key: a new press forgets the earlier targets, and lets the game's own first click through. */
    private static void observe(Minecraft mc){
        boolean down=mc.options.keyUse.isDown();
        if(down&&!wasDown){grace=true;fresh=true;}
        if(!down){grace=false;fresh=false;lastSeen=null;lastPlaced=null;lastPlayer=null;}
        wasDown=down;
    }
    private static boolean active(Minecraft mc){
        var o=ClientState.options;
        if(!o.fastPlace||mc.player==null||mc.level==null||mc.gameMode==null||!mc.player.isCreative()||!CraftiumClient.vanillaPlacement()||ClientCompat.screen(mc)!=null||BuilderMode.active())return false;
        if(!mc.options.keyUse.isDown()||mc.player.isUsingItem()||!(mc.player.getMainHandItem().getItem() instanceof BlockItem))return false;
        if(!(mc.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return false;
        // A block that does something when clicked keeps doing it, unless sneaking, as in the game.
        return mc.player.isShiftKeyDown()||!interactive(mc.level.getBlockState(hit.getBlockPos()).getBlock());
    }
    /** Called from the game's own item use. True to skip its slow repeat, which this replaces; the first click of a press is left to the game. */
    public static boolean cancelVanilla(){
        var mc=Minecraft.getInstance();observe(mc);
        if(!active(mc))return false;
        if(grace){grace=false;return false;}
        return true;
    }
    /** Called after every pick of the crosshair target. */
    public static void afterPick(Minecraft mc){
        observe(mc);
        if(!active(mc))return;
        var hit=(BlockHitResult)mc.hitResult;var pos=hit.getBlockPos();var player=mc.player;
        var context=new BlockPlaceContext(new UseOnContext(player,InteractionHand.MAIN_HAND,hit));
        if(fresh){ // the press itself: the game places this one
            fresh=false;lastSeen=pos;lastPlaced=context.getClickedPos();lastPlayer=player.position();return;
        }
        boolean newTarget=!pos.equals(lastSeen)&&!pos.equals(lastPlaced);
        if(newTarget||backedAway(player,hit,pos)){
            var before=mc.level.getBlockState(context.getClickedPos());
            var result=mc.gameMode.useItemOn(player,InteractionHand.MAIN_HAND,hit);
            if(result.consumesAction())player.swing(InteractionHand.MAIN_HAND);
            if(mc.level.getBlockState(context.getClickedPos())!=before){lastPlaced=context.getClickedPos();lastPlayer=player.position();}
        }
        lastSeen=pos;
    }
    /** Building toward yourself: the last block placed is the target again, and the player has stepped a block away from it since. */
    private static boolean backedAway(Player player,BlockHitResult hit,BlockPos pos){
        if(lastPlaced==null||lastPlayer==null||!pos.equals(lastPlaced))return false;
        var axis=hit.getDirection().getAxis();
        double now=player.position().get(axis),then=lastPlayer.get(axis);
        double block=lastPlaced.get(axis)+(hit.getDirection().getAxisDirection()==net.minecraft.core.Direction.AxisDirection.NEGATIVE?1:0);
        return Math.abs(now-then)>=.99&&Math.abs(then-block)<Math.abs(now-block);
    }

    private static final Map<Class<?>,Boolean> INTERACTIVE=new HashMap<>();
    /** Whether the block reacts to a click, found by the shape of the methods it overrides, which holds under any mapping. */
    private static boolean interactive(Block block){
        return INTERACTIVE.computeIfAbsent(block.getClass(),type->{
            for(Class<?> c=type;c!=null&&c!=Block.class&&c!=BlockBehaviour.class&&c!=Object.class;c=c.getSuperclass())
                for(var m:c.getDeclaredMethods()){
                    var t=m.getParameterTypes();
                    if(t.length==5&&t[0]==BlockState.class&&t[1]==Level.class&&t[2]==BlockPos.class&&t[3]==Player.class&&t[4]==BlockHitResult.class)return true;
                    if(t.length==7&&t[0]==ItemStack.class&&t[1]==BlockState.class&&t[2]==Level.class&&t[3]==BlockPos.class&&t[4]==Player.class&&t[5]==InteractionHand.class&&t[6]==BlockHitResult.class)return true;
                }
            return false;
        });
    }
}
