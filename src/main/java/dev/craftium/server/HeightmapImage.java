package dev.craftium.server;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

final class HeightmapImage {
    private final BufferedImage image;

    private HeightmapImage(BufferedImage image) {
        this.image = image;
    }

    static HeightmapImage load(String name) {
        try (var input = ImageIO.createImageInputStream(
                BlueprintLibrary.file("heightmaps", name).toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("Unsupported heightmap image");
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (reader.getWidth(0) > 2048 || reader.getHeight(0) > 2048)
                    throw new IllegalArgumentException("Heightmap limit: 2048 by 2048");
                return new HeightmapImage(reader.read(0));
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot load heightmap: " + name, e);
        }
    }

    double sample(double u, double v) {
        int x = Math.clamp((int) Math.round(u * (image.getWidth() - 1)), 0, image.getWidth() - 1),
                y = Math.clamp((int) Math.round(v * (image.getHeight() - 1)), 0, image.getHeight() - 1);
        int rgb = image.getRGB(x, y);
        return (((rgb >> 16) & 255) * .2126 + ((rgb >> 8) & 255) * .7152 + (rgb & 255) * .0722) / 255;
    }
}
