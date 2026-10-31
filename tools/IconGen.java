import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 生成 VaultBox 应用图标：icon.png / VaultBox.iconset / VaultBox.ico。 */
public final class IconGen {
    private static final int BASE = 1024;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(out);

        ImageIO.write(render(1024), "png", out.resolve("icon.png").toFile());

        Path set = out.resolve("VaultBox.iconset");
        deleteRec(set);
        Files.createDirectories(set);
        String[][] map = {
                {"icon_16x16.png", "16"}, {"icon_16x16@2x.png", "32"},
                {"icon_32x32.png", "32"}, {"icon_32x32@2x.png", "64"},
                {"icon_128x128.png", "128"}, {"icon_128x128@2x.png", "256"},
                {"icon_256x256.png", "256"}, {"icon_256x256@2x.png", "512"},
                {"icon_512x512.png", "512"}, {"icon_512x512@2x.png", "1024"}
        };
        for (String[] entry : map) {
            ImageIO.write(render(Integer.parseInt(entry[1])), "png", set.resolve(entry[0]).toFile());
        }

        writeIco(out.resolve("VaultBox.ico"), new int[]{16, 32, 48, 64, 128, 256});
        System.out.println("图标已生成：" + out.toAbsolutePath());
    }

    static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / (double) BASE, size / (double) BASE);

        RoundRectangle2D background = new RoundRectangle2D.Double(26, 26, 972, 972, 232, 232);
        g.setClip(background);
        g.setPaint(new GradientPaint(150, 100, new Color(0x173A57), 900, 940, new Color(0x0B7A6C)));
        g.fill(background);

        g.setPaint(new RadialGradientPaint(new Point2D.Double(320, 200), 720f,
                new float[]{0f, 1f},
                new Color[]{new Color(255, 255, 255, 62), new Color(255, 255, 255, 0)}));
        g.fill(background);

        g.setPaint(new RadialGradientPaint(new Point2D.Double(512, 1010), 660f,
                new float[]{0f, 1f},
                new Color[]{new Color(0, 0, 0, 70), new Color(0, 0, 0, 0)}));
        g.fill(background);
        g.setClip(null);

        g.setColor(new Color(255, 255, 255));
        g.setStroke(new BasicStroke(72f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(370, 262, 284, 320, 0, 180, Arc2D.OPEN));
        g.draw(new Line2D.Double(370, 422, 370, 540));
        g.draw(new Line2D.Double(654, 422, 654, 540));

        RoundRectangle2D body = new RoundRectangle2D.Double(262, 466, 500, 336, 86, 86);
        g.fill(body);

        Path2D play = new Path2D.Double();
        play.moveTo(474, 565);
        play.lineTo(474, 703);
        play.lineTo(590, 634);
        play.closePath();
        g.setPaint(new GradientPaint(474, 565, new Color(0x0D3A4A), 600, 703, new Color(0x0B6B60)));
        g.fill(play);
        g.setStroke(new BasicStroke(18f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(play);

        g.dispose();
        return img;
    }

    private static void writeIco(Path path, int[] sizes) throws IOException {
        List<byte[]> images = new ArrayList<>();
        for (int s : sizes) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(render(s), "png", bos);
            images.add(bos.toByteArray());
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            writeLE16(out, 0);
            writeLE16(out, 1);
            writeLE16(out, sizes.length);
            int offset = 6 + 16 * sizes.length;
            for (int i = 0; i < sizes.length; i++) {
                int s = sizes[i];
                out.writeByte(s >= 256 ? 0 : s);
                out.writeByte(s >= 256 ? 0 : s);
                out.writeByte(0);
                out.writeByte(0);
                writeLE16(out, 1);
                writeLE16(out, 32);
                writeLE32(out, images.get(i).length);
                writeLE32(out, offset);
                offset += images.get(i).length;
            }
            for (byte[] data : images) out.write(data);
        }
    }

    private static void writeLE16(DataOutputStream out, int v) throws IOException {
        out.writeByte(v & 0xff);
        out.writeByte((v >>> 8) & 0xff);
    }

    private static void writeLE32(DataOutputStream out, int v) throws IOException {
        out.writeByte(v & 0xff);
        out.writeByte((v >>> 8) & 0xff);
        out.writeByte((v >>> 16) & 0xff);
        out.writeByte((v >>> 24) & 0xff);
    }

    private static void deleteRec(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
