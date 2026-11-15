package dev.craftium.server;

import dev.craftium.core.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Bounded voxel ray lighting with hemisphere AO and diffuse surface light propagation. */
final class Autoshade {
    private final EditPlanner p;
    private final Map<Point, Boolean> occluders = new HashMap<>();
    private int queries;

    private Autoshade(EditPlanner plan) {
        p = plan;
    }

    static void apply(EditPlanner p, ServerPlayer player) {
        new Autoshade(p).shade(player);
    }

    private boolean blocked(Point at) {
        if (++queries > 4_000_000)
            throw new IllegalArgumentException(
                    "Lighting exceeds its ray budget; reduce the selection, samples or ray length");
        return occluders.computeIfAbsent(at, q -> {
            var state = p.state(q);
            return state.isSolidRender()
                    && !state.getCollisionShape(p.level, EditPlanner.pos(q)).isEmpty();
        });
    }

    private boolean visible(Vec3 start, Vec3 direction, double length) {
        Point previous = null;
        for (double t = .1; t < length; t += .7) {
            Vec3 sample = start.add(direction.scale(t));
            Point at =
                    new Point((int) Math.floor(sample.x()), (int) Math.floor(sample.y()), (int) Math.floor(sample.z()));
            if (!at.equals(previous) && blocked(at)) return false;
            previous = at;
        }
        return true;
    }

    private void shade(ServerPlayer player) {
        var o = p.o;
        var surfaces = new LinkedHashMap<Point, Double>();
        var direct = new HashMap<Point, Double>();
        var lights = new ArrayList<>(o.shadeLights);
        if (o.shadeFrom == Options.ShadeFrom.PLAYER_POSITION)
            lights = new ArrayList<>(List.of(new ShadeLight(player.getX(), player.getEyeY(), player.getZ(), 1)));
        if (o.shadeSun && o.shadeFrom == Options.ShadeFrom.CUSTOM_POSITIONS && lights.isEmpty())
            throw new IllegalArgumentException("Add a custom light position first");
        double altitude = Math.toRadians(o.shadeAltitude), azimuth = Math.toRadians(o.shadeAzimuth);
        Vec3 sun = new Vec3(
                Math.cos(altitude) * Math.sin(azimuth), Math.sin(altitude), Math.cos(altitude) * Math.cos(azimuth));
        for (Point at : p.session.selection.points()) {
            if (!p.solid(at) || !p.surface(at)) continue;
            if (surfaces.size() >= 16384)
                throw new IllegalArgumentException("Autoshade supports 16,384 exposed blocks per pass");
            double value = 0;
            int faces = 0;
            for (Point n : EditPlanner.NEIGHBOURS) {
                if (blocked(at.add(n.x(), n.y(), n.z()))) continue;
                faces++;
                Vec3 normal = new Vec3(n.x(), n.y(), n.z()),
                        origin = new Vec3(at.x() + .5, at.y() + .5, at.z() + .5).add(normal.scale(.501));
                double ao = 0;
                if (o.shadeAmbientOcclusion > 0) {
                    Vec3
                            tangent =
                                    normal.cross(Math.abs(normal.y()) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0))
                                            .normalize(),
                            bitangent = normal.cross(tangent);
                    for (int i = 0; i < o.shadeSamples; i++) {
                        double z = (i + .5) / o.shadeSamples, angle = i * 2.399963229728653, r = Math.sqrt(1 - z * z);
                        Vec3 direction = normal.scale(z)
                                .add(tangent.scale(Math.cos(angle) * r))
                                .add(bitangent.scale(Math.sin(angle) * r));
                        if (visible(origin, direction, Math.min(o.shadeRayLength, 12))) ao += 1d / o.shadeSamples;
                    }
                } else ao = 1;
                double illumination = 1;
                if (o.shadeSun) {
                    illumination = 0;
                    if (o.shadeFrom == Options.ShadeFrom.SUN_ANGLE) {
                        double lambert = Math.max(0, normal.dot(sun));
                        if (lambert > 0 && visible(origin, sun, o.shadeRayLength)) illumination = lambert;
                    } else
                        for (var light : lights) {
                            Vec3 delta = new Vec3(light.x(), light.y(), light.z()).subtract(origin);
                            double distance = delta.length();
                            if (distance < .001) {
                                illumination += light.intensity();
                                continue;
                            }
                            Vec3 direction = delta.scale(1 / distance);
                            double lambert = Math.max(0, normal.dot(direction));
                            if (lambert > 0 && visible(origin, direction, Math.min(distance, o.shadeRayLength)))
                                illumination += lambert * light.intensity();
                        }
                }
                value += (o.shadeSun ? .12 + .88 * Math.min(1, illumination) : 1)
                        * (1 - o.shadeAmbientOcclusion + o.shadeAmbientOcclusion * ao);
            }
            double brightness = faces == 0 ? 0 : value / faces;
            surfaces.put(at, brightness);
            direct.put(at, brightness);
        }
        if (surfaces.isEmpty()) throw new IllegalArgumentException("Select exposed solid blocks first");
        // Each iteration diffuses a bounded fraction of the neighbouring surface's energy.
        for (int bounce = 0; bounce < o.shadeBounces; bounce++) {
            var next = new LinkedHashMap<Point, Double>();
            for (var entry : surfaces.entrySet()) {
                double sum = 0;
                int count = 0;
                for (Point n : EditPlanner.NEIGHBOURS) {
                    Double neighbour = surfaces.get(entry.getKey().add(n.x(), n.y(), n.z()));
                    if (neighbour != null) {
                        sum += neighbour;
                        count++;
                    }
                }
                double indirect = count == 0 ? 0 : sum / count;
                next.put(entry.getKey(), Math.clamp(direct.get(entry.getKey()) + indirect * .18, 0, 1));
            }
            surfaces = next;
        }
        var candidates = new ArrayList<Map.Entry<BlockState, double[]>>();
        var colours = new HashMap<BlockState, double[]>();
        if (o.shadeAutomatic) {
            int required = (o.shadeFullBlocks ? 4 : 0)
                    | (o.shadeOpaque ? 2 : 0)
                    | (o.shadeNoOres ? 16 : 0)
                    | (o.shadeNoGlazed ? 32 : 0)
                    | (o.shadeNoBlockEntities ? 64 : 0);
            for (var colour : o.scriptColours) {
                BlockState state = p.parse(colour.block());
                colours.put(state, ColourMath.lab(colour.rgb()));
                if ((colour.flags() & required) == required)
                    candidates.add(Map.entry(state, ColourMath.lab(colour.rgb())));
            }
            if (candidates.isEmpty())
                throw new IllegalArgumentException("No blocks match the automatic palette filters");
        }
        var nearest = new HashMap<String, BlockState>();
        for (var entry : surfaces.entrySet()) {
            Point at = entry.getKey();
            BlockState old = p.state(at), result;
            double brightness = Math.clamp(entry.getValue() * o.shadeExposure, 0, 1),
                    random = BrushMath.hash(at.x(), at.y(), at.z(), o.seed);
            if (o.shadeAutomatic) {
                double[] base = colours.computeIfAbsent(
                        old, s -> ColourMath.lab(s.getMapColor(p.level, EditPlanner.pos(at)).col));
                double[] target = base.clone();
                target[0] = Math.clamp(base[0] * (.3 + .9 * brightness) + (random - .5) * o.shadeDither * .06, 0, 1);
                String key = net.minecraft.world.level.block.Block.getId(old) + ":" + Math.round(target[0] * 128);
                result = nearest.get(key);
                if (result == null) {
                    double best = Double.POSITIVE_INFINITY;
                    result = old;
                    for (var candidate : candidates) {
                        double distance = ColourMath.distance(target, candidate.getValue());
                        if (distance < best) {
                            best = distance;
                            result = candidate.getKey();
                        }
                    }
                    nearest.put(key, result);
                }
            } else
                result = p.materials.get(
                        MaterialList.index(brightness, o.materialWeights, p.materials.size(), random, o.shadeDither));
            p.put(at, o.copyProperties ? p.preserve(old, result) : result);
        }
    }
}
