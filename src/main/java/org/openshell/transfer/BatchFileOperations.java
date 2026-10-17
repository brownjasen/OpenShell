package org.openshell.transfer;

import com.jcraft.jsch.*;
import java.util.*;

/** Captured absolute paths only; directories are deliberately removed only when empty. */
public final class BatchFileOperations {
    private BatchFileOperations() {}
    public record Result(List<String> deleted, Map<String, String> failures) {}
    public static Result delete(Session session, List<String> capturedPaths) throws Exception {
        List<String> paths = List.copyOf(capturedPaths);
        for (String path : paths) TransferPaths.validateRemote(path);
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        var timeout = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "OpenShell-DeleteTimeout"); t.setDaemon(true); return t; });
        timeout.schedule(channel::disconnect, 30, java.util.concurrent.TimeUnit.SECONDS);
        try {
            channel.connect(10000); List<String> done = new ArrayList<>(); Map<String, String> errors = new LinkedHashMap<>();
            for (String path : paths) {
                try {
                    TransferPaths.checkRemoteParents(channel, path);
                    String literal = TransferPaths.literal(path); SftpATTRS attrs = channel.lstat(literal);
                    if (attrs.isDir() && !attrs.isLink()) channel.rmdir(literal); else channel.rm(literal);
                    done.add(path);
                } catch (Exception e) { errors.put(path, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
            }
            return new Result(List.copyOf(done), Collections.unmodifiableMap(errors));
        } finally { channel.disconnect(); timeout.shutdownNow(); }
    }
}
