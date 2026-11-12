package dev.craftium.server;

import dev.craftium.net.Packets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;

/** Operator-controlled world rules, saved with the world rather than in global configuration. */
public final class WorldRules {
    public static final class Rules {public boolean blockGravity=true,trampleFarmland=true,blockDrops=true,destructiveExplosions=true,playerInvulnerability;}
    private static final Map<MinecraftServer,Rules> cache=new WeakHashMap<>();
    private WorldRules(){}
    private static Path file(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("craftium/world-rules.json");}
    public static Rules get(ServerLevel level){return cache.computeIfAbsent(level.getServer(),server->{try{if(Files.exists(file(server)))return Packets.JSON.fromJson(Files.readString(file(server)),Rules.class);}catch(Exception e){dev.craftium.runtime.ServerPlatform.LOG.warn("Cannot load Craftium world rules",e);}return new Rules();});}
    static String set(ServerLevel level,String name,boolean value){
        var rules=get(level);switch(name){case "world_block_gravity"->rules.blockGravity=value;case "world_trample_farmland"->rules.trampleFarmland=value;case "world_block_drops"->rules.blockDrops=value;case "world_destructive_explosions"->rules.destructiveExplosions=value;case "world_player_invulnerability"->rules.playerInvulnerability=value;default->throw new IllegalArgumentException("Unknown Craftium world rule");}
        try{Path path=file(level.getServer());Files.createDirectories(path.getParent());Files.writeString(path,Packets.JSON.toJson(rules));}catch(Exception e){throw new IllegalArgumentException("Could not save world rules",e);}String label=name.substring("world_".length()).replace('_',' ');return Character.toUpperCase(label.charAt(0))+label.substring(1)+": "+(value?"on":"off");
    }
}
