package dev.craftium.server;

import dev.craftium.block.MissingBlock;
import dev.craftium.block.MissingBlockEntity;
import dev.craftium.compat.Compat;
import dev.craftium.core.MissingBlockTable;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/** The placeholder block, and the table of missing blocks of the world that is running. */
public final class MissingBlocks {
    private MissingBlocks(){}
    public static Block BLOCK;
    public static BlockEntityType<MissingBlockEntity> TYPE;
    private static volatile MissingBlockTable table;

    public static void register(){
        var id=Compat.id("craftium","missing_block");
        var key=ResourceKey.create(Registries.BLOCK,id);
        BLOCK=Registry.register(BuiltInRegistries.BLOCK,id,new MissingBlock(BlockBehaviour.Properties.of().setId(key).strength(1.0F)));
        TYPE=Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,id,Compat.blockEntityType(MissingBlockEntity::new,BLOCK));
    }

    /** Loads the table of the world the server opened. Chunks load right after, so this comes first. */
    public static void bind(MinecraftServer server){table=new MissingBlockTable(server.getWorldPath(LevelResource.ROOT).resolve("craftium/missing-blocks.txt"));}
    public static void unbind(){table=null;}
    /** Null until a server has started, and after it stopped. */
    public static MissingBlockTable table(){return table;}

    public static boolean isMissing(BlockState state){return state.is(BLOCK);}
    /** Whether any installed mod provides a block of this name. */
    public static boolean exists(String name){var id=Compat.tryParse(name);return id!=null&&BuiltInRegistries.BLOCK.containsKey(id);}
}
