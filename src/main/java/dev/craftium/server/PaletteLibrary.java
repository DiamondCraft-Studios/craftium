package dev.craftium.server;

import dev.craftium.core.Handshake;
import dev.craftium.core.SharedPalettes;
import dev.craftium.net.Packets;
import dev.craftium.runtime.ServerPlatform;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/** The server's shared palettes: everyone can read the list, only operators change it (the caller checks). */
final class PaletteLibrary {
    /** What a status carries of the list, in characters: a status packet has a limit and the rest of it needs room. */
    private static final int BUDGET = 400_000;

    private PaletteLibrary() {}

    private static SharedPalettes store() {
        return new SharedPalettes(ServerPlatform.assets().resolve("palettes"));
    }

    /** What a client sends to save a palette, as JSON in the request's {@code assetData}; for a delete only {@code name} is read. */
    record Entry(String name, List<String> blocks, List<Double> weights) {}

    /** The whole library as the status carries it. */
    static String json() {
        return SharedPalettes.toJson(store().list(), BUDGET);
    }

    /** Does the action and answers with what to tell the player. */
    static String act(String action, String data, ServerPlayer player) {
        switch (action) {
            case "palette_list" -> {
                return "Palettes · " + store().list().size() + " on the server";
            }
            case "palette_save" -> {
                var entry = read(data);
                var saved = store().save(
                                entry.name(),
                                player.getGameProfile().name(),
                                player.getUUID().toString(),
                                entry.blocks(),
                                entry.weights());
                return "Palettes · saved " + saved.name() + " for everyone on the server";
            }
            case "palette_delete" -> {
                var entry = read(data);
                store().delete(entry.name());
                return "Palettes · deleted " + entry.name().trim();
            }
            default -> throw new IllegalArgumentException("Unknown palette action");
        }
    }

    private static Entry read(String data) {
        try {
            var entry = Packets.JSON.fromJson(data, Entry.class);
            if (entry == null || entry.name() == null) throw new IllegalArgumentException("Name the palette");
            return entry;
        } catch (com.google.gson.JsonParseException e) {
            throw new IllegalArgumentException("The palette could not be read");
        }
    }

    /** Sends the list to every other player whose client knows palettes, after somebody changed it. */
    static void push(ServerPlayer changer) {
        var server = changer.level().getServer();
        if (server == null) return;
        String list = json();
        for (var player : server.getPlayerList().getPlayers()) {
            if (player == changer
                    || !ServerPlatform.canSend(player)
                    || !Handshake.clientHas(player.getUUID(), Handshake.SHARED_PALETTES)) continue;
            ServerPlatform.send(player, new Packets.Update(Packets.JSON.toJson(Packets.palettesOnly(list))));
        }
    }
}
