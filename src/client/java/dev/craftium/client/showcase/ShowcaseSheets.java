package dev.craftium.client.showcase;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * A contact sheet: all of one room's pictures as labelled thumbnails on one image, so the agent can judge every angle at the cost of
 * looking at one picture. It is built from the saved files, off the game's thread, and also saved next to them.
 */
public final class ShowcaseSheets {
    private ShowcaseSheets() {}

    /** The pictures of one room: where they are and what each is called, in the order they were taken. */
    public record Sources(String job, String state, String room, Path folder, List<String> files, List<String> shots) {}

    static final int WIDTH = 1024, LABEL = 22, GAP = 6, MAX_COLUMNS = 3;

    /** The reply for the agent: the sheet as an image, and which thumbnail is which. */
    public static JsonObject build(Sources src) throws IOException {
        int n = src.files().size();
        if (n == 0) throw new IllegalStateException("Room \"" + src.room() + "\" has no saved pictures yet");
        int columns = Math.min(MAX_COLUMNS, n), rows = (n + columns - 1) / columns;
        int cell = (WIDTH - GAP * (columns + 1)) / columns;
        var images = new BufferedImage[n];
        int thumbHeight = 0;
        for (int i = 0; i < n; i++) {
            images[i] = ImageIO.read(src.folder().resolve(src.files().get(i)).toFile());
            if (images[i] == null) throw new IOException("Could not read " + src.files().get(i));
            thumbHeight = Math.max(thumbHeight, Math.max(1, Math.round(images[i].getHeight() * (float) cell / images[i].getWidth())));
        }
        int height = GAP + rows * (thumbHeight + LABEL + GAP);
        var sheet = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x14, 0x18, 0x1f));
        g.fillRect(0, 0, WIDTH, height);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        var listed = new JsonArray();
        for (int i = 0; i < n; i++) {
            int x = GAP + (i % columns) * (cell + GAP), y = GAP + (i / columns) * (thumbHeight + LABEL + GAP);
            int h = Math.max(1, Math.round(images[i].getHeight() * (float) cell / images[i].getWidth()));
            g.drawImage(images[i], x, y, cell, h, null);
            g.setColor(new Color(0xe8, 0xf1, 0xfa));
            g.drawString((i + 1) + ". " + src.shots().get(i), x + 2, y + thumbHeight + LABEL - 6);
            var entry = new JsonObject();
            entry.addProperty("position", i + 1);
            entry.addProperty("shot", src.shots().get(i));
            entry.addProperty("file", src.files().get(i));
            listed.add(entry);
        }
        g.dispose();

        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(0.82f);
        var bytes = new ByteArrayOutputStream();
        try (var out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(sheet, null, null), params);
        } finally { writer.dispose(); }
        Path file = src.folder().resolve(src.room()).resolve("contact-sheet.jpg");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes.toByteArray());

        var reply = new JsonObject();
        reply.addProperty("ok", true);
        reply.addProperty("image", Base64.getEncoder().encodeToString(bytes.toByteArray()));
        reply.addProperty("mime", "image/jpeg");
        reply.addProperty("job", src.job());
        reply.addProperty("state", src.state());
        reply.addProperty("room", src.room());
        reply.addProperty("sheetFile", file.toAbsolutePath().toString());
        reply.addProperty("width", sheet.getWidth());
        reply.addProperty("height", sheet.getHeight());
        reply.add("thumbnails", listed);
        return reply;
    }
}
