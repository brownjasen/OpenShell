package org.openshell.support;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.util.*;

/** Standalone test helper: its only runtime classpath is the application JAR and test-classes. No JUnit. */
public final class PackagedRuntimeProbe {
    private PackagedRuntimeProbe() {}
    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("Expected application artifact path");
            Path artifact = Path.of(args[0]).toRealPath();
            verifyApplicationOrigins(artifact);
            verifyEd25519();
            System.out.println("PACKAGED_RUNTIME ED25519_SIGN_VERIFY_AND_TAMPER_REJECTION=PASS");
            System.out.println("PACKAGED_RUNTIME production_and_dependencies_from_fat_jar=PASS runtime_feature=" + Runtime.version().feature());
        } catch (Throwable failure) {
            // Print classes and source line numbers only; never expose argument values alongside live acceptance.
            System.out.println("PACKAGED_RUNTIME FAILURE=" + failure.getClass().getSimpleName());
            for (StackTraceElement frame : Arrays.stream(failure.getStackTrace()).limit(6).toList())
                System.out.println("PACKAGED_RUNTIME failure_frame=" + frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber());
            System.exit(1);
        }
    }
    public static void requireArtifact(Class<?> type, Path artifact) throws Exception {
        if (type.getProtectionDomain().getCodeSource() == null
                || !Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(artifact))
            throw new IllegalStateException("Runtime class came from outside the packaged artifact");
    }
    private static void verifyApplicationOrigins(Path artifact) throws Exception {
        String[] classes = {
                "org.openshell.Main", "org.openshell.ssh.SshSession", "com.jcraft.jsch.JSch",
                "com.jcraft.jsch.jce.SignatureEd25519", "com.jcraft.jsch.jce.SignatureEd448",
                "com.jcraft.jsch.jce.XDH", "com.jcraft.jsch.jce.KeyPairGenEdDSA",
                "com.jcraft.jsch.UnixDomainSocketFactory", "com.jcraft.jsch.JplLogger",
                "com.formdev.flatlaf.util.MultiResolutionImageSupport",
                "com.fasterxml.jackson.core.io.doubleparser.FastDoubleSwar"
        };
        for (String name : classes) requireArtifact(Class.forName(name, false, PackagedRuntimeProbe.class.getClassLoader()), artifact);
        String selected = Class.forName("com.jcraft.jsch.jce.SignatureEd25519")
                .getResource("SignatureEd25519.class").toExternalForm();
        if (!selected.contains("!/META-INF/versions/15/")) throw new IllegalStateException("JDK Ed25519 MR implementation not selected");
    }
    private static void verifyEd25519() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] privateSeed = ((EdECPrivateKey) pair.getPrivate()).getBytes().orElseThrow();
        EdECPublicKey publicKey = (EdECPublicKey) pair.getPublic();
        byte[] bigEndian = publicKey.getPoint().getY().toByteArray(), rawPublic = new byte[32];
        for (int i = 0; i < Math.min(32, bigEndian.length); i++) rawPublic[i] = bigEndian[bigEndian.length - 1 - i];
        if (publicKey.getPoint().isXOdd()) rawPublic[31] |= (byte) 0x80;
        byte[] message = "OpenShell packaged Ed25519 acceptance".getBytes(StandardCharsets.UTF_8);
        try {
            com.jcraft.jsch.SignatureEdDSA signer = signature(); signer.init(); signer.setPrvKey(privateSeed); signer.update(message);
            byte[] signed = signer.sign();
            if (signed.length != 64) throw new GeneralSecurityException("Invalid raw Ed25519 signature length");
            byte[] sshSigned = sshSignature(signed);
            com.jcraft.jsch.SignatureEdDSA verifier = signature(); verifier.init(); verifier.setPubKey(rawPublic.clone()); verifier.update(message);
            if (!verifier.verify(sshSigned)) throw new GeneralSecurityException("Packaged Ed25519 verification failed");
            // JSch signs and verifies; the independent JDK provider verifies its exact wire-independent raw signature too.
            Signature jdk = Signature.getInstance("Ed25519"); jdk.initVerify(pair.getPublic()); jdk.update(message);
            if (!jdk.verify(signed)) throw new GeneralSecurityException("JDK verification of packaged signature failed");
            message[0] ^= 1;
            com.jcraft.jsch.SignatureEdDSA tampered = signature(); tampered.init(); tampered.setPubKey(rawPublic.clone()); tampered.update(message);
            if (tampered.verify(sshSigned)) throw new GeneralSecurityException("Tampered message was accepted");
            System.out.println("PACKAGED_RUNTIME signature_format=SSH_STRING_ALGORITHM_PLUS_STRING_SIGNATURE raw_bytes=" + signed.length + " ssh_blob_bytes=" + sshSigned.length);
        } finally { Arrays.fill(privateSeed, (byte) 0); }
    }
    private static byte[] sshSignature(byte[] raw) throws IOException {
        // JSch sign() returns raw bytes, while its SSH verifier first parses two length-prefixed SSH strings.
        byte[] algorithm = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(algorithm.length); output.write(algorithm); output.writeInt(raw.length); output.write(raw);
        }
        return bytes.toByteArray();
    }
    private static com.jcraft.jsch.SignatureEdDSA signature() throws Exception {
        return (com.jcraft.jsch.SignatureEdDSA) Class.forName("com.jcraft.jsch.jce.SignatureEd25519").getDeclaredConstructor().newInstance();
    }
}
