package dev.craftium.runtime;

import dev.craftium.core.Options;
import dev.craftium.server.EditorServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import java.util.function.Function;

/**
 * The builder capabilities in force for a player, read by the mixins that bend vanilla block placement.
 * Both sides ask the same question so the client's prediction of a placement (its sound and arm swing) agrees with the server's result.
 */
public final class PlayerCapabilities {
    /** Answers for the local player on the client; set by the client entry point. */
    public static volatile Function<Player,Options> client=player->null;
    private PlayerCapabilities(){}
    /** The player's options when they are building with capabilities, else null. */
    public static Options of(Player player){
        if(player==null)return null;
        if(player instanceof ServerPlayer server)return EditorServer.capabilities(server);
        return client.apply(player);
    }
}
