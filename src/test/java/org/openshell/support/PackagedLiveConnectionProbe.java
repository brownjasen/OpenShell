package org.openshell.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openshell.model.HostConfig;
import org.openshell.ssh.SshSession;
import java.nio.file.*;
import java.util.Arrays;

/** Explicit live-only entry point. Root supplies reviewed HostConfig JSON through stdin, never command-line credentials. */
public final class PackagedLiveConnectionProbe {
    private PackagedLiveConnectionProbe() {}
    public static void main(String[] args) {
        SshSession connection = null;
        int status = 0;
        try {
            if (args.length != 5 || !"--live".equals(args[0])) throw new IllegalArgumentException("Live probe requires explicit reviewed arguments");
            Path artifact = Path.of(args[1]).toRealPath();
            PackagedRuntimeProbe.requireArtifact(SshSession.class, artifact);
            PackagedRuntimeProbe.requireArtifact(ObjectMapper.class, artifact);
            PackagedRuntimeProbe.requireArtifact(Class.forName("com.jcraft.jsch.jce.SignatureEd25519"), artifact);
            String configuration = System.getProperty("openshell.configDir");
            if (configuration == null || configuration.isBlank()) throw new IllegalArgumentException("Temporary verified known_hosts directory is required");
            Path config = Path.of(configuration).toRealPath();
            Path usual = Path.of(System.getProperty("user.home"), ".openshell").toAbsolutePath().normalize();
            if (config.equals(usual) || !Files.isRegularFile(config.resolve("known_hosts")))
                throw new IllegalArgumentException("Use a temporary copy of the original approved known_hosts");
            byte[] encoded = System.in.readNBytes(65537);
            HostConfig host;
            try {
                if (encoded.length > 65536) throw new IllegalArgumentException("Host configuration input exceeds limit");
                host = new ObjectMapper().readValue(encoded, HostConfig.class);
            } finally { Arrays.fill(encoded, (byte) 0); }
            if (!args[2].equals(host.getName()) || !args[3].equals(host.getHost())) throw new IllegalArgumentException("Reviewed live target mismatch");
            connection = new SshSession(host, (h, algorithm, fingerprint) -> false);
            connection.connect();
            String algorithm = connection.getRawSession().getHostKey().getType();
            if (!args[4].equals(algorithm)) throw new IllegalStateException("Negotiated host algorithm differs from reviewed original known_hosts");
            System.out.println("PACKAGED_LIVE connection=PASS strict_known_hosts=true host_key_algorithm=" + algorithm);
        } catch (Throwable failure) {
            System.out.println("PACKAGED_LIVE connection=FAIL error_class=" + failure.getClass().getSimpleName());
            status = 1;
        } finally { if (connection != null) connection.disconnect(); }
        if (status != 0) System.exit(status);
    }
}
