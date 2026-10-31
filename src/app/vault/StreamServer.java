package app.vault;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地流媒体服务：只监听 127.0.0.1，路径带随机 token。
 * 支持 HTTP Range，播放器可以边解密边播放、拖动进度。
 */
final class StreamServer implements AutoCloseable {
    private final HttpServer http;
    private final Vault vault;
    private final String token;
    private final ExecutorService pool;

    private StreamServer(HttpServer http, Vault vault, String token, ExecutorService pool) {
        this.http = http;
        this.vault = vault;
        this.token = token;
        this.pool = pool;
    }

    static StreamServer start(Vault vault) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String token = hex(Crypto.random(16));
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "vault-stream");
            t.setDaemon(true);
            return t;
        });
        StreamServer server = new StreamServer(http, vault, token, pool);
        http.createContext("/", server::handle);
        http.setExecutor(pool);
        http.start();
        return server;
    }

    String url(Entry e) {
        String name = URLEncoder.encode(e.name(), StandardCharsets.UTF_8).replace("+", "%20");
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/" + token + "/v/" + e.id() + "/" + name;
    }

    private void handle(HttpExchange ex) throws IOException {
        boolean headersSent = false;
        try {
            String path = ex.getRequestURI().getPath();
            String prefix = "/" + token + "/v/";
            if (!path.startsWith(prefix)) {
                sendText(ex, 404, "not found");
                return;
            }
            String rest = path.substring(prefix.length());
            int slash = rest.indexOf('/');
            String id = slash < 0 ? rest : rest.substring(0, slash);
            Entry entry = vault.find(id);
            if (entry == null) {
                sendText(ex, 404, "not found");
                return;
            }

            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendText(ex, 405, "method not allowed");
                return;
            }

            long size = entry.size();
            long start = 0;
            long end = size - 1;
            boolean partial = false;

            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=")) {
                long[] r = parseRange(range.substring(6), size);
                if (r == null) {
                    ex.getResponseHeaders().set("Content-Range", "bytes */" + size);
                    ex.sendResponseHeaders(416, -1);
                    return;
                }
                start = r[0];
                end = r[1];
                partial = true;
            }

            long length = size == 0 ? 0 : end - start + 1;
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", mediaType(entry.path()));
            h.set("Accept-Ranges", "bytes");
            h.set("Cache-Control", "no-store");
            h.set("Content-Disposition", "inline; filename*=UTF-8''"
                    + URLEncoder.encode(entry.name(), StandardCharsets.UTF_8).replace("+", "%20"));
            if (partial) h.set("Content-Range", "bytes " + start + "-" + end + "/" + size);

            int status = partial ? 206 : 200;
            if (method.equals("HEAD") || size == 0) {
                ex.sendResponseHeaders(status, -1);
                headersSent = true;
                return;
            }

            ex.sendResponseHeaders(status, length);
            headersSent = true;
            try (OutputStream out = ex.getResponseBody()) {
                try (EncryptedFile.Reader reader = vault.openReader(entry)) {
                    byte[] buf = new byte[128 * 1024];
                    long pos = start;
                    long remaining = length;
                    while (remaining > 0) {
                        int n = reader.read(pos, buf, 0, (int) Math.min(buf.length, remaining));
                        if (n < 0) break;
                        out.write(buf, 0, n);
                        pos += n;
                        remaining -= n;
                    }
                }
            } catch (IOException ignored) {
                // 播放器提前断开或加密库被锁定
            }
        } catch (Exception ex2) {
            // 已经发过响应头时，多半是播放器拖动/关闭导致的断开，忽略即可
            if (!headersSent) {
                ex2.printStackTrace(System.err);
                try {
                    sendText(ex, 500, "server error");
                } catch (Exception ignored) {
                }
            }
        } finally {
            ex.close();
        }
    }

    /** 返回 [start, end]，无法满足时返回 null。 */
    private static long[] parseRange(String spec, long size) {
        String s = spec.trim();
        int comma = s.indexOf(',');
        if (comma >= 0) s = s.substring(0, comma);
        int dash = s.indexOf('-');
        if (dash < 0 || size <= 0) return null;
        String a = s.substring(0, dash).trim();
        String b = s.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (a.isEmpty()) {
                long suffix = Long.parseLong(b);
                if (suffix <= 0) return null;
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(a);
                if (start < 0 || start >= size) return null;
                if (b.isEmpty()) {
                    end = size - 1;
                } else {
                    end = Long.parseLong(b);
                    if (end < start) return null;
                    end = Math.min(end, size - 1);
                }
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void sendText(HttpExchange ex, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static String mediaType(String path) {
        return switch (Entry.ext(path)) {
            case "mp4", "m4v" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "mkv" -> "video/x-matroska";
            case "webm" -> "video/webm";
            case "avi" -> "video/x-msvideo";
            case "ts" -> "video/mp2t";
            case "mpg", "mpeg" -> "video/mpeg";
            case "3gp" -> "video/3gpp";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "flac" -> "audio/flac";
            case "wav" -> "audio/wav";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "heic" -> "image/heic";
            default -> "application/octet-stream";
        };
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Override
    public void close() {
        http.stop(0);
        pool.shutdownNow();
    }
}
