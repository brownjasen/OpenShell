package org.openshell.transfer;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

/** Validate foreign names before resolving them on the local filesystem. */
public final class TransferPaths {
    private TransferPaths() {}
    public static Path child(Path parent, String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.matches(".*[\\\\/:<>\"|?*\\x00-\\x1f].*")
                || name.endsWith(".") || name.endsWith(" ")) throw new IOException("无法安全保存远端文件名: " + name);
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]")) throw new IOException("本地保留文件名: " + name);
        Path base = parent.toAbsolutePath().normalize(), path = base.resolve(name).normalize();
        if (!path.getParent().equals(base)) throw new IOException("目标路径越界: " + name);
        checkLocalAncestors(path); return path;
    }
    public static void checkLocalAncestors(Path path) throws IOException {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("拒绝经过本地符号链接: " + p);
    }
    public static String remoteChild(String parent, String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.indexOf('\0') >= 0)
            throw new IOException("无效远端文件名: " + name);
        return (parent.equals("/") ? "" : parent) + "/" + name;
    }
    public static void validateRemote(String path) throws IOException {
        if (path == null || !path.startsWith("/") || path.equals("/") || path.indexOf(0) >= 0)
            throw new IOException("需要非根目录绝对路径");
        for (String part : path.substring(1).split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("无效远端路径: " + path);
    }
    public static void checkRemoteParents(com.jcraft.jsch.ChannelSftp sftp, String path) throws Exception {
        validateRemote(path); int slash = 0;
        while ((slash = path.indexOf('/', slash + 1)) >= 0) {
            var attrs = sftp.lstat(literal(path.substring(0, slash)));
            if (!attrs.isDir() || attrs.isLink()) throw new IOException("拒绝经过远端符号链接或非目录: " + path.substring(0, slash));
        }
    }
    public static String literal(String path) { return path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?"); }
}
