package dev.craftium.core;

/**
 * The fields of a vanilla structure block, for exporting a structure. Java and Bedrock share the ones they have in common
 * ({@link #name}, {@link #offsetX} and the rest) so switching format keeps what was typed; the other fields belong to one
 * format. Mirror, rotation, integrity and the seed change the exported blocks, because a file has no place to keep them.
 */
public final class StructureSettings {
    public static final String JAVA="java",BEDROCK="bedrock",BLUEPRINT="blueprint",NBT="nbt",SCHEM="schem",SELECTION="selection",CLIPBOARD="clipboard";
    public static final int MAX_SIZE=256;

    /** Which tab the export pop-up is on, and so which file it writes: a Java or Bedrock structure, or a blueprint for the library. */
    public String format=JAVA;
    /** The Java tab writes a vanilla structure ({@link #NBT}) or a Sponge schematic ({@link #SCHEM}). */
    public String javaFile=NBT;
    /** Where the blocks come from: the selection, or what was last copied. */
    public String source=SELECTION;
    /** When above zero, the blocks come from this BuildObject (and everything under it) and not from the selection or the clipboard. */
    public int objectId;
    /** The structure's name as a structure block takes it: {@code namespace:path}, with {@code minecraft} when no namespace is typed. */
    public String name="structure";
    /** Bedrock's structure origin is the selection's lowest corner plus this, and a schematic keeps it as its offset; a structure NBT has no place for it. */
    public int offsetX,offsetY,offsetZ;
    /** Zero keeps the detected size on that axis. A smaller size cuts the structure off at its far edge. */
    public int sizeX,sizeY,sizeZ;
    public String mirror="none";
    public int rotation;
    /** Percent of blocks kept, as the structure block's integrity slider. */
    public int integrity=100;
    public long seed;
    public boolean includeEntities=true,includeBlocks=true,airToVoid,showInvisible,showBounds=true,showAxes=true;

    public void validate(){
        if(!JAVA.equals(format)&&!BEDROCK.equals(format)&&!BLUEPRINT.equals(format))format=JAVA;
        objectId=Math.max(0,objectId);
        if(!NBT.equals(javaFile)&&!SCHEM.equals(javaFile))javaFile=NBT;
        if(!SELECTION.equals(source)&&!CLIPBOARD.equals(source))source=SELECTION;
        if(name==null||!name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+|[a-z0-9_./-]+"))name="structure";
        if(name.length()>120)name=name.substring(0,120);
        offsetX=Math.clamp(offsetX,-30_000_000,30_000_000);offsetY=Math.clamp(offsetY,-4096,4096);offsetZ=Math.clamp(offsetZ,-30_000_000,30_000_000);
        sizeX=Math.clamp(sizeX,0,MAX_SIZE);sizeY=Math.clamp(sizeY,0,MAX_SIZE);sizeZ=Math.clamp(sizeZ,0,MAX_SIZE);
        if(mirror==null||!mirror.matches("none|left_right|front_back"))mirror="none";
        rotation=Math.floorMod(rotation/90,4)*90;
        integrity=Math.clamp(integrity,0,100);
    }

    /** {@code namespace:path}, adding {@code minecraft} when the name has none. */
    public String qualifiedName(){return name.indexOf(':')<0?"minecraft:"+name:name;}
    public String namespace(){String full=qualifiedName();return full.substring(0,full.indexOf(':'));}
    public String path(){String full=qualifiedName();return full.substring(full.indexOf(':')+1);}
    /** The last part of the name, which is what a file is called. */
    public String fileStem(){String path=path();return path.substring(path.lastIndexOf('/')+1);}
    public String extension(){return bedrock()?".mcstructure":schematic()?".schem":".nbt";}
    public String fileName(){return fileStem()+extension();}
    public boolean bedrock(){return BEDROCK.equals(format);}
    /** A blueprint keeps the blocks as they are: the fields that change them (mirror, rotation, size, integrity, void) do not apply. */
    public boolean blueprint(){return BLUEPRINT.equals(format);}
    public boolean schematic(){return !bedrock()&&SCHEM.equals(javaFile);}
    /** Whether the file has a place for the position fields: Bedrock's structure origin and a schematic's offset. */
    public boolean storesOffset(){return bedrock()||schematic();}
}
