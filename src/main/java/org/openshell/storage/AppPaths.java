package org.openshell.storage;

import java.nio.file.Path;

public final class AppPaths {
    private AppPaths() {}
    public static Path configDirectory() {
        String override = System.getProperty("openshell.configDir");
        return override == null || override.isBlank()
                ? Path.of(System.getProperty("user.home"), ".openshell") : Path.of(override);
    }
}
