package dev.craftium.client;

import com.google.gson.*;
import dev.craftium.Craftium;
import dev.craftium.core.Point;
import dev.craftium.core.Selection;
import dev.craftium.net.Packets;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Lets an AI client on this computer (through the craftium-mcp shim) drive the editor. It listens on the loopback address only,
 * answers only to a token written to {@code config/craftium/bridge.json} for this launch, and is off until the player turns it
 * on with {@code /craftium ai on}. Everything it asks for goes through the same requests the editor sends, so the server
 * applies its usual permission, protection and size checks; a build is only ever staged as a ghost until {@code apply_preview}.
 */
public final class AiBridge {
    static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("craftium").resolve("bridge.json");
    /**
     * What the bridge's tools understand, written to the bridge file so the MCP server can tell a player to update the mod or the plugin
     * instead of failing on a tool the other side does not have. Bump it when a tool gains something the MCP server relies on.
     * 2: preview summaries, get_state's world, build spec v2 (materials, mixes, roof, stairs, group), the "world" view of
     * get_preview_blocks (read_blocks) and render_view's "angles".
     */
    public static final int PROTOCOL = 2;
    private static final int BODY_LIMIT = 1_000_000, READ_BLOCKS = 32_768, WAIT_SECONDS = 20;
    private static final Gson GSON = new Gson();
    private static final Object LOCK = new Object();
    private static ServerSocket socket;
    private static ExecutorService worker;
    private static volatile String token = "";
    /** Completed by the next status the server sends after a request, which is how a tool learns what its request did. */
    private static volatile CompletableFuture<Packets.Status> awaiting;
    /** What the AI app is doing, for the indicator: requests being answered now, and when and with what the last one came. */
    private static final java.util.concurrent.atomic.AtomicInteger working = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile long lastCall;
    private static volatile String lastTool = "";
    private static final long ACTIVE_MILLIS = 120_000;
    private AiBridge() {}

    public enum State { OFF, WAITING, ACTIVE, WORKING }

    /** Off; on and waiting for an AI app to call; on with an AI app seen in the last two minutes; or answering a call right now. */
    public static State indicator() {
        if (!running()) return State.OFF;
        if (working.get() > 0) return State.WORKING;
        return lastCall != 0 && System.currentTimeMillis() - lastCall < ACTIVE_MILLIS ? State.ACTIVE : State.WAITING;
    }

    /** The colour of the indicator for a state. */
    public static int colour(State state) {
        return switch (state) { case OFF -> 0xff7a8794; case WAITING -> 0xffffc857; case ACTIVE -> 0xff6fe08f; case WORKING -> 0xff6cb6ff; };
    }

    /** What the AI app last asked for and how long ago, or an empty text when it has not asked yet. */
    public static String lastActivity() {
        if (lastCall == 0) return "";
        long seconds = Math.max(0, (System.currentTimeMillis() - lastCall) / 1000);
        String ago = seconds < 60 ? seconds + "s" : seconds < 3600 ? seconds / 60 + " min" : seconds / 3600 + " h";
        return lastTool + " · " + ago + " ago";
    }

    /** Turns the bridge on or off from the editor, remembering the choice. */
    public static void toggle() {
        boolean on = !ClientState.options.aiBridge;
        ClientState.options.aiBridge = on;
        if (on) start(); else stop();
        ClientState.save();
    }

    /** A small marker in the corner of the screen while the bridge is on, so it is never on without the player seeing it. */
    static void hud(dev.craftium.client.compat.Gfx g) {
        var state = indicator();
        if (state == State.OFF) return;
        var font = Minecraft.getInstance().font;
        boolean preview = ClientState.hasAiPending();
        String text = state == State.WORKING ? "AI working…" : preview ? "AI preview" : "AI";
        int accent = preview && state != State.WORKING ? 0xffc9a0ff : colour(state);
        int width = font.width(text) + 22, x = g.guiWidth() - width - 6, y = g.guiHeight() - 20; // bottom right, below the key hints and clear of the tips and toasts at the top
        g.fill(x, y, x + width, y + 14, 0xb0101923);
        g.outline(x, y, width, 14, accent);
        g.fill(x + 5, y + 4, x + 11, y + 10, accent);
        g.text(font, text, x + 16, y + 3, 0xffe8f1fa, false);
    }

    public static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (ClientState.options.aiBridge != running()) {
                if (ClientState.options.aiBridge) start(); else stop();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(AiBridge::stop, "craftium-ai-bridge-shutdown"));
    }

    public static boolean running() { synchronized (LOCK) { return socket != null; } }

    /**
     * The game folder the AI extension has to be pointed at when this is not the default .minecraft (a launcher profile, or a game
     * started from source): the one that holds config/craftium/bridge.json.
     */
    public static String gameFolder() {
        Path folder = FILE.toAbsolutePath().normalize().getParent().getParent().getParent();
        return folder.toString();
    }

    public static int port() { synchronized (LOCK) { return socket == null ? 0 : socket.getLocalPort(); } }

    /** Called with every status the server sends, after the client has taken it in. */
    static void statusReceived(Packets.Status status) {
        var waiting = awaiting;
        if (waiting != null) waiting.complete(status);
        // A read the server refused answers with an error status instead of data.
        var reading = awaitingData;
        if (reading != null && status.error()) reading.completeExceptionally(new IllegalArgumentException(status.message()));
    }

    /** Completed by the data the server sends for a read, which is how get_blocks and get_surface get their answer. */
    private static volatile CompletableFuture<String> awaitingData;

    /** Called with what the server read for the AI. */
    static void dataReceived(String json) {
        var reading = awaitingData;
        if (reading != null) reading.complete(json);
    }

    public static void start() {
        synchronized (LOCK) {
            if (socket != null) return;
            try {
                byte[] bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                token = HexFormat.of().formatHex(bytes);
                socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "craftium-ai-bridge-worker"); t.setDaemon(true); return t; });
                var acceptor = new Thread(() -> accept(socket), "craftium-ai-bridge");
                acceptor.setDaemon(true);
                acceptor.start();
                Files.createDirectories(FILE.getParent());
                var info = new JsonObject();
                info.addProperty("port", socket.getLocalPort());
                info.addProperty("token", token);
                info.addProperty("pid", ProcessHandle.current().pid());
                info.addProperty("protocol", PROTOCOL);
                info.addProperty("version", ClientHandshake.version());
                Path temp = FILE.resolveSibling("bridge.json.tmp");
                Files.writeString(temp, GSON.toJson(info));
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
                Craftium.LOG.info("Craftium AI bridge listening on 127.0.0.1:{}", socket.getLocalPort());
            } catch (IOException | RuntimeException e) {
                Craftium.LOG.warn("Could not start the Craftium AI bridge", e);
                stop();
                ClientState.options.aiBridge = false;
            }
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            socket = null;
            if (worker != null) worker.shutdownNow();
            worker = null;
            token = "";
            lastCall = 0;
            lastTool = "";
            working.set(0);
            try { Files.deleteIfExists(FILE); } catch (IOException ignored) {}
        }
    }

    // ---- HTTP ---------------------------------------------------------------------------------------------------

    private static void accept(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                ExecutorService pool;
                synchronized (LOCK) { pool = worker; }
                if (pool == null) { client.close(); continue; }
                pool.execute(() -> handle(client));
            } catch (IOException | RejectedExecutionException e) {
                if (server.isClosed()) return;
            }
        }
    }

    private static void handle(Socket client) {
        try (client) {
            client.setSoTimeout(5000);
            var in = new BufferedInputStream(client.getInputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] request = line.split(" ");
            var headers = new HashMap<String, String>();
            for (String header; (header = readLine(in)) != null && !header.isEmpty(); ) {
                int colon = header.indexOf(':');
                if (colon > 0) headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
                if (headers.size() > 50) { reply(client, 431, error("Too many headers")); return; }
            }
            int port = port();
            String host = headers.getOrDefault("host", "");
            // A web page can reach loopback too, so a browser's request (it names an Origin) or a rebound host name is refused outright.
            if (headers.containsKey("origin") || !(host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port))) { reply(client, 403, error("Forbidden")); return; }
            String presented = headers.getOrDefault("authorization", "");
            String expected = "Bearer " + token;
            if (token.isEmpty() || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) { reply(client, 401, error("Unauthorized")); return; }
            if (request.length < 2 || !request[0].equals("POST")) { reply(client, 405, error("Use POST /<tool>")); return; }
            int length;
            try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (NumberFormatException e) { reply(client, 400, error("Bad Content-Length")); return; }
            if (length < 0 || length > BODY_LIMIT) { reply(client, 413, error("Body exceeds 1 MB")); return; }
            byte[] body = in.readNBytes(length);
            if (body.length != length) { reply(client, 400, error("Incomplete body")); return; }
            JsonObject args;
            try {
                var parsed = body.length == 0 ? new JsonObject() : JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) { reply(client, 400, error("Body must be a JSON object")); return; }
                args = parsed.getAsJsonObject();
            } catch (JsonParseException e) { reply(client, 400, error("Body is not valid JSON")); return; }
            String tool = request[1].startsWith("/") ? request[1].substring(1) : request[1];
            JsonObject result;
            working.incrementAndGet();
            lastTool = tool.length() > 24 ? tool.substring(0, 24) : tool;
            try { result = call(tool, args); }
            catch (IllegalArgumentException | IllegalStateException e) { result = error(e.getMessage()); }
            catch (TimeoutException e) { result = error("Minecraft did not answer in time; is the game paused or loading?"); }
            catch (Exception e) { Craftium.LOG.warn("AI bridge tool {} failed", tool, e); result = error("Internal error: " + e.getClass().getSimpleName()); }
            finally { lastCall = System.currentTimeMillis(); working.decrementAndGet(); }
            reply(client, 200, result);
        } catch (IOException ignored) {
            // A dropped or slow connection is not worth reporting.
        }
    }

    /** One header or request line, without its line ending, or null when the connection ended before any of it arrived. */
    private static String readLine(InputStream in) throws IOException {
        var out = new ByteArrayOutputStream();
        int b = in.read();
        if (b == -1) return null;
        for (; b != -1 && b != 10; b = in.read()) { // 10 ends the line
            if (b != 13) out.write(b); // 13, the carriage return before it, is dropped
            if (out.size() > 8192) throw new IOException("Header line too long");
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static void reply(Socket client, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
        var out = client.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static JsonObject error(String message) {
        var o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message == null ? "Failed" : message);
        return o;
    }

    // ---- tools --------------------------------------------------------------------------------------------------

    private static JsonObject call(String tool, JsonObject args) throws Exception {
        return switch (tool) {
            case "get_state" -> onClient(() -> state(true));
            case "read_selection" -> read("ai_selection", null, null);
            case "get_preview_blocks" -> {
                var from = point(args, "from"); var to = point(args, "to");
                String view = args.has("view") ? args.get("view").getAsString() : "after";
                String format = args.has("format") ? args.get("format").getAsString() : "auto";
                // A server from before spec v2 reads the world only through the older, smaller read.
                if (view.equals("world") && !ClientHandshake.serverHas(dev.craftium.core.Handshake.AI_SPEC_V2)) yield read("ai_blocks", from, to);
                // The two settings travel in the action itself, so nothing is put in the shared options and nothing has to be put back.
                yield read("ai_preview_blocks:" + view + ":" + format, from, to);
            }
            case "get_blocks" -> { var from = point(args, "from"); var to = point(args, "to"); yield serverSupported() ? read("ai_blocks", from, to) : onClient(() -> blocks(from, to)); }
            case "get_surface" -> {
                var from = point(args, "from"); var to = point(args, "to");
                String kind = args.has("kind") ? args.get("kind").getAsString() : "top";
                if (!kind.equals("top") && !kind.equals("floor")) throw new IllegalArgumentException("\"kind\" must be \"top\" or \"floor\"");
                yield read(kind.equals("floor") ? "ai_surface_floor" : "ai_surface", from, to);
            }
            case "get_palette" -> onClient(AiBridge::palette);
            case "get_screenshot" -> screenshot();
            case "render_view" -> AiView.render(args);
            case "preview_build" -> {
                if (!args.has("spec")) throw new IllegalArgumentException("Missing \"spec\"");
                var element = args.get("spec");
                String spec = element.isJsonPrimitive() ? element.getAsString() : GSON.toJson(element);
                String mode = args.has("mode") ? args.get("mode").getAsString() : "replace";
                if (!mode.equals("replace") && !mode.equals("add")) throw new IllegalArgumentException("\"mode\" must be \"replace\" or \"add\"");
                boolean add = mode.equals("add");
                if (add && !ClientHandshake.serverHas(dev.craftium.core.Handshake.AI_SPEC_V2))
                    throw new IllegalArgumentException("This server's Craftium cannot add to a preview; send the whole build without \"mode\": \"add\", and tell the player the server needs an update for it");
                String action = add ? "ai_build_preview_add" : "ai_build_preview";
                yield request(action, () -> previewBuild(spec, action));
            }
            case "apply_preview" -> request("ai_apply", () -> CraftiumClient.send("ai_apply", null, null));
            case "cancel_preview" -> request("ai_cancel", () -> CraftiumClient.send("ai_cancel", null, null));
            case "undo" -> request("ai_undo", () -> CraftiumClient.send("ai_undo", null, null));
            default -> throw new IllegalArgumentException("Unknown tool \"" + tool + "\"");
        };
    }

    /** Runs {@code action} on the game's thread and returns what it produces. */
    static <T> T onClient(Callable<T> action) throws Exception {
        var future = new CompletableFuture<T>();
        Minecraft.getInstance().execute(() -> {
            try { future.complete(action.call()); } catch (Throwable t) { future.completeExceptionally(t); }
        });
        try { return future.get(WAIT_SECONDS, TimeUnit.SECONDS); }
        catch (ExecutionException e) { if (e.getCause() instanceof Exception cause) throw cause; throw e; }
    }

    private static boolean serverSupported() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && CraftiumClient.supported();
    }

    /** Asks the server to read part of its world and waits for the data, which comes back as a packet of its own rather than as a status. */
    static JsonObject read(String action, Point from, Point to) throws Exception {
        var data = new CompletableFuture<String>();
        pace();
        onClient(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) throw new IllegalStateException("Not in a world");
            if (!CraftiumClient.supported()) throw new IllegalStateException("This world has no Craftium on the server; install it there to read it");
            awaitingData = data;
            CraftiumClient.send(action, from, to);
            return null;
        });
        lastSent = System.nanoTime();
        try {
            var out = JsonParser.parseString(data.get(WAIT_SECONDS, TimeUnit.SECONDS)).getAsJsonObject();
            return out;
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : new IllegalStateException(String.valueOf(e.getCause()));
        } finally {
            if (awaitingData == data) awaitingData = null;
        }
    }

    /** Sends one editor request from the game thread and waits for the server's reply to it. */
    private static JsonObject request(String name, Runnable send) throws Exception {
        var reply = new CompletableFuture<Packets.Status>();
        // A new preview is described by the server just before its status (see AiWorld.previewSummary); a server from before that sends none.
        var summary = name.startsWith("ai_build_preview") ? new CompletableFuture<String>() : null;
        pace();
        onClient(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) throw new IllegalStateException("Not in a world");
            if (!CraftiumClient.supported()) throw new IllegalStateException("This world has no Craftium on the server; install it there to edit");
            awaiting = reply;
            if (summary != null) awaitingData = summary;
            send.run();
            return null;
        });
        lastSent = System.nanoTime();
        try {
            var status = reply.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return onClient(() -> {
                var out = new JsonObject();
                out.addProperty("ok", !status.error());
                out.addProperty(status.error() ? "error" : "message", status.message());
                if (!status.error() && summary != null && summary.isDone() && !summary.isCompletedExceptionally()) {
                    var preview = JsonParser.parseString(summary.join()).getAsJsonObject();
                    preview.remove("ok");
                    preview.remove("kind");
                    out.add("preview", preview);
                }
                var state = state(false);
                out.add("state", state);
                if (status.error() && summary != null && state.has("previewBlocks") && state.get("previewBlocks").getAsInt() > 0)
                    out.addProperty("previewKept", "The spec was refused, so nothing changed: your earlier preview (" + state.get("previewBlocks").getAsInt() + " blocks) is still waiting. Fix the spec and call preview_build again.");
                if (status.error() && summary != null && !ClientHandshake.serverHas(dev.craftium.core.Handshake.AI_SPEC_V2))
                    out.addProperty("serverOutdated", olderServer());
                return out;
            });
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getMessage());
        } finally {
            if (awaiting == reply) awaiting = null;
            if (summary != null && awaitingData == summary) awaitingData = null;
        }
    }

    /**
     * Why a spec may have been refused on this server: its Craftium predates spec v2, so roof, stairs, group, mixes and material@kind names
     * are unknown there, though the plain shapes still work.
     */
    private static String olderServer() {
        var server = ClientHandshake.server();
        return "This server runs " + (server == null ? "an older Craftium" : "Craftium " + server.version()) + ", which does not know roof, stairs, group, "
            + "{\"mix\": ...} or material@kind names. Build with the plain shapes and full block ids, and tell the player the server's Craftium needs an update for the rest.";
    }

    /** When the last request went to the server. The server silently drops a request that follows another within 65 ms, so they are kept further apart than that. */
    private static long lastSent;
    private static final long REQUEST_GAP_NANOS = 120_000_000L;

    private static void pace() throws InterruptedException {
        long wait = lastSent + REQUEST_GAP_NANOS - System.nanoTime();
        if (wait > 0) Thread.sleep(wait / 1_000_000L + 1);
    }

    private static void previewBuild(String spec, String action) {
        var o = ClientState.options;
        o.buildSpec = spec;
        // The spec rides along on this one request and is not kept in the saved settings.
        try { CraftiumClient.send(action, null, null); }
        finally { o.buildSpec = ""; }
    }

    /**
     * How big the waiting preview is and how much of it is drawn. The game draws only the first blocks of a big preview, in the
     * game and in render_view, so the AI is told outright when that happened: the rest is still built when applied.
     */
    static void previewReport(JsonObject out, GhostBlocks preview) {
        out.addProperty("previewBlocks", preview.total());
        out.addProperty("previewBlocksDrawn", preview.described());
        out.addProperty("previewTruncated", preview.truncated());
        if (preview.truncated()) out.addProperty("previewWarning", "Only " + preview.described() + " of " + preview.total()
            + " preview blocks are described to the game, which is all render_view and the ghost can show. The rest are still in the preview and will be built when it is applied, so do not judge the build from the picture. Build it in smaller stages to see all of it, or read it with get_preview_blocks.");
    }

    /**
     * The player, their selection, the AI's preview and undo list. {@code full} (get_state itself) adds what the AI needs once per task and
     * not after every edit: the world's height limits, sea level and dimension, the biome the player stands in (a hint of the style that
     * fits), and the player's current and recent blocks (a hint of their taste).
     */
    private static JsonObject state(boolean full) {
        var mc = Minecraft.getInstance();
        var out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("inWorld", mc.player != null && mc.level != null);
        out.addProperty("craftiumOnServer", CraftiumClient.supported());
        if (mc.player == null) return out;
        var player = new JsonObject();
        player.add("position", array(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
        player.add("block", array(mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ()));
        player.addProperty("facing", mc.player.getDirection().getName());
        player.addProperty("creative", mc.player.isCreative());
        out.add("player", player);
        var status = ClientState.status;
        var selection = new JsonObject();
        selection.addProperty("size", status.selectionSize());
        if (status.selectionSize() > 0 && status.min() != null && status.max() != null) {
            selection.add("min", array(status.min().x(), status.min().y(), status.min().z()));
            selection.add("max", array(status.max().x(), status.max().y(), status.max().z()));
            selection.add("dimensions", array(status.max().x() - status.min().x() + 1, status.max().y() - status.min().y() + 1, status.max().z() - status.min().z() + 1));
        }
        out.add("selection", selection);
        previewReport(out, ClientState.aiPending);
        out.addProperty("undoSteps", status.aiUndo());
        out.addProperty("busy", status.busy());
        out.addProperty("lastMessage", status.message());
        if (full && mc.level != null) {
            var level = mc.level;
            var world = new JsonObject();
            world.addProperty("dimension", dev.craftium.compat.Compat.keyName(level.dimension()));
            world.addProperty("minY", level.getMinY());
            world.addProperty("maxY", level.getMinY() + level.getHeight() - 1);
            world.addProperty("seaLevel", level.getSeaLevel());
            world.addProperty("biome", level.getBiome(mc.player.blockPosition()).unwrapKey().map(dev.craftium.compat.Compat::keyName).orElse("unknown"));
            out.add("world", world);
            var blocks = new JsonObject();
            blocks.addProperty("current", ClientState.options.block);
            var recent = new JsonArray();
            for (String block : ClientState.palette) recent.add(block);
            blocks.add("recent", recent);
            out.add("playerBlocks", blocks);
        }
        return out;
    }

    /**
     * What the player sees now, as a JPEG no wider than 1024 pixels, including a preview ghost and the interface. The game gets a
     * moment first to draw a frame with whatever was just staged or aimed.
     */
    private static JsonObject screenshot() throws Exception {
        Thread.sleep(250);
        var done = new CompletableFuture<JsonObject>();
        onClient(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) throw new IllegalStateException("Not in a world");
            boolean screenOpen = dev.craftium.client.compat.ClientCompat.screen(mc) != null;
            double yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
            net.minecraft.client.Screenshot.takeScreenshot(dev.craftium.client.compat.ClientCompat.mainTarget(mc), image -> {
                try { done.complete(encode(image, screenOpen, yaw, pitch)); }
                catch (Throwable t) { done.completeExceptionally(t); }
                finally { image.close(); }
            });
            return null;
        });
        try { return done.get(WAIT_SECONDS, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw new IllegalStateException("Could not capture the screen: " + e.getCause()); }
    }

    private static JsonObject encode(com.mojang.blaze3d.platform.NativeImage image, boolean screenOpen, double yaw, double pitch) throws IOException {
        int width = image.getWidth(), height = image.getHeight();
        var full = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        full.setRGB(0, 0, width, height, image.getPixels(), 0, width);
        var shown = full;
        if (width > 1024) {
            int w = 1024, h = Math.max(1, Math.round(height * 1024f / width));
            shown = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g = shown.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(full, 0, 0, w, h, null);
            g.dispose();
        }
        var writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
        var params = writer.getDefaultWriteParam();
        params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(0.8f);
        var bytes = new ByteArrayOutputStream();
        try (var out = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new javax.imageio.IIOImage(shown, null, null), params);
        } finally { writer.dispose(); }
        var reply = new JsonObject();
        reply.addProperty("ok", true);
        reply.addProperty("image", Base64.getEncoder().encodeToString(bytes.toByteArray()));
        reply.addProperty("mime", "image/jpeg");
        reply.addProperty("width", shown.getWidth());
        reply.addProperty("height", shown.getHeight());
        reply.addProperty("screenOpen", screenOpen);
        reply.add("view", array(Math.round(yaw * 10) / 10.0, Math.round(pitch * 10) / 10.0));
        return reply;
    }

    private static JsonObject palette() {
        var out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("activeBlock", ClientState.options.block);
        var list = new JsonArray();
        for (String block : ClientState.palette) list.add(block);
        out.add("palette", list);
        return out;
    }

    /**
     * Reads a box of the world as a palette and runs of palette indexes, running x first, then z, then y upward from the
     * box's minimum corner. Only what this client has loaded can be read; unloaded space reads as air.
     */
    private static JsonObject blocks(Point a, Point b) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) throw new IllegalStateException("Not in a world");
        int x0 = Math.min(a.x(), b.x()), x1 = Math.max(a.x(), b.x()), y0 = Math.min(a.y(), b.y()), y1 = Math.max(a.y(), b.y()), z0 = Math.min(a.z(), b.z()), z1 = Math.max(a.z(), b.z());
        long total = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (total > READ_BLOCKS) throw new IllegalArgumentException("Region has " + total + " blocks; read at most " + READ_BLOCKS + " at a time");
        var index = new LinkedHashMap<String, Integer>();
        var runs = new JsonArray();
        int last = -1, count = 0;
        var at = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    String state = BlockStateParser.serialize(mc.level.getBlockState(at.set(x, y, z)));
                    int id = index.computeIfAbsent(state, k -> index.size());
                    if (id == last) { count++; continue; }
                    if (count > 0) runs.add(array(last, count));
                    last = id;
                    count = 1;
                }
        if (count > 0) runs.add(array(last, count));
        var out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("source", "client: only chunks this game has loaded are read here, and anything else reads as air");
        out.add("min", array(x0, y0, z0));
        out.add("size", array(x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1));
        var names = new JsonArray();
        index.keySet().forEach(names::add);
        out.add("palette", names);
        out.addProperty("order", "x fastest, then z, then y; each run is [paletteIndex, count]");
        out.add("runs", runs);
        return out;
    }

    static Point point(JsonObject args, String key) {
        if (!args.has(key) || !args.get(key).isJsonArray() || args.getAsJsonArray(key).size() != 3) throw new IllegalArgumentException("\"" + key + "\" must be [x,y,z]");
        var list = args.getAsJsonArray(key);
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!list.get(i).isJsonPrimitive() || !list.get(i).getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("\"" + key + "\" must hold numbers");
            double d = list.get(i).getAsDouble();
            if (d != Math.rint(d) || Math.abs(d) > 30_000_000) throw new IllegalArgumentException("\"" + key + "\" must hold whole numbers");
            v[i] = (int) d;
        }
        return new Point(v[0], v[1], v[2]);
    }

    static JsonArray array(Number... values) {
        var out = new JsonArray();
        for (Number n : values) out.add(n);
        return out;
    }
}
