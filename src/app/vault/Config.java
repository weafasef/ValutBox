package app.vault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 用户配置，保存在 ~/.vaultbox/config.properties。 */
final class Config {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".vaultbox", "config.properties");
    private static final Properties PROPS = load();

    private Config() {}

    static synchronized String get(String key, String def) {
        return PROPS.getProperty(key, def);
    }

    static synchronized void set(String key, String value) {
        if (value == null || value.isBlank()) PROPS.remove(key);
        else PROPS.setProperty(key, value);
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling("config.properties.tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                PROPS.store(out, "VaultBox settings");
            }
            Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("保存配置失败: " + e.getMessage());
        }
    }

    private static Properties load() {
        Properties p = new Properties();
        if (Files.isRegularFile(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                p.load(in);
            } catch (IOException ignored) {
            }
        }
        return p;
    }
}
