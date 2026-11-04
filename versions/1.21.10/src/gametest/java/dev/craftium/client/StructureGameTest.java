package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import java.nio.file.*;
import java.util.*;

/**
 * Builds a small structure in a real world, opens the export pop-up to capture both tabs, then exports it as Java NBT, as a
 * schematic and as Bedrock, reads the files back, and imports the Bedrock one into the clipboard.
 */
public final class StructureGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext c){
        if(!"structures".equals(System.getenv("CRAFTIUM_TEST_SCOPE")))return;
        try(var world=c.worldBuilder().adjustSettings(st->{st.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.CREATIVE);st.setAllowCommands(true);}).create()){
            c.runOnClient(mc->ClientState.options=new Options());
            world.getServer().runCommand("tp @a 0 10 18 180 25");
            c.waitTicks(20);world.getClientWorld().waitForChunksDownload();
            run(c,world);
        }
    }

    public static void run(ClientGameTestContext c,TestSingleplayerContext w){
        try{scenario(c,w);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }

    private static void scenario(ClientGameTestContext c,TestSingleplayerContext w)throws java.io.IOException{
        c.runOnClient(mc->{mc.options.guiScale().set(1);mc.resizeDisplay();}); // the test window is small; a player's is larger than its scaled size here
        c.waitFor(mc->mc.player.onGround(),600);
        c.runOnClient(mc->{mc.player.setYRot(0f);mc.player.setXRot(20f);mc.player.setOldPosAndRot();});
        c.runOnClient(mc->CraftiumClient.open(false));
        c.waitTicks(10);
        BlockPos base=c.computeOnClient(mc->mc.player.blockPosition().offset(-2,0,6));
        int bx=base.getX(),by=base.getY(),bz=base.getZ();
        w.getServer().runCommand("fill "+bx+" "+by+" "+bz+" "+(bx+3)+" "+(by+2)+" "+(bz+3)+" air");
        w.getServer().runCommand("fill "+bx+" "+by+" "+bz+" "+(bx+3)+" "+by+" "+(bz+3)+" stone_bricks");
        w.getServer().runCommand("setblock "+bx+" "+(by+1)+" "+bz+" oak_stairs[facing=east,half=top,waterlogged=true]");
        w.getServer().runCommand("setblock "+(bx+1)+" "+(by+1)+" "+bz+" chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}");
        w.getServer().runCommand("setblock "+(bx+3)+" "+(by+1)+" "+(bz+3)+" oak_door[half=lower,facing=west]");
        c.waitTicks(10);
        c.runOnClient(mc->{
            var points=new ArrayList<Point>();
            for(int x=0;x<4;x++)for(int y=0;y<3;y++)for(int z=0;z<4;z++)points.add(new Point(bx+x,by+y,bz+z));
            ClientState.options.selectionPoints.clear();ClientState.options.selectionPoints.addAll(points);
            CraftiumClient.send("lasso_select",null,new Point(0,1,0));
        });
        c.waitTicks(10);
        c.waitTicks(10);
        c.waitTicks(10);
        c.waitTicks(20);
        require(c.computeOnClient(mc->ClientState.status.selectionSize())==48,"The 4 x 3 x 4 selection was not made");

        Path directory=Files.createTempDirectory("craftium-structures");
        // Java, with air kept.
        c.runOnClient(mc->{var s=ClientState.options.structure;s.source=StructureSettings.SELECTION;s.format=StructureSettings.JAVA;s.javaFile=StructureSettings.NBT;s.name="gametest:house";s.airToVoid=false;s.showInvisible=true;});
        StructurePreview preview=open(c);
        c.takeScreenshot("structure-export-java");
        require(preview.sizeX()==4&&preview.sizeY()==3&&preview.sizeZ()==4,"Preview size is "+preview.sizeX()+"x"+preview.sizeY()+"x"+preview.sizeZ());
        long voidCells=Arrays.stream(preview.cells()).filter(i->i==StructurePreview.VOID).count();
        require(voidCells==0,"Air was turned to void without being asked: "+voidCells);
        int solidBlocks=preview.blocks();
        System.out.println("CRAFTIUM_STRUCTURES_PASS java_preview blocks="+solidBlocks+" air="+Arrays.stream(preview.cells()).filter(i->i==StructurePreview.AIR).count());

        // The same, with air turned to void: the preview must change.
        c.runOnClient(mc->ClientState.options.structure.airToVoid=true);
        preview=open(c);
        c.takeScreenshot("structure-export-java-void");
        long voided=Arrays.stream(preview.cells()).filter(i->i==StructurePreview.VOID).count();
        require(voided>0&&preview.blocks()==solidBlocks,"Converting air to void did not update the preview ("+voided+" void cells)");
        System.out.println("CRAFTIUM_STRUCTURES_PASS air_to_void void_cells="+voided);

        // Java NBT file.
        Path nbt=directory.resolve("house.nbt");
        c.runOnClient(mc->ClientStructures.exportTo(nbt,"house.nbt"));
        waitForFile(c,nbt);
        var tag=NbtIo.readCompressed(nbt,NbtAccounter.unlimitedHeap());
        require(tag.getListOrEmpty("size").getIntOr(0,0)==4,"NBT size X is wrong");
        int blocksInFile=tag.getListOrEmpty("blocks").size();
        require(blocksInFile==solidBlocks,"The NBT holds "+blocksInFile+" blocks, not "+solidBlocks+" (air was converted to void)");
        boolean chest=false;for(int i=0;i<blocksInFile;i++)chest|=tag.getListOrEmpty("blocks").getCompoundOrEmpty(i).contains("nbt");
        require(chest,"The chest lost its block entity data");
        System.out.println("CRAFTIUM_STRUCTURES_PASS java_nbt blocks="+blocksInFile+" bytes="+Files.size(nbt));

        // Schematic.
        c.runOnClient(mc->{var s=ClientState.options.structure;s.javaFile=StructureSettings.SCHEM;s.airToVoid=false;});
        Path schem=directory.resolve("house.schem");
        c.waitTicks(20); // the server answers one request at a time
        c.runOnClient(mc->ClientStructures.exportTo(schem,"house.schem"));
        waitForFile(c,schem);
        var sponge=NbtIo.readCompressed(schem,NbtAccounter.unlimitedHeap());
        require(sponge.getShort("Width").orElse((short)0)==4&&sponge.getIntOr("Version",0)==2,"The schematic header is wrong");
        System.out.println("CRAFTIUM_STRUCTURES_PASS schematic palette="+sponge.getIntOr("PaletteMax",0));

        // Bedrock.
        c.runOnClient(mc->{var s=ClientState.options.structure;s.format=StructureSettings.BEDROCK;s.airToVoid=true;});
        preview=open(c);
        c.takeScreenshot("structure-export-bedrock");
        Path mcstructure=directory.resolve("house.mcstructure");
        c.waitTicks(20);
        c.runOnClient(mc->ClientStructures.exportTo(mcstructure,"house.mcstructure"));
        waitForFile(c,mcstructure);
        var root=BedrockNbt.read(Files.readAllBytes(mcstructure));
        @SuppressWarnings("unchecked") var size=(List<Object>)root.get("size");
        require(size.equals(List.of(4,3,4)),"Bedrock size is "+size);
        @SuppressWarnings("unchecked") var structure=(Map<String,Object>)root.get("structure");
        @SuppressWarnings("unchecked") var layers=(List<Object>)structure.get("block_indices");
        @SuppressWarnings("unchecked") var primary=(List<Object>)layers.get(0);
        @SuppressWarnings("unchecked") var secondary=(List<Object>)layers.get(1);
        require(primary.size()==48,"Bedrock block list has "+primary.size()+" entries");
        long placed=primary.stream().filter(i->(Integer)i>=0).count();
        require(placed>=16,"Bedrock kept only "+placed+" blocks");
        require(secondary.stream().anyMatch(i->(Integer)i>=0),"The waterlogged stair has no water in Bedrock's second layer");
        System.out.println("CRAFTIUM_STRUCTURES_PASS bedrock placed="+placed+" water_layer="+secondary.stream().filter(i->(Integer)i>=0).count());

        // Importing the Bedrock file back.
        byte[] bytes=Files.readAllBytes(mcstructure);
        c.runOnClient(mc->{var o=ClientState.options;o.name="house.mcstructure";o.assetData=Base64.getEncoder().encodeToString(bytes);CraftiumClient.send("structure_import_file",null,null);o.assetData="";});
        c.waitTicks(10);
        c.waitTicks(10);
        c.waitTicks(10);
        c.waitTicks(10);
        int clipboard=c.computeOnClient(mc->ClientState.status.clipboardSize());
        require(clipboard>=16,"The imported Bedrock structure left "+clipboard+" blocks in the clipboard");
        System.out.println("CRAFTIUM_STRUCTURES_PASS bedrock_import clipboard="+clipboard+" status="+c.computeOnClient(mc->ClientState.status.message()));
        System.out.println("CRAFTIUM_STRUCTURES_PASS all (files in "+directory+")");
    }

    /** Opens a fresh pop-up on the current settings and waits for the server's preview of them. */
    private static StructurePreview open(ClientGameTestContext c){
        int before=c.computeOnClient(mc->ClientStructures.previews);
        c.runOnClient(mc->ClientCompat.setScreen(mc,new StructureExportScreen(null)));
        try{c.waitFor(mc->ClientStructures.previews>before,400);}
        catch(AssertionError e){throw new AssertionError("No preview; the server said: "+c.computeOnClient(mc->ClientState.status.message()),e);}
        c.waitTicks(10);
        var preview=c.computeOnClient(mc->ClientStructures.preview);
        require(preview!=null,"The server sent no preview");
        return preview;
    }

    /** Waits for an exported file, and says what the server answered if it never comes. */
    private static void waitForFile(ClientGameTestContext c,Path file){
        try{c.waitFor(mc->Files.exists(file),400);}
        catch(AssertionError e){throw new AssertionError("No "+file.getFileName()+"; the server said: "+c.computeOnClient(mc->ClientState.status.message()),e);}
    }

    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
