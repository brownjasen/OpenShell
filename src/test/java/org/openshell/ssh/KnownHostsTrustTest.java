package org.openshell.ssh;

import com.jcraft.jsch.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class KnownHostsTrustTest {
    @TempDir Path directory;
    @Test void persistsAcceptedKeyAndRejectsChangedKeyWithoutAnotherPrompt() throws Exception {
        byte[] first = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048).getPublicKeyBlob();
        byte[] changed = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048).getPublicKeyBlob();
        AtomicInteger prompts = new AtomicInteger();
        KnownHostsTrust trust = new KnownHostsTrust(directory.resolve("known_hosts"), (host, algorithm, fingerprint) -> {
            prompts.incrementAndGet(); assertTrue(fingerprint.startsWith("SHA256:")); return true;
        });
        assertEquals(HostKeyRepository.OK, trust.check("[127.0.0.1]:2222", first));
        KnownHostsTrust reloaded = new KnownHostsTrust(directory.resolve("known_hosts"), (h, a, f) -> {
            prompts.incrementAndGet(); return true;
        });
        assertEquals(HostKeyRepository.OK, reloaded.check("[127.0.0.1]:2222", first));
        assertEquals(HostKeyRepository.CHANGED, reloaded.check("[127.0.0.1]:2222", changed));
        assertEquals(1, prompts.get());
    }
    @Test void decliningAnUnknownKeyDoesNotSaveIt() throws Exception {
        byte[] key = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048).getPublicKeyBlob();
        KnownHostsTrust trust = new KnownHostsTrust(directory.resolve("known_hosts"), (h, a, f) -> false);
        assertEquals(HostKeyRepository.NOT_INCLUDED, trust.check("example.test", key));
        assertEquals(0, trust.getHostKey().length);
    }
    @Test void changingKeyAlgorithmDoesNotBypassAnExistingPin() throws Exception {
        byte[] first = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048).getPublicKeyBlob();
        byte[] replacement = KeyPair.genKeyPair(new JSch(), KeyPair.ECDSA, 256).getPublicKeyBlob();
        AtomicInteger prompts = new AtomicInteger();
        KnownHostsTrust trust = new KnownHostsTrust(directory.resolve("known_hosts"), (h, a, f) -> {
            prompts.incrementAndGet(); return true;
        });
        assertEquals(HostKeyRepository.OK, trust.check("example.test", first));
        assertEquals(HostKeyRepository.CHANGED, trust.check("example.test", replacement));
        assertEquals(1, prompts.get());
    }
}
