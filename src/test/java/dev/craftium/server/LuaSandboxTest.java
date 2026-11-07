package dev.craftium.server;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import static org.junit.jupiter.api.Assertions.*;

/** What a script cannot do. Each test is an attempt to get out of, or run past the limits of, the environment edit scripts run in. */
class LuaSandboxTest {
    private static LuaValue run(String source) {
        var sandbox = new LuaSandbox();
        return sandbox.call(sandbox.load(source, "test"));
    }

    @Test void noHostLibraryIsReachable() {
        for (String name : new String[]{"io", "os", "luajava", "package", "debug", "string", "coroutine", "utf8", "bit32", "jit", "java"})
            assertTrue(run("return " + name + " == nil").toboolean(), name + " is reachable");
    }

    @Test void noWayToLoadFurtherCode() {
        for (String name : new String[]{"load", "loadfile", "loadstring", "dofile", "require", "module"})
            assertTrue(run("return " + name + " == nil").toboolean(), name + " is reachable");
    }

    @Test void noWayToTouchMetatablesOrTheCollector() {
        for (String name : new String[]{"setmetatable", "getmetatable", "collectgarbage", "pcall", "xpcall", "print", "unpack"})
            assertTrue(run("return " + name + " == nil").toboolean(), name + " is reachable");
        assertTrue(run("return table.concat == nil").toboolean());
    }

    @Test void nothingIsReachableThroughTheGlobalTable() {
        // The environment itself, read back through every route a script has to it.
        assertTrue(run("return _G.io == nil and _G.os == nil and _G.debug == nil and _G._G == _G").toboolean());
        assertTrue(run("local n = 0 for k in pairs(_G) do if k == 'io' or k == 'os' or k == 'luajava' or k == 'debug' or k == 'package' then n = n + 1 end end return n == 0").toboolean());
    }

    @Test void whatIsLeftStillWorks() {
        assertEquals(7, run("return math.floor(math.sqrt(49))").toint());
        assertEquals(3, run("local t = {} for i = 1, 3 do table.insert(t, i) end return #t").toint());
        assertEquals("number", run("return type(math.pi)").tojstring());
    }

    @Test void changesToTheStandardLibrariesStayInsideOneSandbox() {
        var first = new LuaSandbox();
        first.call(first.load("math.floor = nil table.insert = nil", "test"));
        assertTrue(first.call(first.load("return math.floor == nil", "test")).toboolean());
        var second = new LuaSandbox();
        assertTrue(second.call(second.load("return math.floor ~= nil and table.insert ~= nil", "test")).toboolean());
    }

    @Test void globalsDoNotLeakBetweenSandboxes() {
        var first = new LuaSandbox();
        first.call(first.load("leaked = 1", "test"));
        var second = new LuaSandbox();
        assertTrue(second.call(second.load("return leaked == nil", "test")).toboolean());
    }

    @Test void anInfiniteLoopIsCancelled() {
        var error = assertThrows(IllegalArgumentException.class, () -> run("while true do end"));
        assertTrue(error.getMessage().contains("budget"), error.getMessage());
    }

    @Test void theBudgetIsSharedByEveryCallSoAScriptRunPerBlockIsCancelledAsAWhole() {
        var sandbox = new LuaSandbox();
        var function = sandbox.load("local n = 0 for i = 1, 100 do n = n + 1 end return n", "test");
        assertEquals(100, sandbox.call(function).toint());
        int calls = 1;
        while (calls < 100_000) {
            try { sandbox.call(function); calls++; }
            catch (IllegalArgumentException spent) { assertTrue(spent.getMessage().contains("budget"), spent.getMessage()); break; }
        }
        assertTrue(calls > 1 && calls < 100_000, "calls before the budget ran out: " + calls);
        // Once spent it stays spent: the whole edit is cancelled, not just the block that ran it out.
        assertThrows(IllegalArgumentException.class, () -> sandbox.call(function));
    }

    @Test void growingATableForeverIsCancelled() {
        var error = assertThrows(IllegalArgumentException.class, () -> run("local t = {} local i = 0 while true do i = i + 1 t[i] = {i, i, i, i} end"));
        assertTrue(error.getMessage().contains("budget"), error.getMessage());
    }

    @Test void runawayRecursionIsAnErrorForThePlayerNotACrash() {
        var error = assertThrows(IllegalArgumentException.class, () -> run("local function f(n) return 1 + f(n + 1) end return f(1)"));
        assertTrue(error.getMessage().startsWith("Lua"), error.getMessage());
    }

    @Test void mutualRecursionThroughTheStandardLibraryIsAnErrorToo() {
        var error = assertThrows(IllegalArgumentException.class, () -> run("local function f(n) local t = {n} table.insert(t, f(n + 1)) return t end return f(1)"));
        assertTrue(error.getMessage().startsWith("Lua"), error.getMessage());
    }

    @Test void aScriptsOwnErrorReachesThePlayerWithItsMessage() {
        var error = assertThrows(IllegalArgumentException.class, () -> run("error('boom')"));
        assertTrue(error.getMessage().startsWith("Lua: ") && error.getMessage().contains("boom"), error.getMessage());
    }

    @Test void aDeeplyNestedScriptIsRejectedWithoutCrashing() {
        // Scripts may be up to 16384 characters, enough to nest thousands deep: the parser must refuse it, not overflow the stack.
        String nested = "return " + "(".repeat(8000) + "1" + ")".repeat(8000);
        assertThrows(RuntimeException.class, () -> new LuaSandbox().load(nested, "test"));
        String blocks = "x = 1 " + "do ".repeat(3000) + "x = 2 " + "end ".repeat(3000);
        assertThrows(RuntimeException.class, () -> new LuaSandbox().load(blocks, "test"));
    }

    @Test void aScriptThatDoesNotParseIsRejectedWhenItIsLoaded() {
        assertThrows(LuaError.class, () -> new LuaSandbox().load("this is not lua", "test"));
    }

    @Test void aSandboxCanBeGivenItsOwnBudget() {
        var small = new LuaSandbox(1_000, LuaSandbox.MEMORY_BYTES);
        assertThrows(IllegalArgumentException.class, () -> small.call(small.load("for i = 1, 10000 do end", "test")));
        var large = new LuaSandbox(LuaSandbox.INSTRUCTIONS * 4, LuaSandbox.MEMORY_BYTES);
        large.call(large.load("for i = 1, 600000 do end", "test"));
        assertTrue(large.used() > LuaSandbox.INSTRUCTIONS, "ran past a brush's budget: " + large.used());
    }

    @Test void anAiScriptAsLongAsAllowedAndNestedAsDeepAsItCanIsAnErrorNotACrash() {
        String nested = "return " + "(".repeat(9990) + "1" + ")".repeat(9990);
        assertThrows(RuntimeException.class, () -> new LuaSandbox().load(nested, "test"));
    }
}
