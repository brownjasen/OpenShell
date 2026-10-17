package org.openshell.ssh;

import com.jcraft.jsch.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Base64;

public final class KnownHostsTrust implements HostKeyRepository {
    @FunctionalInterface public interface Confirmation {
        boolean accept(String host, String algorithm, String fingerprint);
    }
    private static final Object FILE_LOCK = new Object();
    private final Path file;
    private final Confirmation confirmation;
    public KnownHostsTrust(Path file, Confirmation confirmation) throws IOException {
        this.file = file; this.confirmation = confirmation;
        synchronized (FILE_LOCK) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (!Files.exists(file)) Files.createFile(file);
        }
    }
    private HostKeyRepository load() throws JSchException {
        JSch jsch = new JSch(); jsch.setKnownHosts(file.toString()); return jsch.getHostKeyRepository();
    }
    @Override public int check(String host, byte[] key) {
        try {
            synchronized (FILE_LOCK) {
                HostKeyRepository repository = load();
                int result = repository.check(host, key);
                HostKey[] known = repository.getHostKey(host, null);
                if (result == NOT_INCLUDED && known != null && known.length > 0) return CHANGED;
                if (result != NOT_INCLUDED) return result;
            }
            HostKey candidate = new HostKey(host, key);
            String fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
            if (!confirmation.accept(host, candidate.getType(), fingerprint)) return NOT_INCLUDED;
            synchronized (FILE_LOCK) {
                HostKeyRepository repository = load();
                int result = repository.check(host, key);
                HostKey[] known = repository.getHostKey(host, null);
                if (result == NOT_INCLUDED && known != null && known.length > 0) return CHANGED;
                if (result != NOT_INCLUDED) return result;
                repository.add(candidate, null);
                return load().check(host, key); // Never accept an unpersisted key.
            }
        } catch (Exception error) {
            throw new IllegalStateException("无法读取或保存主机指纹: " + file, error);
        }
    }
    @Override public void add(HostKey key, UserInfo info) { /* check() already saved the confirmed key. */ }
    @Override public void remove(String host, String type) { throw new UnsupportedOperationException(); }
    @Override public void remove(String host, String type, byte[] key) { throw new UnsupportedOperationException(); }
    @Override public String getKnownHostsRepositoryID() { return file.toString(); }
    @Override public HostKey[] getHostKey() { return getHostKey(null, null); }
    @Override public HostKey[] getHostKey(String host, String type) {
        synchronized (FILE_LOCK) {
            try { return load().getHostKey(host, type); }
            catch (JSchException error) { throw new IllegalStateException(error); }
        }
    }
}
