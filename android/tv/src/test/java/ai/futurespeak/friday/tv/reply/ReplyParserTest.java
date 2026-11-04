package ai.futurespeak.friday.tv.reply;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ReplyParserTest {
    static final class Rec implements ReplyParser.Sink {
        final StringBuilder caption = new StringBuilder();
        final List<String> sentences = new ArrayList<>();
        final List<String> blocks = new ArrayList<>();
        @Override public void caption(String t) { caption.append(t); }
        @Override public void sentence(String s) { sentences.add(s); }
        @Override public void block(String tag, String body) { blocks.add(tag + ":" + body); }
    }

    /** Feeds the text one character at a time: the worst case for tags split across deltas. */
    static Rec feedChars(String s) {
        Rec r = new Rec();
        ReplyParser p = new ReplyParser(r);
        for (int i = 0; i < s.length(); i++) p.feed(s.substring(i, i + 1));
        p.finish();
        return r;
    }

    @Test public void cardsAreCutOutOfSpeechAndCaptionsEvenWhenSplit() throws Exception {
        Rec r = feedChars("Here is your list. <card>{\"type\":\"list\",\"items\":[\"milk\"]}</card> Anything else?");
        assertEquals(1, r.blocks.size());
        assertEquals("card:{\"type\":\"list\",\"items\":[\"milk\"]}", r.blocks.get(0));
        assertFalse(r.caption.toString().contains("milk"));
        assertFalse(r.caption.toString().contains("<card"));
        assertEquals(2, r.sentences.size());
        assertEquals("Here is your list.", r.sentences.get(0));
        assertEquals("Anything else?", r.sentences.get(1));
    }

    @Test public void channelBlocksAreRecognised() throws Exception {
        Rec r = feedChars("Made it.<channel>{\"title\":\"Trivia\"}</channel>");
        assertEquals("channel:{\"title\":\"Trivia\"}", r.blocks.get(0));
    }

    @Test public void speaksSentenceBySentenceAsTheyComplete() throws Exception {
        Rec r = new Rec();
        ReplyParser p = new ReplyParser(r);
        p.feed("The sky is blue. It sc");
        assertEquals(1, r.sentences.size());          // the first sentence goes out before the reply ends
        p.feed("atters light.");
        p.finish();
        assertEquals("It scatters light.", r.sentences.get(1));
    }

    @Test public void ordinaryAngleBracketsStayText() throws Exception {
        Rec r = feedChars("3 < 5 and <b>bold</b> stay.");
        assertTrue(r.blocks.isEmpty());
        assertEquals("3 < 5 and <b>bold</b> stay.", r.caption.toString());
    }

    @Test public void unclosedBlockIsDroppedNotSpoken() throws Exception {
        Rec r = feedChars("Okay. <card>{\"type\":\"text\",\"body\":\"secret");
        assertTrue(r.blocks.isEmpty());
        for (String s : r.sentences) assertFalse(s.contains("secret"));
    }

    @Test public void abbreviationsDoNotSplit() throws Exception {
        Rec r = feedChars("Dr. Smith met e.g. friends. Then left.");
        assertEquals("Dr. Smith met e.g. friends.", r.sentences.get(0));
    }

    @Test public void veryLongSentencesAreBroken() throws Exception {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 60; i++) s.append("word, ");
        Rec r = feedChars(s.toString());
        assertTrue(r.sentences.size() >= 2);
        for (String x : r.sentences) assertTrue(x.length() <= 245);
    }
}
