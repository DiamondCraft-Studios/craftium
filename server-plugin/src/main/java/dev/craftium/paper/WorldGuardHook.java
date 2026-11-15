package dev.craftium.paper;

import java.lang.reflect.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Optional WorldGuard 7 bridge; absent dependencies never enter the plugin classpath. */
final class WorldGuardHook {
    private final Object plugin, query, worldGuard;
    private final Method wrap, adapt, test, platform;
    private final Object flags;

    WorldGuardHook(Plugin installed) throws ReflectiveOperationException {
        var loader = installed.getClass().getClassLoader();
        plugin = installed;
        Class<?> guard = Class.forName("com.sk89q.worldguard.WorldGuard", true, loader);
        worldGuard = guard.getMethod("getInstance").invoke(null);
        platform = guard.getMethod("getPlatform");
        Object regionContainer = platform.invoke(worldGuard)
                .getClass()
                .getMethod("getRegionContainer")
                .invoke(platform.invoke(worldGuard));
        query = regionContainer.getClass().getMethod("createQuery").invoke(regionContainer);
        wrap = installed.getClass().getMethod("wrapPlayer", Player.class);
        adapt = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, loader)
                .getMethod("adapt", Location.class);
        var location = Class.forName("com.sk89q.worldedit.util.Location", true, loader);
        var local = Class.forName("com.sk89q.worldguard.LocalPlayer", true, loader);
        var flag = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag", true, loader);
        flags = Array.newInstance(flag, 2);
        var known = Class.forName("com.sk89q.worldguard.protection.flags.Flags", true, loader);
        Array.set(flags, 0, known.getField("BLOCK_PLACE").get(null));
        Array.set(flags, 1, known.getField("BLOCK_BREAK").get(null));
        test = query.getClass().getMethod("testBuild", location, local, flags.getClass());
    }

    boolean allows(Player player, Location location) {
        try {
            if (!((Plugin) plugin).isEnabled()) return false;
            Object local = wrap.invoke(plugin, player), plat = platform.invoke(worldGuard);
            Object sessions = plat.getClass().getMethod("getSessionManager").invoke(plat);
            var world =
                    adapt.getDeclaringClass().getMethod("adapt", World.class).invoke(null, location.getWorld());
            Method bypass = java.util.Arrays.stream(sessions.getClass().getMethods())
                    .filter(m -> m.getName().equals("hasBypass") && m.getParameterCount() == 2)
                    .findFirst()
                    .orElseThrow();
            if (Boolean.TRUE.equals(bypass.invoke(sessions, local, world))) return true;
            return Boolean.TRUE.equals(test.invoke(query, adapt.invoke(null, location), local, flags));
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("WorldGuard protection check failed; edit blocked", e);
        }
    }
}
