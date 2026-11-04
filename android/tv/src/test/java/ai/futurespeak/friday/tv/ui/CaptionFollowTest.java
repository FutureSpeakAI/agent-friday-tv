package ai.futurespeak.friday.tv.ui;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import ai.futurespeak.friday.tv.reply.ReplyParser;

import static org.junit.Assert.*;

public class CaptionFollowTest {
    @Test public void sentenceAlreadyVisibleStays() throws Exception {
        assertEquals(0, CaptionFollow.top(0, 3, 1, 2, 10));
    }

    @Test public void laterSentenceComesIntoView() throws Exception {
        assertEquals(4, CaptionFollow.top(0, 3, 4, 5, 10));      // the window moves to the spoken line
        assertEquals(7, CaptionFollow.top(0, 3, 9, 9, 10));      // never past the end
        assertEquals(2, CaptionFollow.top(6, 3, 2, 3, 10));      // or back up
    }

    @Test public void pagingKeepsOneLineOfOverlapAndStopsAtTheEnds() throws Exception {
        assertEquals(2, CaptionFollow.page(0, 3, 1, 10));
        assertEquals(7, CaptionFollow.page(6, 3, 1, 10));
        assertEquals(0, CaptionFollow.page(1, 3, -1, 10));
    }

    @Test public void moreCue() throws Exception {
        assertTrue(CaptionFollow.moreBelow(0, 3, 4));
        assertFalse(CaptionFollow.moreBelow(1, 3, 4));
        assertFalse(CaptionFollow.moreAbove(0));
        assertTrue(CaptionFollow.moreAbove(1));
    }

    /** The parser reports where each spoken sentence ends in the caption, so captions can follow the voice. */
    @Test public void parserReportsCaptionOffsets() throws Exception {
        final StringBuilder caption = new StringBuilder();
        final List<Integer> ends = new ArrayList<>();
        ReplyParser p = new ReplyParser(new ReplyParser.Sink() {
            @Override public void caption(String t) { caption.append(t); }
            @Override public void sentence(String s) { fail("2-arg form expected"); }
            @Override public void sentence(String s, int end) { ends.add(end); }
            @Override public void block(String tag, String body) { }
        });
        String text = "First one here. Second <card>{}</card>one now. Third.";
        for (int i = 0; i < text.length(); i += 3) p.feed(text.substring(i, Math.min(text.length(), i + 3)));
        p.finish();
        String c = caption.toString();
        // A card flushes speech, so "Second" is spoken on its own before the card.
        assertEquals(4, ends.size());
        assertEquals("First one here.", c.substring(0, ends.get(0)).trim());
        assertEquals("Second", c.substring(ends.get(0), ends.get(1)).trim());
        assertEquals("one now.", c.substring(ends.get(1), ends.get(2)).trim());
        assertEquals(c.length(), (int) ends.get(3));
    }
}
