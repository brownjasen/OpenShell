package org.openshell.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class UiPreferencesTest {
    @TempDir Path directory;
    @Test void restoresWindowAndPerHostLayoutWithoutLosingOtherHosts() throws Exception {
        Path file = directory.resolve("workspace.json");
        UiPreferences first = new UiPreferences(file);
        first.update(state -> {
            state.sidebarWidth = 247; state.sidebarVisible = false;
            state.view("a").terminalRatio = 0.72;
            state.view("a").layoutMode = 1;
            state.quickCommands.add(new WorkspaceState.QuickCommand("检查磁盘", "pwd\ndf -h"));
            state.dark = false; state.multiline = true;
        });
        UiPreferences otherWindow = new UiPreferences(file);
        otherWindow.update(state -> state.view("b").terminalRatio = 0.55);
        first.update(state -> state.fontSize = 16);
        var restored = new UiPreferences(file).snapshot();
        assertFalse(restored.sidebarVisible); assertEquals(247, restored.sidebarWidth);
        assertEquals(0.72, restored.view("a").terminalRatio);
        assertEquals(1, restored.view("a").layoutMode);
        assertEquals(0.55, restored.view("b").terminalRatio);
        assertEquals(16, restored.fontSize);
        assertFalse(restored.dark); assertTrue(restored.multiline);
        assertEquals("pwd\ndf -h", restored.quickCommands.get(0).command);
    }
    @Test void corruptSettingsArePreservedAndCannotBeOverwritten() throws Exception {
        Path file = directory.resolve("workspace.json");
        Files.writeString(file, "{broken");
        UiPreferences preferences = new UiPreferences(file);
        assertNotNull(preferences.lastError());
        assertFalse(preferences.tryUpdate(state -> state.fontSize = 20));
        assertEquals("{broken", Files.readString(file));
    }
    @Test void historyIsIsolatedDeduplicatedAndBoundedAndCanBeDisabled() throws Exception {
        UiPreferences preferences = new UiPreferences(directory.resolve("workspace.json"));
        preferences.recordCommand("a", "pwd"); preferences.recordCommand("a", "pwd");
        preferences.recordCommand("b", "df -h");
        assertEquals(java.util.List.of("pwd"), preferences.history("a", ""));
        assertTrue(preferences.history("a", "df").isEmpty());
        preferences.update(state -> state.recordHistory = false);
        preferences.recordCommand("a", "sensitive command");
        assertEquals(java.util.List.of("pwd"), preferences.history("a", ""));
        preferences.update(state -> state.recordHistory = true);
        preferences.update(state -> {
            var commands = new java.util.ArrayList<String>();
            for (int i = 0; i < 1003; i++) commands.add("echo " + i);
            state.histories.put("a", commands);
        });
        var history = new UiPreferences(directory.resolve("workspace.json")).history("a", "");
        assertEquals(1000, history.size()); assertEquals("echo 3", history.get(0));
    }
}
