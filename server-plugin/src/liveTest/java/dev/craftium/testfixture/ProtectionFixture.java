package dev.craftium.testfixture;

import dev.craftium.paper.api.CraftiumEditEvent;
import java.util.*;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable loopback test fixture, excluded from both distributed release JARs. */
public final class ProtectionFixture extends JavaPlugin implements Listener {
    private boolean deny;
    private final Map<UUID, PermissionAttachment> grants = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("craftiumfixture")).setExecutor(this);
    }

    @EventHandler
    public void edit(CraftiumEditEvent event) {
        if (deny) {
            event.setReason("Fixture protected region");
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) return false;
        if (args.length == 1 && (args[0].equals("deny") || args[0].equals("allow"))) {
            deny = args[0].equals("deny");
            sender.sendMessage("deny=" + deny);
            return true;
        }
        if (args.length == 2 && args[0].equals("grant")) {
            var player = getServer().getPlayerExact(args[1]);
            if (player == null) return false;
            var attachment = grants.computeIfAbsent(player.getUniqueId(), id -> player.addAttachment(this));
            attachment.setPermission("craftium.edit", true);
            sender.sendMessage("granted edit");
            return true;
        }
        if (args.length == 2 && args[0].equals("world")) {
            var player = getServer().getPlayerExact(args[1]);
            if (player == null || !grants.containsKey(player.getUniqueId())) return false;
            grants.get(player.getUniqueId()).setPermission("craftium.world", true);
            sender.sendMessage("granted world");
            return true;
        }
        if (args.length == 2 && args[0].equals("claim")) {
            try {
                claim(args[1]);
                sender.sendMessage("claim ready");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return true;
        }
        if (args.length == 1 && args[0].equals("unclaim")) {
            try {
                Object manager = manager();
                manager.getClass().getMethod("removeRegion", String.class).invoke(manager, "craftium_test");
                sender.sendMessage("claim removed");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return true;
        }
        return false;
    }

    private Class<?> wg(String name) throws ClassNotFoundException {
        return Class.forName(
                name,
                true,
                Objects.requireNonNull(getServer().getPluginManager().getPlugin("WorldGuard"))
                        .getClass()
                        .getClassLoader());
    }

    private Object manager() throws ReflectiveOperationException {
        Class<?> guard = wg("com.sk89q.worldguard.WorldGuard");
        Object instance = guard.getMethod("getInstance").invoke(null),
                platform = guard.getMethod("getPlatform").invoke(instance);
        Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
        Object world = wg("com.sk89q.worldedit.bukkit.BukkitAdapter")
                .getMethod("adapt", org.bukkit.World.class)
                .invoke(null, getServer().getWorlds().getFirst());
        return container
                .getClass()
                .getMethod("get", wg("com.sk89q.worldedit.world.World"))
                .invoke(container, world);
    }

    private void claim(String name) throws ReflectiveOperationException {
        var vector = wg("com.sk89q.worldedit.math.BlockVector3");
        Object min = vector.getMethod("at", int.class, int.class, int.class).invoke(null, 0, 4, 0),
                max = vector.getMethod("at", int.class, int.class, int.class).invoke(null, 3, 7, 3);
        var regionClass = wg("com.sk89q.worldguard.protection.regions.ProtectedRegion");
        Object region = wg("com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion")
                .getConstructor(String.class, vector, vector)
                .newInstance("craftium_test", min, max);
        Object owners = regionClass.getMethod("getOwners").invoke(region);
        owners.getClass()
                .getMethod("addPlayer", UUID.class)
                .invoke(
                        owners,
                        Objects.requireNonNull(getServer().getPlayerExact(name)).getUniqueId());
        var flagClass = wg("com.sk89q.worldguard.protection.flags.Flag");
        var known = wg("com.sk89q.worldguard.protection.flags.Flags");
        Object denyState = wg("com.sk89q.worldguard.protection.flags.StateFlag$State")
                .getField("DENY")
                .get(null);
        for (String flag : new String[] {"BLOCK_PLACE", "BLOCK_BREAK"})
            regionClass
                    .getMethod("setFlag", flagClass, Object.class)
                    .invoke(region, known.getField(flag).get(null), denyState);
        Object manager = manager();
        manager.getClass().getMethod("addRegion", regionClass).invoke(manager, region);
    }
}
