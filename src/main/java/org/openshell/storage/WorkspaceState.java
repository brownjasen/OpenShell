package org.openshell.storage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class WorkspaceState {
    public int width = 1360, height = 860, x = -1, y = -1, sidebarWidth = 240;
    public boolean maximized, sidebarVisible = true, dark = true;
    public boolean recentExpanded;
    public float fontSize = 13;
    public boolean recordHistory = true, multiline, clearAfterSend = true, ignoreEmpty;
    public int sendMode;
    public List<String> groups = new ArrayList<>(), collapsedGroups = new ArrayList<>();
    public Map<String, View> views = new HashMap<>();
    public Map<String, List<String>> histories = new HashMap<>();
    public List<QuickCommand> quickCommands = new ArrayList<>();
    public View view(String hostId) { return views.computeIfAbsent(hostId, ignored -> new View()); }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class View {
        public int layoutMode;
        public double terminalRatio = 0.64;
        public int directoryTreeWidth = 180;
        public boolean monitorVisible = true, showHidden, followTerminal;
        public List<String> directoryBookmarks = new ArrayList<>();
        public List<Integer> columns = new ArrayList<>();
    }
    public static final class QuickCommand {
        public String name = "", command = "";
        public QuickCommand() {}
        public QuickCommand(String name, String command) { this.name = name; this.command = command; }
    }
    void normalize() {
        width = Math.max(800, Math.min(8000, width)); height = Math.max(480, Math.min(8000, height));
        sidebarWidth = Math.max(180, Math.min(600, sidebarWidth));
        fontSize = Float.isFinite(fontSize) ? Math.max(9, Math.min(30, fontSize)) : 13;
        sendMode = Math.max(0, Math.min(1, sendMode));
        if (groups == null) groups = new ArrayList<>();
        if (collapsedGroups == null) collapsedGroups = new ArrayList<>();
        if (views == null) views = new HashMap<>();
        if (histories == null) histories = new HashMap<>();
        if (quickCommands == null) quickCommands = new ArrayList<>();
        groups.removeIf(Objects::isNull); collapsedGroups.removeIf(Objects::isNull);
        views.values().removeIf(Objects::isNull);
        for (View view : views.values()) {
            view.layoutMode = Math.max(0, Math.min(2, view.layoutMode));
            view.terminalRatio = Double.isFinite(view.terminalRatio) ? Math.max(0.2, Math.min(0.85, view.terminalRatio)) : 0.64;
            view.directoryTreeWidth = Math.max(80, Math.min(600, view.directoryTreeWidth));
            if (view.directoryBookmarks == null) view.directoryBookmarks = new ArrayList<>();
            view.directoryBookmarks = new ArrayList<>(view.directoryBookmarks.stream().filter(p -> p != null && p.startsWith("/") && p.indexOf(0) < 0).distinct().limit(100).toList());
            if (view.columns == null) view.columns = new ArrayList<>();
            view.columns.replaceAll(value -> value == null ? 100 : Math.max(35, Math.min(1500, value)));
        }
        histories.values().removeIf(Objects::isNull);
        histories.replaceAll((key, values) -> {
            List<String> valid = values.stream().filter(value -> value != null && !value.isBlank()).toList();
            return new ArrayList<>(valid.subList(Math.max(0, valid.size() - 1000), valid.size()));
        });
        quickCommands.removeIf(command -> command == null || command.name == null || command.command == null);
    }
}
