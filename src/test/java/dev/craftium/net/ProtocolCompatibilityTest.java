package dev.craftium.net;

import static org.junit.jupiter.api.Assertions.*;
import dev.craftium.core.BridgeCodec;
import dev.craftium.core.Handshake;
import dev.craftium.core.Options;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@link Handshake#PROTOCOL} honest. The shape of everything a client and a server send as JSON is recorded for each protocol number
 * ({@link ProtocolSnapshot}). Adding a field or an enum constant is fine and needs nothing; removing or renaming one, or changing a field's type,
 * is something an older peer misreads, so it fails here until the protocol number is raised and a new snapshot recorded.
 */
class ProtocolCompatibilityTest {
    private static final Class<?>[] ROOTS={Packets.Request.class,Packets.Status.class,Packets.Download.class,BridgeCodec.Message.class,Handshake.Hello.class,Options.class};

    /** One line per field and per enum constant, for the roots and every non-JDK type they refer to. */
    static SortedSet<String> shape(){
        var lines=new TreeSet<String>();var seen=new HashSet<Class<?>>();var queue=new ArrayDeque<Class<?>>(List.of(ROOTS));
        while(!queue.isEmpty()){
            var type=queue.poll();if(!seen.add(type))continue;
            if(type.isEnum()){for(var c:type.getEnumConstants())lines.add("enum "+type.getName()+"."+((Enum<?>)c).name());continue;}
            for(Field f:type.getDeclaredFields()){
                int m=f.getModifiers();if(Modifier.isStatic(m)||Modifier.isTransient(m)||f.isSynthetic())continue;
                lines.add(type.getName()+"."+f.getName()+" : "+f.getGenericType().getTypeName());
                collect(f.getGenericType(),queue);
            }
        }
        return lines;
    }
    private static void collect(Type t,Deque<Class<?>> queue){
        if(t instanceof Class<?> c){if(c.isArray())collect(c.getComponentType(),queue);else if(c.getName().startsWith("dev.craftium."))queue.add(c);}
        else if(t instanceof ParameterizedType p)for(Type a:p.getActualTypeArguments())collect(a,queue);
    }

    @Test void theWireFormatOnlyGrowsWithinAProtocolNumber(){
        var record=System.getenv("CRAFTIUM_RECORD_PROTOCOL");
        if(record!=null&&!record.isBlank())try{Files.writeString(Path.of(record),String.join("\n",shape())+"\n");}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        String baseline=ProtocolSnapshot.forProtocol(Handshake.PROTOCOL);
        assertNotNull(baseline,"Protocol "+Handshake.PROTOCOL+" has no recorded shape. Record one: set CRAFTIUM_RECORD_PROTOCOL to a file path, run the tests, and paste the file into ProtocolSnapshot.");
        var now=shape();var gone=new TreeSet<String>();
        for(String line:baseline.split("\n"))if(!line.isBlank()&&!now.contains(line.strip()))gone.add(line.strip());
        assertTrue(gone.isEmpty(),"These parts of protocol "+Handshake.PROTOCOL+" were removed, renamed or retyped, which an older client or server misreads. Restore them, or raise Handshake.PROTOCOL and record a new shape:\n"+String.join("\n",gone));
    }
    @Test void everyRootIsDescribed(){
        var lines=shape();
        assertTrue(lines.stream().anyMatch(l->l.startsWith(Packets.Request.class.getName()+".action")));
        assertTrue(lines.stream().anyMatch(l->l.startsWith(Options.class.getName()+".modifyTranslateScale")));
        assertTrue(lines.stream().anyMatch(l->l.startsWith("enum "+Options.ModifyMode.class.getName()+".REVOLVE")));
        assertTrue(lines.stream().anyMatch(l->l.startsWith(Handshake.Hello.class.getName()+".capabilities")));
    }
}
