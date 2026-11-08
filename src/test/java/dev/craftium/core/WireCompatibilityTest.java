package dev.craftium.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins what an older Craftium wrote to disk and sent over the network. {@link Options} travels as JSON (field names) and the tool
 * travels as its enum name; both are also stored in saved settings, presets and Minecraft's own key mapping ids. These tests fail when
 * a field or tool is renamed, removed or changes its JSON shape, so any refactor must keep them passing. Adding fields or tools is allowed.
 * The lists below were taken from 0.9.0-beta.1.
 */
class WireCompatibilityTest {
    private static final Gson JSON = new Gson();

    /** Every {@link Tool} name 0.9.0-beta.1 could send. */
    private static final String TOOLS = """
        MAGIC_SELECT
        BOX_SELECT
        FREEHAND_SELECT
        RULER
        PAINTER
        NOISE_PAINTER
        BIOME_PAINTER
        CLENTAMINATOR
        FREEHAND_DRAW
        SCULPT_DRAW
        ROCK
        WELD
        MELT
        TEXT
        ELEVATION
        FLATTEN
        SLOPE
        SMOOTH
        DISTORT
        ROUGHEN
        SHATTER
        EXTRUDE
        SHAPE
        LINE
        ERASE
        TINKER
        LASSO_SELECT
        GRADIENT_PAINTER
        SCRIPT_BRUSH
        MODELLING
        STAMP
        PATH
        FLOODFILL
        FLUID_BALL
        MODIFY
        ANNOTATION
        SMEAR
        MOVE
        ROTATE
        SCALE
        PIVOT
        """;

    /** Every top-level field {@link Options} wrote in 0.9.0-beta.1, with its JSON shape (primitive, array or object). */
    private static final String FIELDS = """
        aboutSet primitive
        aboutX primitive
        aboutY primitive
        aboutZ primitive
        aiBridge primitive
        angelPlacement primitive
        annotationColor primitive
        annotationId primitive
        annotationImageHeight primitive
        annotationImageScale primitive
        annotationImageWidth primitive
        annotationMode primitive
        annotationNormal object
        annotationPixels array
        annotationPoints array
        annotationWidth primitive
        assetData primitive
        author primitive
        autoSwapToCreative primitive
        baseChance primitive
        billboard primitive
        biome primitive
        block primitive
        blockLimit primitive
        blockStep primitive
        brushFlow primitive
        buildSpec primitive
        builderCells primitive
        builderFlipAxis primitive
        builderFollowsMagic primitive
        builderKeepExisting primitive
        builderLiveBox primitive
        builderMagicCompare primitive
        builderOffset object
        builderPreviewWidth primitive
        builderRotation primitive
        builderRuler primitive
        builderSelectionColor primitive
        builderSelectionFill primitive
        builderSelectionWidth primitive
        builderTool primitive
        bulldozer primitive
        cameraFlight primitive
        colourField array
        compare primitive
        confirmEdits primitive
        copies primitive
        copyEntities primitive
        copyProperties primitive
        deferred primitive
        denoise primitive
        density primitive
        distortDistance primitive
        distortSeparate primitive
        distortSmoothEdges primitive
        distortX primitive
        distortY primitive
        distortZ primitive
        downloadExports primitive
        enterToBuild primitive
        entityGroup primitive
        entityNbt primitive
        entityScaleX primitive
        entityScaleY primitive
        entityScaleZ primitive
        entityType primitive
        entityUuid primitive
        entityX primitive
        entityY primitive
        entityZ primitive
        expandAmount primitive
        expandHitboxes primitive
        exponent primitive
        exposedFaces primitive
        extendToGround primitive
        extrudeCount primitive
        extrudeReplaceable primitive
        farReach primitive
        fastPlace primitive
        fillEdges primitive
        fillVertically primitive
        flightMomentumLevel primitive
        floodCorners primitive
        floodDown primitive
        floodGap primitive
        floodHorizontal primitive
        floodUp primitive
        flowLength primitive
        fluidHeight primitive
        fluidQuality primitive
        fluidType primitive
        flySpeed primitive
        fontFile primitive
        fontName primitive
        fontSize primitive
        forcePlace primitive
        fractureMaterial primitive
        fullBlocks primitive
        gain primitive
        gizmoHideOthers primitive
        gizmoLocal primitive
        gizmoMode primitive
        gizmoPitch primitive
        gizmoRoll primitive
        gizmoTarget primitive
        gizmoYaw primitive
        heightmap primitive
        historyPulse primitive
        historySteps primitive
        hollow primitive
        hoverColor primitive
        hoverFill primitive
        hoverPreviewLimit primitive
        inSelection primitive
        includeNonSolid primitive
        infiniteSources primitive
        innerRadius primitive
        invert primitive
        jitter primitive
        keepExisting primitive
        lacunarity primitive
        largeSelectionWarnings primitive
        lassoDepth primitive
        libraryClient primitive
        liquidOpacity primitive
        looped primitive
        magicPreview primitive
        mask primitive
        maskActive array
        maskBlocks array
        maskExclude primitive
        maskExpression primitive
        maskInBuilder primitive
        maskOff primitive
        maskScript primitive
        maskVariables object
        maskY primitive
        materialWeights array
        materials primitive
        maxY primitive
        meldStrength primitive
        mergeBlocks primitive
        minSpacing primitive
        minY primitive
        minimumBrightness primitive
        mode primitive
        modelColumns primitive
        modelMode primitive
        modifyAngle primitive
        modifyAxis primitive
        modifyMode primitive
        modifyRelative primitive
        modifyTranslate primitive
        modifyTranslateScale primitive
        modifyTranslateX primitive
        modifyTranslateY primitive
        modifyTranslateZ primitive
        modifyType primitive
        name primitive
        noClip primitive
        noUpdates primitive
        nodes array
        noise primitive
        noise3D primitive
        noiseScale primitive
        octaves primitive
        offsetX primitive
        offsetY primitive
        offsetZ primitive
        outline primitive
        outlineThickness primitive
        pasteAir primitive
        pasteEntities primitive
        pathMode primitive
        pinned array
        pitch primitive
        pivotSet primitive
        pivotX primitive
        pivotY primitive
        pivotZ primitive
        pixelPerfect primitive
        polygonSides primitive
        position1 object
        position2 object
        preventInteractions primitive
        previewColor primitive
        previewFill primitive
        previewTint primitive
        property primitive
        radius primitive
        randomFlip primitive
        randomTickSpeed primitive
        randomYaw primitive
        reach primitive
        rebuildAirDelay primitive
        rebuildDifferentDelay primitive
        rebuildHorizontalMultiplier primitive
        rebuildMaxDelay primitive
        rebuildRandomDelay primitive
        rebuildSameDelay primitive
        rebuildStartDelay primitive
        rebuildVerticalMultiplier primitive
        recordPlayerBuilding primitive
        removeFluidsWithoutSources primitive
        removedFill primitive
        removedTint primitive
        replaceMode primitive
        requireSupport primitive
        roll primitive
        roughenRatio primitive
        rulerAxisXColor primitive
        rulerAxisYColor primitive
        rulerAxisZColor primitive
        rulerKnobColor primitive
        rulerKnobSelectedColor primitive
        rulerLabelSize primitive
        rulerLineColor primitive
        rulerMode primitive
        rulerSplit primitive
        rulerTextBackground primitive
        rulerTextColor primitive
        scaleX primitive
        scaleY primitive
        scaleZ primitive
        script primitive
        scriptColours array
        scriptVariables object
        secondary primitive
        seed primitive
        selectCorners primitive
        selectDown primitive
        selectHorizontal primitive
        selectLimit primitive
        selectRange primitive
        selectUp primitive
        selectionColor primitive
        selectionContact primitive
        selectionFill primitive
        selectionInsideFill primitive
        selectionInsideTint primitive
        selectionPoints array
        selectionResize primitive
        selectionStyle primitive
        selectionTint primitive
        selectionWidth primitive
        shadeAltitude primitive
        shadeAmbientOcclusion primitive
        shadeAutomatic primitive
        shadeAzimuth primitive
        shadeBounces primitive
        shadeDither primitive
        shadeExposure primitive
        shadeFrom primitive
        shadeFullBlocks primitive
        shadeLights array
        shadeNoBlockEntities primitive
        shadeNoGlazed primitive
        shadeNoOres primitive
        shadeOpaque primitive
        shadeRayLength primitive
        shadeSamples primitive
        shadeSun primitive
        shape primitive
        shapeKind primitive
        shatterAxis primitive
        shatterWidth primitive
        showAnnotations primitive
        showAxisGizmo primitive
        showBiomes primitive
        showCollisionMesh primitive
        showDisplayGizmos primitive
        showKeyHints primitive
        showLightBlocks primitive
        showMarkerGizmos primitive
        showSelectionContact primitive
        showStructureVoid primitive
        sizeChain primitive
        sizeX primitive
        sizeY primitive
        sizeZ primitive
        slack primitive
        smartExtrude primitive
        smoothFixEdges primitive
        smoothMode primitive
        smoothRatio primitive
        smoothing primitive
        solidFluids primitive
        stackX primitive
        stackY primitive
        stackZ primitive
        stickyFlight primitive
        strength primitive
        strokeAlign primitive
        structure object
        surfaceOnly primitive
        symmetryHalfX primitive
        symmetryHalfY primitive
        symmetryHalfZ primitive
        symmetryOrigin object
        symmetryRotations primitive
        symmetryX primitive
        symmetryY primitive
        symmetryZ primitive
        tags primitive
        text primitive
        textShadow primitive
        textThickness primitive
        threshold primitive
        tinker primitive
        tinkerIndex primitive
        tinkerManual primitive
        tinkerX primitive
        tinkerY primitive
        tinkerZ primitive
        tool primitive
        turnBlocks primitive
        typeReplace primitive
        uiAccent primitive
        uiBackground primitive
        uiBorder primitive
        uiMuted primitive
        uiPanel primitive
        uiText primitive
        unlockRotation primitive
        useBuiltinFont primitive
        useStairsAndSlabs primitive
        visualizeBiome primitive
        worldTime primitive
        worldToggle primitive
        yaw primitive
        """;

    private static List<String> lines(String text) { return text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList(); }

    private static String shape(JsonElement element) {
        return element.isJsonPrimitive() ? "primitive" : element.isJsonArray() ? "array" : element.isJsonObject() ? "object" : "null";
    }

    @Test void everyToolNameFromThePreviousReleaseStillExists() {
        var current = Arrays.stream(Tool.values()).map(Enum::name).collect(Collectors.toSet());
        for (String name : lines(TOOLS)) assertTrue(current.contains(name), "Tool " + name + " was removed or renamed; saved settings and servers still use that name");
    }

    @Test void toolsStillDecodeFromTheirNames() {
        for (String name : lines(TOOLS)) {
            var options = JSON.fromJson("{\"tool\":\"" + name + "\"}", Options.class);
            assertEquals(Tool.valueOf(name), options.tool, name);
        }
    }

    @Test void optionsStillWriteEveryFieldTheyUsedTo() {
        var written = JSON.toJsonTree(new Options()).getAsJsonObject();
        for (String entry : lines(FIELDS)) {
            String[] parts = entry.split(" ");
            assertTrue(written.has(parts[0]), "Options field " + parts[0] + " was removed or renamed; saved settings and older servers use it");
            assertEquals(parts[1], shape(written.get(parts[0])), "Options field " + parts[0] + " changed its JSON shape");
        }
    }

    @Test void aSavedSettingsFileFromThePreviousReleaseStillLoads() {
        // A trimmed craftium.json as 0.9.0-beta.1 saves it: only some fields, in any order, plus one this version has never heard of.
        String saved = """
            {"tool":"NOISE_PAINTER","radius":7,"strength":2.5,"block":"minecraft:andesite","secondary":"minecraft:stone",
             "shape":"SPHERE","mode":"REPLACE","seed":1234,"hollow":true,"outlineThickness":2,"script":"return blocks.stone",
             "scriptVariables":{"Width":"3"},"maskActive":["minecraft:dirt"],"position1":{"x":1,"y":2,"z":3},"someFieldFromTheFuture":5}
            """;
        var options = JSON.fromJson(saved, Options.class);
        options.validate();
        assertEquals(Tool.NOISE_PAINTER, options.tool);
        assertEquals(7, options.radius);
        assertEquals(2.5, options.strength);
        assertEquals("minecraft:andesite", options.block);
        assertEquals(1234L, options.seed);
        assertTrue(options.hollow);
        assertEquals(2, options.outlineThickness);
        assertEquals("3", options.scriptVariables.get("Width"));
        assertEquals(List.of("minecraft:dirt"), options.maskActive);
        assertEquals(new Point(1, 2, 3), options.position1);
    }

    @Test void savingAndLoadingKeepsEveryField() {
        var original = new Options();
        original.tool = Tool.SCRIPT_BRUSH;
        original.radius = 9;
        original.block = "minecraft:gold_block";
        var tree = JSON.toJsonTree(original);
        var again = JSON.toJsonTree(JSON.fromJson(tree, Options.class));
        assertEquals(tree, again);
    }

    @Test void everyToolHasAKeyMappingIdMinecraftCanStore() {
        // CraftiumClient registers "key.craftium.tool.<lowercase name>"; players' options.txt keeps these ids.
        for (Tool tool : Tool.values()) assertTrue(tool.name().toLowerCase(Locale.ROOT).matches("[a-z_]+"), tool.name());
    }
}
