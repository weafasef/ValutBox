package app.vault;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/** 无界面自测：加解密、索引、随机读取、HTTP Range、导出与删除。 */
public final class SelfTest {
    private static int checks;

    private SelfTest() {}

    public static void main(String[] args) {
        run();
    }

    static void run() {
        Path root = null;
        try {
            root = Files.createTempDirectory("vaultbox-selftest-");
            Path vaultDir = root.resolve("我的库");
            Path source = root.resolve("sample.mp4");

            int size = Crypto.CHUNK * 2 + 12345;
            byte[] data = new byte[size];
            new Random(42).nextBytes(data);
            byte[] marker = "PLAINTEXT_MARKER_12345".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(marker, 0, data, 0, marker.length);
            Files.write(source, data);

            char[] password = "测试密码123".toCharArray();

            // 创建 + 导入
            Vault vault = Vault.create(vaultDir, password);
            Entry video = vault.importFile(source, "旅行/视频 片段.mp4");
            check(vault.entries().size() == 1, "索引应有 1 条");
            check(video.size() == size, "记录大小正确");
            check(video.kind() == Entry.Kind.VIDEO, "识别为视频");
            check(java.util.Arrays.equals(vault.readAll(video), data), "解密内容与原文一致");

            // 密文不应包含明文标记
            Path encrypted = vaultDir.resolve("data").resolve(video.id() + ".vfb");
            check(Files.exists(encrypted), "密文文件存在");
            check(indexOf(Files.readAllBytes(encrypted), marker) < 0, "密文中不应出现明文标记");

            // 图片导入 + 解码
            Path png = root.resolve("test.png");
            BufferedImage img = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.ORANGE);
            g.fillRect(0, 0, 32, 24);
            g.dispose();
            ImageIO.write(img, "png", png.toFile());
            Entry picture = vault.importFile(png, "照片/测试.png");
            check(vault.entries().size() == 2, "索引应有 2 条");
            check(Ui.readImage(vault.readAll(picture)) != null, "图片可以解码");

            // 导出
            Path exported = root.resolve("export.mp4");
            vault.exportTo(video, exported);
            check(java.util.Arrays.equals(Files.readAllBytes(exported), data), "导出内容一致");

            // 随机读取
            long[] offsets = {0, 1, Crypto.CHUNK - 1, Crypto.CHUNK, Crypto.CHUNK + 100,
                    Crypto.CHUNK * 2L, size - 10};
            int[] lengths = {1, 100, 2, 5000, 70000, 10, 10};
            for (int i = 0; i < offsets.length; i++) {
                byte[] got = new byte[lengths[i]];
                try (EncryptedFile.Reader r = vault.openReader(video)) {
                    int n = r.read(offsets[i], got, 0, got.length);
                    check(n == got.length, "随机读取长度正确 @ " + offsets[i]);
                    for (int j = 0; j < n; j++) {
                        if (got[j] != data[(int) offsets[i] + j]) {
                            throw new AssertionError("随机读取内容不一致 @ " + offsets[i] + "+" + j);
                        }
                    }
                }
            }

            // HTTP 服务
            StreamServer server = StreamServer.start(vault);
            try {
                String url = server.url(video);
                HttpClient client = HttpClient.newHttpClient();

                HttpResponse<byte[]> full = client.send(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                check(full.statusCode() == 200, "整文件 GET 返回 200，实际 " + full.statusCode());
                check(java.util.Arrays.equals(full.body(), data), "整文件 GET 内容一致");
                check("video/mp4".equals(full.headers().firstValue("Content-Type").orElse("")), "Content-Type 正确");

                HttpResponse<byte[]> range = client.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header("Range", "bytes=65490-66100").GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                check(range.statusCode() == 206, "Range 返回 206，实际 " + range.statusCode());
                check(range.body().length == 611, "Range 长度正确");
                check(java.util.Arrays.equals(range.body(),
                        java.util.Arrays.copyOfRange(data, 65490, 66101)), "Range 内容一致");

                HttpResponse<byte[]> suffix = client.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header("Range", "bytes=-100").GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                check(suffix.statusCode() == 206, "后缀 Range 返回 206");
                check(java.util.Arrays.equals(suffix.body(),
                        java.util.Arrays.copyOfRange(data, size - 100, size)), "后缀 Range 内容一致");

                HttpResponse<byte[]> head = client.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                check(head.statusCode() == 200, "HEAD 返回 200");

                HttpResponse<byte[]> bad = client.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header("Range", "bytes=99999999-").GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                check(bad.statusCode() == 416, "越界 Range 返回 416");
            } finally {
                server.close();
            }

            // 删除
            vault.deleteEntry(picture);
            check(vault.entries().size() == 1, "删除后索引剩 1 条");
            check(!Files.exists(vaultDir.resolve("data").resolve(picture.id() + ".vfb")), "删除后密文文件不存在");
            vault.close();
            check(!vault.isOpen(), "关闭后状态为锁定");
            check(vault.entries().isEmpty(), "关闭后索引不可见");

            // 错误密码
            try {
                Vault.open(vaultDir, "错误密码".toCharArray());
                throw new AssertionError("错误密码本应失败");
            } catch (Vault.BadPasswordException expected) {
                checks++;
            }

            // 重新打开
            try (Vault reopened = Vault.open(vaultDir, password)) {
                check(reopened.entries().size() == 1, "重新打开后索引一致");
                Entry e = reopened.entries().get(0);
                check(e.path().equals("旅行/视频 片段.mp4"), "文件名一致");
                check(java.util.Arrays.equals(reopened.readAll(e), data), "重新打开后内容一致");
            }

            System.out.println("全部自测通过（" + checks + " 项检查）");
        } catch (Throwable t) {
            System.err.println("自测失败：");
            t.printStackTrace(System.err);
            System.exit(1);
        } finally {
            if (root != null) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
