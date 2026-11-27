package dev.craftium;

import dev.craftium.net.Packets;
import dev.craftium.platform.Platform;
import dev.craftium.server.EditorServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Craftium {
    public static final Logger LOG = LoggerFactory.getLogger("Craftium");

    private Craftium() {}

    /** Sets up the shared server side; the loader's entrypoint calls this once at startup. */
    public static void init() {
        Platform.onRegister(
                net.minecraft.core.registries.Registries.BLOCK, dev.craftium.server.MissingBlocks::registerBlock);
        Platform.onRegister(
                net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE,
                dev.craftium.server.MissingBlocks::registerBlockEntity);
        Platform.onServerStarting(dev.craftium.server.MissingBlocks::bind);
        Platform.onServerStopped(server -> dev.craftium.server.MissingBlocks.unbind());
        dev.craftium.runtime.ServerPlatform.install(
                Platform.configDir().resolve("craftium"), new dev.craftium.runtime.ServerPlatform.Host() {
                    public boolean canSend(net.minecraft.server.level.ServerPlayer player) {
                        return Platform.canSend(player, Packets.Update.TYPE);
                    }

                    public void send(
                            net.minecraft.server.level.ServerPlayer player,
                            net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
                        Platform.send(player, payload);
                    }
                });
        dev.craftium.server.ServerCommands.register();
        Platform.serverbound(Packets.Bridge.TYPE, Packets.Bridge.CODEC, 0);
        Platform.clientbound(Packets.Bridge.TYPE, Packets.Bridge.CODEC, 0);
        Platform.serverbound(Packets.Edit.TYPE, Packets.Edit.CODEC, 16_000_000);
        Platform.clientbound(Packets.Update.TYPE, Packets.Update.CODEC, 16_000_000);
        Platform.clientbound(Packets.MissingBlocks.TYPE, Packets.MissingBlocks.CODEC, 0);
        Platform.clientbound(Packets.Progress.TYPE, Packets.Progress.CODEC, 0);
        Platform.clientbound(Packets.Report.TYPE, Packets.Report.CODEC, 400_000);
        Platform.clientbound(Packets.AiReply.TYPE, Packets.AiReply.CODEC, 1_100_000);
        Platform.clientbound(Packets.Download.TYPE, Packets.Download.CODEC, 4_500_000);
        Platform.serverbound(Packets.Hello.TYPE, Packets.Hello.CODEC, 0);
        Platform.clientbound(Packets.Hello.TYPE, Packets.Hello.CODEC, 0);
        // The server greets a client that has the mod; the client answers with its own, and either side names a
        // mismatch.
        String version = Platform.modVersion();
        Platform.onPlayerJoin(dev.craftium.server.MissingBlocks::send);
        Platform.onPlayerJoin(player -> {
            if (Platform.canSend(player, Packets.Hello.TYPE))
                Platform.send(
                        player,
                        new Packets.Hello(dev.craftium.core.Handshake.write(
                                version, dev.craftium.core.Handshake.FABRIC_SERVER_CAPABILITIES)));
        });
        Platform.receive(
                Packets.Hello.TYPE,
                (player, payload) -> dev.craftium.server.HandshakeServer.received(player, payload.json()));
        Platform.receive(Packets.Edit.TYPE, (player, payload) -> EditorServer.handle(player, payload.json()));
        Platform.onServerTick(EditorServer::tick);
        // Prevent block interactions: the held item is used on the block as it is when sneaking, so blocks still place
        // but chests, grindstones and the like do not open.
        Platform.onUseBlock((player, level, hand, hit) -> {
            if (!(player instanceof net.minecraft.server.level.ServerPlayer))
                return net.minecraft.world.InteractionResult.PASS;
            var options = dev.craftium.runtime.PlayerCapabilities.of(player);
            if (options == null || !options.preventInteractions) return net.minecraft.world.InteractionResult.PASS;
            var stack = player.getItemInHand(hand);
            if (stack.isEmpty()) return net.minecraft.world.InteractionResult.FAIL;
            return stack.useOn(new net.minecraft.world.item.context.UseOnContext(player, hand, hit));
        });
        Platform.onPlayerLeave(player -> {
            dev.craftium.core.Handshake.forget(player.getUUID());
            EditorServer.disconnect(player);
        });
        Platform.onServerStopped(server -> EditorServer.clear());
        Platform.onServerStopping(EditorServer::flushHistory);
        LOG.info("Craftium building studio initialized on " + Platform.name());
    }
}
