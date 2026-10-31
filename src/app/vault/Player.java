package app.vault;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 用外部播放器打开本地流地址。Mac 优先 IINA / VLC，Windows 优先 VLC。 */
final class Player {
    private Player() {}

    static void play(String url) throws IOException {
        List<String> cmd = command(url);
        if (cmd != null) {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            return;
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url));
            return;
        }
        throw new IOException("没有找到可用的播放器");
    }

    private static List<String> command(String url) {
        String configured = Config.get("player", "").trim();
        if (!configured.isEmpty()) {
            return List.of(configured, url);
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> candidates = new ArrayList<>();
        if (os.contains("mac")) {
            candidates.add("/Applications/IINA.app/Contents/MacOS/IINA");
            candidates.add("/Applications/VLC.app/Contents/MacOS/VLC");
        } else if (os.contains("win")) {
            candidates.add("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe");
            candidates.add("C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe");
            candidates.add("C:\\Program Files\\DAUM\\PotPlayer\\PotPlayerMini64.exe");
        } else {
            candidates.add("/usr/bin/vlc");
            candidates.add("/usr/bin/mpv");
        }
        for (String c : candidates) {
            if (Files.isExecutable(Path.of(c))) return List.of(c, url);
        }
        return null;
    }
}
