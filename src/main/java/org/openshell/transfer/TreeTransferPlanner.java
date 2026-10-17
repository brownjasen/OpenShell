package org.openshell.transfer;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.openshell.transfer.TransferManager.*;

/** Planning finishes before queue publication; traversal never follows symbolic links. */
public final class TreeTransferPlanner implements AutoCloseable {
    private volatile ChannelSftp channel;
    private volatile boolean closed;
    private final Session session;
    public TreeTransferPlanner(Session session) { this.session = session; }
    public static List<Request> upload(List<Path> sources, String directory, UUID batch, BooleanSupplier cancelled) throws Exception {
        List<Request> result = new ArrayList<>(); Set<String> destinations = new HashSet<>();
        for (Path input : sources) {
            Path source = input.toAbsolutePath().normalize(); TransferPaths.checkLocalAncestors(source);
            String target = TransferPaths.remoteChild(directory, source.getFileName().toString());
            Files.walkFileTree(source, EnumSet.noneOf(FileVisitOption.class), 128, new SimpleFileVisitor<>() {
                private void add(Path path, BasicFileAttributes attrs) throws IOException {
                    check(cancelled, result.size());
                    if (attrs.isSymbolicLink() || (!attrs.isRegularFile() && !attrs.isDirectory())) throw new IOException("不跟随符号链接或特殊文件: " + path);
                    String relative = source.relativize(path).toString().replace(File.separatorChar, '/');
                    String remote = relative.isEmpty() ? target : target + "/" + relative;
                    if (!destinations.add(remote)) throw new IOException("批次中有重复目标: " + remote);
                    result.add(new Request(Direction.UPLOAD, path, remote, batch, attrs.isDirectory() ? Kind.DIRECTORY : Kind.FILE, attrs.isDirectory() ? 0 : attrs.size()));
                }
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException { add(dir, attrs); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { if (attrs.isDirectory()) throw new IOException("目录层级超过 128，未加入队列"); add(file, attrs); return FileVisitResult.CONTINUE; }
            });
        }
        return List.copyOf(result);
    }
    public List<Request> download(List<String> sources, Path directory, UUID batch, BooleanSupplier cancelled) throws Exception {
        connect(); List<Request> result = new ArrayList<>(); Set<String> targets = new HashSet<>();
        for (String source : sources) {
            String name = source.substring(source.lastIndexOf('/') + 1);
            walk(source, TransferPaths.child(directory, name), batch, result, targets, cancelled, 0);
        }
        return List.copyOf(result);
    }
    private void walk(String remote, Path local, UUID batch, List<Request> out, Set<String> targets, BooleanSupplier cancel, int depth) throws Exception {
        check(() -> closed || cancel.getAsBoolean(), out.size());
        if (depth > 128) throw new IOException("目录层级超过 128，未加入队列");
        TransferPaths.checkRemoteParents(channel, remote);
        SftpATTRS attrs = channel.lstat(TransferPaths.literal(remote));
        if (attrs.isLink() || (!attrs.isReg() && !attrs.isDir())) throw new IOException("不跟随符号链接或特殊文件: " + remote);
        if (!targets.add(local.toString().toLowerCase(Locale.ROOT))) throw new IOException("远端名称在本地发生冲突: " + local);
        out.add(new Request(Direction.DOWNLOAD, local, remote, batch, attrs.isDir() ? Kind.DIRECTORY : Kind.FILE, attrs.isDir() ? 0 : attrs.getSize()));
        if (attrs.isDir()) {
            @SuppressWarnings("unchecked") Vector<ChannelSftp.LsEntry> entries = channel.ls(TransferPaths.literal(remote));
            for (var entry : entries) {
                String name = entry.getFilename(); if (name.equals(".") || name.equals("..")) continue;
                walk(TransferPaths.remoteChild(remote, name), TransferPaths.child(local, name), batch, out, targets, cancel, depth + 1);
            }
        }
    }
    private void connect() throws Exception {
        if (closed) throw new InterruptedIOException("目录整理已取消");
        channel = (ChannelSftp) session.openChannel("sftp");
        ChannelSftp owned = channel; java.util.concurrent.CompletableFuture.delayedExecutor(60, java.util.concurrent.TimeUnit.SECONDS).execute(owned::disconnect);
        channel.connect(10000);
        if (closed) { channel.disconnect(); throw new InterruptedIOException("目录整理已取消"); }
    }
    static void check(BooleanSupplier cancelled, int count) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("目录整理已取消");
        if (count >= 100_000) throw new IOException("批次超过 100000 项，请分批传输");
    }
    @Override public void close() { closed = true; ChannelSftp c = channel; if (c != null) c.disconnect(); }
}
