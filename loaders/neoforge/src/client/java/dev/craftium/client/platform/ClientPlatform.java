package dev.craftium.client.platform;

import com.mojang.brigadier.CommandDispatcher;
import dev.craftium.client.compat.Gfx;
import dev.craftium.neoforge.Payloads;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What the shared client code asks of the mod loader, on NeoForge; see the Fabric class of the same name for the
 * contract. Key mappings and HUD layers are kept until NeoForge's events for them.
 */
public final class ClientPlatform {
    private ClientPlatform() {}

    private static final List<KeyMapping> KEYS = new ArrayList<>();
    private static final List<Consumer<String>> COMMANDS_SENT = new CopyOnWriteArrayList<>();

    /** Called by the client entrypoint with the mod's event bus. */
    public static void install(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> KEYS.forEach(event::register));
        modBus.addListener(
                RegisterGuiLayersEvent.class, event -> LAYERS.forEach((id, draw) -> event.registerAboveAll(id, draw)));
    }

    private static final java.util.Map<
                    net.minecraft.resources.ResourceLocation, net.minecraft.client.gui.LayeredDraw.Layer>
            LAYERS = new java.util.LinkedHashMap<>();

    public static <T extends CustomPacketPayload> void receive(CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        Payloads.onClient(type, handler);
    }

    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(type);
    }

    public static void send(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static KeyMapping registerKey(KeyMapping key) {
        KEYS.add(key);
        return key;
    }

    /** Draws on the HUD after the game's own elements; {@code name} is unique within Craftium. */
    public static void addHud(String name, Consumer<Gfx> draw) {
        LAYERS.put(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("craftium", name),
                (graphics, delta) -> draw.accept(Gfx.of(graphics)));
    }

    public static void onDisconnect(Runnable listener) {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> listener.run());
    }

    public static void onEndTick(Consumer<Minecraft> listener) {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> listener.accept(Minecraft.getInstance()));
    }

    /** A player starting to break a block on either side; PASS lets the game go on as usual. */
    public interface AttackBlock {
        InteractionResult attack(Player player, Level level, InteractionHand hand, BlockPos pos, Direction face);
    }

    public static void onAttackBlock(AttackBlock listener) {
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.LeftClickBlock.class, event -> {
            if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
            var face = event.getFace() == null ? Direction.UP : event.getFace();
            var result = listener.attack(event.getEntity(), event.getLevel(), event.getHand(), event.getPos(), face);
            if (result != InteractionResult.PASS) event.setCanceled(true);
        });
    }

    /** Client-side commands; answer through {@link #feedback} and {@link #error}. */
    public static void onClientCommands(Consumer<CommandDispatcher<Object>> registrar) {
        NeoForge.EVENT_BUS.addListener(
                RegisterClientCommandsEvent.class, event -> registrar.accept(erase(event.getDispatcher())));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CommandDispatcher<Object> erase(CommandDispatcher<CommandSourceStack> dispatcher) {
        return (CommandDispatcher) dispatcher;
    }

    /** Called with each command the player sends to the server (without the slash). */
    public static void onCommandSent(Consumer<String> listener) {
        COMMANDS_SENT.add(listener);
    }

    /** The player sent a command; called by the NeoForge client mixin. */
    public static void commandSent(String command) {
        for (var listener : COMMANDS_SENT) listener.accept(command);
    }

    public static void feedback(Object source, Component message) {
        ((CommandSourceStack) source).sendSuccess(() -> message, false);
    }

    public static void error(Object source, Component message) {
        ((CommandSourceStack) source).sendFailure(message);
    }
}
