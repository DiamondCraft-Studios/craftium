package dev.craftium.client;

import org.joml.Vector3f;

/**
 * One face of a box of an entity model, placed: its four corners in blocks, their texture coordinates (0 to 1 across
 * the texture) and the direction it faces.
 */
public record ModelFace(Vector3f[] corners, float[] u, float[] v, Vector3f normal) {}
