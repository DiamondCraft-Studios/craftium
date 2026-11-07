package dev.craftium.core;

import com.google.gson.Gson;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a client and a server tell each other when they meet, so a mismatch is named at once instead of showing up later as an edit that
 * does nothing. {@link #PROTOCOL} is the number of the request and status format: it goes up only when one side could no longer read
 * the other, so a hotfix on either side keeps working with its neighbours and only a real break asks for an update.
 */
public final class Handshake {
    private Handshake() {}
    /** Bump when the request or status JSON changes in a way an older peer misreads. */
    public static final int PROTOCOL=1;
    private static final Gson GSON=new Gson();
    /** {@code version} is the mod or plugin version, such as 0.9.1-beta.1. */
    public record Hello(int protocol,String version) {}
    public static String write(String version){return GSON.toJson(new Hello(PROTOCOL,version));}
    /** The peer's hello, or null when the text is not one (an older build sent a plain sentence). */
    public static Hello read(String text){
        try{var hello=GSON.fromJson(text,Hello.class);return hello==null||hello.version()==null?null:hello;}
        catch(RuntimeException e){return null;}
    }
    /** What is wrong with this peer for the player to read, or null when the two work together. Older builds that send no hello are let through. */
    public static String problem(Hello peer,boolean peerIsServer){
        if(peer==null||peer.protocol()==PROTOCOL)return null;
        boolean peerNewer=peer.protocol()>PROTOCOL;
        String who=peerIsServer?"server":"client",me=peerIsServer?"client":"server";
        return "This "+me+" and the "+who+" (Craftium "+peer.version()+") speak different versions. Update "+(peerNewer?"the "+me:"the "+who)+" to match.";
    }
    /** Players whose client the server turned away, by what {@link #problem} said. */
    private static final java.util.Map<UUID,String> REFUSED=new ConcurrentHashMap<>();
    public static void refuse(UUID player,String reason){REFUSED.put(player,reason);}
    /** Also used when a player leaves, so a reconnect with an updated client starts clean. */
    public static void accept(UUID player){REFUSED.remove(player);}
    public static String refused(UUID player){return REFUSED.get(player);}
}
