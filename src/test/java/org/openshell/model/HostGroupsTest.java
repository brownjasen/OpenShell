package org.openshell.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HostGroupsTest {
    @Test void renameUpdatesDescendantsButNotSimilarlyNamedSiblings() {
        HostConfig web = new HostConfig(); web.setGroup("生产/web/api");
        HostConfig other = new HostConfig(); other.setGroup("生产/web2");
        var change = HostGroups.rename(List.of(web, other), List.of("生产/web/空目录"), "生产/web", "生产/应用");
        assertEquals("生产/应用/api", change.hosts().get(0).getGroup());
        assertEquals("生产/web2", change.hosts().get(1).getGroup());
        assertTrue(change.groups().contains("生产/应用/空目录"));
        assertEquals("生产/web/api", web.getGroup(), "Preparing a change must not mutate live hosts");
    }
    @Test void rejectsMovingAGroupIntoItsOwnSubtreeOrMergingItSilently() {
        HostConfig host = new HostConfig(); host.setGroup("a/child");
        assertThrows(IllegalArgumentException.class, () -> HostGroups.rename(List.of(host), List.of(), "a", "a/child/new"));
        assertThrows(IllegalArgumentException.class, () -> HostGroups.rename(List.of(host), List.of("b"), "a", "b"));
    }
    @Test void movingOneHostAndRemovingEmptyGroupsPreservesTheOthers() {
        HostConfig a = new HostConfig(); a.setGroup("a");
        HostConfig b = new HostConfig(); b.setGroup("b");
        var moved = HostGroups.moveHost(List.of(a, b), List.of(), a.getId(), "b/nested");
        assertEquals("b/nested", moved.hosts().get(0).getGroup());
        assertEquals("b", moved.hosts().get(1).getGroup());
        assertThrows(IllegalArgumentException.class, () -> HostGroups.removeEmpty(moved.hosts(), moved.groups(), "b"));
        assertFalse(HostGroups.removeEmpty(moved.hosts(), moved.groups(), "a").groups().contains("a"));
    }
}
