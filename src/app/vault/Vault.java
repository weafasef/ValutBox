package app.vault;

import javax.crypto.AEADBadTagException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * 一个文件夹即一个加密库：
 *   vault.properties  口令盐、PBKDF2 参数、被口令密钥包裹的主密钥
 *   index.enc         用主密钥加密的文件索引（文件名、大小、时间）
 *   data/*.vfb        每个文件独立的分块 AES-GCM 密文，文件名是随机 UUID
 * 主密钥只存在于内存，close() 时擦除。
 */
public final class Vault implements AutoCloseable {
    private static final String META = "vault.properties";
    private static final String INDEX = "index.enc";
    private static final String DATA = "data";
    private static final String META_VERSION = "1";

    private final Path root;
    private final Path dataDir;
    private byte[] masterKey;
    private byte[] manifestKey;
    private final List<Entry> entries = new ArrayList<>();
    private volatile boolean open = true;

    public static final class BadPasswordException extends Exception {
        BadPasswordException() {
            super("密码不正确");
        }
    }

    private Vault(Path root, byte[] masterKey, byte[] manifestKey) {
        this.root = root.toAbsolutePath().normalize();
        this.dataDir = this.root.resolve(DATA);
        this.masterKey = masterKey;
        this.manifestKey = manifestKey;
    }

    // ---------------------------------------------------------------- 创建 / 打开

    public static Vault create(Path dir, char[] password) throws IOException {
        Path meta = dir.resolve(META);
        if (Files.exists(meta)) throw new IOException("这个文件夹里已经有一个加密库了");
        Files.createDirectories(dir);
        Files.createDirectories(dir.resolve(DATA));

        try {
            byte[] master = Crypto.random(32);
            byte[] salt = Crypto.random(16);
            byte[] kek = Crypto.pbkdf2(password, salt, Crypto.ITERATIONS);
            byte[] iv = Crypto.random(Crypto.NONCE);
            byte[] wrapped;
            try {
                wrapped = Crypto.encrypt(kek, iv, master, 0, master.length);
            } finally {
                Crypto.wipe(kek);
            }

            Properties p = new Properties();
            p.setProperty("version", META_VERSION);
            p.setProperty("kdf", "PBKDF2WithHmacSHA256");
            p.setProperty("iterations", String.valueOf(Crypto.ITERATIONS));
            p.setProperty("salt", b64(salt));
            p.setProperty("wrapIv", b64(iv));
            p.setProperty("wrapped", b64(wrapped));
            p.setProperty("created", Instant.now().toString());
            writeProperties(dir.resolve(META), p);

            Vault v = new Vault(dir, master, Crypto.derive(master, "manifest"));
            v.saveIndex();
            return v;
        } catch (GeneralSecurityException e) {
            throw new IOException("创建加密库失败", e);
        }
    }

    public static Vault open(Path dir, char[] password) throws IOException, BadPasswordException {
        Path meta = dir.resolve(META);
        if (!Files.isDirectory(dir)) throw new IOException("文件夹不存在：" + dir);
        if (!Files.isRegularFile(meta)) throw new IOException("不是有效的加密库（缺少 vault.properties）");

        Properties p;
        try (InputStream in = Files.newInputStream(meta)) {
            p = new Properties();
            p.load(in);
        }

        int version;
        try {
            version = Integer.parseInt(p.getProperty("version", "1").trim());
        } catch (NumberFormatException e) {
            throw new IOException("加密库版本信息损坏");
        }
        if (version != 1) throw new IOException("不支持的加密库版本：" + version);

        byte[] salt = unb64(p.getProperty("salt"), "salt");
        byte[] iv = unb64(p.getProperty("wrapIv"), "wrapIv");
        byte[] wrapped = unb64(p.getProperty("wrapped"), "wrapped");
        if (iv.length != Crypto.NONCE || wrapped.length != 32 + Crypto.TAG) {
            throw new IOException("加密库密钥信息损坏");
        }
        int iterations;
        try {
            iterations = Integer.parseInt(p.getProperty("iterations", String.valueOf(Crypto.ITERATIONS)).trim());
        } catch (NumberFormatException e) {
            throw new IOException("加密库参数损坏");
        }
        if (iterations < 10_000 || iterations > 5_000_000) throw new IOException("加密库参数异常");

        byte[] kek = Crypto.pbkdf2(password, salt, iterations);
        byte[] master;
        try {
            master = Crypto.decrypt(kek, iv, wrapped, 0, wrapped.length);
        } catch (AEADBadTagException e) {
            throw new BadPasswordException();
        } catch (GeneralSecurityException e) {
            throw new IOException("解锁失败", e);
        } finally {
            Crypto.wipe(kek);
        }

        Vault v = new Vault(dir, master, Crypto.derive(master, "manifest"));
        try {
            Files.createDirectories(v.dataDir);
            v.loadIndex();
        } catch (IOException e) {
            v.close();
            throw e;
        }
        return v;
    }

    // ---------------------------------------------------------------- 索引

    private void loadIndex() throws IOException {
        Path file = root.resolve(INDEX);
        List<Entry> loaded = new ArrayList<>();
        if (Files.exists(file)) {
            byte[] blob = Files.readAllBytes(file);
            if (blob.length < Crypto.NONCE + Crypto.TAG) throw new IOException("索引文件已损坏");
            byte[] nonce = Arrays.copyOfRange(blob, 0, Crypto.NONCE);
            try {
                byte[] plain = Crypto.decrypt(manifestKey, nonce, blob, Crypto.NONCE, blob.length - Crypto.NONCE);
                loaded = Manifest.deserialize(plain);
            } catch (GeneralSecurityException e) {
                throw new IOException("索引解密失败，加密库可能已损坏", e);
            }
        }
        synchronized (entries) {
            entries.clear();
            entries.addAll(loaded);
        }
    }

    private void saveIndex() throws IOException {
        List<Entry> snapshot;
        synchronized (entries) {
            snapshot = new ArrayList<>(entries);
        }
        try {
            byte[] nonce = Crypto.random(Crypto.NONCE);
            byte[] plain = Manifest.serialize(snapshot);
            byte[] ct = Crypto.encrypt(manifestKey, nonce, plain, 0, plain.length);
            byte[] blob = new byte[Crypto.NONCE + ct.length];
            System.arraycopy(nonce, 0, blob, 0, Crypto.NONCE);
            System.arraycopy(ct, 0, blob, Crypto.NONCE, ct.length);
            writeAtomic(root.resolve(INDEX), blob);
        } catch (GeneralSecurityException e) {
            throw new IOException("保存索引失败", e);
        }
    }

    // ---------------------------------------------------------------- 查询

    public List<Entry> entries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    public Entry find(String id) {
        synchronized (entries) {
            for (Entry e : entries) {
                if (e.id().equals(id)) return e;
            }
            return null;
        }
    }

    public boolean isOpen() {
        return open;
    }

    // ---------------------------------------------------------------- 导入 / 删除 / 导出

    public Entry importFile(Path src, String relPath) throws IOException {
        ensureOpen();
        if (!Files.isRegularFile(src)) throw new IOException("不是普通文件：" + src);
        Path abs = src.toAbsolutePath().normalize();
        if (abs.startsWith(root)) throw new IOException("不能把加密库自身的文件再导入进来");

        String rel = normalizeRel(relPath);
        if (rel.isEmpty()) throw new IOException("文件名为空");

        Entry old;
        synchronized (entries) {
            old = findByPath(rel);
        }
        if (old != null) removeEntryInternal(old, false);

        String id = UUID.randomUUID().toString();
        byte[] fileKey = Crypto.derive(masterKey, "file/" + id);
        Path dest = dataFile(id);
        try {
            EncryptedFile.encrypt(src, dest, fileKey, Crypto.CHUNK);
        } catch (IOException e) {
            Files.deleteIfExists(dest);
            throw e;
        } finally {
            Crypto.wipe(fileKey);
        }

        Entry e = new Entry(id, rel, Files.size(src), Files.getLastModifiedTime(src).toMillis(), Entry.kindOf(rel));
        synchronized (entries) {
            entries.add(e);
        }
        saveIndex();
        return e;
    }

    public void deleteEntry(Entry e) throws IOException {
        ensureOpen();
        removeEntryInternal(e, true);
    }

    private void removeEntryInternal(Entry e, boolean save) throws IOException {
        synchronized (entries) {
            entries.removeIf(x -> x.id().equals(e.id()));
        }
        Files.deleteIfExists(dataFile(e.id()));
        if (save) saveIndex();
    }

    private Entry findByPath(String path) {
        for (Entry e : entries) {
            if (e.path().equals(path)) return e;
        }
        return null;
    }

    public void exportTo(Entry e, Path target) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            streamTo(e, out);
        }
    }

    public void streamTo(Entry e, OutputStream out) throws IOException {
        ensureOpen();
        try (EncryptedFile.Reader reader = EncryptedFile.Reader.open(dataFile(e.id()), fileKey(e))) {
            byte[] buf = new byte[256 * 1024];
            long pos = 0;
            while (pos < e.size()) {
                int n = reader.read(pos, buf, 0, (int) Math.min(buf.length, e.size() - pos));
                if (n < 0) break;
                out.write(buf, 0, n);
                pos += n;
            }
        }
    }

    public byte[] readAll(Entry e) throws IOException {
        if (e.size() > Integer.MAX_VALUE - 16) throw new IOException("文件太大，无法整体载入内存");
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                (int) Math.min(e.size(), 1 << 20))) {
            streamTo(e, out);
            return out.toByteArray();
        }
    }

    public EncryptedFile.Reader openReader(Entry e) throws IOException {
        ensureOpen();
        EncryptedFile.Reader reader = EncryptedFile.Reader.open(dataFile(e.id()), fileKey(e));
        if (reader.size() != e.size()) {
            reader.close();
            throw new IOException("文件大小与索引不一致，数据可能已损坏");
        }
        return reader;
    }

    private byte[] fileKey(Entry e) {
        byte[] master = masterKey;
        if (master == null) throw new IllegalStateException("加密库已锁定");
        return Crypto.derive(master, "file/" + e.id());
    }

    private Path dataFile(String id) {
        return dataDir.resolve(id + ".vfb");
    }

    private void ensureOpen() throws IOException {
        if (!open || masterKey == null) throw new IOException("加密库已锁定");
    }

    @Override
    public void close() {
        open = false;
        synchronized (entries) {
            entries.clear();
        }
        Crypto.wipe(masterKey);
        Crypto.wipe(manifestKey);
        masterKey = null;
        manifestKey = null;
    }

    // ---------------------------------------------------------------- 工具

    private static String normalizeRel(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.contains("//")) p = p.replace("//", "/");
        if (p.equals("..") || p.startsWith("../") || p.contains("/../")) throw new IllegalArgumentException("非法路径");
        return p.trim();
    }

    private static byte[] unb64(String value, String name) throws IOException {
        if (value == null) throw new IOException("加密库信息缺少 " + name);
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("加密库信息损坏（" + name + "）", e);
        }
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static void writeProperties(Path target, Properties p) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            p.store(out, "VaultBox vault metadata");
        }
        move(tmp, target);
    }

    private static void writeAtomic(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.write(tmp, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        move(tmp, target);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
