package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.net.Packets;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** The hello between this client and the server it joined: what they say, what an older or newer peer does, and that a refused client recovers. */
public final class HandshakeGameTest {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    /** The server greeted this client with a hello it can use. Also run against the Paper companion. */
    public static void check(ClientGameTestContext c){
        c.waitTicks(10);
        require(c.computeOnClient(mc->ClientHandshake.server()!=null),"The server's hello never arrived");
        require(c.computeOnClient(mc->ClientHandshake.server().protocol()==Handshake.PROTOCOL),"The server speaks another protocol");
        require(c.computeOnClient(mc->ClientHandshake.server().version()!=null&&!ClientHandshake.server().version().isBlank()),"The hello has no version");
        require(c.computeOnClient(mc->ClientHandshake.serverHas(Handshake.MODIFY_EXPANSION)),"The server does not list modify-expansion");
        require(c.computeOnClient(mc->ClientHandshake.problem()==null),"A matching server was reported as a problem");
        require(c.computeOnClient(mc->ModifyMath.expansionSupported),"Expansion is switched off against a server that has it");
    }
    private static void clientHello(ClientGameTestContext c,String json){c.runOnClient(mc->ClientHandshake.receive(json,false));}
    public static void run(ClientGameTestContext c,TestSingleplayerContext w){
        check(c);
        String real=Handshake.write(ClientHandshake.version());
        // An older server: no capabilities. The client still works, without what it cannot count on.
        clientHello(c,"{\"protocol\":1,\"version\":\"0.9.0-beta.1\"}");
        require(c.computeOnClient(mc->ClientHandshake.problem()==null),"An older server with the same protocol was refused");
        require(c.computeOnClient(mc->!ClientHandshake.serverHas(Handshake.MODIFY_EXPANSION)&&!ModifyMath.expansionSupported),"The client counted on Expansion from a server without it");
        require(c.computeOnClient(mc->ClientHandshake.missing().contains(Handshake.MODIFY_EXPANSION)),"The status does not name what the server lacks");
        // The request carries a neutral scale to such a server, and the player's own value is back afterwards.
        c.runOnClient(mc->{ClientState.options.modifyTranslateScale=3;CraftiumClient.send("status",null,null);});
        w.getConnection().waitForServerboundPackets();
        require(c.computeOnClient(mc->ClientState.options.modifyTranslateScale==3),"Sending lost the player's Expansion value");
        // A newer server protocol: named, and no edit leaves the client.
        clientHello(c,"{\"protocol\":"+(Handshake.PROTOCOL+1)+",\"version\":\"9.9.9\"}");
        require(c.computeOnClient(mc->ClientHandshake.problem()!=null&&ClientHandshake.problem().contains("Update the client")),"A newer server was not named");
        c.runOnClient(mc->CraftiumClient.send("status",null,null));
        require(c.computeOnClient(mc->ClientState.notice.contains("Update the client")),"The player was not told why nothing happened");
        // The server refuses a client with another protocol until it says hello again, properly.
        c.runOnClient(mc->ClientPlayNetworking.send(new Packets.Hello("{\"protocol\":"+(Handshake.PROTOCOL+1)+",\"version\":\"9.9.9\"}")));
        w.getConnection().waitForServerboundPackets();c.waitTicks(5);
        clientHello(c,real); // back to a good server hello for the client side, which also answers the server with this client's real one
        w.getConnection().waitForServerboundPackets();c.waitTicks(5);
        require(c.computeOnClient(mc->ClientHandshake.problem()==null),"The client did not recover");
        c.runOnClient(mc->CraftiumClient.send("status",null,null));
        w.getConnection().waitForServerboundPackets();c.waitTicks(10);
        require(c.computeOnClient(mc->!ClientState.status.error()),"The server still refuses a client that said hello again");
        check(c);
    }
}
