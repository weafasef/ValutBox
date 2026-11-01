package app.vault;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** 为系统应用 / 拖拽临时解密文件，锁定时覆盖并删除。 */
final class TempStore {
    private final Path dir;
    private final Map<String, Path> cache = new HashMap<>();
    private boolean cleared;

    TempStore() throws IOException {
        dir = Files.createTempDirectory("vaultbox-");
        Runtime.getRuntime().addShutdownHook(new Thread(this::clear, "vaultbox-temp-cleanup"));
    }

    synchronized Path materialize(Entry e, Vault vault) throws IOException {
        return materializeNamed(e, vault);
    }

    /**
     * 解密到临时目录，子目录用条目 id 隔离，文件名保持原名，
     * 这样拖到 Chatbox / 邮件 / 聊天窗口时显示的是原始文件名。
     */
    synchronized Path materializeNamed(Entry e, Vault vault) throws IOException {
        if (cleared) throw new IOException("临时目录已清理");
        Path cached = cache.get(e.id());
        if (cached != null && Files.isRegularFile(cached)) return cached;

        Path sub = dir.resolve(e.id());
        Files.createDirectories(sub);
        Path target = sub.resolve(safeName(e.name()));
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(
                target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            vault.streamTo(e, out);
        }
        target.toFile().deleteOnExit();
        cache.put(e.id(), target);
        return target;
    }

    synchronized boolean isCached(Entry e) {
        Path p = cache.get(e.id());
        return p != null && Files.isRegularFile(p);
    }

    Path dir() {
        return dir;
    }

    synchronized void clear() {
        if (cleared) return;
        cleared = true;
        cache.clear();
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::shred);
        } catch (IOException ignored) {
            // 尽力清理
        }
    }

    private void shred(Path file) {
        try {
            if (Files.isDirectory(file)) {
                Files.deleteIfExists(file);
                return;
            }
            if (Files.isRegularFile(file)) {
                long size = Files.size(file);
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                    byte[] zeros = new byte[64 * 1024];
                    long left = size;
                    while (left > 0) {
                        int n = (int) Math.min(zeros.length, left);
                        raf.write(zeros, 0, n);
                        left -= n;
                    }
                }
            }
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 尽力清理
        }
    }

    private static String safeName(String name) {
        String n = name == null ? "" : name;
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "file";
        if (n.length() > 160) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 12 ? n.substring(dot) : "";
            n = n.substring(0, 160 - ext.length()) + ext;
        }
        return n;
    }
}
