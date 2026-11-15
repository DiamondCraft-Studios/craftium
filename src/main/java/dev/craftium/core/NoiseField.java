package dev.craftium.core;

import dev.craftium.vendor.FastNoiseLite;

public final class NoiseField {
    private final FastNoiseLite noise;
    private final Options options;

    public NoiseField(Options o) {
        options = o;
        noise = new FastNoiseLite((int) o.seed);
        noise.SetFrequency((float) (1 / o.noiseScale));
        noise.SetNoiseType(
                switch (o.noise) {
                    case PERLIN -> FastNoiseLite.NoiseType.Perlin;
                    case VALUE -> FastNoiseLite.NoiseType.Value;
                    case WORLEY, VORONOI_EDGES, METABALLS -> FastNoiseLite.NoiseType.Cellular;
                    default -> FastNoiseLite.NoiseType.OpenSimplex2;
                });
        noise.SetCellularJitter((float) o.jitter);
        noise.SetCellularDistanceFunction(FastNoiseLite.CellularDistanceFunction.Euclidean);
        noise.SetCellularReturnType(
                o.noise == Options.Noise.VORONOI_EDGES
                        ? FastNoiseLite.CellularReturnType.Distance2Sub
                        : FastNoiseLite.CellularReturnType.Distance);
        if (o.octaves > 1) {
            noise.SetFractalType(FastNoiseLite.FractalType.FBm);
            noise.SetFractalOctaves(o.octaves);
            noise.SetFractalLacunarity((float) o.lacunarity);
            noise.SetFractalGain((float) o.gain);
        }
    }

    public double sample(Point p) {
        var o = options;
        if (o.noise == Options.Noise.WHITE) return BrushMath.hash(p.x(), o.noise3D ? p.y() : 0, p.z(), o.seed);
        float x = (float) (p.x() / o.scaleX), y = (float) (p.y() / o.scaleY), z = (float) (p.z() / o.scaleZ);
        double value = (o.noise3D ? noise.GetNoise(x, y, z) : noise.GetNoise(x, z)) * .5 + .5;
        if (o.noise == Options.Noise.METABALLS) value = 1 - value;
        if (o.noise == Options.Noise.SPLATTER) value = value * .7 + BrushMath.hash(p.x(), p.y(), p.z(), o.seed) * .3;
        return Math.clamp(value, 0, 1);
    }

    public double signed(Point p) {
        return sample(p) * 2 - 1;
    }
}
