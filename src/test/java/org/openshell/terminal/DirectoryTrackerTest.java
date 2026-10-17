package org.openshell.terminal;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryTrackerTest {
    @Test void observesSplitSequencesAndUtf8PathsWithoutDuplicateEvents() {
        List<String> paths = new ArrayList<>();
        DirectoryTracker tracker = new DirectoryTracker(paths::add);
        String output = "hello\u001b]7;file:///home/%E4%B8%AD%E6%96%87%20a+\u001b\\world"
                + "\u001b]7;file:///home/%E4%B8%AD%E6%96%87%20a+\u0007"
                + "\u001b]7;file://server/next\u0007";
        for (char c : output.toCharArray()) tracker.accept(new char[]{c}, 0, 1);
        assertEquals(List.of("/home/中文 a+", "/next"), paths);
    }
    @Test void rejectsUntrustedSchemesControlsMalformedEncodingAndRecoversAfterLongSequence() {
        for (String value : List.of("https://server/tmp", "file:///tmp%00x", "file:///tmp%0Ax", "file:///bad%FF", "file:relative", "file:///ok?q=x"))
            assertNull(DirectoryTracker.decodePath(value), value);
        List<String> paths = new ArrayList<>(); DirectoryTracker tracker = new DirectoryTracker(paths::add);
        String text = "\u001b]7;" + "x".repeat(9000) + "\u0007\u001b]7;file:///safe\u0007";
        tracker.accept(text.toCharArray(), 0, text.length());
        assertEquals(List.of("/safe"), paths);
    }
}
