package dev.craftium.net;

import com.google.gson.Gson;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.List;

public final class Packets {
    public static final Gson JSON = new Gson();
    private Packets() {}

    public record Request(String action, Point target, Point normal, Options options, long strokeId) {}
    public record Status(String message, boolean error, boolean busy, int selectionSize,
                         Point min, Point max, int clipboardSize, List<String> history, int undo, int redo,String entityUuid,String entityNbt,List<dev.craftium.core.Annotation> annotations,String selectionData,String clipboardData,List<dev.craftium.core.BlueprintInfo> blueprints,int clipboardEntityCount,String pendingData,String clipboardGhost,String pivotUpdate,
                         long undoAge,long redoAge,String undoName,String redoName,String pulse,
                         /** The AI's own workspace: its waiting preview (as ghost data), and how many builds it can undo, and the name of the last. */
                         String aiPendingData,int aiUndo,String aiUndoName,
                         /** Client-side state to put back after an undo or redo, as {@link dev.craftium.core.Restore} text; empty when there is none. */
                         String restore,
                         /** Where the clipboard's blocks were copied from, or null when they did not come from the world (a blueprint or structure). */
                         Point clipboardOrigin,
                         /**
                          * Other players' AI previews in this player's dimension, to be drawn beside the player's own. Null means the same as last time, and an empty
                          * list means there are none now. They are all there is in a {@code background} status.
                          */
                         List<SharedPreview> sharedAi,
                         /** A status that only brings {@code sharedAi}: something somebody else did, not a reply to anything this player asked, so the client does not take it as one. */
                         boolean background,
                         /**
                          * All of this player's AI sessions, oldest first: what each is doing and holding. Null means the same as last time (a {@code background}
                          * status has none), and an entry's {@code data} is null when its preview is the one the client was last sent.
                          */
                         List<AiSession> aiSessions,
                         /** The ghost a plugin's tool wants shown for the player, as ghost data like {@code pendingData}; empty when it wants none. */
                         String pluginPreviewData,
                         /**
                          * The tools plugins have added, in full: null means the same as last time, and an empty list means there are none now. A status that only brings
                          * the ghost of a tool has null here, and one that only brings the list has null for the ghost.
                          */
                         List<PluginTool> pluginTools,
                         /** The player's saved selections, in order: null means the same as last time, and an empty list means there are none now. */
                         List<SavedSelection> savedSelections,
                         /**
                          * The groups of the Hierarchy, in order, sent whenever the list of BuildObjects is: null means the same as last time, and a server that predates groups never sends them
                          * (the client then shows the list as it was, with no groups).
                          */
                         List<SavedGroup> savedGroups,
                         /** What the jigsaw planner has to say about its last run (problems in the pools, connectors left open), as text lines. Null means the same as last time; empty clears it. */
                         String jigsawReport,
                         /** The pool editor's view of the pools and the one being edited, as JSON text. Null means the same as last time. */
                         String jigsawModel) {
        public Status(String message,boolean error,boolean busy,int selectionSize,Point min,Point max,int clipboardSize,List<String> history,int undo,int redo,String entityUuid,String entityNbt,List<dev.craftium.core.Annotation> annotations,String selectionData,String clipboardData,List<dev.craftium.core.BlueprintInfo> blueprints,int clipboardEntityCount,String pendingData,String clipboardGhost,String pivotUpdate,long undoAge,long redoAge,String undoName,String redoName,String pulse,String aiPendingData,int aiUndo,String aiUndoName){this(message,error,busy,selectionSize,min,max,clipboardSize,history,undo,redo,entityUuid,entityNbt,annotations,selectionData,clipboardData,blueprints,clipboardEntityCount,pendingData,clipboardGhost,pivotUpdate,undoAge,redoAge,undoName,redoName,pulse,aiPendingData,aiUndo,aiUndoName,"",null,null,false,null,"",null,null,null,null,null);}
        /** {@code undoAge} and {@code redoAge} are how many milliseconds ago the next edit to undo or redo was made, or -1 when there is none. */
        public Status(String message,boolean error,boolean busy,int selectionSize,Point min,Point max,int clipboardSize,List<String> history,int undo,int redo){this(message,error,busy,selectionSize,min,max,clipboardSize,history,undo,redo,"","",List.of(),"","",List.of(),0,"","","",-1,-1,"","","","",0,"","",null,null,false,null,"",null,null,null,null,null);}
    }

    /**
     * One AI session of a player. {@code state} is "idle", "preview" (a preview is waiting), "applying", "applied" (it has builds to undo) or
     * "stopped". {@code age} and {@code idle} are how many milliseconds ago it began and last had a request, for the client to count on from.
     * {@code min} and {@code max} are the box of its preview or latest build, or null; {@code previewId} changes with each new preview.
     */
    public record AiSession(String id,String label,String state,int blocks,Point min,Point max,String dimension,int undo,String undoName,long age,long idle,
                            boolean stopped,String lastTool,long previewId,String data,
                            /** The selection this session is working with (see the server's Session#pinned): its box and size, or null and 0, and whether the player's own has changed since. */
                            Point pinMin,Point pinMax,int pinBlocks,boolean pinChanged) {}

    /** One player's waiting AI preview, as ghost data, and whose it is. */
    public record SharedPreview(String owner, String data) {}

    /**
     * A tool a plugin added, as a client is told of it. The kinds are text so that a client that does not know one (or an interaction) can leave the tool out
     * instead of misreading it. See {@code PluginToolCodec}.
     */
    public record PluginTool(String id, String label, String icon, String iconId, String section, String description, String useHint, String interaction, boolean confirms,
                             List<PluginProperty> properties) {}

    /** One setting of a plugin's tool: {@code kind} is NUMBER, INTEGER, TOGGLE or CHOICE, and {@code number} is where it starts (the index of the choice for a choice). */
    public record PluginProperty(String id, String label, String kind, double min, double max, double number, List<String> choices) {}

    /** One saved selection as the client sees it: the blocks stay on the server. {@code active} is the one swapped in for the live selection, {@code editing} whether changes are written back to it. */
    public record SavedSelection(int id, String name, int size, Point min, Point max, boolean active, boolean editing,
                                 /** The blocks as {@code SelectionCodec} text, so the client can draw it in the selection style: null means the client already has them, and empty means they were left out because the status was too big (see {@code StatusFit}). */
                                 String data,
                                 /** The BuildObject's GUID: null from a server that predates it. */
                                 String guid,
                                 /** The id of the BuildObject this one is under, or 0 at the top level (and from a server that predates hierarchy). The list is in tree order. */
                                 int parent,
                                 /** The colour everyone sees its outline in (ARGB), or 0 when none was chosen: the client then takes the next one from the wheel. */
                                 int color,
                                 /** The id of the group it is in (that of the BuildObject at the top that it is under), or 0 from a server that predates groups. */
                                 int group,
                                 /** Whether what is under it is folded away in the Hierarchy: kept by the server, and false from one that predates it. */
                                 boolean folded) {}
    /** One group of the Hierarchy: a heading that BuildObjects at the top level sit under. */
    public record SavedGroup(int id, String guid, String name, boolean folded) {}
    /** A status that carries only the list of plugin tools, sent when it changes: not a reply to anything, so the client takes the list and nothing else from it. */
    public static Status toolsOnly(List<PluginTool> tools) {
        return new Status("",false,false,0,null,null,0,List.of(),0,0,"","",List.of(),"","",List.of(),0,"","","",-1,-1,"","","","",0,"","",null,null,true,null,null,tools,null,null,null,null);
    }

    /** A status that carries only the ghost a plugin's tool is showing: not a reply to anything, so the client takes the ghost and nothing else from it. */
    public static Status pluginOnly(String ghost) {
        return new Status("",false,false,0,null,null,0,List.of(),0,0,"","",List.of(),"","",List.of(),0,"","","",-1,-1,"","","","",0,"","",null,null,true,null,ghost,null,null,null,null,null);
    }

    /** A status that carries only the list of BuildObjects, sent when someone else changed them: not a reply to anything, so the client takes the list and nothing else from it. */
    public static Status savedOnly(List<SavedSelection> saved,List<SavedGroup> groups) {
        return new Status("",false,false,0,null,null,0,List.of(),0,0,"","",List.of(),"","",List.of(),0,"","","",-1,-1,"","","","",0,"","",null,null,true,null,null,null,saved,groups,null,null);
    }

    /** A status that carries only the other players' AI previews, sent when one of them changes. */
    public static Status sharedOnly(List<SharedPreview> shared) {
        return new Status("",false,false,0,null,null,0,List.of(),0,0,"","",List.of(),"","",List.of(),0,"","","",-1,-1,"","","","",0,"","",null,shared,true,null,null,null,null,null,null,null);
    }

    public record Bridge(byte[] bytes) implements CustomPacketPayload {
        public static final Type<Bridge> TYPE=new Type<>(dev.craftium.compat.Compat.id("craftium","bridge"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Bridge> CODEC=StreamCodec.of((buf,p)->{if(p.bytes.length>dev.craftium.core.BridgeCodec.FRAME)throw new IllegalArgumentException("Bridge frame too large");buf.writeBytes(p.bytes);},buf->{if(buf.readableBytes()>dev.craftium.core.BridgeCodec.FRAME)throw new IllegalArgumentException("Bridge frame too large");byte[] bytes=new byte[buf.readableBytes()];buf.readBytes(bytes);return new Bridge(bytes);});
        @Override public Type<Bridge> type(){return TYPE;}
    }
    public record Edit(String json) implements CustomPacketPayload {
        public static final Type<Edit> TYPE = new Type<>(dev.craftium.compat.Compat.id("craftium","edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC = StreamCodec.of(
            (buf,p) -> buf.writeUtf(p.json, 2_000_000), buf -> new Edit(buf.readUtf(2_000_000)));
        @Override public Type<Edit> type() { return TYPE; }
    }
    public record Update(String json) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(dev.craftium.compat.Compat.id("craftium","status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = StreamCodec.of(
            (buf,p) -> buf.writeUtf(p.json, 2_000_000), buf -> new Update(buf.readUtf(2_000_000)));
        @Override public Type<Update> type() { return TYPE; }
    }
    /** What a client and a server tell each other as they meet: {@link dev.craftium.core.Handshake.Hello} as JSON. Sent both ways. */
    public record Hello(String json) implements CustomPacketPayload {
        public static final Type<Hello> TYPE=new Type<>(dev.craftium.compat.Compat.id("craftium","hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Hello> CODEC=StreamCodec.of((buf,p)->buf.writeUtf(p.json,512),buf->new Hello(buf.readUtf(512)));
        @Override public Type<Hello> type(){return TYPE;}
    }
    public record Report(String text) implements CustomPacketPayload {
        public static final Type<Report> TYPE=new Type<>(dev.craftium.compat.Compat.id("craftium","report"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Report> CODEC=StreamCodec.of((buf,p)->buf.writeUtf(p.text,100_000),buf->new Report(buf.readUtf(100_000)));
        @Override public Type<Report> type(){return TYPE;}
    }
    /** How far a big edit has got, sent about twice a second while it applies. A {@code total} of 0 says it has ended. */
    public record Progress(String verb,String name,int done,int total) implements CustomPacketPayload {
        public static final Type<Progress> TYPE=new Type<>(dev.craftium.compat.Compat.id("craftium","progress"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Progress> CODEC=StreamCodec.of(
            (buf,p)->{buf.writeUtf(p.verb,32);buf.writeUtf(p.name,256);buf.writeVarInt(p.done);buf.writeVarInt(p.total);},
            buf->new Progress(buf.readUtf(32),buf.readUtf(256),buf.readVarInt(),buf.readVarInt()));
        @Override public Type<Progress> type(){return TYPE;}
    }
    /** The blocks this world is missing, as {@code namespace:name[properties]} in the order of the world's table: sent when a player joins and whenever another one is found. */
    public record MissingBlocks(java.util.List<String> ids) implements CustomPacketPayload {
        public static final int LIMIT=2000;
        public static final Type<MissingBlocks> TYPE=new Type<>(dev.craftium.compat.Compat.id("craftium","missing_blocks"));
        public static final StreamCodec<RegistryFriendlyByteBuf,MissingBlocks> CODEC=StreamCodec.of(
            (buf,p)->{int n=Math.min(LIMIT,p.ids.size());buf.writeVarInt(n);for(int i=0;i<n;i++)buf.writeUtf(p.ids.get(i),256);},
            buf->{int n=Math.min(LIMIT,buf.readVarInt());var ids=new java.util.ArrayList<String>(n);for(int i=0;i<n;i++)ids.add(buf.readUtf(256));return new MissingBlocks(java.util.List.copyOf(ids));});
        @Override public Type<MissingBlocks> type(){return TYPE;}
    }
    /** What the server read for an AI client: a JSON object, never shown to the player. */
    public record AiReply(String json) implements CustomPacketPayload {
        public static final Type<AiReply> TYPE=new Type<>(dev.craftium.compat.Compat.id("craftium","ai_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf,AiReply> CODEC=StreamCodec.of((buf,p)->buf.writeUtf(p.json,1_000_000),buf->new AiReply(buf.readUtf(1_000_000)));
        @Override public Type<AiReply> type(){return TYPE;}
    }
    public record Download(String folder,String name,String data,String metadata) implements CustomPacketPayload {
        public static final Type<Download> TYPE=new Type<>(dev.craftium.compat.Compat.id("craftium","download"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Download> CODEC=StreamCodec.of((buf,p)->{buf.writeUtf(p.folder,16);buf.writeUtf(p.name,160);buf.writeUtf(p.data,1_400_000);buf.writeUtf(p.metadata,16384);},buf->new Download(buf.readUtf(16),buf.readUtf(160),buf.readUtf(1_400_000),buf.readUtf(16384)));
        @Override public Type<Download> type(){return TYPE;}
    }
}
