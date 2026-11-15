package dev.craftium.core;

/** OKLab conversion using Björn Ottosson's published matrices, in linear sRGB. */
public final class ColourMath {
    private ColourMath() {}

    private static double linear(int channel) {
        double v = channel / 255d;
        return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
    }

    private static int channel(double v) {
        return (int)
                Math.round(Math.clamp(v <= .0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - .055, 0, 1) * 255);
    }

    public static double[] lab(int rgb) {
        double r = linear((rgb >> 16) & 255), g = linear((rgb >> 8) & 255), b = linear(rgb & 255);
        double l = Math.cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b),
                m = Math.cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b),
                s = Math.cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b);
        return new double[] {
            .2104542553 * l + .793617785 * m - .0040720468 * s,
            1.9779984951 * l - 2.428592205 * m + .4505937099 * s,
            .0259040371 * l + .7827717662 * m - .808675766 * s
        };
    }

    public static int rgb(double[] lab) {
        double l = lab[0] + .3963377774 * lab[1] + .2158037573 * lab[2],
                m = lab[0] - .1055613458 * lab[1] - .0638541728 * lab[2],
                s = lab[0] - .0894841775 * lab[1] - 1.291485548 * lab[2];
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        return channel(4.0767416621 * l - 3.3077115913 * m + .2309699292 * s) << 16
                | channel(-1.2684380046 * l + 2.6097574011 * m - .3413193965 * s) << 8
                | channel(-.0041960863 * l - .7034186147 * m + 1.707614701 * s);
    }

    public static double distance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < 3; i++) sum += (a[i] - b[i]) * (a[i] - b[i]);
        return sum;
    }

    public static int interpolate(int a, int b, double t) {
        double[] x = lab(a), y = lab(b);
        for (int i = 0; i < 3; i++) x[i] += (y[i] - x[i]) * t;
        return rgb(x);
    }
    /** CIELAB relative to the sRGB D65 white point, used for spatial colour fields. */
    public static double[] cielab(int rgb) {
        double r = linear((rgb >> 16) & 255), g = linear((rgb >> 8) & 255), b = linear(rgb & 255);
        double x = f((.4124564 * r + .3575761 * g + .1804375 * b) / .95047),
                y = f(.2126729 * r + .7151522 * g + .072175 * b),
                z = f((.0193339 * r + .119192 * g + .9503041 * b) / 1.08883);
        return new double[] {116 * y - 16, 500 * (x - y), 200 * (y - z)};
    }

    private static double f(double t) {
        return t > 216d / 24389 ? Math.cbrt(t) : (24389d / 27 * t + 16) / 116;
    }
}
