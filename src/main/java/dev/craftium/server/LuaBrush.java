package dev.craftium.server;

import dev.craftium.core.*;
import org.luaj.vm2.*;

/** A script run once for each block of a brush, inside a {@link LuaSandbox}: one budget is shared by every voxel in an operation. */
final class LuaBrush {
    private final LuaSandbox sandbox=new LuaSandbox();
    private final LuaValue function;
    private final EditPlanner plan;
    private final LuaFunctions api;
    final boolean once;
    LuaBrush(EditPlanner plan,String source){this(plan,source,false);}
    LuaBrush(EditPlanner plan,String source,boolean mask){
        this.plan=plan;
        api=plan==null?null:new LuaFunctions(sandbox.globals(),plan);
        var template=ScriptTemplates.expand(source,plan==null?java.util.Map.of():mask?plan.o.maskVariables:plan.o.scriptVariables);once=template.once();function=sandbox.load(template.source(),"Craftium brush");
    }
    LuaValue evaluate(Point p){
        var globals=sandbox.globals();globals.set("x",p.x());globals.set("y",p.y());globals.set("z",p.z());if(api!=null)api.position(p);
        return sandbox.call(function);
    }
    void paint(Point p){LuaValue result=evaluate(p);if(!result.isnil())plan.put(p,api.state(result));}
}
