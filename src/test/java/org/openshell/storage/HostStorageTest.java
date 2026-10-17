package org.openshell.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.model.HostConfig;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HostStorageTest {
    @TempDir Path directory;
    @Test void failedSerializationPreservesExistingExport() throws Exception {
        Path target = directory.resolve("hosts.json");
        String original = "[{\"name\":\"previous\"}]";
        Files.writeString(target, original);
        HostConfig broken = new HostConfig() {
            @Override public String getName() { throw new IllegalStateException("serialization failed"); }
        };
        assertThrows(IOException.class, () -> HostStorage.exportHosts(List.of(broken), target.toFile()));
        assertEquals(original, Files.readString(target));
    }
    @Test void refusesToOverwriteUnreadableConfiguration() throws Exception {
        String before = System.getProperty("openshell.configDir");
        System.setProperty("openshell.configDir", directory.toString());
        Path target = directory.resolve("hosts.json");
        String corrupt = "{broken configuration";
        Files.writeString(target, corrupt);
        try {
            assertThrows(RuntimeException.class, HostStorage::loadHosts);
            assertThrows(RuntimeException.class, () -> HostStorage.saveHosts(List.of(new HostConfig())));
            assertEquals(corrupt, Files.readString(target));
        } finally {
            if (before == null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir", before);
        }
    }
    @Test void rejectsJsonNullInsteadOfReturningAnInvalidHostList() throws Exception {
        Path file = directory.resolve("import.json"); Files.writeString(file, "null");
        assertThrows(IOException.class, () -> HostStorage.importHosts(file.toFile()));
    }
}
