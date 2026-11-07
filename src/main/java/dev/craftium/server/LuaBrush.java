package dev.craftium.server;

import dev.craftium.core.*;
import org.luaj.vm2.*;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JseMathLib;

/** No host IO/interop. One instruction budget is shared by every voxel in an operation. */
final class LuaBrush {
    private final Globals globals=new Globals();
    private final LuaValue function;
    private final EditPlanner plan;
    private final LuaFunctions api;
    final boolean once;
    private int remaining=500_000;
    private final long initialMemory=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
    private static final class BudgetExceeded extends Error {}
    LuaBrush(EditPlanner plan,String source){this(plan,source,false);}
    LuaBrush(EditPlanner plan,String source,boolean mask){
        this.plan=plan;
        var packages=new LuaTable();packages.set("loaded",new LuaTable());globals.set("package",packages);
        globals.load(new BaseLib());globals.load(new TableLib());globals.load(new JseMathLib());
        globals.load(new DebugLib(){@Override public void onInstruction(int pc,Varargs values,int top){if(--remaining<0||Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()-initialMemory>32*1024*1024)throw new BudgetExceeded();super.onInstruction(pc,values,top);}});
        for(String key:new String[]{"package","debug","load","loadfile","loadstring","dofile","collectgarbage","pcall","xpcall","require","module","print","setmetatable","getmetatable"})globals.set(key,LuaValue.NIL);
        LuaC.install(globals);LoadState.install(globals);globals.get("table").set("concat",LuaValue.NIL);globals.set("unpack",LuaValue.NIL);
        api=plan==null?null:new LuaFunctions(globals,plan);
        var template=ScriptTemplates.expand(source,plan==null?java.util.Map.of():mask?plan.o.maskVariables:plan.o.scriptVariables);once=template.once();function=globals.load(template.source(),"Craftium brush");
    }
    LuaValue evaluate(Point p){
        globals.set("x",p.x());globals.set("y",p.y());globals.set("z",p.z());if(api!=null)api.position(p);
        try{return function.call();}
        catch(BudgetExceeded e){throw new IllegalArgumentException("Lua instruction or memory budget exceeded; operation cancelled");}
        // A script that calls itself without end overflows the Java stack. That is an Error, which the request handler does not catch, so it ended the server.
        catch(StackOverflowError e){throw new IllegalArgumentException("Lua call depth exceeded; operation cancelled");}
        catch(LuaError e){throw new IllegalArgumentException("Lua: "+e.getMessage());}
    }
    void paint(Point p){LuaValue result=evaluate(p);if(!result.isnil())plan.put(p,api.state(result));}
}
