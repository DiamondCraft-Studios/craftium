package dev.craftium.runtime;

import dev.craftium.core.Point;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.*;
import org.slf4j.*;
import java.nio.file.Path;
import java.util.*;

/** Narrow host boundary shared by Fabric and the Paper/Purpur companion. */
public final class ServerPlatform {
    public static final Logger LOG=LoggerFactory.getLogger("Craftium");
    public interface Host {
        boolean canSend(ServerPlayer player);
        void send(ServerPlayer player,CustomPacketPayload payload);
        default boolean permitted(ServerPlayer player,String action){return dev.craftium.compat.Compat.isGamemaster(player);}
        default CommandSourceStack commands(ServerPlayer player){return player.createCommandSourceStack();}
        default boolean requiresProtection(){return false;}
        /** Biomes are quart coordinates; each cell affects 4 by 4 by 4 blocks. */
        default void checkEdit(ServerPlayer player,ServerLevel level,List<Point> blocks,List<Point> biomes,List<UUID> entities){}
        default boolean canChange(UUID player,ServerLevel level,Point at){return true;}
    }
    private static Path assets=Path.of("config","craftium");
    private static Host host;
    private ServerPlatform(){}
    public static void install(Path assetDirectory,Host adapter){assets=assetDirectory.toAbsolutePath().normalize();host=Objects.requireNonNull(adapter);}
    public static Path assets(){return assets;}
    public static boolean canSend(ServerPlayer player){return host!=null&&host.canSend(player);}
    public static void send(ServerPlayer player,CustomPacketPayload payload){if(host!=null)host.send(player,payload);}
    public static boolean permitted(ServerPlayer player,String action){return host!=null&&host.permitted(player,action);}
    public static CommandSourceStack commands(ServerPlayer player){return host==null?player.createCommandSourceStack():host.commands(player);}
    public static boolean requiresProtection(){return host!=null&&host.requiresProtection();}
    public static void checkEdit(ServerPlayer player,ServerLevel level,List<Point> blocks,List<Point> biomes,List<UUID> entities){if(host!=null)host.checkEdit(player,level,blocks,biomes,entities);}
    public static boolean canChange(UUID player,ServerLevel level,Point at){return host==null||host.canChange(player,level,at);}
}
