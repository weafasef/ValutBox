package app.vault;

import java.util.Locale;

/** 加密库中的一条文件记录。path 为库内相对路径，使用 '/' 分隔。 */
record Entry(String id, String path, long size, long mtime, Kind kind) {

    enum Kind { IMAGE, VIDEO, OTHER }

    String name() {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    String dir() {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    static String ext(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) return "";
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static Kind kindOf(String path) {
        String ext = ext(path);
        return switch (ext) {
            case "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff" -> Kind.IMAGE;
            case "mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "flv", "ts", "mpg", "mpeg", "3gp" -> Kind.VIDEO;
            default -> Kind.OTHER;
        };
    }
}
