package dev.craftium.net;

/** The recorded wire shape of each protocol number; see {@link ProtocolCompatibilityTest}. */
final class ProtocolSnapshot {
    private ProtocolSnapshot() {}
    static String forProtocol(int protocol){return protocol==1?V1:null;}
    static final String V1="""
        dev.craftium.core.Annotation.color : int
        dev.craftium.core.Annotation.from : dev.craftium.core.Point
        dev.craftium.core.Annotation.id : java.util.UUID
        dev.craftium.core.Annotation.imageHeight : int
        dev.craftium.core.Annotation.imageScale : double
        dev.craftium.core.Annotation.imageWidth : int
        dev.craftium.core.Annotation.mode : java.lang.String
        dev.craftium.core.Annotation.normal : dev.craftium.core.Point
        dev.craftium.core.Annotation.pixels : java.util.List<java.lang.Integer>
        dev.craftium.core.Annotation.points : java.util.List<dev.craftium.core.Point>
        dev.craftium.core.Annotation.text : java.lang.String
        dev.craftium.core.Annotation.to : dev.craftium.core.Point
        dev.craftium.core.Annotation.width : int
        dev.craftium.core.BlockColour.block : java.lang.String
        dev.craftium.core.BlockColour.flags : int
        dev.craftium.core.BlockColour.rgb : int
        dev.craftium.core.BlueprintInfo.author : java.lang.String
        dev.craftium.core.BlueprintInfo.blocks : int
        dev.craftium.core.BlueprintInfo.file : java.lang.String
        dev.craftium.core.BlueprintInfo.name : java.lang.String
        dev.craftium.core.BlueprintInfo.size : dev.craftium.core.Point
        dev.craftium.core.BlueprintInfo.tags : java.lang.String
        dev.craftium.core.BlueprintInfo.thumbnail : java.util.List<java.lang.Integer>
        dev.craftium.core.BridgeCodec$Message.data : java.lang.String
        dev.craftium.core.BridgeCodec$Message.kind : java.lang.String
        dev.craftium.core.BridgeCodec$Message.version : int
        dev.craftium.core.ColourPlacement.block : java.lang.String
        dev.craftium.core.ColourPlacement.point : dev.craftium.core.Point
        dev.craftium.core.Handshake$Hello.capabilities : java.util.Set<java.lang.String>
        dev.craftium.core.Handshake$Hello.protocol : int
        dev.craftium.core.Handshake$Hello.version : java.lang.String
        dev.craftium.core.Options.aboutSet : boolean
        dev.craftium.core.Options.aboutX : double
        dev.craftium.core.Options.aboutY : double
        dev.craftium.core.Options.aboutZ : double
        dev.craftium.core.Options.aiBridge : boolean
        dev.craftium.core.Options.angelPlacement : boolean
        dev.craftium.core.Options.annotationColor : int
        dev.craftium.core.Options.annotationId : java.lang.String
        dev.craftium.core.Options.annotationImageHeight : int
        dev.craftium.core.Options.annotationImageScale : double
        dev.craftium.core.Options.annotationImageWidth : int
        dev.craftium.core.Options.annotationMode : java.lang.String
        dev.craftium.core.Options.annotationNormal : dev.craftium.core.Point
        dev.craftium.core.Options.annotationPixels : java.util.List<java.lang.Integer>
        dev.craftium.core.Options.annotationPoints : java.util.List<dev.craftium.core.Point>
        dev.craftium.core.Options.annotationWidth : int
        dev.craftium.core.Options.assetData : java.lang.String
        dev.craftium.core.Options.author : java.lang.String
        dev.craftium.core.Options.autoSwapToCreative : boolean
        dev.craftium.core.Options.baseChance : double
        dev.craftium.core.Options.billboard : java.lang.String
        dev.craftium.core.Options.biome : java.lang.String
        dev.craftium.core.Options.block : java.lang.String
        dev.craftium.core.Options.blockLimit : int
        dev.craftium.core.Options.blockStep : boolean
        dev.craftium.core.Options.brushFlow : int
        dev.craftium.core.Options.buildSpec : java.lang.String
        dev.craftium.core.Options.builderCells : java.lang.String
        dev.craftium.core.Options.builderFlipAxis : int
        dev.craftium.core.Options.builderFollowsMagic : boolean
        dev.craftium.core.Options.builderKeepExisting : boolean
        dev.craftium.core.Options.builderLiveBox : boolean
        dev.craftium.core.Options.builderMagicCompare : dev.craftium.core.Options$Compare
        dev.craftium.core.Options.builderOffset : dev.craftium.core.Point
        dev.craftium.core.Options.builderPreviewWidth : double
        dev.craftium.core.Options.builderRotation : int
        dev.craftium.core.Options.builderRuler : boolean
        dev.craftium.core.Options.builderSelectionColor : int
        dev.craftium.core.Options.builderSelectionFill : int
        dev.craftium.core.Options.builderSelectionWidth : double
        dev.craftium.core.Options.builderTool : dev.craftium.core.Options$BuilderTool
        dev.craftium.core.Options.bulldozer : boolean
        dev.craftium.core.Options.cameraFlight : boolean
        dev.craftium.core.Options.checkForUpdates : boolean
        dev.craftium.core.Options.colourField : java.util.List<dev.craftium.core.ColourPlacement>
        dev.craftium.core.Options.compare : dev.craftium.core.Options$Compare
        dev.craftium.core.Options.confirmEdits : boolean
        dev.craftium.core.Options.copies : int
        dev.craftium.core.Options.copyEntities : boolean
        dev.craftium.core.Options.copyProperties : boolean
        dev.craftium.core.Options.deferred : boolean
        dev.craftium.core.Options.denoise : boolean
        dev.craftium.core.Options.density : double
        dev.craftium.core.Options.distortDistance : double
        dev.craftium.core.Options.distortSeparate : boolean
        dev.craftium.core.Options.distortSmoothEdges : boolean
        dev.craftium.core.Options.distortX : double
        dev.craftium.core.Options.distortY : double
        dev.craftium.core.Options.distortZ : double
        dev.craftium.core.Options.downloadExports : boolean
        dev.craftium.core.Options.enterToBuild : boolean
        dev.craftium.core.Options.entityGroup : java.lang.String
        dev.craftium.core.Options.entityNbt : java.lang.String
        dev.craftium.core.Options.entityScaleX : double
        dev.craftium.core.Options.entityScaleY : double
        dev.craftium.core.Options.entityScaleZ : double
        dev.craftium.core.Options.entityType : java.lang.String
        dev.craftium.core.Options.entityUuid : java.lang.String
        dev.craftium.core.Options.entityX : double
        dev.craftium.core.Options.entityY : double
        dev.craftium.core.Options.entityZ : double
        dev.craftium.core.Options.expandAmount : int
        dev.craftium.core.Options.expandHitboxes : boolean
        dev.craftium.core.Options.exponent : double
        dev.craftium.core.Options.exposedFaces : int
        dev.craftium.core.Options.extendToGround : boolean
        dev.craftium.core.Options.extrudeCount : int
        dev.craftium.core.Options.extrudeReplaceable : boolean
        dev.craftium.core.Options.farReach : int
        dev.craftium.core.Options.fastPlace : boolean
        dev.craftium.core.Options.fillEdges : boolean
        dev.craftium.core.Options.fillVertically : boolean
        dev.craftium.core.Options.flightMomentumLevel : double
        dev.craftium.core.Options.floodCorners : boolean
        dev.craftium.core.Options.floodDown : boolean
        dev.craftium.core.Options.floodGap : int
        dev.craftium.core.Options.floodHorizontal : boolean
        dev.craftium.core.Options.floodUp : boolean
        dev.craftium.core.Options.flowLength : int
        dev.craftium.core.Options.fluidHeight : int
        dev.craftium.core.Options.fluidQuality : int
        dev.craftium.core.Options.fluidType : dev.craftium.core.Options$FluidType
        dev.craftium.core.Options.flySpeed : float
        dev.craftium.core.Options.fontFile : java.lang.String
        dev.craftium.core.Options.fontName : java.lang.String
        dev.craftium.core.Options.fontSize : int
        dev.craftium.core.Options.forcePlace : boolean
        dev.craftium.core.Options.fractureMaterial : boolean
        dev.craftium.core.Options.fullBlocks : boolean
        dev.craftium.core.Options.gain : double
        dev.craftium.core.Options.gizmoHideOthers : boolean
        dev.craftium.core.Options.gizmoLocal : boolean
        dev.craftium.core.Options.gizmoMode : dev.craftium.core.Options$GizmoMode
        dev.craftium.core.Options.gizmoPitch : double
        dev.craftium.core.Options.gizmoRoll : double
        dev.craftium.core.Options.gizmoTarget : dev.craftium.core.Options$GizmoTarget
        dev.craftium.core.Options.gizmoYaw : double
        dev.craftium.core.Options.heightmap : java.lang.String
        dev.craftium.core.Options.historyPulse : boolean
        dev.craftium.core.Options.historySteps : int
        dev.craftium.core.Options.hollow : boolean
        dev.craftium.core.Options.hoverColor : int
        dev.craftium.core.Options.hoverFill : int
        dev.craftium.core.Options.hoverPreviewLimit : int
        dev.craftium.core.Options.inSelection : boolean
        dev.craftium.core.Options.includeNonSolid : boolean
        dev.craftium.core.Options.infiniteSources : boolean
        dev.craftium.core.Options.innerRadius : double
        dev.craftium.core.Options.invert : boolean
        dev.craftium.core.Options.jitter : double
        dev.craftium.core.Options.keepExisting : boolean
        dev.craftium.core.Options.lacunarity : double
        dev.craftium.core.Options.largeSelectionWarnings : boolean
        dev.craftium.core.Options.lassoDepth : int
        dev.craftium.core.Options.libraryClient : boolean
        dev.craftium.core.Options.liquidOpacity : double
        dev.craftium.core.Options.looped : boolean
        dev.craftium.core.Options.magicPreview : boolean
        dev.craftium.core.Options.mask : java.lang.String
        dev.craftium.core.Options.maskActive : java.util.List<java.lang.String>
        dev.craftium.core.Options.maskBlocks : java.util.List<java.lang.String>
        dev.craftium.core.Options.maskExclude : boolean
        dev.craftium.core.Options.maskExpression : java.lang.String
        dev.craftium.core.Options.maskInBuilder : boolean
        dev.craftium.core.Options.maskOff : boolean
        dev.craftium.core.Options.maskScript : java.lang.String
        dev.craftium.core.Options.maskVariables : java.util.Map<java.lang.String, java.lang.String>
        dev.craftium.core.Options.maskY : boolean
        dev.craftium.core.Options.materialWeights : java.util.List<java.lang.Double>
        dev.craftium.core.Options.materials : java.lang.String
        dev.craftium.core.Options.maxY : int
        dev.craftium.core.Options.meldStrength : double
        dev.craftium.core.Options.mergeBlocks : boolean
        dev.craftium.core.Options.minSpacing : double
        dev.craftium.core.Options.minY : int
        dev.craftium.core.Options.minimumBrightness : double
        dev.craftium.core.Options.mode : dev.craftium.core.Selection$Mode
        dev.craftium.core.Options.modelColumns : int
        dev.craftium.core.Options.modelMode : dev.craftium.core.Options$ModelMode
        dev.craftium.core.Options.modifyAngle : double
        dev.craftium.core.Options.modifyAxis : dev.craftium.core.Options$ModifyAxis
        dev.craftium.core.Options.modifyMode : dev.craftium.core.Options$ModifyMode
        dev.craftium.core.Options.modifyRelative : boolean
        dev.craftium.core.Options.modifyTranslate : boolean
        dev.craftium.core.Options.modifyTranslateScale : double
        dev.craftium.core.Options.modifyTranslateX : double
        dev.craftium.core.Options.modifyTranslateY : double
        dev.craftium.core.Options.modifyTranslateZ : double
        dev.craftium.core.Options.modifyType : dev.craftium.core.Options$RotateType
        dev.craftium.core.Options.name : java.lang.String
        dev.craftium.core.Options.noClip : boolean
        dev.craftium.core.Options.noUpdates : boolean
        dev.craftium.core.Options.nodes : java.util.List<dev.craftium.core.Point>
        dev.craftium.core.Options.noise : dev.craftium.core.Options$Noise
        dev.craftium.core.Options.noise3D : boolean
        dev.craftium.core.Options.noiseScale : double
        dev.craftium.core.Options.octaves : int
        dev.craftium.core.Options.offsetX : int
        dev.craftium.core.Options.offsetY : int
        dev.craftium.core.Options.offsetZ : int
        dev.craftium.core.Options.outline : boolean
        dev.craftium.core.Options.outlineThickness : int
        dev.craftium.core.Options.pasteAir : boolean
        dev.craftium.core.Options.pasteEntities : boolean
        dev.craftium.core.Options.pathMode : dev.craftium.core.Options$PathMode
        dev.craftium.core.Options.pinned : java.util.List<java.lang.String>
        dev.craftium.core.Options.pitch : double
        dev.craftium.core.Options.pivotSet : boolean
        dev.craftium.core.Options.pivotX : double
        dev.craftium.core.Options.pivotY : double
        dev.craftium.core.Options.pivotZ : double
        dev.craftium.core.Options.pixelPerfect : boolean
        dev.craftium.core.Options.polygonSides : int
        dev.craftium.core.Options.position1 : dev.craftium.core.Point
        dev.craftium.core.Options.position2 : dev.craftium.core.Point
        dev.craftium.core.Options.preventInteractions : boolean
        dev.craftium.core.Options.previewColor : int
        dev.craftium.core.Options.previewFill : int
        dev.craftium.core.Options.previewTint : int
        dev.craftium.core.Options.property : java.lang.String
        dev.craftium.core.Options.radius : int
        dev.craftium.core.Options.randomFlip : boolean
        dev.craftium.core.Options.randomTickSpeed : int
        dev.craftium.core.Options.randomYaw : boolean
        dev.craftium.core.Options.reach : dev.craftium.core.Options$ReachMode
        dev.craftium.core.Options.rebuildAirDelay : double
        dev.craftium.core.Options.rebuildDifferentDelay : double
        dev.craftium.core.Options.rebuildHorizontalMultiplier : double
        dev.craftium.core.Options.rebuildMaxDelay : double
        dev.craftium.core.Options.rebuildRandomDelay : double
        dev.craftium.core.Options.rebuildSameDelay : double
        dev.craftium.core.Options.rebuildStartDelay : double
        dev.craftium.core.Options.rebuildVerticalMultiplier : double
        dev.craftium.core.Options.recordPlayerBuilding : boolean
        dev.craftium.core.Options.removeFluidsWithoutSources : boolean
        dev.craftium.core.Options.removedFill : int
        dev.craftium.core.Options.removedTint : int
        dev.craftium.core.Options.replaceMode : boolean
        dev.craftium.core.Options.requireSupport : boolean
        dev.craftium.core.Options.roll : double
        dev.craftium.core.Options.roughenRatio : double
        dev.craftium.core.Options.rulerAxisXColor : int
        dev.craftium.core.Options.rulerAxisYColor : int
        dev.craftium.core.Options.rulerAxisZColor : int
        dev.craftium.core.Options.rulerKnobColor : int
        dev.craftium.core.Options.rulerKnobSelectedColor : int
        dev.craftium.core.Options.rulerLabelSize : double
        dev.craftium.core.Options.rulerLineColor : int
        dev.craftium.core.Options.rulerMode : dev.craftium.core.Options$RulerMode
        dev.craftium.core.Options.rulerSplit : int
        dev.craftium.core.Options.rulerTextBackground : int
        dev.craftium.core.Options.rulerTextColor : int
        dev.craftium.core.Options.scaleX : double
        dev.craftium.core.Options.scaleY : double
        dev.craftium.core.Options.scaleZ : double
        dev.craftium.core.Options.script : java.lang.String
        dev.craftium.core.Options.scriptColours : java.util.List<dev.craftium.core.BlockColour>
        dev.craftium.core.Options.scriptVariables : java.util.Map<java.lang.String, java.lang.String>
        dev.craftium.core.Options.secondary : java.lang.String
        dev.craftium.core.Options.seed : long
        dev.craftium.core.Options.selectCorners : boolean
        dev.craftium.core.Options.selectDown : boolean
        dev.craftium.core.Options.selectHorizontal : boolean
        dev.craftium.core.Options.selectLimit : int
        dev.craftium.core.Options.selectRange : int
        dev.craftium.core.Options.selectUp : boolean
        dev.craftium.core.Options.selectionColor : int
        dev.craftium.core.Options.selectionContact : int
        dev.craftium.core.Options.selectionFill : int
        dev.craftium.core.Options.selectionInsideFill : int
        dev.craftium.core.Options.selectionInsideTint : int
        dev.craftium.core.Options.selectionPoints : java.util.List<dev.craftium.core.Point>
        dev.craftium.core.Options.selectionResize : int
        dev.craftium.core.Options.selectionStyle : dev.craftium.core.Options$SelectionStyle
        dev.craftium.core.Options.selectionTint : int
        dev.craftium.core.Options.selectionWidth : double
        dev.craftium.core.Options.shadeAltitude : double
        dev.craftium.core.Options.shadeAmbientOcclusion : double
        dev.craftium.core.Options.shadeAutomatic : boolean
        dev.craftium.core.Options.shadeAzimuth : double
        dev.craftium.core.Options.shadeBounces : int
        dev.craftium.core.Options.shadeDither : double
        dev.craftium.core.Options.shadeExposure : double
        dev.craftium.core.Options.shadeFrom : dev.craftium.core.Options$ShadeFrom
        dev.craftium.core.Options.shadeFullBlocks : boolean
        dev.craftium.core.Options.shadeLights : java.util.List<dev.craftium.core.ShadeLight>
        dev.craftium.core.Options.shadeNoBlockEntities : boolean
        dev.craftium.core.Options.shadeNoGlazed : boolean
        dev.craftium.core.Options.shadeNoOres : boolean
        dev.craftium.core.Options.shadeOpaque : boolean
        dev.craftium.core.Options.shadeRayLength : int
        dev.craftium.core.Options.shadeSamples : int
        dev.craftium.core.Options.shadeSun : boolean
        dev.craftium.core.Options.shape : dev.craftium.core.BrushMath$Shape
        dev.craftium.core.Options.shapeKind : dev.craftium.core.Options$ShapeKind
        dev.craftium.core.Options.shatterAxis : dev.craftium.core.Options$ShatterAxis
        dev.craftium.core.Options.shatterWidth : double
        dev.craftium.core.Options.showAnnotations : boolean
        dev.craftium.core.Options.showAxisGizmo : boolean
        dev.craftium.core.Options.showBiomes : boolean
        dev.craftium.core.Options.showCollisionMesh : boolean
        dev.craftium.core.Options.showDisplayGizmos : boolean
        dev.craftium.core.Options.showKeyHints : boolean
        dev.craftium.core.Options.showLightBlocks : boolean
        dev.craftium.core.Options.showMarkerGizmos : boolean
        dev.craftium.core.Options.showSelectionContact : boolean
        dev.craftium.core.Options.showStructureVoid : boolean
        dev.craftium.core.Options.sizeChain : boolean
        dev.craftium.core.Options.sizeX : double
        dev.craftium.core.Options.sizeY : double
        dev.craftium.core.Options.sizeZ : double
        dev.craftium.core.Options.slack : double
        dev.craftium.core.Options.smartExtrude : boolean
        dev.craftium.core.Options.smoothFixEdges : boolean
        dev.craftium.core.Options.smoothMode : dev.craftium.core.Options$SmoothMode
        dev.craftium.core.Options.smoothRatio : double
        dev.craftium.core.Options.smoothing : double
        dev.craftium.core.Options.solidFluids : boolean
        dev.craftium.core.Options.stackX : int
        dev.craftium.core.Options.stackY : int
        dev.craftium.core.Options.stackZ : int
        dev.craftium.core.Options.stickyFlight : boolean
        dev.craftium.core.Options.strength : double
        dev.craftium.core.Options.strokeAlign : dev.craftium.core.Options$StrokeAlign
        dev.craftium.core.Options.structure : dev.craftium.core.StructureSettings
        dev.craftium.core.Options.surfaceOnly : boolean
        dev.craftium.core.Options.symmetryHalfX : boolean
        dev.craftium.core.Options.symmetryHalfY : boolean
        dev.craftium.core.Options.symmetryHalfZ : boolean
        dev.craftium.core.Options.symmetryOrigin : dev.craftium.core.Point
        dev.craftium.core.Options.symmetryRotations : int
        dev.craftium.core.Options.symmetryX : boolean
        dev.craftium.core.Options.symmetryY : boolean
        dev.craftium.core.Options.symmetryZ : boolean
        dev.craftium.core.Options.tags : java.lang.String
        dev.craftium.core.Options.text : java.lang.String
        dev.craftium.core.Options.textShadow : boolean
        dev.craftium.core.Options.textThickness : int
        dev.craftium.core.Options.threshold : double
        dev.craftium.core.Options.tinker : boolean
        dev.craftium.core.Options.tinkerIndex : int
        dev.craftium.core.Options.tinkerManual : boolean
        dev.craftium.core.Options.tinkerX : double
        dev.craftium.core.Options.tinkerY : double
        dev.craftium.core.Options.tinkerZ : double
        dev.craftium.core.Options.tool : dev.craftium.core.Tool
        dev.craftium.core.Options.turnBlocks : boolean
        dev.craftium.core.Options.typeReplace : boolean
        dev.craftium.core.Options.uiAccent : int
        dev.craftium.core.Options.uiBackground : int
        dev.craftium.core.Options.uiBorder : int
        dev.craftium.core.Options.uiMuted : int
        dev.craftium.core.Options.uiPanel : int
        dev.craftium.core.Options.uiText : int
        dev.craftium.core.Options.unlockRotation : boolean
        dev.craftium.core.Options.useBuiltinFont : boolean
        dev.craftium.core.Options.useStairsAndSlabs : boolean
        dev.craftium.core.Options.visualizeBiome : boolean
        dev.craftium.core.Options.worldTime : int
        dev.craftium.core.Options.worldToggle : boolean
        dev.craftium.core.Options.yaw : double
        dev.craftium.core.Point.x : int
        dev.craftium.core.Point.y : int
        dev.craftium.core.Point.z : int
        dev.craftium.core.ShadeLight.intensity : double
        dev.craftium.core.ShadeLight.x : double
        dev.craftium.core.ShadeLight.y : double
        dev.craftium.core.ShadeLight.z : double
        dev.craftium.core.StructureSettings.airToVoid : boolean
        dev.craftium.core.StructureSettings.format : java.lang.String
        dev.craftium.core.StructureSettings.includeBlocks : boolean
        dev.craftium.core.StructureSettings.includeEntities : boolean
        dev.craftium.core.StructureSettings.integrity : int
        dev.craftium.core.StructureSettings.javaFile : java.lang.String
        dev.craftium.core.StructureSettings.mirror : java.lang.String
        dev.craftium.core.StructureSettings.name : java.lang.String
        dev.craftium.core.StructureSettings.offsetX : int
        dev.craftium.core.StructureSettings.offsetY : int
        dev.craftium.core.StructureSettings.offsetZ : int
        dev.craftium.core.StructureSettings.rotation : int
        dev.craftium.core.StructureSettings.seed : long
        dev.craftium.core.StructureSettings.showAxes : boolean
        dev.craftium.core.StructureSettings.showBounds : boolean
        dev.craftium.core.StructureSettings.showInvisible : boolean
        dev.craftium.core.StructureSettings.sizeX : int
        dev.craftium.core.StructureSettings.sizeY : int
        dev.craftium.core.StructureSettings.sizeZ : int
        dev.craftium.core.StructureSettings.source : java.lang.String
        dev.craftium.net.Packets$Download.data : java.lang.String
        dev.craftium.net.Packets$Download.folder : java.lang.String
        dev.craftium.net.Packets$Download.metadata : java.lang.String
        dev.craftium.net.Packets$Download.name : java.lang.String
        dev.craftium.net.Packets$Request.action : java.lang.String
        dev.craftium.net.Packets$Request.normal : dev.craftium.core.Point
        dev.craftium.net.Packets$Request.options : dev.craftium.core.Options
        dev.craftium.net.Packets$Request.strokeId : long
        dev.craftium.net.Packets$Request.target : dev.craftium.core.Point
        dev.craftium.net.Packets$Status.aiPendingData : java.lang.String
        dev.craftium.net.Packets$Status.aiUndo : int
        dev.craftium.net.Packets$Status.aiUndoName : java.lang.String
        dev.craftium.net.Packets$Status.annotations : java.util.List<dev.craftium.core.Annotation>
        dev.craftium.net.Packets$Status.blueprints : java.util.List<dev.craftium.core.BlueprintInfo>
        dev.craftium.net.Packets$Status.busy : boolean
        dev.craftium.net.Packets$Status.clipboardData : java.lang.String
        dev.craftium.net.Packets$Status.clipboardEntityCount : int
        dev.craftium.net.Packets$Status.clipboardGhost : java.lang.String
        dev.craftium.net.Packets$Status.clipboardOrigin : dev.craftium.core.Point
        dev.craftium.net.Packets$Status.clipboardSize : int
        dev.craftium.net.Packets$Status.entityNbt : java.lang.String
        dev.craftium.net.Packets$Status.entityUuid : java.lang.String
        dev.craftium.net.Packets$Status.error : boolean
        dev.craftium.net.Packets$Status.history : java.util.List<java.lang.String>
        dev.craftium.net.Packets$Status.max : dev.craftium.core.Point
        dev.craftium.net.Packets$Status.message : java.lang.String
        dev.craftium.net.Packets$Status.min : dev.craftium.core.Point
        dev.craftium.net.Packets$Status.pendingData : java.lang.String
        dev.craftium.net.Packets$Status.pivotUpdate : java.lang.String
        dev.craftium.net.Packets$Status.pulse : java.lang.String
        dev.craftium.net.Packets$Status.redo : int
        dev.craftium.net.Packets$Status.redoAge : long
        dev.craftium.net.Packets$Status.redoName : java.lang.String
        dev.craftium.net.Packets$Status.restore : java.lang.String
        dev.craftium.net.Packets$Status.selectionData : java.lang.String
        dev.craftium.net.Packets$Status.selectionSize : int
        dev.craftium.net.Packets$Status.undo : int
        dev.craftium.net.Packets$Status.undoAge : long
        dev.craftium.net.Packets$Status.undoName : java.lang.String
        enum dev.craftium.core.BrushMath$Shape.CONE
        enum dev.craftium.core.BrushMath$Shape.CUBE
        enum dev.craftium.core.BrushMath$Shape.CYLINDER
        enum dev.craftium.core.BrushMath$Shape.DISK
        enum dev.craftium.core.BrushMath$Shape.HEMISPHERE
        enum dev.craftium.core.BrushMath$Shape.OCTAHEDRON
        enum dev.craftium.core.BrushMath$Shape.PLANE
        enum dev.craftium.core.BrushMath$Shape.POLYGON
        enum dev.craftium.core.BrushMath$Shape.PYRAMID
        enum dev.craftium.core.BrushMath$Shape.SPHERE
        enum dev.craftium.core.BrushMath$Shape.TORUS
        enum dev.craftium.core.BrushMath$Shape.TUBE
        enum dev.craftium.core.Options$BuilderTool.CLONE
        enum dev.craftium.core.Options$BuilderTool.ERASE
        enum dev.craftium.core.Options$BuilderTool.EXTRUDE
        enum dev.craftium.core.Options$BuilderTool.MOVE
        enum dev.craftium.core.Options$BuilderTool.SMEAR
        enum dev.craftium.core.Options$BuilderTool.STACK
        enum dev.craftium.core.Options$BuilderTool.SYMMETRY
        enum dev.craftium.core.Options$Compare.ANY
        enum dev.craftium.core.Options$Compare.BLOCK
        enum dev.craftium.core.Options$Compare.NBT
        enum dev.craftium.core.Options$Compare.STATE
        enum dev.craftium.core.Options$Compare.TAG
        enum dev.craftium.core.Options$FluidType.LAVA
        enum dev.craftium.core.Options$FluidType.SNOW
        enum dev.craftium.core.Options$FluidType.WATER
        enum dev.craftium.core.Options$GizmoMode.MOVE
        enum dev.craftium.core.Options$GizmoMode.ROTATE
        enum dev.craftium.core.Options$GizmoMode.SCALE
        enum dev.craftium.core.Options$GizmoTarget.AUTO
        enum dev.craftium.core.Options$GizmoTarget.ENTITY
        enum dev.craftium.core.Options$GizmoTarget.SELECTION
        enum dev.craftium.core.Options$ModelMode.BEZIER
        enum dev.craftium.core.Options$ModelMode.CATMULL_ROM
        enum dev.craftium.core.Options$ModelMode.CONVEX_HULL
        enum dev.craftium.core.Options$ModelMode.FLAT
        enum dev.craftium.core.Options$ModelMode.SMART
        enum dev.craftium.core.Options$ModelMode.TRIANGLE_FAN
        enum dev.craftium.core.Options$ModelMode.TRIANGLE_STRIP
        enum dev.craftium.core.Options$ModifyAxis.X
        enum dev.craftium.core.Options$ModifyAxis.Y
        enum dev.craftium.core.Options$ModifyAxis.Z
        enum dev.craftium.core.Options$ModifyMode.REVOLVE
        enum dev.craftium.core.Options$ModifyMode.ROTATE_COPIES
        enum dev.craftium.core.Options$ModifyMode.TRANSLATE_COPIES
        enum dev.craftium.core.Options$ModifyMode.TWIST
        enum dev.craftium.core.Options$Noise.METABALLS
        enum dev.craftium.core.Options$Noise.PERLIN
        enum dev.craftium.core.Options$Noise.SIMPLEX
        enum dev.craftium.core.Options$Noise.SPLATTER
        enum dev.craftium.core.Options$Noise.VALUE
        enum dev.craftium.core.Options$Noise.VORONOI_EDGES
        enum dev.craftium.core.Options$Noise.WHITE
        enum dev.craftium.core.Options$Noise.WORLEY
        enum dev.craftium.core.Options$PathMode.BEZIER
        enum dev.craftium.core.Options$PathMode.BRESENHAM
        enum dev.craftium.core.Options$PathMode.CATENARY
        enum dev.craftium.core.Options$PathMode.CATMULL_ROM
        enum dev.craftium.core.Options$PathMode.DDA
        enum dev.craftium.core.Options$ReachMode.FAR
        enum dev.craftium.core.Options$ReachMode.INFINITE
        enum dev.craftium.core.Options$ReachMode.NORMAL
        enum dev.craftium.core.Options$RotateType.CUSTOM_ANGLE
        enum dev.craftium.core.Options$RotateType.EQUIDISTANT
        enum dev.craftium.core.Options$RulerMode.CIRCLE
        enum dev.craftium.core.Options$RulerMode.LINE
        enum dev.craftium.core.Options$SelectionStyle.ALL_EDGES
        enum dev.craftium.core.Options$SelectionStyle.OUTLINE
        enum dev.craftium.core.Options$SelectionStyle.SILHOUETTE
        enum dev.craftium.core.Options$ShadeFrom.CUSTOM_POSITIONS
        enum dev.craftium.core.Options$ShadeFrom.PLAYER_POSITION
        enum dev.craftium.core.Options$ShadeFrom.SUN_ANGLE
        enum dev.craftium.core.Options$ShapeKind.BRUSH
        enum dev.craftium.core.Options$ShapeKind.CONE
        enum dev.craftium.core.Options$ShapeKind.CUBOID
        enum dev.craftium.core.Options$ShapeKind.CYLINDER
        enum dev.craftium.core.Options$ShapeKind.DISK
        enum dev.craftium.core.Options$ShapeKind.DODECAHEDRON
        enum dev.craftium.core.Options$ShapeKind.ICOSAHEDRON
        enum dev.craftium.core.Options$ShapeKind.OCTAHEDRON
        enum dev.craftium.core.Options$ShapeKind.PLANE
        enum dev.craftium.core.Options$ShapeKind.POLYGON
        enum dev.craftium.core.Options$ShapeKind.PYRAMID
        enum dev.craftium.core.Options$ShapeKind.SPHERE
        enum dev.craftium.core.Options$ShapeKind.SPIRAL
        enum dev.craftium.core.Options$ShapeKind.SUPERSPHERE
        enum dev.craftium.core.Options$ShapeKind.TORUS
        enum dev.craftium.core.Options$ShapeKind.TUBE
        enum dev.craftium.core.Options$ShatterAxis.ALL
        enum dev.craftium.core.Options$ShatterAxis.X
        enum dev.craftium.core.Options$ShatterAxis.Y
        enum dev.craftium.core.Options$ShatterAxis.Z
        enum dev.craftium.core.Options$SmoothMode.GROW
        enum dev.craftium.core.Options$SmoothMode.MELT
        enum dev.craftium.core.Options$SmoothMode.STABLE
        enum dev.craftium.core.Options$StrokeAlign.CENTER
        enum dev.craftium.core.Options$StrokeAlign.INSIDE
        enum dev.craftium.core.Options$StrokeAlign.OUTSIDE
        enum dev.craftium.core.Selection$Mode.ADD
        enum dev.craftium.core.Selection$Mode.INTERSECT
        enum dev.craftium.core.Selection$Mode.REPLACE
        enum dev.craftium.core.Selection$Mode.SUBTRACT
        enum dev.craftium.core.Tool.ANNOTATION
        enum dev.craftium.core.Tool.BIOME_PAINTER
        enum dev.craftium.core.Tool.BOX_SELECT
        enum dev.craftium.core.Tool.CLENTAMINATOR
        enum dev.craftium.core.Tool.DISTORT
        enum dev.craftium.core.Tool.ELEVATION
        enum dev.craftium.core.Tool.ERASE
        enum dev.craftium.core.Tool.EXTRUDE
        enum dev.craftium.core.Tool.FLATTEN
        enum dev.craftium.core.Tool.FLOODFILL
        enum dev.craftium.core.Tool.FLUID_BALL
        enum dev.craftium.core.Tool.FLUID_BLOCK
        enum dev.craftium.core.Tool.FREEHAND_DRAW
        enum dev.craftium.core.Tool.FREEHAND_SELECT
        enum dev.craftium.core.Tool.GRADIENT_PAINTER
        enum dev.craftium.core.Tool.LASSO_SELECT
        enum dev.craftium.core.Tool.LINE
        enum dev.craftium.core.Tool.MAGIC_SELECT
        enum dev.craftium.core.Tool.MELT
        enum dev.craftium.core.Tool.MODELLING
        enum dev.craftium.core.Tool.MODIFY
        enum dev.craftium.core.Tool.MOVE
        enum dev.craftium.core.Tool.NOISE_PAINTER
        enum dev.craftium.core.Tool.PAINTER
        enum dev.craftium.core.Tool.PAINT_BUCKET
        enum dev.craftium.core.Tool.PATH
        enum dev.craftium.core.Tool.PIVOT
        enum dev.craftium.core.Tool.ROCK
        enum dev.craftium.core.Tool.ROTATE
        enum dev.craftium.core.Tool.ROUGHEN
        enum dev.craftium.core.Tool.RULER
        enum dev.craftium.core.Tool.SCALE
        enum dev.craftium.core.Tool.SCRIPT_BRUSH
        enum dev.craftium.core.Tool.SCULPT_DRAW
        enum dev.craftium.core.Tool.SHAPE
        enum dev.craftium.core.Tool.SHATTER
        enum dev.craftium.core.Tool.SLOPE
        enum dev.craftium.core.Tool.SMEAR
        enum dev.craftium.core.Tool.SMOOTH
        enum dev.craftium.core.Tool.STAMP
        enum dev.craftium.core.Tool.TEXT
        enum dev.craftium.core.Tool.TINKER
        enum dev.craftium.core.Tool.WELD
        """;
}
