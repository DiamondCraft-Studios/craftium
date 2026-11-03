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
        dev.craftium.compat.FabricCompat.serverboundPlay().registerLarge(Packets.Edit.TYPE,Packets.Edit.CODEC,6_000_000);
        dev.craftium.compat.FabricCompat.clientboundPlay().registerLarge(Packets.Update.TYPE,Packets.Update.CODEC,6_000_000);
        dev.craftium.compat.FabricCompat.clientboundPlay().registerLarge(Packets.Report.TYPE,Packets.Report.CODEC,400_000);
        dev.craftium.compat.FabricCompat.clientboundPlay().registerLarge(Packets.Download.TYPE,Packets.Download.CODEC,4_500_000);
        ServerPlayNetworking.registerGlobalReceiver(Packets.Edit.TYPE, (payload,context) ->
            context.server().execute(() -> EditorServer.handle(context.player(),payload.json())));
        ServerTickEvents.END_SERVER_TICK.register(EditorServer::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> EditorServer.disconnect(handler.player));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> EditorServer.clear());
        ServerLifecycleEvents.SERVER_STOPPING.register(EditorServer::flushHistory);
        LOG.info("Craftium building studio initialized for Minecraft 26.2");
    }
}
