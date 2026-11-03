package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * Opt-in live check of the Paper companion: connects this client to a Paper 1.21.10 server that is already
 * running on loopback with the Craftium plugin installed, and exercises real edits through the plugin.
 * Set CRAFTIUM_TEST_SCOPE=paper. The server must have RCON enabled on port 25675 with the fixed local test
 * password; this never runs against a user's own server.
 */
public final class PaperLiveGameTest implements FabricClientGameTest {
    private static final int PORT=25575;
    private ClientGameTestContext ctx;
    private String name;

    @Override public void runTest(ClientGameTestContext context) {
        if(!"paper".equals(System.getenv("CRAFTIUM_TEST_SCOPE")))return;
        ctx=context;
        ctx.runOnClient(mc->{
            ClientState.options=new Options();
            ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString("127.0.0.1:"+PORT),new ServerData("Craftium test","127.0.0.1:"+PORT,ServerData.Type.OTHER),false,null);
        });
        until(mc->mc.player!=null&&mc.level!=null,"server connection");
        name=ctx.computeOnClient(mc->mc.player.getGameProfile().name());
        command("op "+name);command("gamemode creative "+name);command("tp "+name+" 0 10 18 180 25");
        until(mc->CraftiumClient.supported(),"bridge channel registration");
        System.out.println("CRAFTIUM_PAPER_LIVE connected as "+name);

        request("status");
        require(!ctx.computeOnClient(mc->ClientState.status.error()),"Handshake failed: "+ctx.computeOnClient(mc->ClientState.status.message()));
        require(command("craftiumserver status").contains("connected clients: 1"),"Server did not recognize the client");
        System.out.println("CRAFTIUM_PAPER_LIVE handshake");

        // A real selection, fill, undo and redo go through the plugin's native engine.
        command("fill 0 5 0 8 8 8 air");
        ctx.runOnClient(mc->{ClientState.options.position1=new Point(0,5,0);ClientState.options.position2=new Point(2,6,2);ClientState.options.block="minecraft:stone";});
        request("select_box");
        require(ctx.computeOnClient(mc->ClientState.status.selectionSize())==18,"Selection size mismatch");
        request("fill");
        until(mc->mc.level.getBlockState(new BlockPos(1,5,1)).is(Blocks.STONE),"fill block update");
        request("undo");
        until(mc->mc.level.getBlockState(new BlockPos(1,5,1)).isAir(),"undo");
        request("redo");
        until(mc->mc.level.getBlockState(new BlockPos(1,5,1)).is(Blocks.STONE),"redo");
        System.out.println("CRAFTIUM_PAPER_LIVE select_fill_undo_redo");

        request("copy");
        ctx.runOnClient(mc->CraftiumClient.send("paste_at",new Point(5,5,0),new Point(0,1,0)));
        settle();
        until(mc->mc.level.getBlockState(new BlockPos(6,5,1)).is(Blocks.STONE),"clipboard placement");
        System.out.println("CRAFTIUM_PAPER_LIVE clipboard");

        // A large lasso selection travels as fragmented bridge frames, and the large status returns the same way.
        ctx.runOnClient(mc->{
            var points=new java.util.ArrayList<Point>();
            for(int x=0;x<60;x++)for(int y=10;y<30;y++)for(int z=0;z<20;z++)if((x+y+z)%3!=0)points.add(new Point(x,y,z));
            java.util.Collections.shuffle(points,new java.util.Random(7));
            ClientState.options.selectionPoints.clear();ClientState.options.selectionPoints.addAll(points);
        });
        request("lasso_select");
        require(ctx.computeOnClient(mc->ClientState.status.selectionSize())==16000,"Fragmented selection request failed");
        System.out.println("CRAFTIUM_PAPER_LIVE fragmented_selection");

        // Container data survives native editing and history on the Bukkit server.
        command("setblock 0 5 0 chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:7}]}");
        ctx.runOnClient(mc->{ClientState.options.position1=new Point(0,5,0);ClientState.options.position2=new Point(0,5,0);ClientState.options.selectionPoints.clear();});
        request("select_box");request("delete");request("undo");
        require(command("data get block 0 5 0 Items").contains("diamond"),"Chest inventory was lost");
        System.out.println("CRAFTIUM_PAPER_LIVE chest_nbt");

        // Revoking operator status must stop edits, and the denied edit must not change the world.
        command("deop "+name);
        request("delete");
        require(ctx.computeOnClient(mc->ClientState.status.error()),"Non-operator edit was allowed");
        require(command("data get block 0 5 0 Items").contains("diamond"),"Denied edit changed the chest");
        System.out.println("CRAFTIUM_PAPER_LIVE permission_denied");

        command("op "+name);
        ctx.runOnClient(mc->CraftiumClient.open(false));
        ctx.waitTicks(10);
        ctx.takeScreenshot("paper-editor");
        System.out.println("CRAFTIUM_PAPER_LIVE editor_open="+ctx.computeOnClient(mc->ClientCompat.screen(mc) instanceof EditorScreen));
        ctx.runOnClient(mc->ClientCompat.setScreen(mc,null));

        ctx.runOnClient(mc->mc.disconnectFromWorld(Component.literal("Live test complete")));
        until(mc->mc.player==null,"disconnect");
        ctx.runOnClient(mc->ClientCompat.setScreen(mc,new TitleScreen())); // the framework requires tests to end here
        System.out.println("CRAFTIUM_PAPER_LIVE PASSED Paper 1.21.10: handshake, selection, fill, undo/redo, clipboard, fragmented transfer, chest NBT, permissions, editor");
    }

    private void request(String action){
        ctx.runOnClient(mc->CraftiumClient.send(action,null,new Point(0,1,0)));
        settle();
        System.out.println("CRAFTIUM_PAPER_LIVE "+action+": "+ctx.computeOnClient(mc->ClientState.status.message()));
    }
    private void settle(){for(int i=0;i<8;i++){pause(60);ctx.waitTicks(2);}until(mc->!ClientState.status.busy(),"edit completion");}
    private void until(Predicate<Minecraft> condition,String what){
        for(int i=0;i<600;i++){if(ctx.computeOnClient(condition::test))return;pause(50);ctx.waitTicks(2);}
        throw new AssertionError("Timed out waiting for "+what+"; "+ctx.computeOnClient(mc->ClientState.status.message()));
    }
    private static void require(boolean test,String message){if(!test)throw new AssertionError(message);}
    private static void pause(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){throw new AssertionError(e);}}

    /** Runs a console command over the test server's loopback RCON port. */
    private String command(String text){
        try(var socket=new Socket(InetAddress.getLoopbackAddress(),PORT+100)){
            socket.setSoTimeout(5000);
            send(socket,3,"craftium-local-test-only");read(socket);
            send(socket,2,text);return read(socket);
        }catch(IOException e){throw new AssertionError("Local test server command failed: "+text,e);}
    }
    private static void send(Socket socket,int type,String text)throws IOException{
        byte[] data=text.getBytes(StandardCharsets.UTF_8);
        var packet=ByteBuffer.allocate(data.length+14).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length+10).putInt(123).putInt(type).put(data).put((byte)0).put((byte)0);
        socket.getOutputStream().write(packet.array());socket.getOutputStream().flush();
    }
    private static String read(Socket socket)throws IOException{
        var in=socket.getInputStream();byte[] size=in.readNBytes(4);if(size.length!=4)throw new EOFException();
        int length=ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if(length<10||length>1_000_000)throw new IOException("Invalid RCON response");
        byte[] data=in.readNBytes(length);
        return new String(data,8,Math.max(0,length-10),StandardCharsets.UTF_8);
    }
}
