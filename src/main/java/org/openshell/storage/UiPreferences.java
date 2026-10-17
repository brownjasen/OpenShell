package org.openshell.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Independent settings, with read-modify-write so two views cannot overwrite each other's fields. */
public final class UiPreferences {
    private static final Object IO_LOCK = new Object();
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Map<Path, UiPreferences> OPEN = new ConcurrentHashMap<>();
    private final Path path;
    private WorkspaceState state;
    private volatile String lastError;
    public static UiPreferences current() {
        Path path = AppPaths.configDirectory().resolve("workspace.json").toAbsolutePath().normalize();
        return OPEN.computeIfAbsent(path, UiPreferences::new);
    }
    public UiPreferences(Path path) {
        this.path = path.toAbsolutePath();
        synchronized (IO_LOCK) {
            try { state = read(); }
            catch (IOException error) { state = new WorkspaceState(); lastError = "工作区设置读取失败，原文件保留: " + path; }
        }
    }
    private WorkspaceState read() throws IOException {
        WorkspaceState result = Files.exists(path) ? JSON.readValue(path.toFile(), WorkspaceState.class) : new WorkspaceState();
        if (result == null) throw new IOException("工作区设置不能为 null");
        result.normalize(); return result;
    }
    public WorkspaceState snapshot() {
        synchronized (IO_LOCK) { return JSON.convertValue(state, WorkspaceState.class); }
    }
    public void update(Consumer<WorkspaceState> change) {
        synchronized (IO_LOCK) {
            try {
                WorkspaceState next = read();
                change.accept(next); next.normalize();
                byte[] bytes = JSON.writeValueAsBytes(next);
                Files.createDirectories(path.getParent());
                Path temp = Files.createTempFile(path.getParent(), ".workspace-", ".tmp");
                try {
                    try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                        ByteBuffer data = ByteBuffer.wrap(bytes);
                        while (data.hasRemaining()) channel.write(data);
                        channel.force(true);
                    }
                    try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
                } finally { Files.deleteIfExists(temp); }
                state = next; lastError = null;
            } catch (IOException error) {
                lastError = "工作区设置保存失败，原文件保留: " + path;
                throw new UncheckedIOException(lastError, error);
            }
        }
    }
    public boolean tryUpdate(Consumer<WorkspaceState> change) {
        try { update(change); return true; } catch (UncheckedIOException error) { return false; }
    }
    public String lastError() { return lastError; }
    public void recordCommand(String hostId, String command) {
        if (command == null || command.isBlank()) return;
        update(next -> {
            if (!next.recordHistory) return;
            List<String> history = next.histories.computeIfAbsent(hostId, key -> new ArrayList<>());
            if (history.isEmpty() || !history.get(history.size() - 1).equals(command)) history.add(command);
        });
    }
    public List<String> history(String hostId, String query) {
        String filter = query.toLowerCase(Locale.ROOT);
        return snapshot().histories.getOrDefault(hostId, List.of()).stream()
                .filter(command -> command.toLowerCase(Locale.ROOT).contains(filter)).toList();
    }
}
