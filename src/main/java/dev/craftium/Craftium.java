package dev.craftium;

import dev.craftium.net.Packets;
import dev.craftium.server.EditorServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Craftium implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Craftium");
    @Override public void onInitialize() {
        dev.craftium.runtime.ServerPlatform.install(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("craftium"),new dev.craftium.runtime.ServerPlatform.Host(){
            public boolean canSend(net.minecraft.server.level.ServerPlayer player){return ServerPlayNetworking.canSend(player,Packets.Update.TYPE);}
            public void send(net.minecraft.server.level.ServerPlayer player,net.minecraft.network.protocol.common.custom.CustomPacketPayload payload){ServerPlayNetworking.send(player,payload);}
        });
        dev.craftium.server.ServerCommands.register();
        dev.craftium.compat.FabricCompat.serverboundPlay().register(Packets.Bridge.TYPE,Packets.Bridge.CODEC);
        dev.craftium.compat.FabricCompat.clientboundPlay().register(Packets.Bridge.TYPE,Packets.Bridge.CODEC);
        dev.craftium.compat.FabricCompat.serverboundPlay().registerLarge(Packets.Edit.TYPE,Packets.Edit.CODEC,16_000_000);
        dev.craftium.compat.FabricCompat.clientboundPlay().registerLarge(Packets.Update.TYPE,Packets.Update.CODEC,16_000_000);
        dev.craftium.compat.FabricCompat.clientboundPlay().registerLarge(Packets.Report.TYPE,Packets.Report.CODEC,400_000);
        dev.craftium.compat.FabricCompat.clientboundPlay().registerLarge(Packets.AiReply.TYPE,Packets.AiReply.CODEC,1_100_000);
        dev.craftium.compat.FabricCompat.clientboundPlay().registerLarge(Packets.Download.TYPE,Packets.Download.CODEC,4_500_000);
        dev.craftium.compat.FabricCompat.serverboundPlay().register(Packets.Hello.TYPE,Packets.Hello.CODEC);
        dev.craftium.compat.FabricCompat.clientboundPlay().register(Packets.Hello.TYPE,Packets.Hello.CODEC);
        // The server greets a client that has the mod; the client answers with its own, and either side names a mismatch.
        String version=net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("craftium").map(m->m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{if(ServerPlayNetworking.canSend(handler.player,Packets.Hello.TYPE))ServerPlayNetworking.send(handler.player,new Packets.Hello(dev.craftium.core.Handshake.write(version,dev.craftium.core.Handshake.FABRIC_SERVER_CAPABILITIES)));});
        ServerPlayNetworking.registerGlobalReceiver(Packets.Hello.TYPE,(payload,context)->context.server().execute(()->dev.craftium.server.HandshakeServer.received(context.player(),payload.json())));
        ServerPlayNetworking.registerGlobalReceiver(Packets.Edit.TYPE, (payload,context) ->
            context.server().execute(() -> EditorServer.handle(context.player(),payload.json())));
        ServerTickEvents.END_SERVER_TICK.register(EditorServer::tick);
        // Prevent block interactions: the held item is used on the block as it is when sneaking, so blocks still place but chests, grindstones and the like do not open.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player,level,hand,hit)->{
            if(!(player instanceof net.minecraft.server.level.ServerPlayer))return net.minecraft.world.InteractionResult.PASS;
            var options=dev.craftium.runtime.PlayerCapabilities.of(player);
            if(options==null||!options.preventInteractions)return net.minecraft.world.InteractionResult.PASS;
            var stack=player.getItemInHand(hand);
            if(stack.isEmpty())return net.minecraft.world.InteractionResult.FAIL;
            return stack.useOn(new net.minecraft.world.item.context.UseOnContext(player,hand,hit));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> {dev.craftium.core.Handshake.forget(handler.player.getUUID());EditorServer.disconnect(handler.player);});
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> EditorServer.clear());
        ServerLifecycleEvents.SERVER_STOPPING.register(EditorServer::flushHistory);
        LOG.info("Craftium building studio initialized for Minecraft 26.2");
    }
}
