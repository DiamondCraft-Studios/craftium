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
    public static void bind(MinecraftServer server){
        var opened=new MissingBlockTable(server.getWorldPath(LevelResource.ROOT).resolve("craftium/missing-blocks.txt"));
        // A state is added while a chunk loads, off the game thread: players are told on it.
        opened.onAdded(key->server.execute(()->{for(var player:server.getPlayerList().getPlayers())send(player);}));
        table=opened;
    }
    public static void unbind(){table=null;}
    /** Null until a server has started, and after it stopped. */
    public static MissingBlockTable table(){return table;}

    /** The missing blocks of this world, as text, in the order of the table. */
    public static java.util.List<String> ids(){
        var current=table;
        var out=new java.util.ArrayList<String>();
        if(current!=null)for(var entry:current.entries())if(entry!=null)out.add(entry.text());
        return out;
    }
    /** Tells the player which blocks the world is missing, when they have Craftium to show it. Nothing is sent while none is missing. */
    public static void send(net.minecraft.server.level.ServerPlayer player){
        var ids=ids();
        if(ids.isEmpty()||!dev.craftium.runtime.ServerPlatform.canSend(player))return;
        dev.craftium.runtime.ServerPlatform.send(player,new dev.craftium.net.Packets.MissingBlocks(ids));
    }
    /**
     * The placeholders for {@code wanted} in the chunks loaded around the player, out as far as they can see. Only block entities are looked at, so this costs
     * the number of loaded chunks and not the number of blocks. Chunks that are not loaded are left out: they hold blocks this cannot see.
     */
    public static java.util.List<net.minecraft.core.BlockPos> loadedAround(net.minecraft.server.level.ServerPlayer player,MissingBlockTable.Entry wanted,int limit){
        var level=player.level();
        int radius=Math.max(2,player.level().getServer().getPlayerList().getViewDistance());
        int cx=player.blockPosition().getX()>>4,cz=player.blockPosition().getZ()>>4;
        var found=new java.util.ArrayList<net.minecraft.core.BlockPos>();
        for(int x=cx-radius;x<=cx+radius;x++)for(int z=cz-radius;z<=cz+radius;z++){
            var chunk=level.getChunkSource().getChunkNow(x,z);
            if(chunk==null)continue;
            for(var entity:chunk.getBlockEntities().values())
                if(entity instanceof MissingBlockEntity missing&&wanted.equals(missing.entry())){found.add(entity.getBlockPos());if(found.size()>=limit)return found;}
        }
        return found;
    }

    public static boolean isMissing(BlockState state){return state.is(BLOCK);}
    /** The placeholder at {@code pos}, or null when the block there is any other. The state is asked first so an ordinary block costs no block entity lookup. */
    public static MissingBlockEntity at(net.minecraft.world.level.BlockGetter level,net.minecraft.core.BlockPos pos,BlockState state){
        return state.is(BLOCK)&&level.getBlockEntity(pos) instanceof MissingBlockEntity missing?missing:null;
    }
    /** The number of the missing block at {@code pos}, or -1 when the block there is not a placeholder. */
    public static int keyAt(net.minecraft.world.level.BlockGetter level,net.minecraft.core.BlockPos pos,BlockState state){var missing=at(level,pos,state);return missing==null?-1:missing.key();}
    /** Whether any installed mod provides a block of this name. */
    public static boolean exists(String name){var id=Compat.tryParse(name);return id!=null&&BuiltInRegistries.BLOCK.containsKey(id);}
}
