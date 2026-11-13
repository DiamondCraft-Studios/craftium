package dev.craftium.server;

import dev.craftium.block.MissingBlockEntity;
import dev.craftium.core.MissingBlockTable;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Missing Block does on a live server that unit tests cannot reach, because it needs the real registry and a world: which blocks the
 * comparison modes treat as the same, what a bucket fill takes, how masks pick them, and how an import and an export treat them.
 * Returns what went wrong, one line each; an empty list means everything held. The same file is in each version's game tests.
 */
public final class MissingBlocksServerCheck {
    private MissingBlocksServerCheck() {}

    public static List<String> run(MinecraftServer server) {
        var failures = new ArrayList<String>();
        try { checks(server, failures); } catch (RuntimeException e) { failures.add("threw " + e + " at " + e.getStackTrace()[0]); }
        return failures;
    }

    private static void expect(List<String> failures, boolean ok, String what) { if (!ok) failures.add(what); }

    private static void checks(MinecraftServer server, List<String> failures) {
        var level = server.overworld();
        var player = server.getPlayerList().getPlayers().getFirst();
        var table = MissingBlocks.table();
        int marble = table.intern("fakemod:marble[axis=y]"), slab = table.intern("fakemod:slab");
        // blocks of that kind may already be in the world, from an earlier part of the same test
        int already = MissingBlocks.loadedAround(player, MissingBlockTable.Entry.parse("fakemod:marble[axis=y]"), 1000).size();
        // a row along x at one height, the three marble blocks touching the three slab blocks, and a stone block at the end
        var base = player.blockPosition().offset(-6, 6, 10);
        for (int i = 0; i < 7; i++) {
            var at = base.offset(i, 0, 0);
            if (i < 6) {
                level.setBlock(at, MissingBlocks.BLOCK.defaultBlockState(), 3);
                if (level.getBlockEntity(at) instanceof MissingBlockEntity entity) entity.set(i < 3 ? marble : slab, null);
            } else level.setBlock(at, Blocks.STONE.defaultBlockState(), 3);
        }
        Point[] p = new Point[7];
        for (int i = 0; i < 7; i++) p[i] = new Point(base.getX() + i, base.getY(), base.getZ());

        var session = new Session();
        var plan = new EditPlanner(level, session, new Options());

        // identity: the number of the original block, -1 for anything else
        expect(failures, AdvancedTools.missingKey(plan, p[0]) == marble && AdvancedTools.missingKey(plan, p[3]) == slab && AdvancedTools.missingKey(plan, p[6]) == -1, "missingKey names the original block");
        var origin = plan.state(p[0]);
        for (var compare : List.of(Options.Compare.BLOCK, Options.Compare.STATE, Options.Compare.TAG)) {
            expect(failures, AdvancedTools.matches(compare, plan, origin, marble, null, p[1]), compare + " matches a placeholder for the same block");
            expect(failures, !AdvancedTools.matches(compare, plan, origin, marble, null, p[3]), compare + " does not run on into a touching placeholder for another block");
            expect(failures, !AdvancedTools.matches(compare, plan, origin, marble, null, p[6]), compare + " does not match stone");
        }
        expect(failures, AdvancedTools.matches(Options.Compare.ANY, plan, origin, marble, null, p[3]), "ANY matches every block that is not air");
        expect(failures, AdvancedTools.matches(Options.Compare.BLOCK, plan, origin, -1, null, p[3]), "with no number a placeholder is any placeholder");

        // a bucket fill from the first block takes the three marble blocks and stops at the slab blocks beside them
        try {
            var o = new Options();
            var bucket = new EditPlanner(level, session, o);
            BuiltinTools.bucketFill(player, bucket, o, p[0], Options.Compare.BLOCK, "Bucket");
            var taken = new ArrayList<Point>(bucket.changes.keySet());
            expect(failures, taken.size() == 3 && taken.containsAll(List.of(p[0], p[1], p[2])), "the bucket took " + taken + " instead of the three marble blocks");
        } catch (RuntimeException e) { failures.add("bucket fill threw " + e.getMessage()); }

        // masks: an original id in the block mask picks only the placeholders for it; exclude flips that
        var included = new Options(); included.maskOff = false; included.maskActive.add("fakemod:marble");
        var maskPlan = new EditPlanner(level, session, included);
        expect(failures, maskPlan.accepted(p[0]) && maskPlan.accepted(p[2]), "the block mask takes the marble blocks");
        expect(failures, !maskPlan.accepted(p[3]) && !maskPlan.accepted(p[6]), "the block mask leaves the slab blocks and stone");
        var excluded = new Options(); excluded.maskOff = false; excluded.maskExclude = true; excluded.maskActive.add("fakemod:marble");
        var excludePlan = new EditPlanner(level, session, excluded);
        expect(failures, !excludePlan.accepted(p[0]) && excludePlan.accepted(p[3]) && excludePlan.accepted(p[6]), "excluding marble keeps everything else");
        var single = new Options(); single.mask = "fakemod:slab";
        var singlePlan = new EditPlanner(level, session, single);
        expect(failures, singlePlan.accepted(p[4]) && !singlePlan.accepted(p[0]), "the mask block can be an original id");
        var typed = new Options(); typed.maskOff = false; typed.maskActive.add("fakemod:marble[axis=x]");
        expect(failures, !new EditPlanner(level, session, typed).accepted(p[0]), "a property that does not match leaves the block out");

        // import: an unknown block in a file becomes a placeholder for the same number, with its block entity data inside it
        var entry = new CompoundTag(); entry.putString("Name", "fakemod:marble");
        var properties = new CompoundTag(); properties.putString("axis", "y"); entry.put("Properties", properties);
        var snapshot = AssetNbt.snapshot(entry, null, plan);
        expect(failures, MissingBlocks.isMissing(snapshot.state()) && snapshot.data().getIntOr("key", -1) == marble, "an unknown block imports as the placeholder with its number");
        var kept = new CompoundTag(); kept.putString("id", "fakemod:chest"); kept.putString("Lock", "key");
        var withData = AssetNbt.snapshot(entry, kept, plan);
        expect(failures, kept.equals(AssetNbt.fileData(withData)), "block entity data an unknown block had is written back out");
        expect(failures, AssetNbt.fileData(snapshot) == null, "a placeholder with no block entity data writes none");
        expect(failures, AssetNbt.standsFor(snapshot) != null && AssetNbt.standsFor(snapshot).text().equals("fakemod:marble[axis=y]"), "the placeholder stands for the original block");
        var viaText = AssetNbt.snapshot("fakemod:slab", 0, plan);
        expect(failures, viaText.data().getIntOr("key", -1) == slab, "a block written as text imports the same way");
        // and the block entity comes with it when it is placed
        var placedAt = base.offset(0, 2, 0);
        snapshot.place(level, placedAt, BlockSnapshot.NO_UPDATES);
        expect(failures, level.getBlockEntity(placedAt) instanceof MissingBlockEntity placed && placed.key() == marble, "placing the snapshot gives the block its number");

        // export and read back: the file holds the original names, never craftium:missing_block, and reading it gives the placeholder again
        session.clipboard.put(new Point(0, 0, 0), snapshot);
        session.clipboard.put(new Point(1, 0, 0), new BlockSnapshot(Blocks.STONE.defaultBlockState()));
        try {
            var root = BlueprintLibrary.structure(plan);
            var names = new ArrayList<String>();
            var palette = root.getListOrEmpty("palette");
            for (int i = 0; i < palette.size(); i++) names.add(palette.getCompoundOrEmpty(i).getStringOr("Name", ""));
            expect(failures, names.contains("fakemod:marble") && names.contains("minecraft:stone") && !names.contains(MissingBlockRewrite.BLOCK), "the structure file names the original block, found " + names);
            var back = BlueprintLibrary.read(root, plan);
            var first = back.get(new Point(0, 0, 0));
            expect(failures, first != null && MissingBlockTable.Entry.parse("fakemod:marble[axis=y]").equals(AssetNbt.standsFor(first)), "reading the file gives the placeholder back");
            expect(failures, back.get(new Point(1, 0, 0)).state().is(Blocks.STONE), "an ordinary block in the file is untouched");
        } catch (java.io.IOException e) { failures.add("structure round trip threw " + e); }

        // and what Select all finds: the three in the row and the one placed above, on top of any there already were
        int found = MissingBlocks.loadedAround(player, MissingBlockTable.Entry.parse("fakemod:marble[axis=y]"), 1000).size();
        expect(failures, found == already + 4, "Select all finds the marble placeholders in the loaded chunks: " + already + " before and " + found + " after the four placed here");
    }
}
