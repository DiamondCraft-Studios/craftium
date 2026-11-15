package dev.craftium.server;

import dev.craftium.core.Options;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

final class HotbarLibrary {
    private HotbarLibrary() {}

    static String cycle(ServerPlayer player, Session session, Options o, int direction) {
        Path directory = player.level()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("craftium/hotbars")
                .resolve(player.getUUID().toString());
        try {
            Files.createDirectories(directory);
            Path active = directory.resolve("active.txt");
            if (session.currentHotbar < 0) {
                try {
                    session.currentHotbar =
                            Integer.parseInt(Files.readString(active).trim());
                } catch (Exception e) {
                    session.currentHotbar = 0;
                }
            }
            String original = o.name;
            try {
                o.name = "Slot-" + session.currentHotbar;
                apply("hotbar_save", player, o);
                session.currentHotbar = Math.clamp(session.currentHotbar + direction, 0, 4095);
                o.name = "Slot-" + session.currentHotbar;
                if (Files.exists(directory.resolve(o.name + ".nbt"))) apply("hotbar_load", player, o);
                else {
                    for (int i = 0; i < 9; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
                    player.inventoryMenu.broadcastChanges();
                }
            } finally {
                o.name = original;
            }
            Files.writeString(active, Integer.toString(session.currentHotbar));
            return "Hotbar slot " + session.currentHotbar
                    + " · scroll to switch · current items are saved automatically";
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not cycle saved hotbars", e);
        }
    }

    static String apply(String action, ServerPlayer player, Options o) {
        if (!o.name.matches("[A-Za-z0-9 _.-]{1,80}") || o.name.equals(".") || o.name.equals(".."))
            throw new IllegalArgumentException("Choose a plain hotbar name");
        Path
                directory =
                        player.level()
                                .getServer()
                                .getWorldPath(LevelResource.ROOT)
                                .resolve("craftium/hotbars")
                                .resolve(player.getUUID().toString()),
                file = directory.resolve(o.name + ".nbt");
        try {
            var ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            if (action.equals("hotbar_save")) {
                var slots = new ListTag();
                for (int i = 0; i < 9; i++)
                    slots.add(ItemStack.OPTIONAL_CODEC
                            .encodeStart(ops, player.getInventory().getItem(i))
                            .getOrThrow());
                var tag = new CompoundTag();
                tag.put("slots", slots);
                Files.createDirectories(directory);
                NbtIo.writeCompressed(tag, file);
                return "Saved hotbar " + o.name;
            }
            if (action.equals("hotbar_load")) {
                var slots = NbtIo.readCompressed(file, NbtAccounter.create(1_048_576))
                        .getListOrEmpty("slots");
                if (slots.size() != 9) throw new IllegalArgumentException("Hotbar must contain nine slots");
                var stacks = new ArrayList<ItemStack>();
                for (int i = 0; i < 9; i++)
                    stacks.add(ItemStack.OPTIONAL_CODEC.parse(ops, slots.get(i)).getOrThrow());
                for (int i = 0; i < 9; i++) player.getInventory().setItem(i, stacks.get(i));
                player.inventoryMenu.broadcastChanges();
                return "Loaded hotbar " + o.name;
            }
            if (action.equals("hotbar_list")) {
                if (!Files.isDirectory(directory)) return "No saved hotbars";
                try (var files = Files.list(directory)) {
                    return "Saved hotbars\n\n"
                            + String.join(
                                    "\n",
                                    files.filter(p -> p.getFileName().toString().endsWith(".nbt"))
                                            .map(p -> p.getFileName().toString().replaceFirst("\\.nbt$", ""))
                                            .sorted()
                                            .limit(2048)
                                            .toList());
                }
            }
            throw new IllegalArgumentException("Unknown hotbar action");
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not access saved hotbar", e);
        }
    }
}
