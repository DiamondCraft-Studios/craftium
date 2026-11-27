/**
 * Craftium's copies of the input records Minecraft 1.21.9 added, for the 1.21.1 build only. Minecraft 1.21.1 has no
 * such package, so nothing here clashes with the game. They let the shared screens, written against the 1.21.9 input
 * API, compile and run unchanged: {@code dev.craftium.client.compat.CraftiumScreen} turns 1.21.1's coordinate-and-button
 * calls into these records. Only the parts Craftium uses are here.
 */
package net.minecraft.client.input;
