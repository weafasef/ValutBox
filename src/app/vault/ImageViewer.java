package app.vault;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;

/** 应用内看图：滚轮/触控板双指缩放、拖动、双击 1:1，左右键切换。 */
final class ImageViewer extends JDialog {
    private final Vault vault;
    private final TempStore temps;
    private final List<Entry> images;
    private int index;

    private final ImagePanel canvas = new ImagePanel();
    private final JLabel title = new JLabel();
    private final JLabel counter = new JLabel();
    private final JLabel zoomLabel = new JLabel("—");
    private Object gestureRef;

    ImageViewer(Frame owner, Vault vault, TempStore temps, List<Entry> images, int start) {
        super(owner, "看图", false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        this.vault = vault;
        this.temps = temps;
        this.images = images;
        this.index = Math.max(0, Math.min(start, images.size() - 1));

        setLayout(new BorderLayout());
        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        top.add(title, BorderLayout.CENTER);
        top.add(counter, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        canvas.setZoomListener(this::updateZoomLabel);
        add(canvas, BorderLayout.CENTER);
        installMouseControls();
        installPinchGesture();

        JButton prev = new JButton("‹");
        JButton next = new JButton("›");
        JButton zoomOut = new JButton("−");
        JButton zoomIn = new JButton("+");
        JButton fit = new JButton("适应");
        JButton actual = new JButton("1:1");
        JButton external = new JButton("用系统应用打开");
        JButton close = new JButton("关闭");

        prev.setPreferredSize(new Dimension(52, 38));
        next.setPreferredSize(new Dimension(52, 38));
        zoomOut.setPreferredSize(new Dimension(44, 38));
        zoomIn.setPreferredSize(new Dimension(44, 38));
        for (JButton b : new JButton[]{prev, next, zoomOut, zoomIn, fit, actual, external, close}) {
            b.setFocusable(false);
            b.setMargin(new java.awt.Insets(4, 8, 4, 8));
        }

        prev.addActionListener(e -> move(-1));
        next.addActionListener(e -> move(1));
        zoomOut.addActionListener(e -> canvas.zoomBy(1 / 1.25));
        zoomIn.addActionListener(e -> canvas.zoomBy(1.25));
        fit.addActionListener(e -> canvas.fitToView());
        actual.addActionListener(e -> canvas.actualSize());
        external.addActionListener(e -> openExternal());
        close.addActionListener(e -> dispose());

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        nav.add(prev);
        nav.add(next);
        bottom.add(nav, BorderLayout.WEST);

        JPanel zoom = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        zoom.add(zoomOut);
        zoom.add(zoomLabel);
        zoom.add(zoomIn);
        zoom.add(fit);
        zoom.add(actual);
        bottom.add(zoom, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(external);
        actions.add(close);
        bottom.add(actions, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "prev");
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next");
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, 0), "zoomIn");
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, 0), "zoomIn");
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0), "zoomOut");
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, 0), "zoomOut");
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_0, 0), "fit");
        getRootPane().getActionMap().put("prev", action(() -> move(-1)));
        getRootPane().getActionMap().put("next", action(() -> move(1)));
        getRootPane().getActionMap().put("close", action(this::dispose));
        getRootPane().getActionMap().put("zoomIn", action(() -> canvas.zoomBy(1.25)));
        getRootPane().getActionMap().put("zoomOut", action(() -> canvas.zoomBy(1 / 1.25)));
        getRootPane().getActionMap().put("fit", action(canvas::fitToView));

        setSize(1040, 720);
        setLocationRelativeTo(owner);
        setMinimumSize(new Dimension(720, 480));
        load();
    }

    private static javax.swing.AbstractAction action(Runnable r) {
        return new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }

    private void updateZoomLabel() {
        zoomLabel.setText(canvas.percent() + "%");
    }

    private void move(int delta) {
        if (images.isEmpty()) return;
        index = Math.floorMod(index + delta, images.size());
        load();
    }

    private void load() {
        if (images.isEmpty()) {
            title.setText("没有可显示的图片");
            counter.setText("");
            canvas.setError("没有可显示的图片");
            updateZoomLabel();
            return;
        }
        Entry e = images.get(index);
        title.setText(e.name());
        counter.setText((index + 1) + " / " + images.size());
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            byte[] data = vault.readAll(e);
            BufferedImage img = Ui.readImage(data);
            if (img == null) {
                canvas.setError("这种格式不能直接预览，请用系统应用打开");
            } else {
                canvas.setImage(img);
            }
        } catch (Exception ex) {
            canvas.setError("读取失败：" + ex.getMessage());
        } finally {
            setCursor(Cursor.getDefaultCursor());
            updateZoomLabel();
        }
    }

    private void openExternal() {
        if (images.isEmpty()) return;
        Entry e = images.get(index);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            Path p = temps.materialize(e, vault);
            Desktop.getDesktop().open(p.toFile());
        } catch (IOException ex) {
            canvas.setError("打开失败：" + ex.getMessage());
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    // ---------------------------------------------------------------- 鼠标 / 触控板

    private void installMouseControls() {
        canvas.addMouseWheelListener(e -> {
            if (e.isMetaDown() || e.isControlDown()) {
                double factor = Math.pow(1.1, -e.getPreciseWheelRotation());
                canvas.zoomAt(factor, e.getX(), e.getY());
            } else {
                double d = e.getPreciseWheelRotation() * 36;
                if (e.isShiftDown()) canvas.panBy(-d, 0);
                else canvas.panBy(0, -d);
            }
        });

        MouseAdapter drag = new MouseAdapter() {
            private Point last;

            @Override
            public void mousePressed(MouseEvent e) {
                last = e.getPoint();
                canvas.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                last = null;
                canvas.setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (last == null) return;
                canvas.panBy(e.getX() - last.x, e.getY() - last.y);
                last = e.getPoint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    canvas.toggleActual(e.getX(), e.getY());
                }
            }
        };
        canvas.addMouseListener(drag);
        canvas.addMouseMotionListener(drag);
    }

    /**
     * macOS 触控板双指捏合。com.apple.eawt.* 只在 Mac 的 JDK 里存在，
     * 用反射注册，Windows 上自动跳过，不影响编译和运行。
     */
    private void installPinchGesture() {
        try {
            Class<?> gestureListener = Class.forName("com.apple.eawt.event.GestureListener");
            Class<?> magnificationListener = Class.forName("com.apple.eawt.event.MagnificationListener");
            Class<?> magnificationEvent = Class.forName("com.apple.eawt.event.MagnificationEvent");
            Method getMagnification = magnificationEvent.getMethod("getMagnification");

            Object listener = Proxy.newProxyInstance(
                    gestureListener.getClassLoader(),
                    new Class<?>[]{gestureListener, magnificationListener},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "magnify":
                                if (args != null && args.length == 1) {
                                    double mag = ((Number) getMagnification.invoke(args[0])).doubleValue();
                                    double factor = Math.max(0.5, Math.min(2.0, 1.0 + mag));
                                    SwingUtilities.invokeLater(() ->
                                            canvas.zoomAt(factor, canvas.getWidth() / 2.0, canvas.getHeight() / 2.0));
                                }
                                return null;
                            case "toString":
                                return "VaultBoxMagnify";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return null;
                        }
                    });

            Class<?> utilities = Class.forName("com.apple.eawt.event.GestureUtilities");
            Method add = utilities.getMethod("addGestureListenerTo", JComponent.class, gestureListener);
            add.invoke(null, canvas, listener);
            gestureRef = listener;
        } catch (Throwable ignored) {
            // 非 macOS 或不支持手势的 JDK：滚轮、按钮、双击仍然可用
        }
    }

    // ---------------------------------------------------------------- 画布

    private static final class ImagePanel extends JPanel {
        private static final double MIN_SCALE = 0.02;
        private static final double MAX_SCALE = 8.0;

        private BufferedImage image;
        private String error;
        private double scale = 1.0;
        private double offsetX;
        private double offsetY;
        private boolean fitMode = true;
        private Runnable zoomListener = () -> {};

        ImagePanel() {
            setBackground(new Color(0x1f2328));
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (fitMode) applyFit();
                    else clampPan();
                    repaint();
                    zoomListener.run();
                }
            });
        }

        void setZoomListener(Runnable listener) {
            this.zoomListener = listener == null ? () -> {} : listener;
        }

        void setImage(BufferedImage img) {
            this.image = img;
            this.error = null;
            this.fitMode = true;
            this.scale = 1.0;
            applyFit();
            repaint();
        }

        void setError(String message) {
            this.image = null;
            this.error = message;
            this.scale = 1.0;
            repaint();
        }

        int percent() {
            return (int) Math.round(scale * 100);
        }

        void fitToView() {
            fitMode = true;
            applyFit();
            repaint();
            zoomListener.run();
        }

        void actualSize() {
            zoomTo(1.0, getWidth() / 2.0, getHeight() / 2.0);
        }

        void zoomBy(double factor) {
            zoomAt(factor, getWidth() / 2.0, getHeight() / 2.0);
        }

        void zoomAt(double factor, double ax, double ay) {
            if (image == null) return;
            if (fitMode) applyFit();
            zoomTo(scale * factor, ax, ay);
        }

        private void zoomTo(double target, double ax, double ay) {
            if (image == null) return;
            double next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, target));
            if (Math.abs(next - scale) < 1e-9) return;
            offsetX = ax - (ax - offsetX) * (next / scale);
            offsetY = ay - (ay - offsetY) * (next / scale);
            scale = next;
            fitMode = false;
            clampPan();
            repaint();
            zoomListener.run();
        }

        void panBy(double dx, double dy) {
            if (image == null) return;
            offsetX += dx;
            offsetY += dy;
            fitMode = false;
            clampPan();
            repaint();
        }

        void toggleActual(double ax, double ay) {
            if (image == null) return;
            if (fitMode || scale < 0.999) {
                zoomTo(1.0, ax, ay);
            } else {
                fitToView();
            }
        }

        private void applyFit() {
            if (image == null || getWidth() <= 0 || getHeight() <= 0) return;
            double s = Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight());
            s = Math.min(s, 1.0);
            scale = Math.max(MIN_SCALE, s);
            offsetX = (getWidth() - image.getWidth() * scale) / 2.0;
            offsetY = (getHeight() - image.getHeight() * scale) / 2.0;
        }

        private void clampPan() {
            if (image == null || getWidth() <= 0) return;
            double w = image.getWidth() * scale;
            double h = image.getHeight() * scale;
            if (w <= getWidth()) offsetX = (getWidth() - w) / 2.0;
            else offsetX = Math.max(getWidth() - w, Math.min(0, offsetX));
            if (h <= getHeight()) offsetY = (getHeight() - h) / 2.0;
            else offsetY = Math.max(getHeight() - h, Math.min(0, offsetY));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (fitMode) applyFit();

            Graphics2D g2 = (Graphics2D) g.create();
            if (image != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        scale >= 2.0 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                                : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
                g2.drawImage(image, (int) Math.round(offsetX), (int) Math.round(offsetY), w, h, null);
            } else if (error != null) {
                g2.setColor(new Color(0xd0d5dc));
                g2.setFont(getFont().deriveFont(14f));
                int tw = g2.getFontMetrics().stringWidth(error);
                g2.drawString(error, Math.max(12, (getWidth() - tw) / 2), getHeight() / 2);
            }
            g2.dispose();
        }
    }
}
