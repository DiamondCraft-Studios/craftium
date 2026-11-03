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
                         Point min, Point max, int clipboardSize, List<String> history, int undo, int redo,String entityUuid,String entityNbt,List<dev.craftium.core.Annotation> annotations,String selectionData,String clipboardData,List<dev.craftium.core.BlueprintInfo> blueprints,int clipboardEntityCount) {
        public Status(String message,boolean error,boolean busy,int selectionSize,Point min,Point max,int clipboardSize,List<String> history,int undo,int redo){this(message,error,busy,selectionSize,min,max,clipboardSize,history,undo,redo,"","",List.of(),"","",List.of(),0);}
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
    public record Report(String text) implements CustomPacketPayload {
        public static final Type<Report> TYPE=new Type<>(dev.craftium.compat.Compat.id("craftium","report"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Report> CODEC=StreamCodec.of((buf,p)->buf.writeUtf(p.text,100_000),buf->new Report(buf.readUtf(100_000)));
        @Override public Type<Report> type(){return TYPE;}
    }
    public record Download(String folder,String name,String data,String metadata) implements CustomPacketPayload {
        public static final Type<Download> TYPE=new Type<>(dev.craftium.compat.Compat.id("craftium","download"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Download> CODEC=StreamCodec.of((buf,p)->{buf.writeUtf(p.folder,16);buf.writeUtf(p.name,160);buf.writeUtf(p.data,1_400_000);buf.writeUtf(p.metadata,16384);},buf->new Download(buf.readUtf(16),buf.readUtf(160),buf.readUtf(1_400_000),buf.readUtf(16384)));
        @Override public Type<Download> type(){return TYPE;}
    }
}
