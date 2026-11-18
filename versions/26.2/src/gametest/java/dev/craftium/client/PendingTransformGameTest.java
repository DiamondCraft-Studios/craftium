package dev.craftium.client;

import dev.craftium.core.Point;
import dev.craftium.core.Tool;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** The Move, Rotate and Scale tools on a waiting Shape build and on a waiting paste preview (the green outlines). */
public final class PendingTransformGameTest {
    private PendingTransformGameTest() {}

    public static void run(ClientGameTestContext c, TestSingleplayerContext w) {
        w.getServer().runCommand("tp @a 55.5 36 70 180 20");
        c.waitTicks(8);
        c.runOnClient(mc -> {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
            mc.getWindow().setWindowed(1200, 800);
            mc.options.guiScale().set(2);
            mc.resizeGui();
            ClientState.options.confirmEdits = true;
            ClientState.options.tool = Tool.SHAPE;
            CraftiumClient.open(false);
        });
        c.waitTicks(10);

        // --- A waiting shape build ---
        c.runOnClient(mc -> CraftiumClient.send("use", new Point(55, 30, 50), new Point(0, 1, 0)));
        sync(c, w);
        System.out.println("PENDING_TEST shape: pending=" + c.computeOnClient(mc -> ClientState.hasPending())
                + " selection=" + c.computeOnClient(mc -> ClientState.status.selectionSize()));
        for (Tool tool : new Tool[] {Tool.MOVE, Tool.ROTATE, Tool.SCALE}) {
            c.runOnClient(mc -> {
                ClientState.options.tool = tool;
                ((EditorScreen) mc.gui.screen()).select(tool);
            });
            c.waitTicks(3);
            boolean avail = c.computeOnClient(mc -> TransformGizmo.available((EditorScreen) mc.gui.screen()));
            double[] g = c.computeOnClient(mc -> grab(mc, TransformGizmo.pivot()));
            System.out.println("PENDING_TEST shape " + tool + ": gizmo=" + avail + " grabbed=" + (g != null));
            if (g != null) {
                c.runOnClient(mc -> TransformGizmo.drag(g[0] + 40, g[1] + 20));
                c.runOnClient(mc -> TransformGizmo.release((EditorScreen) mc.gui.screen()));
                sync(c, w);
                System.out.println("PENDING_TEST shape " + tool + " after drag: pending="
                        + c.computeOnClient(mc -> ClientState.hasPending()) + " min="
                        + c.computeOnClient(mc -> ClientState.status.min()) + " size="
                        + c.computeOnClient(mc -> ClientState.status.selectionSize()));
            }
        }
        c.runOnClient(mc -> CraftiumClient.send("pending_confirm", null, null));
        sync(c, w);

        // --- A paste preview ---
        c.runOnClient(mc -> {
            ClientState.options.tool = Tool.BOX_SELECT;
            ClientState.options.position1 = new Point(55, 30, 50);
            ClientState.options.position2 = new Point(58, 32, 52);
            CraftiumClient.send("select_box", null, null);
        });
        sync(c, w);
        c.runOnClient(mc -> CraftiumClient.send("copy", null, null));
        sync(c, w);
        c.runOnClient(mc -> {
            PlacementPreview.begin(new Point(55, 30, 56));
            ((EditorScreen) mc.gui.screen()).select(Tool.MOVE);
        });
        c.waitTicks(5);
        for (Tool tool : new Tool[] {Tool.MOVE, Tool.ROTATE, Tool.SCALE}) {
            c.runOnClient(mc -> ((EditorScreen) mc.gui.screen()).select(tool));
            c.waitTicks(3);
            Vec3 anchor = c.computeOnClient(mc -> {
                var p = PlacementPreview.origin;
                return new Vec3(p.x(), p.y(), p.z())
                        .add(PlacementPreview.shape.bounds().getCenter());
            });
            Point o0 = c.computeOnClient(mc -> PlacementPreview.origin);
            String b0 = c.computeOnClient(mc -> String.valueOf(PlacementPreview.shape.bounds()));
            double[] g = c.computeOnClient(mc -> grab(mc, anchor));
            System.out.println("PENDING_TEST paste " + tool + ": gizmo="
                    + c.computeOnClient(mc -> TransformGizmo.available((EditorScreen) mc.gui.screen()))
                    + " grabbed=" + (g != null));
            if (g != null) {
                int far = tool == Tool.SCALE ? 160 : 40;
                double[] at = c.computeOnClient(mc -> project(mc, anchor));
                double len = Math.max(1, Math.hypot(g[0] - at[0], g[1] - at[1]));
                double ux = tool == Tool.SCALE ? (g[0] - at[0]) / len * far : far,
                        uy = tool == Tool.SCALE ? (g[1] - at[1]) / len * far : far / 2;
                c.runOnClient(mc -> TransformGizmo.drag(g[0] + ux, g[1] + uy));
                c.runOnClient(mc -> TransformGizmo.release((EditorScreen) mc.gui.screen()));
                sync(c, w);
                System.out.println("PENDING_TEST paste " + tool + " after drag: origin " + o0 + " -> "
                        + c.computeOnClient(mc -> PlacementPreview.origin) + " bounds " + b0 + " -> "
                        + c.computeOnClient(mc -> String.valueOf(PlacementPreview.shape.bounds())));
            }
        }
        System.out.println("PENDING_TEST done");
    }

    private static void sync(ClientGameTestContext c, TestSingleplayerContext w) {
        w.getConnection().waitForServerboundPackets();
        w.getConnection().waitForClientboundPackets();
        c.waitTicks(5);
    }

    private static double[] grab(Minecraft mc, Vec3 anchor) {
        var screen = (EditorScreen) mc.gui.screen();
        double[] from = project(mc, anchor);
        for (double r = 0; r < 160; r += 2)
            for (int a = 0; a < 360; a += (r == 0 ? 360 : 30)) {
                double x = from[0] + Math.cos(Math.toRadians(a)) * r, y = from[1] + Math.sin(Math.toRadians(a)) * r;
                if (TransformGizmo.click(screen, x, y) && TransformGizmo.dragging()) return new double[] {x, y};
                TransformGizmo.cancel();
            }
        return null;
    }

    private static double[] project(Minecraft mc, Vec3 point) {
        var camera = mc.gameRenderer.mainCamera();
        var near = camera.getNearPlane(camera.getFov());
        var center = near.getPointOnPlane(0, 0);
        var offset = point.subtract(camera.position());
        double depth = offset.dot(center.normalize()),
                focal = mc.getWindow().getGuiScaledHeight() / (2 * Math.tan(Math.toRadians(camera.getFov()) / 2));
        return new double[] {
            mc.getWindow().getGuiScaledWidth() / 2d
                    + offset.dot(near.getPointOnPlane(1, 0).subtract(center).normalize()) * focal / depth,
            mc.getWindow().getGuiScaledHeight() / 2d
                    - offset.dot(near.getPointOnPlane(0, 1).subtract(center).normalize()) * focal / depth
        };
    }
}
