package ai.futurespeak.friday.tv.reply;

import org.junit.Test;

import static org.junit.Assert.*;

public class SpeakableTest {
    @Test public void trademarkIsNeverSpoken() throws Exception {
        assertEquals("Welcome to Agent Friday by FutureSpeak.AI.", Speakable.clean("Welcome to Agent Friday™ by FutureSpeak.AI™."));
    }

    @Test public void markdownAndLinksAreDropped() throws Exception {
        assertEquals("Read this guide, it is good.", Speakable.clean("Read **[this guide](https://x.example/a)**, it is _good_."));
        assertEquals("See a link now.", Speakable.clean("See https://example.com/page now."));
        assertEquals("Step one", Speakable.clean("## Step one"));
        assertEquals("milk", Speakable.clean("- milk"));
    }

    @Test public void emojiAreSilent() throws Exception {
        assertEquals("Great job!", Speakable.clean("Great job! 🎉🚀"));
    }
}
