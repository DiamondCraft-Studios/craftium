package dev.craftium.core;

import com.google.gson.Gson;
import java.util.Set;
import java.util.TreeSet;
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
    /**
     * Each name is one optional feature a build understands, so the other side can use it or fall back. A name means one thing for good:
     * when the behaviour changes, add a new name and keep the old one.
     */
    public static final String MODIFY_EXPANSION="modify-expansion";
    /** AI build specs with materials, mixes, roof, stairs and group, and the preview summary sent before the status. */
    public static final String AI_SPEC_V2="ai-spec-v2";
    /** The server does not put a flying player back for moving fast, so a client may fly or turn the arcball at any speed. Only the Fabric server has it. */
    public static final String FAST_MOVEMENT="fast-movement";
    /** What this build supports. */
    public static final Set<String> CAPABILITIES=Set.of(MODIFY_EXPANSION,AI_SPEC_V2);
    /** What the Fabric server adds to that: things only a server with mixins can do. */
    public static final Set<String> FABRIC_SERVER_CAPABILITIES=Set.of(MODIFY_EXPANSION,AI_SPEC_V2,FAST_MOVEMENT);
    /** {@code version} is the mod or plugin version, such as 0.9.1-beta.1. {@code capabilities} is null from a build that predates the list, which means none. */
    public record Hello(int protocol,String version,Set<String> capabilities) {
        public boolean has(String capability){return capabilities!=null&&capabilities.contains(capability);}
        /** What this build supports that the peer does not list, in a stable order. */
        public Set<String> missingFrom(Set<String> mine){var out=new TreeSet<>(mine);if(capabilities!=null)out.removeAll(capabilities);return out;}
    }
    public static String write(String version){return write(version,CAPABILITIES);}
    public static String write(String version,Set<String> capabilities){return GSON.toJson(new Hello(PROTOCOL,version,new TreeSet<>(capabilities)));}
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
    /** What each connected client said it supports, for features the server can do two ways. */
    private static final java.util.Map<UUID,Hello> CLIENTS=new ConcurrentHashMap<>();
    public static void refuse(UUID player,String reason){REFUSED.put(player,reason);}
    /** A client whose hello is fine: remembered with what it supports. */
    public static void accept(UUID player,Hello hello){REFUSED.remove(player);if(hello==null)CLIENTS.remove(player);else CLIENTS.put(player,hello);}
    /** Also used when a player leaves, so a reconnect with an updated client starts clean. */
    public static void forget(UUID player){REFUSED.remove(player);CLIENTS.remove(player);}
    /** Whether this player's client listed the capability. A client that sent no hello lists none. */
    public static boolean clientHas(UUID player,String capability){var hello=CLIENTS.get(player);return hello!=null&&hello.has(capability);}
    public static String refused(UUID player){return REFUSED.get(player);}
}
