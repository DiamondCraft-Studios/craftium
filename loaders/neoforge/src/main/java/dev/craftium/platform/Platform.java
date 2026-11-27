package dev.craftium.platform;

import com.mojang.brigadier.CommandDispatcher;
import dev.craftium.neoforge.Payloads;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * What the shared code asks of the mod loader, on NeoForge; see the Fabric class of the same name for the contract.
 * Registrations that NeoForge only takes inside its own events (registry entries, payloads) are kept until then.
 */
public final class Platform {
    private Platform() {}

    /** The mod's event bus, set by the entrypoint before anything else runs. */
    public static IEventBus modBus;

    public static String name() {
        return "NeoForge";
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    public static String modVersion() {
        return ModList.get()
                .getModContainerById("craftium")
                .map(m -> m.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    public static String modDescription() {
        return ModList.get()
                .getModContainerById("craftium")
                .map(m -> m.getModInfo().getDescription().strip())
                .orElse("");
    }

    public static Optional<String> modName(String id) {
        return ModList.get().getModContainerById(id).map(m -> m.getModInfo().getDisplayName());
    }

    public static boolean isModLoaded(String id) {
        return ModList.get().isLoaded(id);
    }

    /** Runs {@code action} inside NeoForge's register event for {@code registry}, when entries may be added to it. */
    public static void onRegister(ResourceKey<? extends Registry<?>> registry, Runnable action) {
        modBus.addListener(RegisterEvent.class, event -> {
            if (event.getRegistryKey().equals(registry)) action.run();
        });
    }

    public static <T extends CustomPacketPayload> void clientbound(
            CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, int maxSize) {
        Payloads.declare(type, codec, true);
    }

    public static <T extends CustomPacketPayload> void serverbound(
            CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, int maxSize) {
        Payloads.declare(type, codec, false);
    }

    public static <T extends CustomPacketPayload> void receive(
            CustomPacketPayload.Type<T> type, BiConsumer<ServerPlayer, T> handler) {
        Payloads.onServer(type, handler);
    }

    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection.hasChannel(type);
    }

    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void onCommands(Consumer<CommandDispatcher<CommandSourceStack>> registrar) {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> registrar.accept(event.getDispatcher()));
    }

    public static void onServerStarting(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener(ServerStartingEvent.class, event -> listener.accept(event.getServer()));
    }

    public static void onServerStopping(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> listener.accept(event.getServer()));
    }

    public static void onServerStopped(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> listener.accept(event.getServer()));
    }

    public static void onServerTick(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> listener.accept(event.getServer()));
    }

    public static void onPlayerJoin(Consumer<ServerPlayer> listener) {
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) listener.accept(player);
        });
    }

    public static void onPlayerLeave(Consumer<ServerPlayer> listener) {
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) listener.accept(player);
        });
    }

    /** A player using an item on a block, on either side; PASS lets the game go on as usual. */
    public interface UseBlock {
        InteractionResult use(Player player, Level level, InteractionHand hand, BlockHitResult hit);
    }

    public static void onUseBlock(UseBlock listener) {
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, event -> {
            var result = listener.use(event.getEntity(), event.getLevel(), event.getHand(), event.getHitVec());
            if (result != InteractionResult.PASS) {
                event.setCancellationResult(result);
                event.setCanceled(true);
            }
        });
    }
}
