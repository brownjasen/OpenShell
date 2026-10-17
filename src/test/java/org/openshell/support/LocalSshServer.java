package org.openshell.support;

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.*;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.openshell.model.HostConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.concurrent.*;

/** Real SSH/SFTP protocol, ephemeral loopback port, generated in-memory test key. */
public final class LocalSshServer implements AutoCloseable {
    public final SshServer server = SshServer.setUpDefaultServer();
    public volatile EchoShell shell;
    public final BlockingQueue<String> commands = new LinkedBlockingQueue<>();
    public LocalSshServer(Path root) throws Exception {
        server.setIoServiceFactoryFactory(new org.apache.sshd.mina.MinaServiceFactoryFactory());
        server.setHost("127.0.0.1"); server.setPort(0);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        server.setKeyPairProvider(KeyPairProvider.wrap(generator.generateKeyPair()));
        server.setPasswordAuthenticator((user, password, session) -> "test".equals(user) && "test".equals(password));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.setCommandFactory((channel, command) -> {
            commands.add(command);
            return new org.apache.sshd.server.shell.UnknownCommand(command);
        });
        server.setShellFactory(channel -> {
            EchoShell created = new EchoShell();
            shell = created;
            return created;
        });
        server.start();
    }
    public HostConfig host() {
        HostConfig host = new HostConfig("Isolated test", "127.0.0.1", server.getPort(), "test");
        host.setPassword("test"); return host;
    }
    @Override public void close() throws IOException { server.stop(false); }

    public static final class EchoShell implements Command {
        private InputStream input;
        private OutputStream output;
        private ExitCallback callback;
        private Thread thread;
        public volatile Environment environment;
        public final BlockingQueue<Integer> received = new LinkedBlockingQueue<>();
        public final CountDownLatch resized = new CountDownLatch(1);
        @Override public void setInputStream(InputStream stream) { input = stream; }
        @Override public void setOutputStream(OutputStream stream) { output = stream; }
        @Override public void setErrorStream(OutputStream stream) {}
        @Override public void setExitCallback(ExitCallback value) { callback = value; }
        @Override public void start(ChannelSession channel, Environment environment) {
            this.environment = environment;
            environment.addSignalListener((c, signal) -> resized.countDown(), Signal.WINCH);
            thread = new Thread(() -> {
                try {
                    output.write("\u001b[2J\u001b[H\u001b[31mRED\u001b[0m\r\n中文\r\n$ ".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    int value;
                    while ((value = input.read()) != -1) received.add(value);
                } catch (IOException ignored) {
                    // Client teardown closes the pipe.
                } finally { callback.onExit(0); }
            }, "OpenShell-TestShell");
            thread.setDaemon(true); thread.start();
        }
        public void send(String text) throws IOException { output.write(text.getBytes(StandardCharsets.UTF_8)); output.flush(); }
        @Override public void destroy(ChannelSession channel) throws IOException {
            if (input != null) input.close();
            if (thread != null) thread.interrupt();
        }
    }
}
