package dev.craftium.compat;

import com.mojang.math.Transformation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import java.util.Set;

/**
 * Minecraft-version boundary. Shared code calls these methods instead of the APIs that changed
 * between releases; each {@code versions/<mc>} module replaces this file with its own implementation.
 * This is the 26.2 implementation.
 */
public final class Compat {
    private Compat(){}

    public static Identifier id(String namespace,String path){return Identifier.fromNamespaceAndPath(namespace,path);}
    public static Identifier parse(String id){return Identifier.parse(id);}
    /** Like {@link #parse} but returns null for text that is not a valid identifier. */
    public static Identifier tryParse(String id){return Identifier.tryParse(id);}
    public static Identifier vanilla(String path){return Identifier.withDefaultNamespace(path);}
    /** Namespaced name of a registry or dimension key, e.g. {@code minecraft:overworld}. */
    public static String keyName(ResourceKey<?> key){return key.identifier().toString();}

    public static boolean isGamemaster(ServerPlayer player){return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);}
    public static boolean isGamemaster(CommandSourceStack source){return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);}

    /** Rebuilds an entity, with passengers, from its saved data. Returns null when the data is invalid. */
    public static Entity loadEntity(CompoundTag tag,Level level){
        return EntityType.loadEntityRecursive(tag,level,new net.minecraft.world.entity.EntitySpawnRequest(EntitySpawnReason.COMMAND,true),net.minecraft.world.entity.EntityProcessor.NOP);
    }
    public static Set<String> entityTags(Entity entity){return entity.entityTags();}
    public static long chunkKey(int x,int z){return net.minecraft.world.level.ChunkPos.pack(x,z);}
    public static Transformation identityTransform(){return Transformation.IDENTITY;}
}
