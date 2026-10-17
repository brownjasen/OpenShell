package org.openshell.transfer;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;
import static org.openshell.transfer.TransferManager.*;

/** One independently cancellable SFTP channel. Original targets are never deleted as a rename fallback. */
public final class SftpTransfer implements Transport {
    private final Session session;
    private volatile ChannelSftp channel;
    private volatile boolean closed;
    public SftpTransfer(Session session) { this.session = session; }

    @Override public void execute(Request request, Control control) throws Exception {
        control.checkCancelled();
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp"); channel = sftp;
        try {
            if (closed) throw new InterruptedIOException("传输已取消");
            sftp.connect(10000); control.checkCancelled();
            if (closed) throw new InterruptedIOException("传输已取消");
            if (request.kind() == Kind.DIRECTORY) directory(sftp, request, control);
            else if (request.direction() == Direction.UPLOAD) upload(sftp, request, control);
            else download(sftp, request, control);
        } finally { sftp.disconnect(); }
    }

    private void directory(ChannelSftp sftp, Request request, Control control) throws Exception {
        control.checkCancelled(); control.progress(0, 0);
        if (request.direction() == Direction.UPLOAD) {
            TransferPaths.checkLocalAncestors(request.local());
            if (!Files.isDirectory(request.local(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("本地源目录已消失或发生变化: " + request.local());
            TransferPaths.checkRemoteParents(sftp, request.remote());
            try {
                SftpATTRS existing = sftp.lstat(literal(request.remote()));
                if (!existing.isDir() || existing.isLink()) throw new IOException("目标不是普通目录: " + request.remote());
            } catch (SftpException absent) {
                if (absent.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw absent;
                control.checkCancelled(); sftp.mkdir(literal(request.remote()));
            }
        } else {
            TransferPaths.checkRemoteParents(sftp, request.remote());
            SftpATTRS source = sftp.lstat(literal(request.remote()));
            if (!source.isDir() || source.isLink()) throw new IOException("远端源目录已消失或发生变化: " + request.remote());
            TransferPaths.checkLocalAncestors(request.local());
            if (!Files.exists(request.local(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(request.local());
            if (!Files.isDirectory(request.local(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("目标不是普通目录: " + request.local());
        }
    }
    private void upload(ChannelSftp sftp, Request request, Control control) throws Exception {
        TransferPaths.checkLocalAncestors(request.local());
        TransferPaths.checkRemoteParents(sftp, request.remote());
        LocalVersion source = localVersion(request.local());
        if (source == null) throw new IOException("本地源文件不存在: " + request.local());
        String destination = request.remote();
        RemoteVersion target = remoteVersion(sftp, destination);
        if (target != null) {
            Choice choice = control.conflict(destination);
            if (choice == Choice.SKIP) throw new Skipped();
            if (choice == Choice.KEEP_BOTH) {
                int n = 1;
                do { destination = siblingName(request.remote(), n++); target = remoteVersion(sftp, destination); } while (target != null && n <= 10000);
                if (target != null) throw new IOException("无法找到可用的目标文件名");
            }
        }
        control.destination(destination); control.progress(0, source.size);
        String temporary = destination.substring(0, destination.lastIndexOf('/') + 1) + ".openshell-" + UUID.randomUUID() + ".part";
        boolean published = false;
        Exception failure = null;
        try {
            control.checkCancelled();
            try (InputStream input = Files.newInputStream(request.local())) {
                sftp.put(input, literal(temporary), monitor(control, source.size), ChannelSftp.OVERWRITE);
            }
            control.checkCancelled();
            if (!source.equals(localVersion(request.local()))) throw new IOException("传输期间本地源文件发生变化，请重新提交");
            RemoteVersion staged = remoteVersion(sftp, temporary);
            if (staged == null || staged.size != source.size) throw new IOException("上传字节数不完整");
            if (!Objects.equals(target, remoteVersion(sftp, destination))) throw new IOException("传输期间远端目标发生变化，未覆盖原文件");
            if (target != null) {
                if (!"1".equals(sftp.getExtension("posix-rename@openssh.com")))
                    throw new IOException("服务器不支持安全覆盖替换；原文件保留，请选择保留两者或更名");
                sftp.chmod(target.permissions & 0777, literal(temporary));
            }
            control.committing();
            sftp.rename(literal(temporary), literal(destination));
            published = true;
        } catch (Exception error) { failure = error; throw error; }
        finally { if (!published) cleanupRemote(sftp, temporary, failure); }
    }

    private void download(ChannelSftp sftp, Request request, Control control) throws Exception {
        TransferPaths.checkLocalAncestors(request.local());
        TransferPaths.checkRemoteParents(sftp, request.remote());
        RemoteVersion source = remoteVersion(sftp, request.remote());
        if (source == null) throw new IOException("远端源文件不存在: " + request.remote());
        Path destination = request.local();
        LocalVersion target = localVersion(destination);
        if (target != null) {
            Choice choice = control.conflict(destination.toString());
            if (choice == Choice.SKIP) throw new Skipped();
            if (choice == Choice.KEEP_BOTH) {
                int n = 1;
                do { destination = request.local().resolveSibling(siblingName(request.local().getFileName().toString(), n++)); target = localVersion(destination); }
                while (target != null && n <= 10000);
                if (target != null) throw new IOException("无法找到可用的目标文件名");
            }
        }
        control.destination(destination.toString()); control.progress(0, source.size);
        Path temporary = Files.createTempFile(destination.getParent(), ".openshell-", ".part");
        boolean published = false;
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                sftp.get(literal(request.remote()), output, monitor(control, source.size));
            }
            control.checkCancelled();
            if (Files.size(temporary) != source.size) throw new IOException("下载字节数不完整");
            if (!source.equals(remoteVersion(sftp, request.remote()))) throw new IOException("传输期间远端源文件发生变化，请重新下载");
            if (!Objects.equals(target, localVersion(destination))) throw new IOException("传输期间本地目标发生变化，未覆盖原文件");
            control.committing();
            if (target == null) Files.move(temporary, destination); // No REPLACE_EXISTING: a concurrently created file is protected.
            else {
                try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException error) { throw new IOException("本地文件系统不支持安全替换；原文件保留，请选择保留两者", error); }
            }
            published = true;
        } finally {
            if (!published) Files.deleteIfExists(temporary);
        }
    }

    private static SftpProgressMonitor monitor(Control control, long total) {
        return new SftpProgressMonitor() {
            long done;
            public void init(int operation, String source, String destination, long max) {}
            public boolean count(long count) {
                done += count;
                try { control.progress(done, total); control.checkCancelled(); return true; }
                catch (InterruptedIOException error) { return false; }
            }
            public void end() {}
        };
    }
    private record RemoteVersion(long size, int mtime, int permissions) {}
    private static RemoteVersion remoteVersion(ChannelSftp sftp, String path) throws Exception {
        try {
            SftpATTRS attrs = sftp.lstat(literal(path));
            if (!attrs.isReg()) throw new IOException("仅支持普通文件，请先进入符号链接真实路径或选择文件: " + path);
            return new RemoteVersion(attrs.getSize(), attrs.getMTime(), attrs.getPermissions());
        } catch (SftpException error) { if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null; throw error; }
    }
    private record LocalVersion(long size, java.nio.file.attribute.FileTime modified, Object key) {}
    private static LocalVersion localVersion(Path path) throws IOException {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) throw new IOException("仅支持普通文件: " + path);
            return new LocalVersion(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey());
        } catch (NoSuchFileException absent) { return null; }
    }
    private void cleanupRemote(ChannelSftp existing, String path, Exception original) throws Exception {
        ChannelSftp cleanup = existing;
        try {
            if (!existing.isConnected()) {
                if (!session.isConnected()) throw new IOException("连接已断开");
                cleanup = (ChannelSftp) session.openChannel("sftp"); cleanup.connect(3000);
            }
            ChannelSftp cleanupChannel = cleanup;
            java.util.concurrent.CompletableFuture.delayedExecutor(3, java.util.concurrent.TimeUnit.SECONDS).execute(cleanupChannel::disconnect);
            try { cleanup.rm(literal(path)); }
            catch (SftpException error) { if (error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error; }
        } catch (Exception error) {
            throw new IOException((original == null ? "传输未完成" : original.getMessage())
                    + "；临时文件可能保留: " + path, error);
        } finally { if (cleanup != existing) cleanup.disconnect(); }
    }
    private static String literal(String path) { return path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?"); }
    private static String siblingName(String path, int number) {
        int slash = path.lastIndexOf('/'), dot = path.lastIndexOf('.');
        if (dot <= slash + 1) dot = path.length();
        return path.substring(0, dot) + " (" + number + ")" + path.substring(dot);
    }
    @Override public void close() { closed = true; ChannelSftp current = channel; if (current != null) current.disconnect(); }
}
