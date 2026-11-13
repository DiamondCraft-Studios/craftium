package dev.craftium.server;

import dev.craftium.core.Point;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** An AI or jigsaw preview of the largest size still fits in the status packet. */
class GhostCodecLimitTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void theLargestPreviewFitsTheStatusPacketAtTheEdgeOfTheWorld() {
        var air = new BlockSnapshot(Blocks.AIR.defaultBlockState());
        var kinds = List.of(Blocks.STONE, Blocks.GLASS, Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.BRICKS, Blocks.SEA_LANTERN);
        var changes = new ArrayList<Session.Change>();
        for (int i = 0; i < GhostCodec.AI_LIMIT + 1000; i++)
            changes.add(new Session.Change(new Point(29_999_000 + i % 400, 300 + i % 90, -29_999_000 + i / 400), air, new BlockSnapshot(kinds.get(i % kinds.size()).defaultBlockState())));
        String json = GhostCodec.encodeEdit(new Session.Edit("big", changes, List.of(), 0), GhostCodec.AI_LIMIT);
        assertTrue(json.length() < 1_300_000, "a preview of " + GhostCodec.AI_LIMIT + " blocks was " + json.length() + " characters");
        System.out.println("GHOST_LIMIT " + GhostCodec.AI_LIMIT + " blocks = " + json.length() + " characters");
    }
}
