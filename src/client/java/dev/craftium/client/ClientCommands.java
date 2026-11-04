package dev.craftium.client;

import dev.craftium.client.compat.ClientCompat;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.craftium.core.*;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.*;
import static dev.craftium.client.compat.CommandCompat.*;

/** User-facing commands and a non-interfering visual mirror of WorldEdit position commands. */
final class ClientCommands {
    private static Runnable pending;
    private static Point pos1,pos2;
    private static final List<String> CAPABILITIES=List.of("enhancedFlight","bulldozer","replaceMode","forcePlace","noUpdates","tinker","fastPlace","angelPlacement","noClip");
    private ClientCommands(){}
    static void register(){
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)->{
            var root=literal("craftium").executes(c->{pending=()->CraftiumClient.open(false);return 1;});
            root.then(literal("editor").executes(c->{pending=()->CraftiumClient.open(false);return 1;}));
            root.then(literal("builder").executes(c->{pending=BuilderMode::enable;return 1;}));
            root.then(literal("intro").executes(c->{pending=()->ClientCompat.setScreen(Minecraft.getInstance(),new ReportScreen(null,"CRAFTIUM\n\nRight Shift: editor\nHold Left Alt: builder settings\n0: tenth-slot builder tools\nAlt + scroll: change builder tool\nLMB / RMB: first / second corner\nMMB: select or expand a face\nScroll: preview and nudge; X/Y/Z restrict axis\nRMB: confirm preview\nCtrl+Z / Ctrl+Y: undo / redo\n\nInstall Craftium and Fabric API on both client and server. Editing requires Creative mode and operator level 2.\n\nAxiom documentation inspired these independent tools. See docs/PARITY-AUDIT.md for remaining differences."));return 1;}));
            root.then(literal("docs").executes(c->{c.getSource().sendFeedback(Component.literal("Craftium: README.md and docs/FEATURES.md in your download. Axiom reference: https://axiomdocs.moulberry.com/"));return 1;}));
            root.then(literal("status").executes(c->{c.getSource().sendFeedback(Component.literal(CraftiumClient.supported()?"Craftium connected · "+ClientState.status.message():"Craftium server channel unavailable. Install the same Craftium version and Fabric API on the server."));return 1;}));
            root.then(literal("handshake").executes(c->{CraftiumClient.send("status",null,null);return 1;}));
            root.then(literal("enablecheats").executes(c->{var server=Minecraft.getInstance().getSingleplayerServer();if(server==null){c.getSource().sendError(Component.literal("This command is available only in your local singleplayer world"));return 0;}server.execute(()->ClientCompat.enableCheats(server));c.getSource().sendFeedback(Component.literal("Cheats enabled for this local world"));return 1;}));
            root.then(literal("toggle").then(argument("capability",StringArgumentType.word()).suggests((c,b)->{for(String name:CAPABILITIES)b.suggest(name);return b.buildFuture();}).executes(c->{String name=StringArgumentType.getString(c,"capability");if(!CAPABILITIES.contains(name)){c.getSource().sendError(Component.literal("Unknown builder capability"));return 0;}try{var field=Options.class.getField(name);boolean enabled=!field.getBoolean(ClientState.options);field.setBoolean(ClientState.options,enabled);ClientState.save();CraftiumClient.send("capabilities",null,null);c.getSource().sendFeedback(Component.literal(name+": "+enabled));return 1;}catch(Exception e){return 0;}})));
            for(String action:List.of("undo","redo","copy","cut","fill","delete","clear"))root.then(literal(action).executes(c->{CraftiumClient.send(action,null,null);return 1;}));
            for(int corner=1;corner<=2;corner++){int index=corner;root.then(literal("pos"+index).executes(c->{position(index,"");return 1;}).then(argument("coordinates",StringArgumentType.greedyString()).executes(c->{try{position(index,StringArgumentType.getString(c,"coordinates"));return 1;}catch(IllegalArgumentException e){c.getSource().sendError(Component.literal(e.getMessage()));return 0;}})));}
            dispatcher.register(root);
            dispatcher.register(literal("craftiumdocs").executes(c->{c.getSource().sendFeedback(Component.literal("Craftium docs: README.md and docs/FEATURES.md. Axiom reference: https://axiomdocs.moulberry.com/"));return 1;}));
            dispatcher.register(literal("whynocraftium").executes(c->{c.getSource().sendFeedback(Component.literal(CraftiumClient.supported()?"Craftium connected. Creative mode and operator level 2 are required for edits.":"No Craftium server channel: check that both sides have matching Craftium and Fabric API versions."));return 1;}));
        });
        ClientSendMessageEvents.COMMAND.register(command->{String[] pieces=command.strip().split("\\s+",2);if(pieces[0].equals("/pos1")||pieces[0].equals("/pos2"))try{position(pieces[0].equals("/pos1")?1:2,pieces.length==2?pieces[1]:"");}catch(IllegalArgumentException e){ClientState.notify(e.getMessage());}});
    }
    static void tick(){Runnable action=pending;pending=null;if(action!=null)action.run();}
    static void reset(){pos1=pos2=null;pending=null;}
    private static void position(int corner,String coordinates){
        var mc=Minecraft.getInstance();if(mc.player==null)return;Point at=CraftiumClient.point(mc.player.blockPosition());
        if(!coordinates.isBlank()){String[] tokens=coordinates.trim().split("[,\\s]+");if(tokens.length!=3)throw new IllegalArgumentException("Use three coordinates, optionally relative with ~");at=new Point(coordinate(tokens[0],mc.player.getX()),coordinate(tokens[1],mc.player.getY()),coordinate(tokens[2],mc.player.getZ()));}
        if(corner==1)pos1=at;else pos2=at;if(pos1!=null&&pos2!=null){var o=ClientState.options;Point first=o.position1,second=o.position2;var mode=o.mode;o.position1=pos1;o.position2=pos2;o.mode=Selection.Mode.REPLACE;CraftiumClient.send("select_box",at,null);o.position1=first;o.position2=second;o.mode=mode;}else ClientState.notify("Position "+corner+": "+at.coordinates());
    }
    private static int coordinate(String token,double base){try{double value=token.startsWith("~")?base+(token.length()==1?0:Double.parseDouble(token.substring(1))):Double.parseDouble(token);if(!Double.isFinite(value)||Math.abs(value)>30_000_000)throw new NumberFormatException();return (int)Math.floor(value);}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid block coordinates");}}
}
