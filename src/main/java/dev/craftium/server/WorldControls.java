package dev.craftium.server;

import dev.craftium.core.Options;
import dev.craftium.net.Packets;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.Locale;

final class WorldControls {
    private record View(String dimension,double x,double y,double z,float yaw,float pitch){}
    private WorldControls(){}
    static String apply(String action,ServerPlayer player,Options o){
        if(java.util.Set.of("world_block_gravity","world_trample_farmland","world_block_drops","world_player_invulnerability").contains(action))return WorldRules.set(player.level(),action,o.worldToggle);
        var server=player.level().getServer();String command=switch(action){
            case "world_time"->"time set "+o.worldTime;
            case "world_clear"->"weather clear";case "world_rain"->"weather rain";case "world_thunder"->"weather thunder";
            case "world_random_ticks"->"gamerule minecraft:random_tick_speed "+o.randomTickSpeed;
            case "world_advance_time"->"gamerule minecraft:advance_time "+o.worldToggle;
            case "world_advance_weather"->"gamerule minecraft:advance_weather "+o.worldToggle;
            case "world_spawn_mobs"->"gamerule minecraft:spawn_mobs "+o.worldToggle;
            case "world_keep_inventory"->"gamerule minecraft:keep_inventory "+o.worldToggle;
            case "world_fire_tick"->"gamerule minecraft:fire_spread_radius_around_player "+(o.worldToggle?128:0);
            default->null;
        };
        if(command!=null){server.getCommands().performPrefixedCommand(dev.craftium.runtime.ServerPlatform.commands(player),command);return "Applied "+action.replace('_',' ');}
        if(!o.name.matches("[A-Za-z0-9 _.-]{1,80}"))throw new IllegalArgumentException("Use a plain view name");
        Path path=server.getWorldPath(LevelResource.ROOT).resolve("craftium/views").resolve(player.getUUID()+"-"+o.name+".json");
        try{
            if(action.equals("view_save")){var view=new View(dev.craftium.compat.Compat.keyName(player.level().dimension()),player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot());Files.createDirectories(path.getParent());Files.writeString(path,Packets.JSON.toJson(view));return "Saved view "+o.name;}
            if(action.equals("view_load")){var view=Packets.JSON.fromJson(Files.readString(path),View.class);server.getCommands().performPrefixedCommand(dev.craftium.runtime.ServerPlatform.commands(player),String.format(Locale.ROOT,"execute in %s run tp @s %.6f %.6f %.6f %.6f %.6f",view.dimension,view.x,view.y,view.z,view.yaw,view.pitch));return "Opened view "+o.name;}
            throw new IllegalArgumentException("Unknown world control");
        }catch(java.io.IOException e){throw new IllegalArgumentException("Cannot access view: "+o.name,e);}
    }
}
