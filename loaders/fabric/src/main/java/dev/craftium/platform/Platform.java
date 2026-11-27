package dev.craftium.platform;

import com.mojang.brigadier.CommandDispatcher;
import dev.craftium.compat.FabricCompat;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * What the shared code asks of the mod loader. Every loader supplies a class at this path from {@code loaders/<loader>},
 * and a module compiles the one for its loader. A call belongs here only when its answer comes from the loader;
 * differences between Minecraft versions belong in {@code Compat}.
 */
public final class Platform {
    private Platform() {}

    /** "Fabric" or "NeoForge", for logs and messages. */
    public static String name() {
        return "Fabric";
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    /** This mod's version, as the loader read it from the mod's metadata. */
    public static String modVersion() {
        return FabricLoader.getInstance()
                .getModContainer("craftium")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /** This mod's one-line description from its metadata. */
    public static String modDescription() {
        return FabricLoader.getInstance()
                .getModContainer("craftium")
                .map(m -> m.getMetadata().getDescription())
                .orElse("");
    }

    /** The display name of an installed mod, by its id. */
    public static Optional<String> modName(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(m -> m.getMetadata().getName());
    }

    public static boolean isModLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    /**
     * Runs {@code action}, which adds entries to the registry {@code registry}, while the loader takes entries for it.
     * Fabric takes them at any time during startup, so this runs it now.
     */
    public static void onRegister(
            net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>> registry, Runnable action) {
        action.run();
    }

    /** Registers a payload the server sends; {@code maxSize} is the largest it may encode to. */
    public static <T extends CustomPacketPayload> void clientbound(
            CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, int maxSize) {
        FabricCompat.clientbound(type, codec, maxSize);
    }

    /** Registers a payload the client sends; {@code maxSize} is the largest it may encode to. */
    public static <T extends CustomPacketPayload> void serverbound(
            CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, int maxSize) {
        FabricCompat.serverbound(type, codec, maxSize);
    }

    /** Handles a payload from a player, on the server thread. */
    public static <T extends CustomPacketPayload> void receive(
            CustomPacketPayload.Type<T> type, BiConsumer<ServerPlayer, T> handler) {
        FabricCompat.receive(type, handler);
    }

    /** Whether the player's game has a receiver for this payload. */
    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return FabricCompat.canSend(player, type);
    }

    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        FabricCompat.send(player, payload);
    }

    public static void onCommands(Consumer<CommandDispatcher<CommandSourceStack>> registrar) {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> registrar.accept(dispatcher));
    }

    public static void onServerStarting(Consumer<MinecraftServer> listener) {
        ServerLifecycleEvents.SERVER_STARTING.register(listener::accept);
    }

    public static void onServerStopping(Consumer<MinecraftServer> listener) {
        ServerLifecycleEvents.SERVER_STOPPING.register(listener::accept);
    }

    public static void onServerStopped(Consumer<MinecraftServer> listener) {
        ServerLifecycleEvents.SERVER_STOPPED.register(listener::accept);
    }

    public static void onServerTick(Consumer<MinecraftServer> listener) {
        ServerTickEvents.END_SERVER_TICK.register(listener::accept);
    }

    public static void onPlayerJoin(Consumer<ServerPlayer> listener) {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> listener.accept(handler.player));
    }

    public static void onPlayerLeave(Consumer<ServerPlayer> listener) {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> listener.accept(handler.player));
    }

    /** A player using an item on a block, on either side; PASS lets the game go on as usual. */
    public interface UseBlock {
        InteractionResult use(Player player, Level level, InteractionHand hand, BlockHitResult hit);
    }

    public static void onUseBlock(UseBlock listener) {
        UseBlockCallback.EVENT.register(listener::use);
    }
}
