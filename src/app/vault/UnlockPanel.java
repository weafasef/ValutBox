package app.vault;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** 解锁 / 新建加密库界面。 */
final class UnlockPanel extends JPanel {
    private final MainFrame app;
    private final JTextField pathField = new JTextField(28);
    private final JPasswordField passwordField = new JPasswordField(28);
    private final JLabel errorLabel = new JLabel(" ");
    private final JButton unlockButton = new JButton("解锁");
    private final JButton createButton = new JButton("新建加密库");
    private final JButton browseButton = new JButton("选择…");

    UnlockPanel(MainFrame app) {
        super(new GridBagLayout());
        this.app = app;

        JPanel card = new JPanel(new GridBagLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xdfe3e8)),
                BorderFactory.createEmptyBorder(30, 40, 30, 40)));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.insets = new Insets(0, 0, 4, 0);
        c.anchor = GridBagConstraints.WEST;
        JLabel title = new JLabel("VaultBox");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 30f));
        card.add(title, c);

        c.gridy = 1;
        c.insets = new Insets(0, 0, 24, 0);
        JLabel subtitle = new JLabel("加密图片与视频库 · 解锁后直接查看");
        subtitle.setForeground(new Color(0x6b7280));
        card.add(subtitle, c);

        c.gridwidth = 1;
        c.insets = new Insets(6, 0, 6, 8);
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 2;
        c.gridx = 0;
        card.add(new JLabel("加密库"), c);

        pathField.setEditable(false);
        JPanel pathRow = new JPanel(new BorderLayout(6, 0));
        pathRow.add(pathField, BorderLayout.CENTER);
        pathRow.add(browseButton, BorderLayout.EAST);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(6, 0, 6, 0);
        card.add(pathRow, c);

        c.gridy = 3;
        c.gridx = 0;
        c.weightx = 0;
        c.insets = new Insets(6, 0, 6, 8);
        c.fill = GridBagConstraints.NONE;
        card.add(new JLabel("密码"), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(6, 0, 6, 0);
        card.add(passwordField, c);

        errorLabel.setForeground(new Color(0xc0392b));
        c.gridy = 4;
        c.gridx = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 0, 10, 0);
        card.add(errorLabel, c);

        JPanel buttons = new JPanel(new BorderLayout(8, 0));
        buttons.add(unlockButton, BorderLayout.CENTER);
        buttons.add(createButton, BorderLayout.EAST);
        c.gridy = 5;
        c.insets = new Insets(0, 0, 10, 0);
        card.add(buttons, c);

        c.gridy = 6;
        c.insets = new Insets(0, 0, 0, 0);
        JLabel hint = new JLabel("密码无法找回，请记牢；锁定后文件恢复为密文。");
        hint.setForeground(new Color(0x9aa1ab));
        hint.setFont(hint.getFont().deriveFont(11f));
        card.add(hint, c);

        add(card);

        browseButton.addActionListener(e -> chooseVault());
        unlockButton.addActionListener(e -> attemptUnlock());
        createButton.addActionListener(e -> chooseCreate());
        passwordField.addActionListener(e -> attemptUnlock());

        String last = Config.get("lastVault", "");
        if (!last.isBlank()) pathField.setText(last);
    }

    void setBusy(boolean busy) {
        unlockButton.setEnabled(!busy);
        createButton.setEnabled(!busy);
        browseButton.setEnabled(!busy);
        passwordField.setEnabled(!busy);
        unlockButton.setText(busy ? "正在解锁…" : "解锁");
    }

    void showError(String message) {
        errorLabel.setText(message == null ? " " : message);
    }

    void clearPassword() {
        passwordField.setText("");
    }

    void focusPassword() {
        SwingUtilities.invokeLater(passwordField::requestFocusInWindow);
    }

    void setPath(String path) {
        pathField.setText(path);
    }

    private void chooseVault() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("选择加密库文件夹");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            if (f != null) {
                pathField.setText(f.getAbsolutePath());
                passwordField.requestFocusInWindow();
            }
        }
    }

    private void attemptUnlock() {
        String path = pathField.getText().trim();
        if (path.isEmpty()) {
            showError("先选择加密库文件夹");
            return;
        }
        char[] pw = passwordField.getPassword();
        if (pw.length == 0) {
            showError("请输入密码");
            return;
        }
        showError(" ");
        app.unlockVault(Path.of(path), pw);
    }

    private void chooseCreate() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("选择一个文件夹作为新加密库");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (f == null) return;
        Path dir = f.toPath();
        if (Files.exists(dir.resolve("vault.properties"))) {
            showError("这个文件夹已经是加密库，直接解锁即可");
            return;
        }
        char[] pw = askPassword(dir);
        if (pw == null) return;
        app.createVault(dir, pw);
    }

    private char[] askPassword(Path dir) {
        JPasswordField p1 = new JPasswordField(22);
        JPasswordField p2 = new JPasswordField(22);
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 8, 0);
        panel.add(new JLabel("将创建加密库：" + dir.getFileName()), c);
        c.gridy = 1;
        panel.add(new JLabel("设置密码（至少 6 位）"), c);
        c.gridy = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 8, 0);
        panel.add(p1, c);
        c.gridy = 3;
        c.insets = new Insets(0, 0, 4, 0);
        panel.add(new JLabel("再输入一次"), c);
        c.gridy = 4;
        c.insets = new Insets(0, 0, 8, 0);
        panel.add(p2, c);
        c.gridy = 5;
        c.insets = new Insets(0, 0, 0, 0);
        JLabel warn = new JLabel("密码无法找回。");
        warn.setForeground(new Color(0xc0392b));
        panel.add(warn, c);

        while (true) {
            int r = JOptionPane.showConfirmDialog(this, panel, "新建加密库", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            char[] a = p1.getPassword();
            char[] b = p2.getPassword();
            if (a.length < 6) {
                JOptionPane.showMessageDialog(this, "密码至少 6 位", "密码太短", JOptionPane.WARNING_MESSAGE);
                Arrays.fill(b, '\0');
                continue;
            }
            if (!Arrays.equals(a, b)) {
                JOptionPane.showMessageDialog(this, "两次输入的密码不一致", "密码不一致", JOptionPane.WARNING_MESSAGE);
                Crypto.wipe(a);
                Crypto.wipe(b);
                continue;
            }
            Crypto.wipe(b);
            return a;
        }
    }
}
