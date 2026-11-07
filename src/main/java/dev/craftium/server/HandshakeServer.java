package dev.craftium.server;

import dev.craftium.core.Handshake;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** The server's side of the handshake: a client whose protocol it cannot read is told so and its edits are refused until it reconnects updated. */
public final class HandshakeServer {
    private HandshakeServer() {}
    public static void received(ServerPlayer player,String json){
        var hello=Handshake.read(json);
        String problem=Handshake.problem(hello,false);
        if(problem==null){Handshake.accept(player.getUUID());return;}
        Handshake.refuse(player.getUUID(),problem);
        player.sendSystemMessage(Component.literal("Craftium: "+problem));
        org.slf4j.LoggerFactory.getLogger("Craftium").warn("{} has Craftium {} (protocol {}), the server speaks protocol {}",player.getUUID(),hello.version(),hello.protocol(),Handshake.PROTOCOL);
    }
}
