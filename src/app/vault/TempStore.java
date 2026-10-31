package app.vault;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

/** 为系统应用临时解密文件，锁定时覆盖并删除。 */
final class TempStore {
    private final Path dir;
    private boolean cleared;

    TempStore() throws IOException {
        dir = Files.createTempDirectory("vaultbox-");
        Runtime.getRuntime().addShutdownHook(new Thread(this::clear, "vaultbox-temp-cleanup"));
    }

    synchronized Path materialize(Entry e, Vault vault) throws IOException {
        if (cleared) throw new IOException("临时目录已清理");
        String ext = Entry.ext(e.path());
        Path target = dir.resolve(e.id() + (ext.isEmpty() ? "" : "." + ext));
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(
                target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            vault.streamTo(e, out);
        }
        target.toFile().deleteOnExit();
        return target;
    }

    Path dir() {
        return dir;
    }

    synchronized void clear() {
        if (cleared) return;
        cleared = true;
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(this::shred);
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
        }
    }

    private void shred(Path file) {
        try {
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
        }
    }
}
