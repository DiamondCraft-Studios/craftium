package dev.craftium.mixin;

import dev.craftium.Craftium;
import dev.craftium.server.MissingBlockRewrite;
import dev.craftium.server.MissingBlocks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Before the game reads a chunk, the blocks no mod provides become placeholders; the game would otherwise turn them into air and forget them. */
@Mixin(SerializableChunkData.class)
abstract class MissingBlockChunkMixin {
    @Inject(method="parse",at=@At("HEAD"))
    private static void craftium$keepMissingBlocks(LevelHeightAccessor accessor,PalettedContainerFactory factory,CompoundTag tag,CallbackInfoReturnable<SerializableChunkData> cir){
        var table=MissingBlocks.table();
        if(table==null||MissingBlocks.BLOCK==null)return;
        try{MissingBlockRewrite.apply(tag,table,MissingBlocks::exists);}
        // A chunk that cannot be rewritten loads the way it always did, with its missing blocks as air.
        catch(RuntimeException e){Craftium.LOG.warn("Could not keep the missing blocks of a chunk",e);}
    }
}
