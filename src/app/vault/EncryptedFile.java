package app.vault;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * 分块加密文件格式：
 *   头部 16 字节 = magic(4) + chunkSize(4) + noncePrefix(8)
 *   第 i 块 = AES-GCM(文件密钥, noncePrefix + i, 明文块)，密文 = 明文 + 16 字节 tag。
 * 支持按字节随机读取，用于视频拖动进度。
 */
final class EncryptedFile {
    static final byte[] MAGIC = {'V', 'B', 'E', '1'};
    static final int HEADER = 16;

    private EncryptedFile() {}

    static void encrypt(Path src, Path dst, byte[] key, int chunkSize) throws IOException {
        byte[] prefix = Crypto.random(8);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(src), 1 << 20);
             OutputStream out = new BufferedOutputStream(
                     Files.newOutputStream(dst, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                     1 << 20)) {
            out.write(MAGIC);
            writeInt(out, chunkSize);
            out.write(prefix);

            byte[] buf = new byte[chunkSize];
            int counter = 0;
            int n;
            while ((n = readFully(in, buf)) > 0) {
                byte[] ct = Crypto.encrypt(key, Crypto.chunkNonce(prefix, counter++), buf, 0, n);
                out.write(ct);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("加密失败", e);
        }
    }

    private static void writeInt(OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    /** 随机读取器。非线程安全，每个请求单独创建一个，close 时擦除密钥。 */
    static final class Reader implements Closeable {
        private final FileChannel channel;
        private final byte[] key;
        private final int chunkSize;
        private final byte[] prefix;
        private final long size;

        private long cachedIndex = -1;
        private byte[] cachedChunk;

        private Reader(FileChannel channel, byte[] key, int chunkSize, byte[] prefix, long size) {
            this.channel = channel;
            this.key = key;
            this.chunkSize = chunkSize;
            this.prefix = prefix;
            this.size = size;
        }

        static Reader open(Path file, byte[] key) throws IOException {
            FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
            try {
                ByteBuffer header = ByteBuffer.allocate(HEADER);
                while (header.hasRemaining()) {
                    if (ch.read(header) < 0) throw new IOException("加密文件头被截断");
                }
                header.flip();
                byte[] magic = new byte[4];
                header.get(magic);
                if (!Arrays.equals(magic, MAGIC)) throw new IOException("不是有效的加密文件");
                int cs = header.getInt();
                if (cs < 1024 || cs > 16 * 1024 * 1024) throw new IOException("加密文件块大小异常");
                byte[] prefix = new byte[8];
                header.get(prefix);

                long body = ch.size() - HEADER;
                if (body < 0) throw new IOException("加密文件已损坏");
                long full = (long) cs + Crypto.TAG;
                long fullChunks = body / full;
                long rem = body % full;
                long plainSize;
                if (rem == 0) {
                    plainSize = fullChunks * cs;
                } else {
                    if (rem < Crypto.TAG) throw new IOException("加密文件已损坏");
                    plainSize = fullChunks * cs + (rem - Crypto.TAG);
                }
                return new Reader(ch, key, cs, prefix, plainSize);
            } catch (IOException e) {
                ch.close();
                Crypto.wipe(key);
                throw e;
            }
        }

        long size() {
            return size;
        }

        /** 读取 [pos, pos+len)，返回实际字节数，读到结尾返回 -1。 */
        int read(long pos, byte[] out, int off, int len) throws IOException {
            if (len <= 0) return 0;
            if (pos >= size) return -1;
            int total = 0;
            while (len > 0 && pos < size) {
                long index = pos / chunkSize;
                int within = (int) (pos % chunkSize);
                byte[] plain = chunk(index);
                int n = Math.min(plain.length - within, len);
                System.arraycopy(plain, within, out, off, n);
                pos += n;
                off += n;
                len -= n;
                total += n;
            }
            return total == 0 ? -1 : total;
        }

        private byte[] chunk(long index) throws IOException {
            if (index == cachedIndex && cachedChunk != null) return cachedChunk;

            long start = index * (long) chunkSize;
            int plainLen = (int) Math.min(chunkSize, size - start);
            int ctLen = plainLen + Crypto.TAG;
            long offset = HEADER + index * ((long) chunkSize + Crypto.TAG);

            ByteBuffer bb = ByteBuffer.allocate(ctLen);
            long readPos = offset;
            while (bb.hasRemaining()) {
                int r = channel.read(bb, readPos);
                if (r < 0) throw new EOFException("加密文件被截断");
                readPos += r;
            }

            byte[] plain;
            try {
                plain = Crypto.decrypt(key, Crypto.chunkNonce(prefix, (int) index), bb.array(), 0, ctLen);
            } catch (GeneralSecurityException e) {
                throw new IOException("解密失败，文件可能已损坏", e);
            }
            if (plain.length != plainLen) throw new IOException("数据块长度异常");

            cachedIndex = index;
            cachedChunk = plain;
            return plain;
        }

        @Override
        public void close() throws IOException {
            cachedIndex = -1;
            cachedChunk = null;
            Crypto.wipe(key);
            channel.close();
        }
    }
}
