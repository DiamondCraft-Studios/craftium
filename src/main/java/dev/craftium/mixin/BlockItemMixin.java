package dev.craftium.mixin;

import dev.craftium.runtime.PlayerCapabilities;
import dev.craftium.server.EditorServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Builder capabilities for vanilla block placement: force place, no updates, and the editor's history and symmetry. */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {
    /** Where the block in flight is being placed, and what was there; the server thread places one block at a time. */
    @Unique
    private static final ThreadLocal<Object[]> craftium$placing = new ThreadLocal<>();

    @Redirect(
            method = "canPlace",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/state/BlockState;canSurvive(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean craftium$survive(
            BlockState state, LevelReader level, BlockPos pos, BlockPlaceContext context, BlockState placing) {
        var options = PlayerCapabilities.of(context.getPlayer());
        return options != null && options.forcePlace || state.canSurvive(level, pos);
    }

    @ModifyConstant(method = "placeBlock", constant = @Constant(intValue = 11))
    private int craftium$flags(int flags, BlockPlaceContext context, BlockState state) {
        var options = PlayerCapabilities.of(context.getPlayer());
        return options != null && options.noUpdates ? 2 | 16 : flags;
    }

    /** Replace material: where the clicked variant is being replaced, what is placed is the same variant of the held block's material, with the old one's states. */
    @Inject(method = "getPlacementState", at = @At("RETURN"), cancellable = true)
    private void craftium$material(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        var options = PlayerCapabilities.of(context.getPlayer());
        if (options == null || !options.replaceMaterial || !context.replacingClickedOnBlock()) return;
        var next = dev.craftium.server.ReplaceMaterial.resolve(
                context.getLevel().getBlockState(context.getClickedPos()), ((BlockItem) (Object) this).getBlock());
        if (next != null) cir.setReturnValue(next);
    }

    @Inject(method = "placeBlock", at = @At("HEAD"))
    private void craftium$before(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        craftium$placing.remove();
        if (context.getPlayer() instanceof ServerPlayer player) {
            var before = EditorServer.before(player, context.getClickedPos());
            if (before != null) craftium$placing.set(new Object[] {player, context.getClickedPos(), before});
        }
    }

    /** Replace material only changes materials: a click that has no variant to turn places nothing, where Replace mode would place the block. With both on, Replace mode places as it does. */
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void craftium$onlyMaterial(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        var options = PlayerCapabilities.of(context.getPlayer());
        if (options == null || !options.replaceMaterial || options.replaceMode) return;
        if (dev.craftium.server.ReplaceMaterial.forContext(context) != null) return;
        dev.craftium.runtime.ForcePlacement.set(false);
        cir.setReturnValue(InteractionResult.FAIL);
    }

    @Inject(method = "place", at = @At("HEAD"))
    private void craftium$forceBegin(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        var options = PlayerCapabilities.of(context.getPlayer());
        dev.craftium.runtime.ForcePlacement.set(options != null && options.forcePlace);
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void craftium$after(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        dev.craftium.runtime.ForcePlacement.set(false);
        var placing = craftium$placing.get();
        craftium$placing.remove();
        if (placing != null && cir.getReturnValue().consumesAction())
            EditorServer.placed((ServerPlayer) placing[0], (BlockPos) placing[1], placing[2]);
    }
}
