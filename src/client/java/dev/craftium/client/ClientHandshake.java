package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.net.Packets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import java.util.Optional;

/** The client's side of the handshake with the server, and the look at the project's releases for a newer build. */
final class ClientHandshake {
    private ClientHandshake() {}
    private static volatile Handshake.Hello server;
    private static volatile String problem;
    private static volatile Optional<UpdateChecker.Release> latest=Optional.empty();
    private static boolean checked,announced;

    /** The full version of this build, such as 0.9.1-beta.1+mc26.2. */
    static String version(){return FabricLoader.getInstance().getModContainer("craftium").map(m->m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");}
    /** The Minecraft version this build is for, read from the +mc suffix of its version. */
    static String minecraft(){String v=version();int at=v.indexOf("+mc");return at<0?"":v.substring(at+3);}
    /** What is wrong with the server this client is on, or null. */
    static String problem(){return problem;}
    static Handshake.Hello server(){return server;}
    static Optional<UpdateChecker.Release> latest(){return latest;}

    /** The server's hello, from the Fabric packet or the Paper bridge. Names a mismatch and, once, answers with this client's own. */
    static void receive(String json,boolean bridge){
        server=Handshake.read(json);
        problem=Handshake.problem(server,true);
        ModifyMath.expansionSupported=serverHas(Handshake.MODIFY_EXPANSION);
        if(problem!=null)ClientState.notify(problem);
        String hello=Handshake.write(version());
        if(bridge){for(byte[] frame:BridgeCodec.encode(Packets.JSON.toJson(new BridgeCodec.Message("hello",hello))))ClientPlayNetworking.send(new Packets.Bridge(frame));}
        else if(ClientPlayNetworking.canSend(Packets.Hello.TYPE))ClientPlayNetworking.send(new Packets.Hello(hello));
    }
    static void disconnected(){server=null;problem=null;ModifyMath.expansionSupported=true;}
    /** Whether the player may move at any speed: the server skips its movement check, or there is no other server than this game's own. Otherwise a long step in one tick throws the player back. */
    static boolean fastMovement(){return net.minecraft.client.Minecraft.getInstance().hasSingleplayerServer()||serverHas(Handshake.FAST_MOVEMENT);}
    /** The longest step, in blocks per tick, that a server which checks movement lets through with room to spare; its limit is about ten. */
    static final double SAFE_STEP=9;
    /** Whether the server listed the capability. A server with no hello (an older build) lists none. */
    static boolean serverHas(String capability){var hello=server;return hello!=null&&hello.has(capability);}
    /** What this client can do that the server cannot, for the player to read, or empty when the server has it all. */
    static String missing(){var hello=server;if(hello==null)return "";var gone=hello.missingFrom(Handshake.CAPABILITIES);return gone.isEmpty()?"":"the server lacks: "+String.join(", ",gone);}

    /** Looks for a newer release once per game session, unless {@code force}. Never blocks the game; any failure is silence. */
    static void checkForUpdates(boolean force){
        if(!force&&(checked||!ClientState.options.checkForUpdates))return;
        if(minecraft().isEmpty())return; // a development build has no release to compare with
        checked=true;
        UpdateChecker.check(version(),minecraft(),FabricLoader.getInstance().getConfigDir().resolve("craftium").resolve("update-check.json")).thenAccept(found->{latest=found;announced=false;});
    }
    /** Tells the player, once, about a newer release found by the check. Called from the client tick. */
    static void announce(){
        var found=latest;
        if(announced||found.isEmpty())return;
        announced=true;
        String line="Craftium "+found.get().version()+" is out (you have "+UpdateChecker.clean(version())+"). /craftium update";
        ClientState.notify(line);
        // the HUD line is gone in a few seconds; the chat keeps it
        dev.craftium.client.compat.ClientCompat.chat(net.minecraft.client.Minecraft.getInstance(),dev.craftium.core.Chat.say("Craftium "+found.get().version()+" is out.\nYou have "+UpdateChecker.clean(version())+".\nRun /craftium update for the download link."));
    }
    static String describe(){
        var found=latest;
        String me=UpdateChecker.clean(version());
        if(!checked&&!ClientState.options.checkForUpdates)return "Craftium "+me+".\nUpdate checks are off. Run /craftium update on to turn them on.";
        return found.map(r->"Craftium "+r.version()+" is out. You have "+me+".\nDownload it from "+r.url()+"\nThen replace the jar in your mods folder.").orElse("Craftium "+me+" is the newest release for Minecraft "+minecraft()+".\nIf this looks wrong, the check could not reach GitHub.");
    }
}
