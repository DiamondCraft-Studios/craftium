package dev.craftium.paper;

import dev.craftium.core.*;
import dev.craftium.net.Packets;
import dev.craftium.runtime.ServerPlatform;
import dev.craftium.server.EditorServer;
import dev.craftium.paper.api.CraftiumEditEvent;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.util.*;

/** Paper/Purpur host for the same native world-edit engine used by the Fabric mod. */
public final class CraftiumPlugin extends JavaPlugin implements Listener,PluginMessageListener,ServerPlatform.Host {
    private static final Set<String> FABRIC_RULES=Set.of("world_block_gravity","world_trample_farmland","world_block_drops","world_player_invulnerability");
    private static final class Connection {
        final BridgeCodec.Assembler incoming=new BridgeCodec.Assembler();final Deque<byte[]> outgoing=new ArrayDeque<>();
        int queued,frames,requests;long window=System.nanoTime();boolean ready;
    }
    private final Map<UUID,Connection> connections=new HashMap<>();
    private MinecraftServer nativeServer;private WorldGuardHook worldGuard;private boolean protectionFailed;
    private Set<String> allowedWorlds=Set.of();private int queueBudget,framesPerTick;
    @Override public void onEnable(){
        String target=getPluginMeta().getAPIVersion();if(!Bukkit.getMinecraftVersion().equals(target)){getLogger().severe("This Craftium build requires Minecraft "+target+" but the server runs "+Bukkit.getMinecraftVersion()+". Plugin disabled.");getServer().getPluginManager().disablePlugin(this);return;}
        try{Class.forName("io.papermc.paper.threadedregions.RegionizedServer");getLogger().severe("Craftium requires Paper or Purpur; Folia is not supported.");getServer().getPluginManager().disablePlugin(this);return;}catch(ClassNotFoundException ignored){}
        saveDefaultConfig();loadSettings();nativeServer=((CraftServer)getServer()).getServer();
        ServerPlatform.install(getDataFolder().toPath(),this);
        getServer().getMessenger().registerIncomingPluginChannel(this,BridgeCodec.CHANNEL,this);
        getServer().getMessenger().registerOutgoingPluginChannel(this,BridgeCodec.CHANNEL);
        getServer().getPluginManager().registerEvents(this,this);
        Objects.requireNonNull(getCommand("craftiumserver")).setExecutor(this);
        getServer().getScheduler().runTaskTimer(this,this::tick,1,1);
        for(Player p:getServer().getOnlinePlayers())hello(p);
        if(getConfig().getBoolean("check-for-updates",true))UpdateChecker.check(getPluginMeta().getVersion(),Bukkit.getMinecraftVersion(),getDataFolder().toPath().resolve("update-check.json")).thenAccept(found->found.ifPresent(r->getLogger().info("Craftium "+r.version()+" is out (this is "+UpdateChecker.clean(getPluginMeta().getVersion())+"): "+r.url()+" . Set check-for-updates: false in config.yml to stop this check.")));
        getLogger().info("Craftium "+getPluginMeta().getVersion()+" ready on "+getServer().getName()+" "+Bukkit.getMinecraftVersion()+". Clients need protocol "+Handshake.PROTOCOL+" (Craftium "+getPluginMeta().getVersion()+" or a build with the same protocol).");
    }
    private void loadSettings(){
        allowedWorlds=Set.copyOf(getConfig().getStringList("allowed-worlds"));
        queueBudget=Math.clamp(getConfig().getInt("outgoing-budget-bytes",8_000_000),2_000_000,16_000_000);
        framesPerTick=Math.clamp(getConfig().getInt("outgoing-frames-per-tick",8),1,32);
        worldGuard=null;protectionFailed=false;
        var guard=getServer().getPluginManager().getPlugin("WorldGuard");
        if(guard!=null&&getConfig().getBoolean("worldguard",true))try{worldGuard=new WorldGuardHook(guard);getLogger().info("WorldGuard build protection enabled");}
        catch(ReflectiveOperationException|RuntimeException e){protectionFailed=true;getLogger().log(java.util.logging.Level.SEVERE,"Cannot initialize WorldGuard protection. Craftium edits will be blocked.",e);}
    }
    @Override public void onDisable(){
        if(nativeServer!=null){EditorServer.flushHistory(nativeServer);EditorServer.clear();}
        connections.clear();getServer().getMessenger().unregisterIncomingPluginChannel(this);getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }
    @EventHandler public void join(PlayerJoinEvent event){getServer().getScheduler().runTaskLater(this,()->hello(event.getPlayer()),10);}
    @EventHandler public void registered(PlayerRegisterChannelEvent event){if(event.getChannel().equals(BridgeCodec.CHANNEL))getServer().getScheduler().runTask(this,()->hello(event.getPlayer()));}
    /**
     * Creative No Clip. The Fabric mod does this in a mixin on Entity.move; a plugin has no such hook, and the server only accepts a player inside
     * blocks when its noPhysics flag is set while the movement packet is handled. Player.tick clears the flag each tick, and packets are handled
     * between ticks, so it is set again here as the tick ends (the client sends its own clipped position, which the server then accepts).
     */
    @EventHandler public void tickEnd(com.destroystokyo.paper.event.server.ServerTickEndEvent event){
        for(Player p:getServer().getOnlinePlayers()){var nms=((CraftPlayer)p).getHandle();if(EditorServer.noClip(nms))nms.noPhysics=true;}
    }
    @EventHandler public void quit(PlayerQuitEvent event){connections.remove(event.getPlayer().getUniqueId());Handshake.forget(event.getPlayer().getUniqueId());EditorServer.disconnect(((CraftPlayer)event.getPlayer()).getHandle());}
    private void hello(Player player){if(player.isOnline()&&player.getListeningPluginChannels().contains(BridgeCodec.CHANNEL)){connections.computeIfAbsent(player.getUniqueId(),id->new Connection());enqueue(player,"hello",Handshake.write(getPluginMeta().getVersion()));}}
    private void tick(){
        EditorServer.tick(nativeServer);
        for(var entry:connections.entrySet()){
            Player player=getServer().getPlayer(entry.getKey());if(player==null)continue;
            var c=entry.getValue();for(int i=0;i<framesPerTick&&!c.outgoing.isEmpty();i++){byte[] frame=c.outgoing.removeFirst();c.queued-=frame.length;player.sendPluginMessage(this,BridgeCodec.CHANNEL,frame);}
        }
    }
    @Override public void onPluginMessageReceived(String channel,Player player,byte[] data){
        if(!channel.equals(BridgeCodec.CHANNEL)||!player.isOnline())return;
        if(!Bukkit.isPrimaryThread()){byte[] copy=data.clone();getServer().getScheduler().runTask(this,()->onPluginMessageReceived(channel,player,copy));return;}
        var c=connections.computeIfAbsent(player.getUniqueId(),id->new Connection());
        try{
            long now=System.nanoTime();if(now-c.window>1_000_000_000L){c.window=now;c.frames=0;c.requests=0;}
            if(++c.frames>256)throw new IllegalArgumentException("Craftium transfer rate exceeded");
            c.incoming.accept(data).ifPresent(json->{
                if(++c.requests>20)throw new IllegalArgumentException("Craftium request rate exceeded");
                var message=Packets.JSON.fromJson(json,BridgeCodec.Message.class);
                if(message!=null&&message.version()==BridgeCodec.VERSION&&"hello".equals(message.kind())&&message.data()!=null){clientHello(player,message.data());return;}
                if(message==null||message.version()!=BridgeCodec.VERSION||!"edit".equals(message.kind())||message.data()==null)throw new IllegalArgumentException("Update both Craftium client and server to matching versions");
                c.ready=true;
                EditorServer.handle(((CraftPlayer)player).getHandle(),message.data());
            });
        }catch(RuntimeException e){c.incoming.clear();error(player,e.getMessage()==null?"Invalid Craftium request":e.getMessage());}
    }
    /** The client's own hello: a protocol this server cannot read is named to the player and its edits are refused until it reconnects updated. */
    private void clientHello(Player player,String data){
        var hello=Handshake.read(data);String problem=Handshake.problem(hello,false);
        if(problem==null){Handshake.accept(player.getUniqueId(),hello);return;}
        Handshake.refuse(player.getUniqueId(),problem);error(player,problem);
        getLogger().warning(player.getName()+" has Craftium "+hello.version()+" (protocol "+hello.protocol()+"), this server speaks protocol "+Handshake.PROTOCOL);
    }
    private void error(Player player,String message){enqueue(player,"status",Packets.JSON.toJson(new Packets.Status(message,true,false,0,new Point(0,0,0),new Point(0,0,0),0,List.of(),0,0)));}
    private void enqueue(Player player,String kind,String data){
        var c=connections.computeIfAbsent(player.getUniqueId(),id->new Connection());
        List<byte[]> frames=BridgeCodec.encode(Packets.JSON.toJson(new BridgeCodec.Message(kind,data)));int bytes=frames.stream().mapToInt(f->f.length).sum();
        if(c.queued+bytes>queueBudget){c.outgoing.clear();c.queued=0;c.incoming.clear();player.kick(net.kyori.adventure.text.Component.text("Craftium transfer queue exceeded. Reconnect and use a smaller selection."));return;}
        c.outgoing.addAll(frames);c.queued+=bytes;
    }
    @Override public boolean canSend(ServerPlayer player){var c=connections.get(player.getUUID());return c!=null&&c.ready;}
    @Override public void send(ServerPlayer player,CustomPacketPayload payload){
        Player bukkit=getServer().getPlayer(player.getUUID());if(bukkit==null)return;
        if(payload instanceof Packets.Update p)enqueue(bukkit,"status",p.json());
        else if(payload instanceof Packets.Report p)enqueue(bukkit,"report",p.text());
        else if(payload instanceof Packets.AiReply p)enqueue(bukkit,"ai_reply",p.json());
        else if(payload instanceof Packets.Download p)enqueue(bukkit,"download",Packets.JSON.toJson(p));
        else throw new IllegalArgumentException("Unsupported Craftium payload");
    }
    @Override public boolean permitted(ServerPlayer nativePlayer,String action){
        Player player=getServer().getPlayer(nativePlayer.getUUID());if(player==null||!player.hasPermission("craftium.edit")||!worldAllowed(player.getWorld()))return false;
        if(FABRIC_RULES.contains(action))throw new IllegalArgumentException("This custom world rule currently requires a Fabric server");
        String permission=action.startsWith("world_")?"world":action.startsWith("gamemode")?"gamemode":action.equals("flight")||action.equals("capabilities")?"capabilities":action.equals("animated_rebuild")||action.equals("rebuild_cancel")?"rebuild":Set.of("asset_upload","structure_export","schematic_export","selection_csv","blueprint_save","structure_preview","structure_export_file","structure_import_file").contains(action)?"assets":"edit";
        return player.hasPermission("craftium."+permission);
    }
    @Override public net.minecraft.commands.CommandSourceStack commands(ServerPlayer player){
        // Only fixed, validated world/view commands call this after action-specific authorization.
        return dev.craftium.compat.Compat.gamemasterSource(player);
    }
    private boolean worldAllowed(World world){return allowedWorlds.isEmpty()||allowedWorlds.contains(world.getName());}
    @Override public boolean requiresProtection(){return true;}
    @Override public boolean canChange(UUID owner,ServerLevel level,Point at){
        Player player=getServer().getPlayer(owner);if(player==null||!player.hasPermission("craftium.edit")||player.getGameMode()!=GameMode.CREATIVE)return false;
        var world=level.getWorld();if(!worldAllowed(world)||protectionFailed)return false;
        Location location=new Location(world,at.x(),at.y(),at.z());if(!world.getWorldBorder().isInside(location))return false;
        int radius=getServer().getSpawnRadius();Location spawn=world.getSpawnLocation();
        if(!player.isOp()&&radius>0&&world.getEnvironment()==World.Environment.NORMAL&&Math.max(Math.abs(at.x()-spawn.getBlockX()),Math.abs(at.z()-spawn.getBlockZ()))<=radius)return false;
        return worldGuard==null||worldGuard.allows(player,location);
    }
    @Override public void checkEdit(ServerPlayer nativePlayer,ServerLevel level,List<Point> blocks,List<Point> biomes,List<UUID> entities){
        Player player=getServer().getPlayer(nativePlayer.getUUID());if(player==null)throw new IllegalArgumentException("Player disconnected");
        for(Point point:blocks)if(!canChange(player.getUniqueId(),level,point))throw new IllegalArgumentException("This edit reaches a protected area or world border");
        for(Point cell:biomes)for(int x=0;x<4;x++)for(int y=0;y<4;y++)for(int z=0;z<4;z++)if(!canChange(player.getUniqueId(),level,new Point(cell.x()*4+x,cell.y()*4+y,cell.z()*4+z)))throw new IllegalArgumentException("This biome edit reaches a protected area or world border");
        var event=new CraftiumEditEvent(player,level.getWorld(),blocks,biomes,entities);getServer().getPluginManager().callEvent(event);
        if(event.isCancelled())throw new IllegalArgumentException(event.getReason());
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!sender.hasPermission("craftium.admin")){sender.sendMessage("You do not have permission to manage Craftium.");return true;}
        if(args.length==1&&args[0].equalsIgnoreCase("reload")){reloadConfig();loadSettings();sender.sendMessage("Craftium settings reloaded.");return true;}
        if(args.length>1||args.length==1&&!args[0].equalsIgnoreCase("status"))return false;
        sender.sendMessage("Craftium "+getPluginMeta().getVersion()+" | "+getServer().getName()+" "+Bukkit.getMinecraftVersion()+" | connected clients: "+connections.values().stream().filter(c->c.ready).count());
        sender.sendMessage("WorldGuard: "+(protectionFailed?"FAILED — edits blocked":worldGuard==null?"not enabled":"enabled")+" | worlds: "+(allowedWorlds.isEmpty()?"all":String.join(", ",allowedWorlds)));return true;
    }
}
