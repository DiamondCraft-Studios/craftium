package dev.craftium.server;

import dev.craftium.core.*;
import java.util.*;

/**
 * Runs the {@code script} operations of an AI's build spec: Lua that places blocks inside a box, for what shapes cannot describe well (terrain,
 * a tree, a pattern worked out per block). Each script runs once, in the sandbox brushes use, with the functions brushes have; it may only
 * place inside its own box, reads the blocks the shapes before it planned, and is given a fixed seed so the same spec always builds the same.
 */
final class AiScript {
    private AiScript() {}

    /**
     * Instructions every script of one spec may run in all. The sandbox runs about ten million a second, and a spec is planned on the server
     * thread, so this holds a script to about half a second.
     */
    static final int INSTRUCTIONS = 5_000_000;
    /** Heap the scripts may grow by; more than a brush has, since a script that runs longer leaves more garbage before it is collected. */
    static final long MEMORY_BYTES = 256L * 1024 * 1024;

    /** Runs the scripts in order, planning their blocks into {@code plan}, and adds what the AI should hear about them to {@code warnings}. */
    static void run(EditPlanner plan, List<BuildSpec.ScriptOp> scripts, List<String> warnings) {
        int spent = 0;
        for (var script : scripts) {
            var sandbox = new LuaSandbox(INSTRUCTIONS - spent, MEMORY_BYTES);
            var api = new LuaFunctions(sandbox.globals(), plan);
            Point a = script.from(), b = script.to();
            api.confine(p -> p.x() >= a.x() && p.x() <= b.x() && p.y() >= a.y() && p.y() <= b.y() && p.z() >= a.z() && p.z() <= b.z(), script.seed(), BuildSpec.MAX_BLOCKS);
            var globals = sandbox.globals();
            globals.set("x0", a.x()); globals.set("y0", a.y()); globals.set("z0", a.z());
            globals.set("x1", b.x()); globals.set("y1", b.y()); globals.set("z1", b.z());
            globals.set("seed", script.seed());
            try {
                sandbox.call(sandbox.load("math.randomseed(" + script.seed() + ")", "seed"));
                org.luaj.vm2.LuaValue code;
                try { code = sandbox.load(script.code(), "script"); }
                // A script nested too deep is refused by the parser with a plain RuntimeException rather than a LuaError.
                catch (RuntimeException e) { throw new IllegalArgumentException("the code does not compile: " + e.getMessage()); }
                sandbox.call(code);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(script.label() + ": " + e.getMessage());
            }
            spent += sandbox.used();
            if (api.placed == 0) warnings.add(script.label() + " placed no blocks: check that it calls setBlock with positions inside its box (x0..x1, y0..y1, z0..z1)");
            if (api.outside > 0) warnings.add(script.label() + " set " + api.outside + " blocks outside its box (" + a.x() + " " + a.y() + " " + a.z() + " to " + b.x() + " " + b.y() + " " + b.z() + "); they were dropped. Widen \"from\" and \"to\" if they should show");
        }
    }
}
