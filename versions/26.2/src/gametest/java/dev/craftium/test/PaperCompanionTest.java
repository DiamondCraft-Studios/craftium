package dev.craftium.test;

import dev.craftium.client.*;
import dev.craftium.core.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.net.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/** Opt-in live loopback compatibility suite; never runs against a user's server. */
final class PaperCompanionTest {
    private final ClientGameTestContext ctx;private final int port;private String name;
    private PaperCompanionTest(ClientGameTestContext ctx,int port){this.ctx=ctx;this.port=port;}
    static void run(ClientGameTestContext ctx){for(int port:new int[]{25575,25576})new PaperCompanionTest(ctx,port).run();}
    private void run(){
        ctx.runOnClient(mc->{ClientState.options=new Options();ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString("127.0.0.1:"+port),new ServerData("Craftium test","127.0.0.1:"+port,ServerData.Type.OTHER),false,null);});
        until(mc->mc.player!=null&&mc.level!=null,"server connection");
        name=ctx.computeOnClient(mc->mc.player.getGameProfile().name());
        command("op "+name);command("gamemode creative "+name);command("tp "+name+" 0 10 18 180 25");
        until(mc->CraftiumClient.supported(),"bridge channel registration");
        request("status");require(!ctx.computeOnClient(mc->ClientState.status.error()),"Handshake failed");
        require(command("craftiumserver status").contains("connected clients: 1"),"Server did not recognize the client");
        command("fill 0 5 0 8 8 8 air");
        ctx.runOnClient(mc->{ClientState.options.position1=new Point(0,5,0);ClientState.options.position2=new Point(2,6,2);ClientState.options.block="minecraft:stone";});
        request("select_box");require(ctx.computeOnClient(mc->ClientState.status.selectionSize())==18,"Selection size mismatch");
        request("fill");until(mc->mc.level.getBlockState(new BlockPos(1,5,1)).is(Blocks.STONE),"fill block update");
        request("undo");until(mc->mc.level.getBlockState(new BlockPos(1,5,1)).isAir(),"undo");request("redo");until(mc->mc.level.getBlockState(new BlockPos(1,5,1)).is(Blocks.STONE),"redo");
        request("copy");ctx.runOnClient(mc->CraftiumClient.send("paste_at",new Point(5,5,0),new Point(0,1,0)));settle();until(mc->mc.level.getBlockState(new BlockPos(6,5,1)).is(Blocks.STONE),"clipboard placement");
        // A fragmented request carries a large lasso, then the large status returns over the bridge.
        ctx.runOnClient(mc->{var points=new java.util.ArrayList<Point>();for(int x=0;x<60;x++)for(int y=10;y<30;y++)for(int z=0;z<20;z++)if((x+y+z)%3!=0)points.add(new Point(x,y,z));java.util.Collections.shuffle(points,new java.util.Random(42));ClientState.options.selectionPoints=points;});
        request("lasso_select");require(ctx.computeOnClient(mc->ClientState.status.selectionSize())==16000,"Fragmented selection request failed");
        // Container NBT survives native editing and history on the Bukkit server.
        command("setblock 0 5 0 chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:7}]}");
        ctx.runOnClient(mc->{ClientState.options.position1=new Point(0,5,0);ClientState.options.position2=new Point(0,5,0);ClientState.options.selectionPoints.clear();});
        request("select_box");request("delete");request("undo");require(command("data get block 0 5 0 Items").contains("diamond"),"Chest inventory was lost");
        command("fillbiome 0 4 0 3 7 3 minecraft:plains");ctx.runOnClient(mc->ClientState.options.biome="minecraft:desert");request("set_biome");until(mc->mc.level.getNoiseBiome(0,1,0).unwrapKey().orElseThrow().identifier().toString().equals("minecraft:desert"),"biome update");request("undo");
        request("copy");ctx.runOnClient(mc->{ClientState.options.name="companion-chest";ClientState.options.downloadExports=true;});request("structure_export");require(!ctx.computeOnClient(mc->ClientState.status.error()),"Blueprint export failed");require(java.nio.file.Files.exists(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("craftium/blueprints/companion-chest.nbt")),"Client did not receive blueprint download");
        ctx.runOnClient(mc->ClientState.options.name="companion-chest.nbt");request("blueprint_load");require(ctx.computeOnClient(mc->ClientState.status.clipboardSize())==1,"Blueprint load failed");
        command("setblock 10 8 0 air");ctx.runOnClient(mc->{ClientState.options.tool=Tool.SCRIPT_BRUSH;ClientState.options.radius=1;ClientState.options.script="$once$ return blocks.emerald_block";CraftiumClient.send("use",new Point(10,8,0),new Point(0,1,0));});settle();until(mc->mc.level.getBlockState(new BlockPos(10,8,0)).is(Blocks.EMERALD_BLOCK),"bundled Lua brush");ctx.runOnClient(mc->{ClientState.options.tool=Tool.BOX_SELECT;ClientState.options.script="";});
        // World controls and permission revocation are exercised through real client requests.
        command("gamerule minecraft:advance_time false");ctx.runOnClient(mc->ClientState.options.worldTime=6000);request("world_time");require(command("time query time").contains("6000"),"World control failed");
        command("deop "+name);request("delete");require(ctx.computeOnClient(mc->ClientState.status.error()),"Non-operator edit was allowed");require(command("data get block 0 5 0 Items").contains("diamond"),"Denied edit changed the chest");command("op "+name);
        command("craftiumfixture deny");request("delete");require(ctx.computeOnClient(mc->ClientState.status.error()&&ClientState.status.message().contains("Fixture protected")),"Protection event did not reject edit");require(command("data get block 0 5 0 Items").contains("diamond"),"Cancelled edit changed chest");
        command("craftiumfixture allow");command("deop "+name);command("craftiumfixture grant "+name);request("delete");require(!ctx.computeOnClient(mc->ClientState.status.error()),"Explicit non-op edit permission failed");request("undo");require(command("data get block 0 5 0 Items").contains("diamond"),"Non-op undo failed");
        if("true".equals(System.getenv("CRAFTIUM_TEST_PROTECTION"))){
            require(command("craftiumfixture claim "+name).contains("claim ready"),"WorldGuard fixture failed");
            request("delete");require(ctx.computeOnClient(mc->ClientState.status.error()),"WorldGuard block flags were bypassed");request("redo");require(ctx.computeOnClient(mc->ClientState.status.error()),"WorldGuard did not protect history replay");require(command("data get block 0 5 0 Items").contains("diamond"),"Protected chest changed");command("craftiumfixture unclaim");
        }
        request("world_time");require(ctx.computeOnClient(mc->ClientState.status.error()),"Edit permission granted world control");command("craftiumfixture world "+name);ctx.runOnClient(mc->ClientState.options.worldTime=7000);request("world_time");require(command("time query time").contains("7000"),"Non-op world permission failed");command("op "+name);
        ctx.runOnClient(mc->CraftiumClient.open(false));ctx.waitTicks(10);ctx.takeScreenshot(port==25575?"craftium-paper-editor":"craftium-purpur-editor");ctx.runOnClient(mc->mc.gui.setScreen(null));
        System.out.println("CRAFTIUM COMPANION PASSED: "+(port==25575?"Paper 134":"Purpur 2633")+"; handshake, fragmented selection, fill, undo/redo, clipboard, chest NBT, world controls, permissions, editor");
        ctx.runOnClient(mc->mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Compatibility test complete")));until(mc->mc.player==null,"disconnect");ctx.runOnClient(mc->mc.gui.setScreen(new TitleScreen()));
    }
    private void request(String action){ctx.runOnClient(mc->CraftiumClient.send(action,null,new Point(0,1,0)));settle();System.out.println("COMPANION "+port+" "+action+": "+ctx.computeOnClient(mc->ClientState.status.message()));}
    private void settle(){for(int i=0;i<8;i++){pause(60);ctx.waitTicks(2);}until(mc->!ClientState.status.busy(),"edit completion");}
    private void until(Predicate<net.minecraft.client.Minecraft> condition,String what){for(int i=0;i<600;i++){if(ctx.computeOnClient(condition::test))return;pause(50);ctx.waitTicks(2);}throw new AssertionError("Timed out waiting for "+what+"; "+ctx.computeOnClient(mc->ClientState.status.message()));}
    private static void require(boolean test,String message){if(!test)throw new AssertionError(message);}
    private static void pause(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){throw new AssertionError(e);}}
    private String command(String text){
        try(var socket=new Socket(InetAddress.getLoopbackAddress(),port+100)){
            socket.setSoTimeout(5000);send(socket,3,"craftium-local-test-only");read(socket);send(socket,2,text);return read(socket);
        }catch(IOException e){throw new AssertionError("Local fixture command failed: "+text,e);}
    }
    private static void send(Socket socket,int type,String text)throws IOException{byte[] data=text.getBytes(StandardCharsets.UTF_8);var packet=ByteBuffer.allocate(data.length+14).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length+10).putInt(123).putInt(type).put(data).put((byte)0).put((byte)0);socket.getOutputStream().write(packet.array());}
    private static String read(Socket socket)throws IOException{var in=socket.getInputStream();byte[] size=in.readNBytes(4);if(size.length!=4)throw new EOFException();int length=ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN).getInt();if(length<10||length>1_000_000)throw new IOException("Invalid RCON response");byte[] data=in.readNBytes(length);return new String(data,8,data.length-10,StandardCharsets.UTF_8);}
}
