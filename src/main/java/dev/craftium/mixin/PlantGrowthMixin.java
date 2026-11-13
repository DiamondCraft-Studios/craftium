package dev.craftium.mixin;
import dev.craftium.server.WorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.block.*;
/** Crops, saplings, cactus, cane, bamboo, kelp and the vines, stems, nether wart, berries, cocoa, chorus and mushrooms all grow and spread in randomTick. */
@Mixin({CropBlock.class,SaplingBlock.class,CactusBlock.class,SugarCaneBlock.class,BambooStalkBlock.class,BambooSaplingBlock.class,GrowingPlantHeadBlock.class,StemBlock.class,NetherWartBlock.class,SweetBerryBushBlock.class,CocoaBlock.class,ChorusFlowerBlock.class,MushroomBlock.class})
abstract class PlantGrowthMixin {
    @Inject(method="randomTick",at=@At("HEAD"),cancellable=true)
    private void craftium$rule(BlockState state,ServerLevel level,BlockPos pos,RandomSource random,CallbackInfo ci){if(!WorldRules.get(level).plantGrowth)ci.cancel();}
}
