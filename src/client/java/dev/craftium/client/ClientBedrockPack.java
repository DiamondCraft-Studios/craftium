package dev.craftium.client;

import dev.craftium.net.Packets;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.file.*;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The client's half of exporting a Bedrock pack: the save dialog (which starts in the Documents folder), writing the {@code .mcpack} the server sends to the place the
 * player chose, and showing it in the system's file manager. Like a structure export, a file is only written for a request this client made, to the path it chose.
 */
final class ClientBedrockPack {
    private static final long WAIT = 60_000;
    private static final int LIMIT = 1_048_576;
    private static Path pending;
    private static long pendingUntil;
    /** Whether a saved pack is shown in the file manager. A game test turns it off so no window opens. */
    static boolean revealAfterSaving = true;
    private ClientBedrockPack() {}

    /** Where the save dialog starts: a Craftium folder in the player's Documents, or the home folder when there is no Documents folder. */
    static Path defaultDirectory() {
        Path documents = null;
        try { documents = javax.swing.filechooser.FileSystemView.getFileSystemView().getDefaultDirectory().toPath(); } catch (Throwable ignored) { /* no desktop classes: use the home folder */ }
        if (documents == null || !Files.isDirectory(documents)) {
            Path home = Path.of(System.getProperty("user.home", "."));
            documents = Files.isDirectory(home.resolve("Documents")) ? home.resolve("Documents") : home;
        }
        return documents.resolve("Craftium");
    }

    /** The system's save dialog, started in {@link #defaultDirectory} with the pack's file name; {@code then} runs on the game thread with the chosen path, never when the dialog is cancelled. */
    static void choose(String fileName, Consumer<Path> then) {
        Path directory = defaultDirectory();
        try { Files.createDirectories(directory); } catch (Exception ignored) { /* the dialog opens elsewhere */ }
        String start = directory.resolve(fileName).toString();
        var thread = new Thread(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.mcpack")).flip();
                String chosen = TinyFileDialogs.tinyfd_saveFileDialog("Export Bedrock pack", start, filters, "Bedrock pack (*.mcpack)");
                if (chosen == null) return;
                Path path = Path.of(chosen);
                String file = path.getFileName().toString();
                if (!file.toLowerCase(Locale.ROOT).endsWith(".mcpack")) path = path.resolveSibling(file + ".mcpack");
                Path result = path;
                Minecraft.getInstance().execute(() -> then.accept(result));
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> ClientState.notify("Could not open the file dialog: " + e.getMessage()));
            }
        }, "Craftium Bedrock pack export");
        thread.setDaemon(true);
        thread.start();
    }

    /** Remembers where the pack the server is about to send goes. */
    static void expect(Path path) {
        pending = path;
        pendingUntil = System.currentTimeMillis() + WAIT;
    }

    /** Writes the pack to the path chosen for it, then shows it in the file manager. Anything this client did not ask for is ignored. */
    static void receive(Packets.Download download) {
        Path path = pending;
        if (path == null || System.currentTimeMillis() > pendingUntil) return;
        pending = null;
        try {
            byte[] bytes = Base64.getDecoder().decode(download.data());
            if (bytes.length > LIMIT) throw new IllegalArgumentException("Export limit: 1 MiB");
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            ClientState.notify("Saved " + path);
            if (revealAfterSaving) reveal(path);
        } catch (Exception e) {
            ClientState.notify("Could not save the pack: " + e.getMessage());
        }
    }

    /** Shows the file in the system's file manager, selected where the system can do that. */
    static void reveal(Path file) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) new ProcessBuilder("explorer.exe", "/select," + file).start();
            else if (os.contains("mac")) new ProcessBuilder("open", "-R", file.toString()).start();
            else new ProcessBuilder("xdg-open", file.getParent() == null ? "." : file.getParent().toString()).start();
        } catch (Exception e) {
            ClientState.notify("Saved " + file + " (could not open the folder: " + e.getMessage() + ")");
        }
    }

    static boolean exporting() { return pending != null && System.currentTimeMillis() < pendingUntil; }
}
