package dev.craftium.paper.api;

import dev.craftium.core.Point;
import java.util.*;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.*;

/** Fired synchronously before an edit, undo, redo, or rebuild changes the world.
 * Cancel to reject the entire operation. Biome cells use quart coordinates.
 * Native edits do not emit Bukkit BlockPlaceEvent/BlockBreakEvent per block. */
public final class CraftiumEditEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final World world;
    private final List<Point> blocks, biomes;
    private final List<UUID> entities;
    private boolean cancelled;
    private String reason = "Another server plugin protected this edit";

    public CraftiumEditEvent(Player player, World world, List<Point> blocks, List<Point> biomes, List<UUID> entities) {
        this.player = player;
        this.world = world;
        this.blocks = List.copyOf(blocks);
        this.biomes = List.copyOf(biomes);
        this.entities = List.copyOf(entities);
    }

    public Player getPlayer() {
        return player;
    }

    public World getWorld() {
        return world;
    }

    public List<Point> getBlocks() {
        return blocks;
    }

    public List<Point> getBiomeCells() {
        return biomes;
    }

    public List<UUID> getEntities() {
        return entities;
    }
    /** Immutable lazy view avoids allocating millions of positions for large biome edits. */
    public List<Point> getBiomeBlocks() {
        return Collections.unmodifiableList(new AbstractList<>() {
            public int size() {
                return Math.multiplyExact(biomes.size(), 64);
            }

            public Point get(int index) {
                Objects.checkIndex(index, size());
                Point cell = biomes.get(index / 64);
                int offset = index % 64;
                return new Point(
                        cell.x() * 4 + offset / 16, cell.y() * 4 + (offset / 4) % 4, cell.z() * 4 + offset % 4);
            }
        });
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = Objects.requireNonNull(reason);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean value) {
        cancelled = value;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
