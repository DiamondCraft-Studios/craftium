package dev.craftium.server;

import org.luaj.vm2.*;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JseMathLib;

/**
 * The Lua environment edit scripts run in: no host IO or interop, no way to load further code, and one instruction and memory budget that every
 * call through it shares, so a script that runs for each block of an edit is cancelled as a whole. What a script can reach beyond the standard
 * math, table and base functions is added to {@link #globals()} by whoever creates the sandbox.
 */
final class LuaSandbox {
    /** Instructions all calls through one sandbox may run in total. */
    static final int INSTRUCTIONS = 500_000;
    /** Heap a script may grow by while it runs. */
    static final long MEMORY_BYTES = 32L * 1024 * 1024;

    private static final class BudgetExceeded extends Error {}

    private final Globals globals = new Globals();
    private final int budget;
    private final long memoryBytes;
    private int remaining;
    private final long initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

    LuaSandbox() { this(INSTRUCTIONS, MEMORY_BYTES); }

    /** A sandbox with its own budget, for scripts that are meant to do more than a brush does for one block (an AI's build script). */
    LuaSandbox(int instructions, long memoryBytes) {
        this.budget = instructions; this.memoryBytes = memoryBytes; remaining = instructions;
        var packages = new LuaTable(); packages.set("loaded", new LuaTable()); globals.set("package", packages);
        globals.load(new BaseLib()); globals.load(new TableLib()); globals.load(new JseMathLib());
        globals.load(new DebugLib() {
            @Override public void onInstruction(int pc, Varargs values, int top) {
                if (--remaining < 0 || Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() - initialMemory > memoryBytes) throw new BudgetExceeded();
                super.onInstruction(pc, values, top);
            }
        });
        for (String key : new String[]{"package", "debug", "load", "loadfile", "loadstring", "dofile", "collectgarbage", "pcall", "xpcall", "require", "module", "print", "setmetatable", "getmetatable"}) globals.set(key, LuaValue.NIL);
        LuaC.install(globals); LoadState.install(globals); globals.get("table").set("concat", LuaValue.NIL); globals.set("unpack", LuaValue.NIL);
    }

    /** The script's global table: where the functions and values it may use are put. */
    Globals globals() { return globals; }

    /** Instructions run so far, across every call. */
    int used() { return budget - Math.max(remaining, 0); }

    /** Compiles {@code source}; a script that does not parse throws a {@link LuaError}. */
    LuaValue load(String source, String chunkName) { return globals.load(source, chunkName); }

    /**
     * Runs {@code function}, turning a spent budget, a Lua error or a script that calls itself without end into the message the player is shown.
     * The last matters: a stack overflow is a Java {@link Error}, which the request handler does not catch, so it would end the server.
     */
    LuaValue call(LuaValue function) {
        try { return function.call(); }
        catch (BudgetExceeded e) { throw new IllegalArgumentException("Lua instruction or memory budget exceeded; operation cancelled"); }
        catch (StackOverflowError e) { throw new IllegalArgumentException("Lua call depth exceeded; operation cancelled"); }
        catch (LuaError e) { throw new IllegalArgumentException("Lua: " + e.getMessage()); }
    }
}
