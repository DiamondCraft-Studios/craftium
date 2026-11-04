package dev.craftium.client;

import com.google.gson.*;
import dev.craftium.client.compat.ClientCompat;
import dev.craftium.core.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Opt-in (CRAFTIUM_TEST_SCOPE=paper-ai): the AI bridge against a real Paper 1.21.10 server that is already running on loopback with the
 * Craftium plugin (a flat world, offline mode, RCON on port 25675 with the fixed local test password). It drives the bridge over HTTP as
 * the MCP shim does, and checks what only a dedicated server can show: reads and builds answered over the plugin's bridge frames, the
 * player's own selection left alone, a build far from the player in ground nobody had loaded, and permissions.
 */
public final class PaperAiGameTest implements FabricClientGameTest {
    private static final int PORT = 25575;
    private ClientGameTestContext ctx;
    private int port;
    private String token;
    private String name;

    @Override public void runTest(ClientGameTestContext context) {
        if (!"paper-ai".equals(System.getenv("CRAFTIUM_TEST_SCOPE"))) return;
        ctx = context;
        ctx.runOnClient(mc -> {
            ClientState.options = new Options();
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString("127.0.0.1:" + PORT), new ServerData("Craftium test", "127.0.0.1:" + PORT, ServerData.Type.OTHER), false, null);
        });
        until(mc -> mc.player != null && mc.level != null, "server connection");
        name = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
        command("op " + name);
        command("gamemode creative " + name);
        command("tp " + name + " 0 10 18 180 25");
        until(mc -> CraftiumClient.supported(), "bridge channel registration");
        System.out.println("CRAFTIUM_PAPER_AI connected as " + name);

        ctx.runOnClient(mc -> AiBridge.toggle());
        until(mc -> AiBridge.running(), "the AI bridge to start");
        try {
            var info = JsonParser.parseString(Files.readString(AiBridge.FILE)).getAsJsonObject();
            port = info.get("port").getAsInt();
            token = info.get("token").getAsString();
        } catch (Exception e) { throw new AssertionError(e); }

        var state = call("get_state", "{}");
        require(state.get("ok").getAsBoolean() && state.get("craftiumOnServer").getAsBoolean(), "get_state: " + state);
        System.out.println("CRAFTIUM_PAPER_AI bridge_ok");

        // The player's own selection, which the AI must leave alone.
        ctx.runOnClient(mc -> {
            var o = ClientState.options;
            o.position1 = new Point(10, 5, 10);
            o.position2 = new Point(11, 6, 11);
            o.mode = Selection.Mode.REPLACE;
            CraftiumClient.send("select_box", o.position1, null);
        });
        pause(500);
        ctx.waitTicks(10);
        require(selectionSize() == 8, "the player's own selection should be 8 blocks, was " + selectionSize());

        // Reads come from the server over the plugin's frames: the flat world's layers, and its surface.
        var layers = call("get_blocks", "{\"from\":[0,-64,0],\"to\":[1,-61,1]}");
        require(layers.get("ok").getAsBoolean() && "server".equals(layers.get("source").getAsString()), "get_blocks: " + layers);
        String palette = layers.getAsJsonArray("palette").toString();
        require(palette.contains("bedrock") && palette.contains("grass_block"), "the flat world's layers: " + palette);
        var surface = call("get_surface", "{\"from\":[0,0,0],\"to\":[3,0,3]}");
        require(surface.get("ok").getAsBoolean() && surface.getAsJsonArray("heights").size() == 16 && surface.getAsJsonArray("heights").get(0).getAsInt() == -61, "get_surface: " + surface);
        System.out.println("CRAFTIUM_PAPER_AI read_ok");

        // Ground nobody has loaded, 2000 blocks away: a read, a render and a build, none of which need the player there.
        int fx = 2000, fz = 2000;
        var far = call("get_blocks", "{\"from\":[" + fx + ",-64," + fz + "],\"to\":[" + (fx + 3) + ",-61," + (fz + 3) + "]}");
        require(far.get("ok").getAsBoolean() && far.get("chunksLoadedForThisRead").getAsInt() >= 1 && far.getAsJsonArray("palette").toString().contains("bedrock"), "a far read: " + far);
        System.out.println("CRAFTIUM_PAPER_AI far_read_ok");

        String spec = "{\"ops\":[{\"op\":\"pyramid\",\"base\":[" + (fx + 8) + ",-60," + (fz + 8) + "],\"radius\":4,\"height\":5,\"block\":\"minecraft:sandstone\"}]}";
        var preview = call("preview_build", "{\"spec\":" + spec + "}");
        require(preview.get("ok").getAsBoolean() && preview.getAsJsonObject("state").get("previewBlocks").getAsInt() > 20, "a far preview: " + preview);
        require(selectionSize() == 8, "the AI's preview must not change the player's selection");
        var view = call("render_view", "{\"from\":[" + (fx + 4) + ",-61," + (fz + 4) + "],\"to\":[" + (fx + 12) + ",-54," + (fz + 12) + "],\"angle\":\"se\"}");
        require(view.get("ok").getAsBoolean() && view.get("mime").getAsString().equals("image/png") && view.get("previewBlocksShown").getAsInt() > 20, "a far render shows the preview: " + view.keySet());
        System.out.println("CRAFTIUM_PAPER_AI far_preview_ok");

        var applied = call("apply_preview", "{}");
        require(applied.get("ok").getAsBoolean(), "a far apply: " + applied);
        pollFor("the far pyramid", () -> blockAt(fx + 8, -60, fz + 8).contains("minecraft:sandstone"));
        System.out.println("CRAFTIUM_PAPER_AI far_apply_ok");

        var undone = call("undo", "{}");
        require(undone.get("ok").getAsBoolean(), "a far undo: " + undone);
        pollFor("the far undo", () -> !blockAt(fx + 8, -60, fz + 8).contains("minecraft:sandstone"));
        require(selectionSize() == 8, "the AI's undo must not undo the player's own selection");
        System.out.println("CRAFTIUM_PAPER_AI far_undo_ok");

        // A build that would need too much new ground is refused, with a message.
        var tooMuch = call("preview_build", "{\"spec\":{\"ops\":[{\"op\":\"box\",\"from\":[7000,-60,7000],\"to\":[7200,-60,7200],\"block\":\"minecraft:stone\"}]}}");
        require(!tooMuch.get("ok").getAsBoolean() && tooMuch.get("error").getAsString().contains("chunks"), "an oversized far build: " + tooMuch);
        System.out.println("CRAFTIUM_PAPER_AI chunk_limit_ok");

        // Permissions are the player's own: without operator status the AI cannot read or build either.
        command("deop " + name);
        var refused = call("get_blocks", "{\"from\":[0,-64,0],\"to\":[1,-63,1]}");
        require(!refused.get("ok").getAsBoolean(), "a read without permission should be refused: " + refused);
        var refusedBuild = call("preview_build", "{\"spec\":" + spec + "}");
        require(!refusedBuild.get("ok").getAsBoolean(), "a build without permission should be refused: " + refusedBuild);
        command("op " + name);
        System.out.println("CRAFTIUM_PAPER_AI permission_ok");

        ctx.runOnClient(mc -> AiBridge.toggle());
        ctx.runOnClient(mc -> mc.disconnectFromWorld(Component.literal("Live test complete")));
        until(mc -> mc.player == null, "disconnect");
        ctx.runOnClient(mc -> ClientCompat.setScreen(mc, new TitleScreen())); // the framework requires tests to end here
        System.out.println("CRAFTIUM_PAPER_AI PASSED Paper 1.21.10: bridge frames, server reads, far build/undo, chunk limit, own selection untouched, permissions");
    }

    private int selectionSize() { return ctx.computeOnClient(mc -> ClientState.status.selectionSize()); }

    /** The block at a position as the server reports it, through the bridge itself. */
    private String blockAt(int x, int y, int z) {
        var read = call("get_blocks", "{\"from\":[" + x + "," + y + "," + z + "],\"to\":[" + x + "," + y + "," + z + "]}");
        return read.get("ok").getAsBoolean() ? read.getAsJsonArray("palette").toString() : "";
    }

    private void pollFor(String what, java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return;
            ctx.waitTicks(4);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    /** Calls a tool from another thread while the game keeps ticking, since the bridge answers from the game thread. */
    private JsonObject call(String tool, String body) {
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/" + tool)).header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        for (int i = 0; i < 1200 && !future.isDone(); i++) ctx.waitTick();
        long millis = (System.nanoTime() - started) / 1_000_000;
        if (millis > 1000) System.out.println("CRAFTIUM_PAPER_AI slow_call " + tool + " " + millis + " ms");
        try { return JsonParser.parseString(future.get().body()).getAsJsonObject(); } catch (Exception e) { throw new AssertionError("bridge call " + tool + " failed", e); }
    }

    private void until(Predicate<Minecraft> condition, String what) {
        for (int i = 0; i < 600; i++) { if (ctx.computeOnClient(condition::test)) return; pause(50); ctx.waitTicks(2); }
        throw new AssertionError("Timed out waiting for " + what + "; " + ctx.computeOnClient(mc -> ClientState.status.message()));
    }

    private static void require(boolean test, String message) { if (!test) throw new AssertionError(message); }
    private static void pause(long millis) { try { Thread.sleep(millis); } catch (InterruptedException e) { throw new AssertionError(e); } }

    /** Runs a console command over the test server's loopback RCON port. */
    private String command(String text) {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), PORT + 100)) {
            socket.setSoTimeout(5000);
            send(socket, 3, "craftium-local-test-only"); read(socket);
            send(socket, 2, text); return read(socket);
        } catch (IOException e) { throw new AssertionError("Local test server command failed: " + text, e); }
    }

    private static void send(Socket socket, int type, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        var packet = ByteBuffer.allocate(data.length + 14).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length + 10).putInt(123).putInt(type).put(data).put((byte) 0).put((byte) 0);
        socket.getOutputStream().write(packet.array());
        socket.getOutputStream().flush();
    }

    private static String read(Socket socket) throws IOException {
        var in = socket.getInputStream();
        byte[] size = in.readNBytes(4);
        if (size.length != 4) throw new EOFException();
        int length = ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 10 || length > 1_000_000) throw new IOException("Invalid RCON response");
        byte[] data = in.readNBytes(length);
        return new String(data, 8, Math.max(0, length - 10), StandardCharsets.UTF_8);
    }
}
