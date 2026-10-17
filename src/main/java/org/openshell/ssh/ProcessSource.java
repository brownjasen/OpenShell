package org.openshell.ssh;

import java.util.List;

public interface ProcessSource extends AutoCloseable {
    List<LinuxProcessSampler.ProcessInfo> load() throws Exception;
    void kill(int pid) throws Exception;
    @Override void close();
}
