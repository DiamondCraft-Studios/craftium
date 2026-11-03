package dev.craftium.client;

import dev.craftium.core.BrushMath;
import dev.craftium.core.Options;
import dev.craftium.core.Point;
import dev.craftium.core.Tool;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.util.List;

/**
 * The Shape tool's hover preview: the blocks the brush would place, drawn as ghost blocks. The ghost is kept and only
 * rebuilt when the shape, radius or hollowness changes.
 */
final class BrushGhost {
    private static List<Object> key=List.of();
    private static ShellGhost ghost;

    private BrushGhost(){}

    /** Draws the preview at {@code at} and returns true, or returns false when it is too large to ghost. */
    static boolean draw(Minecraft mc,BlockPos at,Options o){
        if(mc.level==null)return false;
        var now=List.<Object>of(o.shape,o.radius,o.hollow);
        if(ghost==null||!now.equals(key)){key=now;ghost=ShellGhost.of(BrushMath.brush(new Point(0,0,0),o.radius,o.shape,o.hollow));}
        if(ghost.tooLarge())return false;
        // Erasing shows the blocks that would go, in red, on the blocks that are really there.
        ghost.draw(CraftiumClient.point(at),o.invert||o.tool==Tool.ERASE?null:ShellGhost.state(mc,o.block));
        return true;
    }
}
