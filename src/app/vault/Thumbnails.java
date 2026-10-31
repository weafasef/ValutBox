package app.vault;

import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 后台解密并生成缩略图，避免在渲染线程做解密。 */
final class Thumbnails {
    private final Map<String, ImageIcon> cache = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "vault-thumb");
        t.setDaemon(true);
        return t;
    });

    private volatile Vault vault;
    private volatile Runnable repaintHook = () -> {};

    void setRepaintHook(Runnable hook) {
        this.repaintHook = hook;
    }

    void setVault(Vault vault) {
        this.vault = vault;
        cache.clear();
        pending.clear();
        failed.clear();
    }

    void invalidate(String id) {
        cache.remove(id);
        failed.remove(id);
    }

    ImageIcon get(Entry e) {
        Vault v = vault;
        if (v == null || e.kind() != Entry.Kind.IMAGE) return null;
        ImageIcon icon = cache.get(e.id());
        if (icon != null) return icon;
        if (failed.contains(e.id()) || pending.contains(e.id())) return null;
        if (e.size() > 80L * 1024 * 1024) {
            failed.add(e.id());
            return null;
        }
        if (!pending.add(e.id())) return null;

        pool.submit(() -> {
            try {
                byte[] data = v.readAll(e);
                BufferedImage img = Ui.readImage(data);
                if (img == null) {
                    failed.add(e.id());
                    return;
                }
                ImageIcon scaled = Ui.scale(img, 72, 54);
                if (scaled != null) {
                    cache.put(e.id(), scaled);
                    SwingUtilities.invokeLater(repaintHook);
                } else {
                    failed.add(e.id());
                }
            } catch (Exception ex) {
                failed.add(e.id());
            } finally {
                pending.remove(e.id());
            }
        });
        return null;
    }
}
