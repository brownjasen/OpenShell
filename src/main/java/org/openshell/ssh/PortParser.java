package org.openshell.ssh;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Linux ss and netstat have different layouts, especially for UDP. */
public final class PortParser {
    public record PortEntry(String protocol, String address, int port, String state, Integer pid, String process) {}
    private record Endpoint(String address, int port) {}
    private static final Pattern SS_PROCESS = Pattern.compile("\\\"([^\\\"]*)\\\",pid=(\\d+)");
    private PortParser() {}

    public static List<PortEntry> parseSs(String text) {
        List<PortEntry> rows = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] fields = line.strip().split("\\s+", 7);
            if (fields.length < 6 || !protocol(fields[0])) continue;
            Endpoint endpoint = endpoint(fields[4]);
            if (endpoint == null) continue;
            Integer pid = null;
            List<String> names = new ArrayList<>();
            Matcher matcher = SS_PROCESS.matcher(fields.length > 6 ? fields[6] : "");
            while (matcher.find()) {
                Integer candidate = positivePid(matcher.group(2));
                if (pid == null) pid = candidate;
                String name = matcher.group(1);
                if (!names.contains(name)) names.add(name);
            }
            rows.add(new PortEntry(fields[0].toUpperCase(Locale.ROOT), endpoint.address, endpoint.port,
                    fields[1], pid, names.isEmpty() ? "不可见（可能需要权限）" : String.join(", ", names)));
        }
        return List.copyOf(rows);
    }

    public static List<PortEntry> parseNetstat(String text) {
        List<PortEntry> rows = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] fields = line.strip().split("\\s+", 7);
            if (fields.length < 6 || !protocol(fields[0])) continue;
            Endpoint endpoint = endpoint(fields[3]);
            if (endpoint == null) continue;
            boolean tcp = fields[0].startsWith("tcp");
            String state = tcp ? fields[5] : "UNCONN";
            String owner = tcp ? fields.length > 6 ? fields[6] : "-"
                    : fields[5] + (fields.length > 6 ? " " + fields[6] : "");
            int slash = owner.indexOf('/');
            Integer pid = slash > 0 ? positivePid(owner.substring(0, slash)) : null;
            String process = slash >= 0 ? owner.substring(slash + 1).strip() : "不可见（可能需要权限）";
            rows.add(new PortEntry(fields[0].toUpperCase(Locale.ROOT), endpoint.address, endpoint.port, state, pid, process));
        }
        return List.copyOf(rows);
    }

    private static boolean protocol(String value) { return value.matches("(?:tcp|udp)6?"); }
    private static Integer positivePid(String value) {
        try { int pid = Integer.parseInt(value); return pid > 0 ? pid : null; }
        catch (NumberFormatException ignored) { return null; }
    }
    private static Endpoint endpoint(String value) {
        int colon = value.lastIndexOf(':');
        if (colon < 1 || colon == value.length() - 1) return null;
        try {
            int port = Integer.parseInt(value.substring(colon + 1));
            if (port < 0 || port > 65535) return null;
            String address = value.substring(0, colon);
            if (address.startsWith("[") && address.endsWith("]")) address = address.substring(1, address.length() - 1);
            return new Endpoint(address, port);
        } catch (NumberFormatException ignored) { return null; }
    }
}
