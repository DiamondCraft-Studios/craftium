package dev.craftium.client;

import dev.craftium.core.*;
import dev.craftium.core.EditorControls.*;
import dev.craftium.net.Packets;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.*;
import org.lwjgl.glfw.GLFW;

final class EditorBindings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("craftium-controls.json");
    private static final EnumMap<Action, Binding> bindings = new EnumMap<>(Action.class);
    private static boolean migrated;

    private record Saved(int version, boolean migrated, Map<Action, Binding> bindings) {}

    static {
        reset();
    }

    private EditorBindings() {}

    static void reset() {
        for (Action a : Action.values()) bindings.put(a, a.defaults);
    }

    static Binding get(Action action) {
        return bindings.get(action);
    }

    static void set(Action action, Binding binding) {
        bindings.put(action, binding);
        save();
    }

    static void load() {
        try {
            if (Files.exists(FILE)) {
                var saved = Packets.JSON.fromJson(Files.readString(FILE), Saved.class);
                if (saved != null && saved.bindings != null) {
                    for (var e : saved.bindings.entrySet())
                        if (!(saved.version < 2 && isFormerFillDefault(e.getKey(), e.getValue()))
                                && e.getValue() != null
                                && e.getValue().key() >= -1
                                && (!e.getKey().keyboardOnly() || !e.getValue().mouse())
                                && (!e.getKey().mouseOnly()
                                        || e.getValue().mouse()
                                        || e.getValue().key() < 0)
                                && (e.getValue().modifiers() & ~7) == 0) bindings.put(e.getKey(), e.getValue());
                    migrated = saved.migrated;
                }
            }
        } catch (Exception e) {
            dev.craftium.Craftium.LOG.warn("Cannot read Craftium editor bindings", e);
        }
    }

    /** Quick fill and replace used to share Ctrl+F and Ctrl+R with flip and rotate; a saved copy of that old default moves to the new one. */
    private static boolean isFormerFillDefault(Action action, Binding binding) {
        return (action == Action.QUICK_FILL
                        && Binding.key('F', EditorControls.CONTROL).equals(binding))
                || (action == Action.QUICK_REPLACE
                        && Binding.key('R', EditorControls.CONTROL).equals(binding));
    }

    static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(temp, Packets.JSON.toJson(new Saved(2, migrated, bindings)));
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            dev.craftium.Craftium.LOG.warn("Cannot save Craftium editor bindings", e);
        }
    }

    static void migrate(Minecraft mc) {
        if (migrated) return;
        migrated = true;
        try {
            Path options = mc.gameDirectory.toPath().resolve("options.txt"),
                    backup = FILE.resolveSibling("craftium-controls-before-update-options.txt");
            if (Files.exists(options) && !Files.exists(backup)) Files.copy(options, backup);
        } catch (Exception e) {
            dev.craftium.Craftium.LOG.warn("Cannot back up previous key mappings", e);
        }
        // Only replace recognizable former factory values; other custom mappings survive.
        for (var e : CraftiumClient.toolKeys.entrySet()) {
            int old = e.getKey() == Tool.BOX_SELECT ? 'B' : e.getKey() == Tool.MAGIC_SELECT ? 'M' : -1;
            if (e.getValue()
                    .saveString()
                    .equals(old < 0 ? "key.keyboard.unknown" : "key.keyboard." + (char) Character.toLowerCase(old)))
                e.getValue().setKey(e.getValue().getDefaultKey());
        }
        if (CraftiumClient.swapKey.saveString().equals("key.keyboard.z"))
            CraftiumClient.swapKey.setKey(CraftiumClient.swapKey.getDefaultKey());
        net.minecraft.client.KeyMapping.resetMapping();
        mc.options.save();
        save();
    }

    static boolean matches(Action action, KeyEvent event) {
        return get(action).matches(false, event.key(), event.modifiers());
    }

    static boolean matches(Action action, MouseButtonEvent event) {
        return get(action).matches(true, event.button(), event.buttonInfo().modifiers());
    }

    static boolean held(Action action) {
        var b = get(action);
        if (b.key() < 0) return false;
        var mc = Minecraft.getInstance();
        long window = mc.getWindow().handle();
        int modifiers = (mc.hasShiftDown() ? 1 : 0) | (mc.hasControlDown() ? 2 : 0) | (mc.hasAltDown() ? 4 : 0);
        return b.modifiers() == modifiers
                && (b.mouse()
                        ? GLFW.glfwGetMouseButton(window, b.key()) == GLFW.GLFW_PRESS
                        : com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), b.key()));
    }

    static String label(Action action) {
        return label(get(action));
    }

    static String label(Binding binding) {
        if (binding.key() < 0) return "Unbound";
        String key = binding.mouse()
                ? switch (binding.key()) {
                    case 0 -> "LMB";
                    case 1 -> "RMB";
                    case 2 -> "MMB";
                    default -> "Mouse " + (binding.key() + 1);
                }
                : com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
                        .getOrCreate(binding.key())
                        .getDisplayName()
                        .getString();
        return ((binding.modifiers() & 2) != 0 ? "Ctrl+" : "")
                + ((binding.modifiers() & 1) != 0 ? "Shift+" : "")
                + ((binding.modifiers() & 4) != 0 ? "Alt+" : "")
                + key;
    }
}
