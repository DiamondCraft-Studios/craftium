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
    private long initialMemory = heap();
    /** Bytes this thread has allocated so far, when the sandbox judges memory by what its own script allocated; null to go by the heap alone. */
    private final java.util.function.LongSupplier allocated;

    LuaSandbox() { this(INSTRUCTIONS, MEMORY_BYTES); }

    LuaSandbox(int instructions, long memoryBytes) { this(instructions, memoryBytes, false); }

    /**
     * A sandbox with its own budget, for scripts that are meant to do more than a brush does for one block (an AI's build script).
     * <p>
     * The memory limit is watched through the heap, which is cheap enough to ask at every instruction, but the heap also grows from garbage
     * and from the server's other threads, which are not the script's doing and would cancel a script on one run and not on the next. With
     * {@code ownAllocation}, a script is cancelled only when the heap has grown by the limit <em>and</em> the thread running it has itself
     * allocated at least that much since the sandbox was made, so the same script is cancelled or not by what it does. It must then run on the
     * thread that creates the sandbox. Where the JVM cannot count a thread's allocation the heap alone decides, as for brushes.
     */
    LuaSandbox(int instructions, long memoryBytes, boolean ownAllocation) {
        this.budget = instructions; this.memoryBytes = memoryBytes; remaining = instructions;
        allocated = ownAllocation ? ThreadAllocation.counter() : null;
        var packages = new LuaTable(); packages.set("loaded", new LuaTable()); globals.set("package", packages);
        globals.load(new BaseLib()); globals.load(new TableLib()); globals.load(new JseMathLib());
        globals.load(new DebugLib() {
            @Override public void onInstruction(int pc, Varargs values, int top) {
                if (--remaining < 0) throw new BudgetExceeded();
                if (heap() - initialMemory > memoryBytes) {
                    if (allocated == null || allocated.getAsLong() >= memoryBytes) throw new BudgetExceeded();
                    initialMemory = heap(); // the growth was not this script's: count from here
                }
                super.onInstruction(pc, values, top);
            }
        });
        for (String key : new String[]{"package", "debug", "load", "loadfile", "loadstring", "dofile", "collectgarbage", "pcall", "xpcall", "require", "module", "print", "setmetatable", "getmetatable"}) globals.set(key, LuaValue.NIL);
        LuaC.install(globals); LoadState.install(globals); globals.get("table").set("concat", LuaValue.NIL); globals.set("unpack", LuaValue.NIL);
    }

    private static long heap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }

    /**
     * What this thread has allocated since the counter was made, through the JVM's own count. It is a class of its own because the count
     * lives in a module a trimmed runtime may leave out: asking for it then fails here, where it is caught, and not when the sandbox loads.
     */
    private static final class ThreadAllocation {
        static java.util.function.LongSupplier counter() {
            try {
                if (java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                        && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()) {
                    long id = Thread.currentThread().threadId();
                    long start = bean.getThreadAllocatedBytes(id);
                    return () -> bean.getThreadAllocatedBytes(id) - start;
                }
            } catch (Throwable unavailable) { /* no count: the heap decides */ }
            return null;
        }
    }

    /** Whether the sandbox can tell its own script's allocation from the rest of the JVM's (it can on the usual HotSpot runtime). */
    boolean countsOwnAllocation() { return allocated != null; }

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
