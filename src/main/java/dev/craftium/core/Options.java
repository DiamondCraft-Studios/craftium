package dev.craftium.core;

public final class Options {
    /** Which blocks the Magic Wand treats as connected: any block, the same block, the same block state, a shared block tag, or the same block entity data. */
    public enum Compare { ANY, BLOCK, STATE, TAG, NBT }
    public enum Noise { SIMPLEX, PERLIN, VALUE, WORLEY, VORONOI_EDGES, METABALLS, WHITE, SPLATTER }
    public enum PathMode { BRESENHAM, DDA, CATMULL_ROM, BEZIER, CATENARY }
    public enum ModelMode { CONVEX_HULL, TRIANGLE_STRIP, TRIANGLE_FAN, FLAT, CATMULL_ROM, BEZIER, SMART }
    public enum ModifyMode { TRANSLATE_COPIES, ROTATE_COPIES, REVOLVE, TWIST }
    public enum ShapeKind { BRUSH, SPHERE, CUBOID, OCTAHEDRON, SUPERSPHERE, CYLINDER, TUBE, CONE, PYRAMID, TORUS, DODECAHEDRON, ICOSAHEDRON, DISK, PLANE, POLYGON, SPIRAL }
    public Tool tool = Tool.BOX_SELECT;
    public BrushMath.Shape shape = BrushMath.Shape.SPHERE;
    public Selection.Mode mode = Selection.Mode.REPLACE;
    public int radius = 5;
    public double strength = 1;
    public double noiseScale = 8;
    public double density = 0.5;
    public long seed = 42;
    public boolean invert, hollow, surfaceOnly = true, keepExisting, pasteAir;
    public boolean noUpdates, forcePlace, replaceMode, enhancedFlight, bulldozer, tinker;
    public boolean inSelection;
    /** Whether undo and redo flash the blocks they change. */
    public boolean historyPulse = true;
    public String block = "minecraft:stone";
    public String secondary = "minecraft:andesite";
    public String mask = "";
    /** The blocks shown as cells in the mask palette, and which of them are toggled on. A cell with a state ("[") matches that exact state; without one, any state of the block. */
    public java.util.List<String> maskBlocks = new java.util.ArrayList<>(), maskActive = new java.util.ArrayList<>();
    /** False: edit only the toggled blocks. True: edit everything but them. With none toggled the block mask does nothing. */
    public boolean maskExclude;
    public String biome = "minecraft:plains";
    public String text = "CRAFTIUM";
    public int minY = -64, maxY = 319;
    public float flySpeed = 1;
    public int offsetX, offsetY = 1, offsetZ, copies = 3;
    public Compare compare=Compare.BLOCK;
    public int selectLimit=100_000,selectRange=1,lassoDepth=64;
    /** How many blocks the Select menu's Expand and Shrink move the selection's surface. */
    /** Edits that place or remove blocks are shown as a ghost first and only applied when confirmed with Enter. */
    public boolean confirmEdits=true;
    /** The point the Rotate and Scale tools work around. When not set it is the centre of the selection. */
    /** Draw every crease of a selection's outline instead of only its silhouette as seen from the camera. */
    public boolean selectionAllEdges;
    /** The colour (ARGB) and width, in pixels from 0.1 to 10, of the selection's outline. */
    public int selectionColor=0xffffd66d;
    public double selectionWidth=1;
    /** The point one transform request works about, which can differ from the player's pivot, as when scaling from the far side. */
    public boolean aboutSet;
    /** A selection transform that also turns and mirrors the blocks' states, as the Edit menu's Rotate and Flip do; the gizmo only moves them. */
    public boolean turnBlocks;
    public double aboutX,aboutY,aboutZ;
    public boolean pivotSet;
    public double pivotX,pivotY,pivotZ;
    public int expandAmount=5;
    /** When above zero, how many blocks one expand or shrink request moves the surface, instead of the brush strength. */
    public int selectionResize;
    /** Hovering with the Magic Wand outlines what a click would select, unless that is this many blocks or more: likely terrain, not an object. */
    public boolean magicPreview=true;
    public int magicPreviewLimit=500;
    public boolean selectUp=true,selectDown=true,selectHorizontal=true,selectCorners,includeNonSolid;
    public Noise noise=Noise.SIMPLEX;
    public int octaves=1;
    public double lacunarity=2,gain=.5,jitter=1,scaleX=1,scaleY=1,scaleZ=1;
    public boolean noise3D=true,copyProperties=true,typeReplace,fillVertically,visualizeBiome=true;
    public String materials="minecraft:stone,minecraft:andesite";
    public java.util.List<Double> materialWeights=new java.util.ArrayList<>();
    public enum ShadeFrom { PLAYER_POSITION,CUSTOM_POSITIONS,SUN_ANGLE }
    public ShadeFrom shadeFrom=ShadeFrom.SUN_ANGLE;
    public boolean shadeSun=true,shadeAutomatic,shadeFullBlocks=true,shadeOpaque=true,shadeNoOres=true,shadeNoGlazed=true,shadeNoBlockEntities=true;
    public double shadeAzimuth=315,shadeAltitude=45,shadeAmbientOcclusion=.65,shadeDither=.5,shadeExposure=1;
    public int shadeBounces=1,shadeSamples=4,shadeRayLength=48;
    public java.util.List<ShadeLight> shadeLights=new java.util.ArrayList<>();
    public String maskExpression="",maskScript="",script="return blocks.stone";
    public PathMode pathMode=PathMode.DDA;
    public ModelMode modelMode=ModelMode.CONVEX_HULL;
    public ModifyMode modifyMode=ModifyMode.TRANSLATE_COPIES;
    public ShapeKind shapeKind=ShapeKind.BRUSH;
    public java.util.List<Point> nodes=new java.util.ArrayList<>();
    public java.util.List<Point> selectionPoints=new java.util.ArrayList<>();
    public Point position1=new Point(0,0,0),position2=new Point(0,0,0),symmetryOrigin=new Point(0,0,0);
    public boolean looped,extendToGround,useStairsAndSlabs,deferred,symmetryX,symmetryY,symmetryZ;
    public double slack=4,yaw,pitch,roll,sizeX=1,sizeY=1,sizeZ=1,exponent=4,innerRadius=.5,baseChance=1,minSpacing=1;
    public boolean randomYaw=true,randomFlip;
    public int polygonSides=6,fluidHeight=100,fontSize=16,textThickness=1,modelColumns=4;
    public String fontName="SansSerif",fontFile="",heightmap="",name="Untitled",entityType="block_display",entityNbt="{}",property="";
    public boolean useBuiltinFont=true,textShadow,fullBlocks=true,denoise=true,maskY,fastPlace,angelPlacement,infiniteReach,noClip;
    public boolean showCollisionMesh,showLightBlocks,showStructureVoid,expandHitboxes,solidFluids,preventInteractions;
    public double smoothing=2,threshold=.5,meldStrength=1;
    public int annotationColor=0xffa5d6ff,annotationWidth=2;
    public String annotationMode="TEXT",billboard="CENTER";
    public String assetData="";
    public String entityUuid="";
    public String annotationId="";
    public int worldTime=6000,randomTickSpeed=3;
    public boolean worldToggle=true;
    public boolean fractureMaterial;
    public int exposedFaces=2;
    public int uiBackground=0xf510141a,uiPanel=0xff10151c,uiAccent=0xff5b9fdb,uiText=0xffdce6ef,uiMuted=0xff8595a8,uiBorder=0xff303b49;
    public enum FluidType { WATER,LAVA,SNOW }
    public FluidType fluidType=FluidType.WATER;
    public int floodLimit=1000,flowLength=7,fluidQuality=1;
    public boolean floodUp,floodDown=true,floodHorizontal=true,floodCorners,requireSupport,infiniteSources,fillEdges=true,removeFluidsWithoutSources;
    public enum RulerMode { LINE,CIRCLE }
    public RulerMode rulerMode=RulerMode.LINE;
    public int rulerSplit=2;
    public String entityGroup="";
    public String author="",tags="";
    public java.util.List<Point> annotationPoints=new java.util.ArrayList<>();
    public java.util.List<Integer> annotationPixels=new java.util.ArrayList<>();
    public int annotationImageWidth,annotationImageHeight;
    public double annotationImageScale=4;
    public Point annotationNormal=new Point(0,0,1);
    public boolean mergeBlocks;
    public boolean copyEntities,pasteEntities=true;
    public java.util.Map<String,String> scriptVariables=new java.util.LinkedHashMap<>();
    public java.util.Map<String,String> maskVariables=new java.util.LinkedHashMap<>();
    public java.util.List<BlockColour> scriptColours=new java.util.ArrayList<>();
    public java.util.List<ColourPlacement> colourField=new java.util.ArrayList<>();
    public boolean autoSwapToCreative=true;
    public boolean cameraFlight,showKeyHints,showBiomes,showMarkerGizmos=true,showDisplayGizmos=true,showAnnotations=true;
    public double flightMomentum,liquidOpacity=1,minimumBrightness;
    public enum GizmoMode { MOVE,ROTATE,SCALE }
    public GizmoMode gizmoMode=GizmoMode.MOVE;
    public enum GizmoTarget { AUTO,SELECTION,ENTITY }
    public GizmoTarget gizmoTarget=GizmoTarget.AUTO;
    public boolean gizmoLocal,unlockRotation;
    public double gizmoYaw,gizmoPitch,gizmoRoll;
    public boolean libraryClient,downloadExports=true;
    public int historySteps=1;
    public enum BuilderTool { MOVE,CLONE,STACK,SMEAR,EXTRUDE,ERASE,SYMMETRY }
    public BuilderTool builderTool=BuilderTool.MOVE;
    public int builderRotation,builderFlipAxis=-1;
    public int symmetryRotations=1;
    public boolean symmetryHalfX,symmetryHalfY,symmetryHalfZ;
    public double rebuildStartDelay=1,rebuildMaxDelay=.25,rebuildAirDelay=.01,rebuildSameDelay=.03,rebuildDifferentDelay=.08,rebuildRandomDelay=.01,rebuildHorizontalMultiplier=1,rebuildVerticalMultiplier=1;
    public double entityX,entityY,entityZ,entityScaleX=1,entityScaleY=1,entityScaleZ=1;
    /** Names of the properties pinned from the Studio inspector to the editor's Pinned tab. Their order is the inspector's, not this list's. */
    public java.util.List<String> pinned=new java.util.ArrayList<>();

    /** Whether placing a block is to be changed or recorded by the editor, rather than left to the game alone. */
    public boolean placementCapabilities(){return symmetryX||symmetryY||symmetryZ||symmetryRotations>1||replaceMode||forcePlace||noUpdates||fastPlace||infiniteReach||angelPlacement||preventInteractions;}

    public void validate() {
        if (tool == null || shape == null || mode == null) throw new IllegalArgumentException("Invalid tool options");
        if(gizmoMode==null)gizmoMode=GizmoMode.MOVE;gizmoYaw=bounded(gizmoYaw,-360,360);gizmoPitch=bounded(gizmoPitch,-360,360);gizmoRoll=bounded(gizmoRoll,-360,360);
        if(gizmoTarget==null)gizmoTarget=GizmoTarget.AUTO;
        flightMomentum=bounded(flightMomentum,0,.98);liquidOpacity=bounded(liquidOpacity,0,1);minimumBrightness=bounded(minimumBrightness,0,1);
        if(builderTool==null)throw new IllegalArgumentException("Choose a builder tool");builderRotation=Math.floorMod(builderRotation,4);builderFlipAxis=Math.clamp(builderFlipAxis,-1,2);symmetryRotations=symmetryRotations>=4?4:symmetryRotations>=2?2:1;
        if(shadeFrom==null||shadeLights==null||shadeLights.size()>16||shadeLights.contains(null)||materialWeights==null||materialWeights.size()>256||materialWeights.contains(null))throw new IllegalArgumentException("Invalid lighting or palette options");
        for(var light:shadeLights)if(!Double.isFinite(light.x())||!Double.isFinite(light.y())||!Double.isFinite(light.z())||!Double.isFinite(light.intensity())||Math.abs(light.x())>30_000_000||Math.abs(light.z())>30_000_000||Math.abs(light.y())>4096||light.intensity()<0||light.intensity()>16)throw new IllegalArgumentException("Invalid light source");
        for(int i=0;i<materialWeights.size();i++)materialWeights.set(i,bounded(materialWeights.get(i),.01,100));
        shadeAzimuth=bounded(shadeAzimuth,-360,360);shadeAltitude=bounded(shadeAltitude,-90,90);shadeAmbientOcclusion=bounded(shadeAmbientOcclusion,0,1);shadeDither=bounded(shadeDither,0,1);shadeExposure=bounded(shadeExposure,.1,4);shadeBounces=Math.clamp(shadeBounces,0,4);shadeSamples=Math.clamp(shadeSamples,1,8);shadeRayLength=Math.clamp(shadeRayLength,4,96);
        if(pinned==null)pinned=new java.util.ArrayList<>();
        if(pinned.size()>256||pinned.stream().anyMatch(name->name==null||name.length()>64))throw new IllegalArgumentException("Invalid pinned properties");
        radius = Math.clamp(radius,1,24);
        strength = Double.isFinite(strength) ? Math.clamp(strength,0.1,5) : 1;
        noiseScale = Double.isFinite(noiseScale) ? Math.clamp(noiseScale,1,64) : 8;
        density = Double.isFinite(density) ? Math.clamp(density,0,1) : 0.5;
        flySpeed = Float.isFinite(flySpeed) ? Math.clamp(flySpeed,0.1f,10f) : 1;
        offsetX=Math.clamp(offsetX,-128,128);offsetY=Math.clamp(offsetY,-128,128);offsetZ=Math.clamp(offsetZ,-128,128);copies=Math.clamp(copies,1,16);
        if(compare==null)compare=Compare.ANY; // Gson reads a constant that no longer exists, the old SOLID, as null
        if(noise==null||pathMode==null||modelMode==null||modifyMode==null||shapeKind==null||rulerMode==null)throw new IllegalArgumentException("Missing advanced options");
        rulerSplit=Math.clamp(rulerSplit,2,16);
        historySteps=Math.clamp(historySteps,1,32);
        if(colourField==null||colourField.size()>4096||colourField.contains(null))throw new IllegalArgumentException("Colour field limit: 4096 blocks");for(var placement:colourField)if(placement.point()==null||placement.block()==null||placement.block().length()>256)throw new IllegalArgumentException("Invalid colour field entry");
        if(scriptVariables==null||scriptVariables.size()>64||maskVariables==null||maskVariables.size()>64||scriptColours==null||scriptColours.size()>4096||scriptColours.contains(null))throw new IllegalArgumentException("Too much script configuration");
        for(var map:java.util.List.of(scriptVariables,maskVariables))for(var variable:map.entrySet())if(variable.getKey()==null||variable.getValue()==null||variable.getKey().length()>80||variable.getValue().length()>256)throw new IllegalArgumentException("Invalid script variable");
        for(var colour:scriptColours)if(colour.block()==null||colour.block().length()>256)throw new IllegalArgumentException("Invalid script colour entry");
        rebuildStartDelay=bounded(rebuildStartDelay,0,60);rebuildMaxDelay=bounded(rebuildMaxDelay,0,30);rebuildAirDelay=bounded(rebuildAirDelay,0,30);rebuildSameDelay=bounded(rebuildSameDelay,0,30);rebuildDifferentDelay=bounded(rebuildDifferentDelay,0,30);rebuildRandomDelay=bounded(rebuildRandomDelay,0,30);rebuildHorizontalMultiplier=bounded(rebuildHorizontalMultiplier,0,16);rebuildVerticalMultiplier=bounded(rebuildVerticalMultiplier,0,16);
        if(annotationPoints==null||annotationPoints.size()>256||annotationPoints.contains(null)||annotationPixels==null||annotationPixels.size()>1024||annotationPixels.contains(null))throw new IllegalArgumentException("Invalid annotation data");
        annotationImageScale=bounded(annotationImageScale,.1,32);annotationImageWidth=Math.clamp(annotationImageWidth,0,32);annotationImageHeight=Math.clamp(annotationImageHeight,0,32);
        if(annotationNormal==null||Math.abs(annotationNormal.x())+Math.abs(annotationNormal.y())+Math.abs(annotationNormal.z())!=1)throw new IllegalArgumentException("Invalid annotation plane");
        if(author==null||tags==null||author.length()>80||tags.length()>256)throw new IllegalArgumentException("Blueprint metadata is too long");
        if(entityGroup==null||!entityGroup.matches("[A-Za-z0-9_. -]{0,64}"))throw new IllegalArgumentException("Use a plain entity group name");
        entityX=bounded(entityX,-128,128);entityY=bounded(entityY,-128,128);entityZ=bounded(entityZ,-128,128);entityScaleX=bounded(entityScaleX,.01,16);entityScaleY=bounded(entityScaleY,.01,16);entityScaleZ=bounded(entityScaleZ,.01,16);
        selectLimit=Math.clamp(selectLimit,1,Selection.LIMIT);magicPreviewLimit=Math.clamp(magicPreviewLimit,1,4096);selectionWidth=Double.isFinite(selectionWidth)?Math.clamp(selectionWidth,.1,10):1;if((selectionColor>>>24)==0)selectionColor|=0xff000000;expandAmount=Math.clamp(expandAmount,1,64);selectionResize=Math.clamp(selectionResize,0,64);selectRange=Math.clamp(selectRange,1,8);lassoDepth=Math.clamp(lassoDepth,1,256);
        octaves=Math.clamp(octaves,1,8);lacunarity=bounded(lacunarity,1,4);gain=bounded(gain,.01,1);jitter=bounded(jitter,0,1);
        scaleX=bounded(scaleX,.1,16);scaleY=bounded(scaleY,.1,16);scaleZ=bounded(scaleZ,.1,16);
        sizeX=Math.copySign(bounded(Math.abs(sizeX),.1,8),sizeX);sizeY=Math.copySign(bounded(Math.abs(sizeY),.1,8),sizeY);sizeZ=Math.copySign(bounded(Math.abs(sizeZ),.1,8),sizeZ); // negative flips
        yaw=bounded(yaw,-360,360);pitch=bounded(pitch,-360,360);roll=bounded(roll,-360,360);
        exponent=bounded(exponent,.25,16);innerRadius=bounded(innerRadius,.05,.95);slack=bounded(slack,-64,64);
        baseChance=bounded(baseChance,0,1);minSpacing=bounded(minSpacing,.1,8);smoothing=bounded(smoothing,.1,5);threshold=bounded(threshold,0,1);meldStrength=bounded(meldStrength,0,3);
        polygonSides=Math.clamp(polygonSides,3,32);fluidHeight=Math.clamp(fluidHeight,0,100);fontSize=Math.clamp(fontSize,6,96);textThickness=Math.clamp(textThickness,1,8);modelColumns=Math.clamp(modelColumns,2,32);
        if(nodes==null||nodes.size()>64||nodes.contains(null))throw new IllegalArgumentException("Maximum 64 control points");
        if(selectionPoints==null||selectionPoints.size()>32768||selectionPoints.contains(null))throw new IllegalArgumentException("Lasso selection limit: 32,768 blocks");
        for(String value:new String[]{materials,maskExpression,maskScript,script,fontName,fontFile,heightmap,name,entityType,entityNbt,property,annotationMode,billboard})
            if(value==null||value.length()>1_000_000)throw new IllegalArgumentException("Invalid advanced text setting");
        if(script.length()>16384||maskScript.length()>16384||maskExpression.length()>4096||name.length()>80)throw new IllegalArgumentException("Advanced setting is too long");
        if(position1==null||position2==null||symmetryOrigin==null)throw new IllegalArgumentException("Missing position");
        if(assetData==null||assetData.length()>1_500_000)throw new IllegalArgumentException("Imported asset is too large");
        if(entityUuid==null||entityUuid.length()>64)throw new IllegalArgumentException("Invalid entity identifier");
        if(annotationId==null||annotationId.length()>64)throw new IllegalArgumentException("Invalid annotation identifier");
        worldTime=Math.clamp(worldTime,0,2_000_000_000);randomTickSpeed=Math.clamp(randomTickSpeed,0,4096);
        exposedFaces=Math.clamp(exposedFaces,1,6);
        if(fluidType==null)throw new IllegalArgumentException("Choose a fluid");floodLimit=Math.clamp(floodLimit,1,Selection.LIMIT);flowLength=Math.clamp(flowLength,1,32);fluidQuality=Math.clamp(fluidQuality,1,4);
        if (maskBlocks==null || maskActive==null || maskBlocks.size()>64 || maskActive.size()>64) throw new IllegalArgumentException("Block mask limit: 64 blocks");
        for (var list : java.util.List.of(maskBlocks, maskActive)) for (String entry : list) if (entry==null || entry.length()>256) throw new IllegalArgumentException("Invalid block mask entry");
        if (block == null || secondary == null || mask == null || biome == null || text == null)
            throw new IllegalArgumentException("Missing tool settings");
        if (block.length()>256 || secondary.length()>256 || mask.length()>256 || biome.length()>128 || text.length()>48)
            throw new IllegalArgumentException("Tool setting is too long");
    }
    private static double bounded(double value,double min,double max){return Double.isFinite(value)?Math.clamp(value,min,max):min;}
}
