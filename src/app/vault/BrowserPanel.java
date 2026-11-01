package app.vault;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** 解锁后的文件浏览界面。 */
final class BrowserPanel extends JPanel {
    private final MainFrame app;
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model);
    private final JTextField search = new JTextField(16);
    private final JLabel status = new JLabel(" ");
    private final Thumbnails thumbs = new Thumbnails();

    private Vault vault;
    private StreamServer server;
    private TempStore temps;
    private List<Entry> all = new ArrayList<>();
    private final List<ImageViewer> viewers = new ArrayList<>();

    BrowserPanel(MainFrame app) {
        super(new BorderLayout());
        this.app = app;
        thumbs.setRepaintHook(list::repaint);

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        bar.add(makeButton("导入文件", this::chooseFiles));
        bar.add(makeButton("导入文件夹", this::chooseFolder));
        bar.addSeparator();
        bar.add(makeButton("导出", this::exportSelected));
        bar.add(makeButton("删除", this::deleteSelected));
        bar.addSeparator();
        bar.add(makeButton("播放器设置", this::playerSettings));
        bar.add(makeButton("锁定", app::lockVault));
        bar.add(javax.swing.Box.createHorizontalGlue());
        bar.add(new JLabel("搜索 "));
        search.setMaximumSize(new Dimension(220, 30));
        bar.add(search);
        add(bar, BorderLayout.NORTH);

        list.setCellRenderer(new EntryRenderer());
        list.setFixedCellHeight(76);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        list.setDragEnabled(true);
        list.setTransferHandler(new DragOutHandler());
        list.setToolTipText("单击选中，⌘ 点选多个，Shift 连选；可直接拖到聊天窗口");
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int idx = list.locationToIndex(e.getPoint());
                    if (idx >= 0 && idx < model.size()) open(model.getElementAt(idx));
                }
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) renderStatus();
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        status.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        status.setForeground(new Color(0x6b7280));
        add(status, BorderLayout.SOUTH);

        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter();
            }
        });
    }

    private JButton makeButton(String text, Runnable action) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.setMargin(new java.awt.Insets(6, 12, 6, 12));
        b.addActionListener(e -> action.run());
        return b;
    }

    // ---------------------------------------------------------------- 生命周期

    void attach(Vault vault, StreamServer server, TempStore temps) {
        this.vault = vault;
        this.server = server;
        this.temps = temps;
        thumbs.setVault(vault);
        search.setText("");
        reload();
    }

    void detach() {
        for (ImageViewer viewer : new ArrayList<>(viewers)) viewer.dispose();
        viewers.clear();
        this.vault = null;
        this.server = null;
        this.temps = null;
        all = new ArrayList<>();
        model.clear();
        thumbs.setVault(null);
        status.setText("未解锁");
    }

    private void reload() {
        if (vault == null) return;
        List<Entry> next = new ArrayList<>(vault.entries());
        next.sort(Comparator.comparing((Entry e) -> e.path().toLowerCase(Locale.ROOT)));
        all = next;
        refilter();
    }

    private void refilter() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        List<Entry> shown = new ArrayList<>();
        for (Entry e : all) {
            if (q.isEmpty() || e.path().toLowerCase(Locale.ROOT).contains(q)) shown.add(e);
        }
        model.clear();
        for (Entry e : shown) model.addElement(e);
        renderStatus();
    }

    private void renderStatus() {
        if (vault == null) {
            status.setText("未解锁");
            return;
        }
        int selected = list.getSelectedIndices().length;
        StringBuilder sb = new StringBuilder("已解锁 · 共 ").append(all.size()).append(" 项");
        if (!search.getText().trim().isEmpty()) {
            sb.append(" · 筛选出 ").append(model.size()).append(" 项");
        }
        if (selected > 0) sb.append(" · 已选 ").append(selected).append(" 项");
        sb.append(" · 双击打开，可拖到聊天窗口");
        status.setText(sb.toString());
    }

    private Entry selected() {
        return list.getSelectedValue();
    }

    private List<Entry> selectedEntries() {
        return list.getSelectedValuesList();
    }

    private List<Entry> visible() {
        List<Entry> out = new ArrayList<>(model.size());
        for (int i = 0; i < model.size(); i++) out.add(model.getElementAt(i));
        return out;
    }

    // ---------------------------------------------------------------- 打开

    private void open(Entry e) {
        switch (e.kind()) {
            case IMAGE -> openImage(e);
            case VIDEO -> openVideo(e);
            default -> openExternal(e);
        }
    }

    private void openImage(Entry e) {
        List<Entry> images = visible().stream().filter(x -> x.kind() == Entry.Kind.IMAGE).toList();
        if (images.isEmpty()) {
            openExternal(e);
            return;
        }
        int idx = images.indexOf(e);
        ImageViewer viewer = new ImageViewer(app, vault, temps, images, Math.max(0, idx));
        viewers.add(viewer);
        viewer.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent ev) {
                viewers.remove(viewer);
            }
        });
        viewer.setVisible(true);
    }

    private void openVideo(Entry e) {
        if (server == null) return;
        try {
            Player.play(server.url(e));
        } catch (Exception ex) {
            error("打不开视频：" + ex.getMessage()
                    + "\n\n可以到「播放器设置」填写 IINA 或 VLC 的完整路径。");
        }
    }

    private void openExternal(Entry e) {
        background("正在解密", "正在把「" + e.name() + "」解密到临时目录…", () -> {
            Path p = temps.materialize(e, vault);
            Desktop.getDesktop().open(p.toFile());
        }, null);
    }

    // ---------------------------------------------------------------- 导入

    private record Job(Path src, String rel) {}

    private void chooseFiles() {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("选择要加密的文件");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File[] selected = fc.getSelectedFiles();
        if (selected.length == 0 && fc.getSelectedFile() != null) {
            selected = new File[]{fc.getSelectedFile()};
        }
        List<Job> jobs = new ArrayList<>();
        for (File f : selected) {
            if (f.isFile()) jobs.add(new Job(f.toPath(), f.getName()));
        }
        runImport(jobs);
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("选择要加密的文件夹");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (f == null || !f.isDirectory()) return;

        Path root = f.toPath();
        String base = root.getFileName() == null ? "folder" : root.getFileName().toString();
        List<Job> jobs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isJunk(p))
                    .sorted()
                    .forEach(p -> jobs.add(new Job(p, base + "/" + root.relativize(p).toString().replace('\\', '/'))));
        } catch (IOException ex) {
            error("读取文件夹失败：" + ex.getMessage());
            return;
        }
        if (jobs.isEmpty()) {
            info("这个文件夹里没有可导入的文件");
            return;
        }
        runImport(jobs);
    }

    private static boolean isJunk(Path p) {
        String n = p.getFileName().toString();
        return n.equals(".DS_Store") || n.startsWith("._");
    }

    private void runImport(List<Job> jobs) {
        if (jobs.isEmpty() || vault == null) return;

        JDialog dialog = new JDialog(app, "正在加密导入", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        JProgressBar bar = new JProgressBar(0, jobs.size());
        bar.setStringPainted(true);
        bar.setString("0 / " + jobs.size());
        JLabel label = new JLabel("准备导入 " + jobs.size() + " 个文件…");
        JButton cancel = new JButton("取消");

        JPanel center = new JPanel(new BorderLayout(8, 10));
        center.setBorder(BorderFactory.createEmptyBorder(16, 18, 6, 18));
        center.add(label, BorderLayout.NORTH);
        center.add(bar, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(0, 18, 12, 18));
        south.add(cancel, BorderLayout.EAST);
        dialog.add(center, BorderLayout.CENTER);
        dialog.add(south, BorderLayout.SOUTH);
        dialog.setSize(460, 150);
        dialog.setLocationRelativeTo(this);

        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<String> firstError = new AtomicReference<>();
        cancel.addActionListener(e -> {
            stop.set(true);
            cancel.setEnabled(false);
            label.setText("正在停止，等当前文件处理完…");
        });

        SwingWorker<int[], int[]> worker = new SwingWorker<>() {
            @Override
            protected int[] doInBackground() {
                int done = 0;
                int failed = 0;
                for (Job job : jobs) {
                    if (stop.get()) break;
                    try {
                        vault.importFile(job.src(), job.rel());
                        done++;
                    } catch (Exception ex) {
                        failed++;
                        firstError.compareAndSet(null, ex.getMessage());
                    }
                    publish(new int[]{done + failed, done, failed});
                }
                return new int[]{done, failed};
            }

            @Override
            protected void process(List<int[]> chunks) {
                int[] v = chunks.get(chunks.size() - 1);
                bar.setValue(Math.min(v[0], jobs.size()));
                bar.setString(v[0] + " / " + jobs.size());
                label.setText("已完成 " + v[1] + " 个" + (v[2] > 0 ? "，失败 " + v[2] + " 个" : "")
                        + (stop.get() ? "（正在停止）" : ""));
            }

            @Override
            protected void done() {
                dialog.dispose();
            }
        };
        worker.execute();
        dialog.setVisible(true);

        int done = 0;
        int failed = 0;
        try {
            int[] result = worker.get();
            done = result[0];
            failed = result[1];
        } catch (Exception ignored) {
        }
        reload();
        if (failed > 0) {
            error("导入完成：成功 " + done + " 个，失败 " + failed + " 个。\n首个错误：" + firstError.get());
        } else {
            status.setText("已导入 " + done + " 个文件 · 共 " + all.size() + " 项");
        }
    }

    // ---------------------------------------------------------------- 导出 / 删除

    private void exportSelected() {
        List<Entry> entries = selectedEntries();
        if (entries.isEmpty()) {
            info("先在列表里选择文件");
            return;
        }
        if (entries.size() == 1) {
            Entry e = entries.get(0);
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("导出到");
            fc.setSelectedFile(new File(e.name()));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path target = fc.getSelectedFile().toPath();
            if (Files.exists(target)) {
                int r = JOptionPane.showConfirmDialog(this, "「" + target.getFileName() + "」已存在，覆盖吗？",
                        "覆盖确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r != JOptionPane.YES_OPTION) return;
            }
            background("正在导出", "正在解密到 " + target.getFileName() + "…",
                    () -> vault.exportTo(e, target),
                    () -> status.setText("已导出到 " + target));
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("导出 " + entries.size() + " 个文件到文件夹");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File chosen = fc.getSelectedFile();
        if (chosen == null) return;
        Path dir = chosen.toPath();

        int[] counts = new int[2];
        String[] firstError = new String[1];
        background("正在导出", "正在解密 " + entries.size() + " 个文件…", () -> {
            for (Entry e : entries) {
                try {
                    vault.exportTo(e, uniqueTarget(dir, e.name()));
                    counts[0]++;
                } catch (Exception ex) {
                    counts[1]++;
                    if (firstError[0] == null) firstError[0] = ex.getMessage();
                }
            }
        }, () -> {
            if (counts[1] > 0) {
                error("导出完成：成功 " + counts[0] + " 个，失败 " + counts[1] + " 个。\n" + firstError[0]);
            } else {
                status.setText("已导出 " + counts[0] + " 个文件到 " + dir);
            }
        });
    }

    private static Path uniqueTarget(Path dir, String name) {
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        Path target = dir.resolve(name);
        int i = 2;
        while (Files.exists(target)) {
            target = dir.resolve(base + " (" + i++ + ")" + ext);
        }
        return target;
    }

    private void deleteSelected() {
        List<Entry> entries = selectedEntries();
        if (entries.isEmpty()) {
            info("先在列表里选择文件");
            return;
        }
        String message = entries.size() == 1
                ? "删除「" + entries.get(0).name() + "」？\n加密文件会一起删除，无法恢复。"
                : "删除选中的 " + entries.size() + " 个文件？\n加密文件会一起删除，无法恢复。";
        int r = JOptionPane.showConfirmDialog(this, message,
                "删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;

        int ok = 0;
        String firstError = null;
        for (Entry e : entries) {
            try {
                vault.deleteEntry(e);
                thumbs.invalidate(e.id());
                ok++;
            } catch (Exception ex) {
                if (firstError == null) firstError = ex.getMessage();
            }
        }
        reload();
        if (firstError != null) {
            error("已删除 " + ok + " 个，部分失败：" + firstError);
        } else {
            status.setText("已删除 " + ok + " 个文件 · 共 " + all.size() + " 项");
        }
    }

    // ---------------------------------------------------------------- 拖出到其它应用

    private final class DragOutHandler extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return COPY;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            if (vault == null || temps == null) return null;
            List<Entry> entries = selectedEntries();
            if (entries.isEmpty()) return null;
            try {
                List<File> files = prepareDragFiles(entries);
                return files.isEmpty() ? null : new FileListTransferable(files);
            } catch (Exception ex) {
                error("准备拖出失败：" + ex.getMessage());
                return null;
            }
        }
    }

    private static final class FileListTransferable implements Transferable {
        private final List<File> files;

        FileListTransferable(List<File> files) {
            this.files = files;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return files;
        }
    }

    private List<File> prepareDragFiles(List<Entry> entries) throws Exception {
        long missing = 0;
        for (Entry e : entries) {
            if (!temps.isCached(e)) missing += e.size();
        }
        if (missing <= 8L * 1024 * 1024) {
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            status.setText("正在准备拖出的文件…");
            try {
                return decryptToFiles(entries, null);
            } finally {
                setCursor(Cursor.getDefaultCursor());
                renderStatus();
            }
        }

        JDialog dialog = new JDialog(app, "正在准备拖出", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JLabel label = new JLabel("正在解密 " + entries.size() + " 个文件…");
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JPanel panel = new JPanel(new BorderLayout(8, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        panel.add(label, BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        dialog.setContentPane(panel);
        dialog.setSize(440, 130);
        dialog.setLocationRelativeTo(this);

        AtomicReference<List<File>> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                try {
                    result.set(decryptToFiles(entries,
                            msg -> SwingUtilities.invokeLater(() -> label.setText(msg))));
                } catch (Exception ex) {
                    failure.set(ex);
                }
                return null;
            }

            @Override
            protected void done() {
                dialog.dispose();
            }
        };
        worker.execute();
        dialog.setVisible(true);
        renderStatus();
        if (failure.get() != null) throw failure.get();
        List<File> files = result.get();
        if (files == null) throw new IOException("准备拖出失败");
        return files;
    }

    private List<File> decryptToFiles(List<Entry> entries,
                                      java.util.function.Consumer<String> progress) throws Exception {
        List<File> files = new ArrayList<>(entries.size());
        int i = 0;
        for (Entry e : entries) {
            if (progress != null) {
                i++;
                progress.accept("正在解密：" + e.name() + "（" + i + "/" + entries.size() + "）");
            }
            files.add(temps.materializeNamed(e, vault).toFile());
        }
        return files;
    }

    // ---------------------------------------------------------------- 设置 / 工具

    private void playerSettings() {
        JTextField field = new JTextField(Config.get("player", ""), 36);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html>播放器可执行文件路径，留空则自动查找 IINA / VLC。<br>"
                + "Windows 示例：C:\\Program Files\\VideoLAN\\VLC\\vlc.exe</html>"), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        int r = JOptionPane.showConfirmDialog(this, panel, "播放器设置",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r == JOptionPane.OK_OPTION) {
            Config.set("player", field.getText().trim());
            status.setText("播放器设置已保存");
        }
    }

    private interface Task {
        void run() throws Exception;
    }

    private void background(String title, String message, Task task, Runnable after) {
        JDialog dialog = new JDialog(app, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel panel = new JPanel(new BorderLayout(8, 14));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        panel.add(new JLabel(message), BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        panel.add(bar, BorderLayout.CENTER);
        dialog.setContentPane(panel);
        dialog.setSize(420, 130);
        dialog.setLocationRelativeTo(this);

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            private Exception failure;

            @Override
            protected Void doInBackground() {
                try {
                    task.run();
                } catch (Exception ex) {
                    failure = ex;
                }
                return null;
            }

            @Override
            protected void done() {
                dialog.dispose();
                if (failure != null) {
                    error("操作失败：" + failure.getMessage());
                } else if (after != null) {
                    after.run();
                }
            }
        };
        worker.execute();
        dialog.setVisible(true);
    }

    private void info(String message) {
        JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.INFORMATION_MESSAGE);
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "出错了", JOptionPane.ERROR_MESSAGE);
    }

    // ---------------------------------------------------------------- 渲染

    private final class EntryRenderer extends JPanel implements ListCellRenderer<Entry> {
        private final JLabel thumb = new JLabel();
        private final JLabel titleLabel = new JLabel();
        private final JLabel subLabel = new JLabel();
        private final javax.swing.ImageIcon imageIcon = Ui.placeholder("图", new Color(0x2f7d6d));
        private final javax.swing.ImageIcon videoIcon = Ui.placeholder("▶", new Color(0x35507d));
        private final javax.swing.ImageIcon fileIcon = Ui.placeholder("文", new Color(0x6b6f78));

        EntryRenderer() {
            super(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            thumb.setPreferredSize(new Dimension(72, 54));
            thumb.setHorizontalAlignment(JLabel.CENTER);
            thumb.setVerticalAlignment(JLabel.CENTER);
            add(thumb, BorderLayout.WEST);

            JPanel text = new JPanel(new GridLayout(2, 1, 0, 2));
            text.setOpaque(false);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.PLAIN, 13.5f));
            subLabel.setFont(subLabel.getFont().deriveFont(11.5f));
            text.add(titleLabel);
            text.add(subLabel);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Entry> list, Entry e, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            javax.swing.ImageIcon icon = thumbs.get(e);
            if (icon == null) {
                icon = switch (e.kind()) {
                    case IMAGE -> imageIcon;
                    case VIDEO -> videoIcon;
                    default -> fileIcon;
                };
            }
            thumb.setIcon(icon);
            titleLabel.setText(e.name());
            String dir = e.dir();
            subLabel.setText((dir.isEmpty() ? "" : dir + "  ·  ") + Ui.humanSize(e.size()));

            Color fg = isSelected ? list.getSelectionForeground() : list.getForeground();
            titleLabel.setForeground(fg);
            subLabel.setForeground(isSelected ? fg : new Color(0x6b7280));
            setBackground(isSelected ? list.getSelectionBackground()
                    : (index % 2 == 0 ? new Color(0xffffff) : new Color(0xf6f7f9)));
            setOpaque(true);
            return this;
        }
    }
}
