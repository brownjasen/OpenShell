package org.openshell.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.openshell.model.HostConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class HostStorage {
    private static final String HOSTS_FILE = "hosts.json";
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static Path getConfigPath() {
        return AppPaths.configDirectory().resolve(HOSTS_FILE);
    }

    public static synchronized List<HostConfig> loadHosts() {
        Path path = getConfigPath();
        File file = path.toFile();
        if (!file.exists()) {
            List<HostConfig> defaultList = createDemoHosts();
            saveHosts(defaultList);
            return defaultList;
        }

        try {
            return readHosts(path);
        } catch (Exception e) {
            throw new IllegalStateException("主机配置读取失败，原文件已保留: " + path, e);
        }
    }

    public static synchronized void saveHosts(List<HostConfig> hosts) {
        Path path = getConfigPath();
        try {
            // A failed read must never turn into overwriting the old host collection.
            if (Files.exists(path)) readHosts(path);
            writeAtomically(hosts, path);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("主机配置保存失败，原文件已保留: " + path, e);
        }
    }

    public static void exportHosts(List<HostConfig> hosts, File targetFile) throws IOException {
        writeAtomically(hosts, targetFile.toPath());
    }

    private static void writeAtomically(List<HostConfig> hosts, Path target) throws IOException {
        validate(hosts);
        byte[] content = mapper.writeValueAsBytes(hosts);
        Path absolute = target.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".openshell-", ".tmp");
        try {
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temporary,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.move(temporary, absolute, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static List<HostConfig> importHosts(File sourceFile) throws IOException {
        return readHosts(sourceFile.toPath());
    }

    private static List<HostConfig> readHosts(Path path) throws IOException {
        List<HostConfig> hosts = mapper.readValue(path.toFile(), new TypeReference<List<HostConfig>>() {});
        validate(hosts);
        return hosts;
    }
    private static void validate(List<HostConfig> hosts) throws IOException {
        if (hosts == null || hosts.stream().anyMatch(java.util.Objects::isNull))
            throw new IOException("主机配置必须是JSON 数组，不能包含 null 项");
    }

    private static List<HostConfig> createDemoHosts() {
        List<HostConfig> list = new ArrayList<>();
        HostConfig demo = new HostConfig("本地/测试示例", "127.0.0.1", 22, "root");
        demo.setRemarks("欢迎使用 OpenShell！点击可编辑或连接你的云服务器");
        demo.setGroup("示例分组");
        list.add(demo);
        return list;
    }
}
