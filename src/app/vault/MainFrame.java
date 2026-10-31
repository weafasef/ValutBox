package app.vault;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;

/** 主窗口：未解锁时显示密码页，解锁后显示文件列表。 */
final class MainFrame extends JFrame {
    private static final String CARD_UNLOCK = "unlock";
    private static final String CARD_BROWSE = "browse";

    private final CardLayout cards = new CardLayout();
    private final JPanel stacked = new JPanel(cards);
    private final UnlockPanel unlockPanel = new UnlockPanel(this);
    private final BrowserPanel browserPanel = new BrowserPanel(this);

    private Vault vault;
    private StreamServer server;
    private TempStore temps;

    MainFrame() {
        super("VaultBox · 加密库");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                lockVault();
                dispose();
                System.exit(0);
            }
        });

        stacked.add(unlockPanel, CARD_UNLOCK);
        stacked.add(browserPanel, CARD_BROWSE);
        setContentPane(stacked);
        setSize(1000, 680);
        setMinimumSize(new Dimension(720, 480));
        setLocationRelativeTo(null);
        cards.show(stacked, CARD_UNLOCK);
        unlockPanel.focusPassword();
    }

    void unlockVault(Path dir, char[] password) {
        unlockPanel.setBusy(true);
        unlockPanel.showError(" ");
        try {
            Vault v = Vault.open(dir, password);
            StreamServer s = null;
            TempStore t = null;
            try {
                s = StreamServer.start(v);
                t = new TempStore();
            } catch (Exception ex) {
                if (s != null) s.close();
                v.close();
                throw ex;
            }
            this.vault = v;
            this.server = s;
            this.temps = t;
            browserPanel.attach(v, s, t);
            cards.show(stacked, CARD_BROWSE);
            setTitle("VaultBox · " + dir.getFileName());
            Config.set("lastVault", dir.toAbsolutePath().toString());
            unlockPanel.clearPassword();
        } catch (Vault.BadPasswordException e) {
            unlockPanel.showError("密码不对");
        } catch (Exception e) {
            unlockPanel.showError("打不开：" + e.getMessage());
        } finally {
            Crypto.wipe(password);
            unlockPanel.setBusy(false);
        }
    }

    void createVault(Path dir, char[] password) {
        unlockPanel.setBusy(true);
        Vault v = null;
        StreamServer s = null;
        TempStore t = null;
        try {
            v = Vault.create(dir, password);
            s = StreamServer.start(v);
            t = new TempStore();
            this.vault = v;
            this.server = s;
            this.temps = t;
            browserPanel.attach(v, s, t);
            cards.show(stacked, CARD_BROWSE);
            setTitle("VaultBox · " + dir.getFileName());
            Config.set("lastVault", dir.toAbsolutePath().toString());
            unlockPanel.setPath(dir.toAbsolutePath().toString());
            unlockPanel.clearPassword();
        } catch (Exception e) {
            if (t != null) t.clear();
            if (s != null) s.close();
            if (v != null) v.close();
            unlockPanel.showError("创建失败：" + e.getMessage());
        } finally {
            Crypto.wipe(password);
            unlockPanel.setBusy(false);
        }
    }

    void lockVault() {
        if (server != null) server.close();
        if (vault != null) vault.close();
        if (temps != null) temps.clear();
        server = null;
        vault = null;
        temps = null;
        browserPanel.detach();
        unlockPanel.clearPassword();
        unlockPanel.showError(" ");
        setTitle("VaultBox · 加密库");
        cards.show(stacked, CARD_UNLOCK);
        unlockPanel.focusPassword();
    }
}
