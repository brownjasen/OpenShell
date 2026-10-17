package org.openshell.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openshell.support.PackagedRuntimeProbe;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit post-package acceptance: test dependencies and target/classes cannot conceal shading faults. */
class PackagedJarAcceptanceIT {
    private static Path artifact() { return Path.of("target/openshell-1.0.0.jar").toAbsolutePath().normalize(); }

    @Test @Timeout(25)
    void publishedJarMustProvideRealEd25519SigningAndVerificationWithoutExternalDependencyJars() throws Exception {
        Path jar = artifact(); assertTrue(Files.isRegularFile(jar), "Run package before packaged acceptance");
        Path runtime = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String classpath = jar + File.pathSeparator + Path.of("target/test-classes").toAbsolutePath();
        ProcessBuilder command = new ProcessBuilder(runtime.toString(), "-Djava.awt.headless=true", "-cp", classpath,
                PackagedRuntimeProbe.class.getName(), jar.toString()).redirectErrorStream(true);
        // Do not inherit injected classpaths or agents that could make the real artifact appear healthy.
        command.environment().remove("CLASSPATH"); command.environment().remove("JAVA_TOOL_OPTIONS");
        command.environment().remove("JDK_JAVA_OPTIONS"); command.environment().remove("_JAVA_OPTIONS");
        Process process = command.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Packaged crypto probe timeout");
            String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println(result.strip());
            assertEquals(0, process.exitValue(), "Packaged JAR lacks a working JDK Ed25519 implementation: " + result);
            assertTrue(result.contains("ED25519_SIGN_VERIFY_AND_TAMPER_REJECTION=PASS"));
            assertTrue(result.contains("production_and_dependencies_from_fat_jar=PASS"));
        } finally { process.destroyForcibly(); }
    }

    @Test void java17ViewSelectsSupportedMultiReleaseClassesFromEveryShadedDependency() throws Exception {
        assertTrue(Files.isRegularFile(artifact()));
        try (JarFile jar = new JarFile(artifact().toFile(), true, JarFile.OPEN_READ, Runtime.Version.parse("17"))) {
            assertEquals("true", jar.getManifest().getMainAttributes().getValue("Multi-Release"));
            assertEquals("org.openshell.Main", jar.getManifest().getMainAttributes().getValue("Main-Class"));
            Map<String, Integer> versioned = new TreeMap<>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("META-INF/versions/") || !name.endsWith(".class")) continue;
                String suffix = name.substring("META-INF/versions/".length()); int slash = suffix.indexOf('/');
                int version = Integer.parseInt(suffix.substring(0, slash)); String logical = suffix.substring(slash + 1);
                if (version <= 17 && !logical.equals("module-info.class")) versioned.merge(logical, version, Math::max);
            }
            assertTrue(versioned.size() >= 10, "All JSch, FlatLaf and Jackson MR entries must survive shading");
            assertEquals(15, versioned.get("com/jcraft/jsch/jce/SignatureEd25519.class"));
            assertEquals(17, versioned.get("com/fasterxml/jackson/core/io/doubleparser/FastDoubleSwar.class"));
            for (var selected : versioned.entrySet()) {
                JarEntry entry = jar.getJarEntry(selected.getKey()); assertNotNull(entry, selected.getKey());
                assertEquals("META-INF/versions/" + selected.getValue() + "/" + selected.getKey(), entry.getRealName());
                try (DataInputStream input = new DataInputStream(jar.getInputStream(entry))) {
                    assertEquals(0xCAFEBABE, input.readInt()); input.readUnsignedShort();
                    assertTrue(input.readUnsignedShort() <= 61, "Java17 MR view selected unsupported bytecode: " + selected.getKey());
                }
            }
            System.out.println("PACKAGED_ACCEPTANCE java17_MR_selection_and_bytecode=PASS classes=" + versioned.size());
        }
    }
}
