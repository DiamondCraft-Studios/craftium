package dev.craftium.client.platform;

import com.mojang.brigadier.CommandDispatcher;
import dev.craftium.client.compat.FabricClientCompat;
import dev.craftium.client.compat.Gfx;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** What the shared client code asks of the mod loader; see {@code dev.craftium.platform.Platform} for the common half. */
public final class ClientPlatform {
    private ClientPlatform() {}

    /** Handles a payload from the server on the network thread; the handler moves to the client thread itself. */
    public static <T extends CustomPacketPayload> void receive(CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        FabricClientCompat.receive(type, handler);
    }

    /** Whether the server has a receiver for this payload. */
    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        return FabricClientCompat.canSend(type);
    }

    public static void send(CustomPacketPayload payload) {
        FabricClientCompat.send(payload);
    }

    public static KeyMapping registerKey(KeyMapping key) {
        return FabricClientCompat.registerKey(key);
    }

    /** Draws on the HUD after the game's own elements; {@code name} is unique within Craftium. */
    public static void addHud(String name, Consumer<Gfx> draw) {
        FabricClientCompat.addHud(name, draw);
    }

    public static void onDisconnect(Runnable listener) {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> listener.run());
    }

    public static void onEndTick(Consumer<Minecraft> listener) {
        ClientTickEvents.END_CLIENT_TICK.register(listener::accept);
    }

    /** A player starting to break a block on either side; PASS lets the game go on as usual. */
    public interface AttackBlock {
        InteractionResult attack(Player player, Level level, InteractionHand hand, BlockPos pos, Direction face);
    }

    public static void onAttackBlock(AttackBlock listener) {
        AttackBlockCallback.EVENT.register(listener::attack);
    }

    /**
     * Client-side commands. The dispatcher's source is the loader's own client command source: answer through
     * {@link #feedback} and {@link #error}.
     */
    public static void onClientCommands(Consumer<CommandDispatcher<Object>> registrar) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> registrar.accept(erase(dispatcher)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CommandDispatcher<Object> erase(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        return (CommandDispatcher) dispatcher;
    }

    /** Called with each command the player sends to the server (without the slash). */
    public static void onCommandSent(Consumer<String> listener) {
        ClientSendMessageEvents.COMMAND.register(listener::accept);
    }

    public static void feedback(Object source, Component message) {
        ((FabricClientCommandSource) source).sendFeedback(message);
    }

    public static void error(Object source, Component message) {
        ((FabricClientCommandSource) source).sendError(message);
    }
}
