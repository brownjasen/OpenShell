package org.openshell.model;

import java.util.*;

/** Group paths stay compatible with the existing HostConfig.group string. */
public final class HostGroups {
    private HostGroups() {}
    public record Change(List<HostConfig> hosts, List<String> groups) {}
    public static String normalize(String path) {
        if (path == null || path.isBlank()) return "默认分组";
        List<String> parts = Arrays.stream(path.split("/+")).map(String::strip).filter(part -> !part.isEmpty()).toList();
        if (parts.isEmpty() || parts.stream().anyMatch(part -> part.equals(".") || part.equals("..")))
            throw new IllegalArgumentException("分组名称不能包含 . 或 .. 路径段");
        return String.join("/", parts);
    }
    public static boolean within(String path, String parent) { return path.equals(parent) || path.startsWith(parent + "/"); }
    public static List<String> allPaths(List<HostConfig> hosts, List<String> explicit) {
        SortedSet<String> result = new TreeSet<>();
        List<String> paths = new ArrayList<>(explicit);
        hosts.forEach(host -> paths.add(host.getGroup()));
        for (String path : paths) {
            String normalized = normalize(path);
            while (true) {
                result.add(normalized);
                int slash = normalized.lastIndexOf('/'); if (slash < 0) break;
                normalized = normalized.substring(0, slash);
            }
        }
        return new ArrayList<>(result);
    }
    public static Change rename(List<HostConfig> hosts, List<String> explicit, String source, String target) {
        String from = normalize(source), to = normalize(target);
        List<String> paths = allPaths(hosts, explicit);
        if (!paths.contains(from)) throw new IllegalArgumentException("分组不存在");
        if (!from.equals(to) && (within(to, from) || paths.contains(to)))
            throw new IllegalArgumentException("不能移入自身子分组，也不能覆盖已有分组");
        List<HostConfig> copies = hosts.stream().map(HostConfig::copy).toList();
        for (HostConfig host : copies) {
            String group = normalize(host.getGroup());
            if (within(group, from)) host.setGroup(to + group.substring(from.length()));
        }
        return new Change(new ArrayList<>(copies), paths.stream().map(path -> within(path, from)
                ? to + path.substring(from.length()) : path).distinct().sorted().toList());
    }
    public static Change moveHost(List<HostConfig> hosts, List<String> explicit, String id, String target) {
        List<HostConfig> copies = new ArrayList<>(hosts.stream().map(HostConfig::copy).toList());
        HostConfig host = copies.stream().filter(value -> value.getId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("主机不存在"));
        host.setGroup(normalize(target));
        return new Change(copies, allPaths(copies, allPaths(hosts, explicit)));
    }
    public static Change removeEmpty(List<HostConfig> hosts, List<String> explicit, String source) {
        String group = normalize(source);
        List<String> paths = allPaths(hosts, explicit);
        if (hosts.stream().anyMatch(host -> within(normalize(host.getGroup()), group))
                || paths.stream().anyMatch(path -> !path.equals(group) && within(path, group)))
            throw new IllegalArgumentException("分组非空，请先移出主机或子分组");
        return new Change(new ArrayList<>(hosts), paths.stream().filter(path -> !path.equals(group)).toList());
    }
}
