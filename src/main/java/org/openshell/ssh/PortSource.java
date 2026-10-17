package org.openshell.ssh;

import java.util.List;

public interface PortSource extends AutoCloseable {
    List<PortParser.PortEntry> load() throws Exception;
    @Override void close();
}
