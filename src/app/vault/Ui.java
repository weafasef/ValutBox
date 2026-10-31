package app.vault;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Locale;

/** 界面小工具：大小格式化、占位图标。 */
final class Ui {
    private Ui() {}

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }

    static ImageIcon placeholder(String glyph, Color color) {
        int w = 72;
        int h = 54;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.fillRoundRect(0, 0, w, h, 12, 12);
        g.setColor(new Color(255, 255, 255, 235));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        int tw = g.getFontMetrics().stringWidth(glyph);
        int th = g.getFontMetrics().getAscent();
        g.drawString(glyph, (w - tw) / 2, (h + th) / 2 - 2);
        g.dispose();
        return new ImageIcon(img);
    }

    static ImageIcon scale(java.awt.Image image, int maxW, int maxH) {
        int iw = image.getWidth(null);
        int ih = image.getHeight(null);
        if (iw <= 0 || ih <= 0) return null;
        double scale = Math.min(maxW / (double) iw, maxH / (double) ih);
        int w = Math.max(1, (int) Math.round(iw * scale));
        int h = Math.max(1, (int) Math.round(ih * scale));
        BufferedImage out = new BufferedImage(maxW, maxH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, (maxW - w) / 2, (maxH - h) / 2, w, h, null);
        g.dispose();
        return new ImageIcon(out);
    }

    static BufferedImage readImage(byte[] data) {
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(data));
        } catch (IOException e) {
            return null;
        }
    }
}
