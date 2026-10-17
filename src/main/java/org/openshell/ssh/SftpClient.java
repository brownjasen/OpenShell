package org.openshell.ssh;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public class SftpClient {
    private final Session session;
    private volatile ChannelSftp sftpChannel;
    private volatile boolean closed;
    private volatile String currentPath = "/";

    public static class FileItem {
        private final String name;
        private final long size;
        private final boolean directory;
        private final long mtime;
        private final String permissions;

        public FileItem(String name, long size, boolean directory, long mtime, String permissions) {
            this.name = name;
            this.size = size;
            this.directory = directory;
            this.mtime = mtime;
            this.permissions = permissions;
        }

        public String getName() { return name; }
        public long getSize() { return size; }
        public boolean isDirectory() { return directory; }
        public long getMtime() { return mtime; }
        public String getPermissions() { return permissions; }

        public String getFormattedSize() {
            if (directory) return "<DIR>";
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
            return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
        }
    }

    public SftpClient(Session session) {
        this.session = session;
    }

    public synchronized void connect() throws Exception {
        if (closed) throw new java.io.IOException("SFTP 已关闭");
        if (sftpChannel != null && sftpChannel.isConnected()) return;
        sftpChannel = (ChannelSftp) session.openChannel("sftp");
        sftpChannel.connect(10000);
        if (closed) { sftpChannel.disconnect(); throw new java.io.IOException("SFTP 已关闭"); }
        try {
            currentPath = sftpChannel.getHome();
        } catch (Exception e) {
            currentPath = "/root";
        }
    }

    public synchronized List<FileItem> listFiles(String path) throws Exception {
        connect();
        String resolved = resolvePath(path);
        Vector<ChannelSftp.LsEntry> entries = sftpChannel.ls(escapeGlob(resolved));
        List<FileItem> result = new ArrayList<>();

        for (ChannelSftp.LsEntry entry : entries) {
            String filename = entry.getFilename();
            if (".".equals(filename) || "..".equals(filename)) continue;
            boolean isDir = entry.getAttrs().isDir();
            long size = entry.getAttrs().getSize();
            long mtime = entry.getAttrs().getMTime() * 1000L;
            String perms = entry.getAttrs().getPermissionsString();

            result.add(new FileItem(filename, size, isDir, mtime, perms));
        }

        // 排序：文件夹置顶，然后按名称字母顺序排序
        result.sort(Comparator.comparing((FileItem f) -> !f.isDirectory()).thenComparing(FileItem::getName));
        return result;
    }

    public synchronized void cd(String path) throws Exception {
        connect();
        this.currentPath = resolvePath(path);
    }

    public synchronized void cdUp() throws Exception {
        connect();
        this.currentPath = resolvePath("..");
    }

    public synchronized void downloadFile(String remoteFile, File localTarget) throws Exception {
        connect();
        try (FileOutputStream fos = new FileOutputStream(localTarget)) {
            sftpChannel.get(escapeGlob(absolutePath(remoteFile)), fos);
        }
    }

    public synchronized void uploadFile(File localFile, String remoteTargetName) throws Exception {
        connect();
        try (FileInputStream fis = new FileInputStream(localFile)) {
            sftpChannel.put(fis, escapeGlob(absolutePath(remoteTargetName)), ChannelSftp.OVERWRITE);
        }
    }

    /** The editor uses bytes as its version, so same-size edits within one mtime second are detected. */
    public record RemoteFileSnapshot(byte[] content, String sha256, long size, int mtime, int permissions) {
        public RemoteFileSnapshot { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }

    public static final class RemoteFileConflictException extends IOException {
        private final RemoteFileSnapshot current;
        public RemoteFileConflictException(RemoteFileSnapshot current) {
            super(current == null ? "远端文件已被删除，未覆盖" : "远端文件已发生变化，未覆盖");
            this.current = current;
        }
        public RemoteFileSnapshot current() { return current; }
    }

    /** Resolve a file identity, including a missing filename below an existing canonical parent. */
    public synchronized String canonicalFilePath(String path) throws Exception {
        connect();
        if (path == null || path.indexOf('\0') >= 0) throw new IOException("文件路径无效");
        String absolute = absolutePath(path);
        int slash = absolute.lastIndexOf('/');
        String name = absolute.substring(slash + 1);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) throw new IOException("请输入完整文件路径，末尾不能是目录或 . / ..");
        try { return sftpChannel.realpath(absolute); }
        catch (SftpException missing) {
            if (missing.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw missing;
            String parent = sftpChannel.realpath(slash == 0 ? "/" : absolute.substring(0, slash));
            if (!sftpChannel.stat(escapeGlob(parent)).isDir()) throw new IOException("目标父路径不是目录");
            return (parent.equals("/") ? "" : parent) + "/" + name;
        }
    }

    /** A bounded UTF-8 editor snapshot. Missing files return null; permission and transport errors propagate. */
    public synchronized RemoteFileSnapshot readFileSnapshot(String path) throws Exception {
        connect();
        String absolute = absolutePath(path);
        SftpATTRS before = editorAttributes(absolute);
        if (before == null) return null;
        final int limit = 20 * 1024 * 1024;
        if (before.getSize() > limit) throw new IOException("文件超过 20 MiB，请下载后使用本地编辑器打开");
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                if ((long) size() + length > limit) throw new IllegalStateException("文件超过 20 MiB，已停止读取");
                super.write(bytes, offset, length);
            }
        };
        sftpChannel.get(escapeGlob(absolute), output);
        byte[] content = output.toByteArray();
        SftpATTRS after = editorAttributes(absolute);
        if (after == null || before.getSize() != after.getSize() || before.getMTime() != after.getMTime()
                || before.getPermissions() != after.getPermissions() || after.getSize() != content.length)
            throw new IOException("读取期间远端文件发生变化，请重新载入");
        return new RemoteFileSnapshot(content, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                after.getSize(), after.getMTime(), after.getPermissions());
    }

    /** Stages in the same directory, rechecks content, then atomically publishes; never removes the original. */
    public synchronized RemoteFileSnapshot saveFileSafely(String path, byte[] content, RemoteFileSnapshot expected) throws Exception {
        connect();
        String absolute = absolutePath(path);
        RemoteFileSnapshot current = readFileSnapshot(absolute);
        requireVersion(expected, current);
        if (current != null && !"1".equals(sftpChannel.getExtension("posix-rename@openssh.com")))
            throw new IOException("服务器不支持安全覆盖替换，原文件已保留；请使用另存为");
        String temporary = absolute.substring(0, absolute.lastIndexOf('/') + 1) + ".openshell-editor-" + UUID.randomUUID() + ".part";
        boolean published = false;
        Exception failure = null;
        try {
            sftpChannel.put(new ByteArrayInputStream(content), escapeGlob(temporary), ChannelSftp.OVERWRITE);
            if (current != null) sftpChannel.chmod(current.permissions() & 0777, escapeGlob(temporary));
            RemoteFileSnapshot staged = readFileSnapshot(temporary);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            if (staged == null || !digest.equals(staged.sha256())) throw new IOException("上传内容校验失败，原文件已保留");
            requireVersion(expected, readFileSnapshot(absolute));
            sftpChannel.rename(escapeGlob(temporary), escapeGlob(absolute));
            published = true;
            return staged;
        } catch (Exception error) { failure = error; throw error; }
        finally {
            if (!published) {
                try { sftpChannel.rm(escapeGlob(temporary)); }
                catch (SftpException cleanup) {
                    if (cleanup.id != ChannelSftp.SSH_FX_NO_SUCH_FILE && failure != null)
                        failure.addSuppressed(new IOException("临时文件可能保留: " + temporary, cleanup));
                }
            }
        }
    }

    private SftpATTRS editorAttributes(String absolute) throws Exception {
        try {
            SftpATTRS attrs = sftpChannel.lstat(escapeGlob(absolute));
            if (!attrs.isReg()) throw new IOException("在线编辑仅支持普通文件；符号链接请先打开真实路径: " + absolute);
            return attrs;
        } catch (SftpException error) { if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null; throw error; }
    }

    private static void requireVersion(RemoteFileSnapshot expected, RemoteFileSnapshot actual) throws RemoteFileConflictException {
        if (expected == null ? actual != null : actual == null || !Objects.equals(expected.sha256(), actual.sha256())
                || expected.permissions() != actual.permissions()) throw new RemoteFileConflictException(actual);
    }

    public synchronized void delete(String remotePath, boolean isDir) throws Exception {
        connect();
        if (isDir) {
            sftpChannel.rmdir(escapeGlob(absolutePath(remotePath)));
        } else {
            sftpChannel.rm(escapeGlob(absolutePath(remotePath)));
        }
    }

    public synchronized void mkdir(String dirName) throws Exception {
        connect();
        sftpChannel.mkdir(absolutePath(dirName));
    }

    public synchronized void rename(String oldPath, String newPath) throws Exception {
        connect();
        sftpChannel.rename(escapeGlob(absolutePath(oldPath)), escapeGlob(absolutePath(newPath)));
    }

    public synchronized void chmod(int permissions, String path) throws Exception {
        connect();
        sftpChannel.chmod(permissions, escapeGlob(absolutePath(path)));
    }

    public synchronized void createEmptyFile(String filename) throws Exception {
        connect();
        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(new byte[0])) {
            sftpChannel.put(bais, escapeGlob(absolutePath(filename)), ChannelSftp.OVERWRITE);
        }
    }

    public synchronized String executeRemoteCommand(String cmd) throws Exception {
        com.jcraft.jsch.ChannelExec exec = (com.jcraft.jsch.ChannelExec) session.openChannel("exec");
        exec.setCommand(cmd);
        exec.setInputStream(null);
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(exec.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        exec.connect(5000);
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        exec.disconnect();
        return sb.toString();
    }

    public boolean usesSession(Session transport) { return session == transport; }

    public String getHome() {
        if (sftpChannel != null && sftpChannel.isConnected()) {
            try {
                return sftpChannel.getHome();
            } catch (Exception ignored) {}
        }
        return currentPath;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    public synchronized String resolvePath(String path) throws Exception {
        connect();
        if ("~".equals(path)) path = getHome();
        else if (path.startsWith("~/")) path = getHome() + path.substring(1);
        return sftpChannel.realpath(absolutePath(path));
    }

    private String absolutePath(String path) {
        if (path.startsWith("/")) return path;
        return (currentPath.endsWith("/") ? currentPath : currentPath + "/") + path;
    }

    // JSch treats remote operands as globs, even when the UI selected one literal name.
    private static String escapeGlob(String path) {
        return path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    public void disconnect() {
        closed = true;
        if (sftpChannel != null && sftpChannel.isConnected()) {
            sftpChannel.disconnect();
        }
    }
}
