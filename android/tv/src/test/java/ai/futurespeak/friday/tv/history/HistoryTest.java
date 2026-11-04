package ai.futurespeak.friday.tv.history;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import ai.futurespeak.friday.tv.brain.Message;

import static org.junit.Assert.*;

public class HistoryTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void contextIsCappedAndStartsWithUser() throws Exception {
        Conversation c = new Conversation();
        for (int i = 0; i < 100; i++) { c.add(Message.user("q" + i)); c.add(Message.assistant("a" + i)); }
        List<Message> ctx = c.context();
        assertTrue(ctx.size() <= Conversation.CONTEXT_MESSAGES);
        assertEquals("user", ctx.get(0).role);
        assertEquals("a99", ctx.get(ctx.size() - 1).text);
        assertEquals(Conversation.KEEP, c.size());
    }

    @Test public void contextRespectsCharBudget() throws Exception {
        Conversation c = new Conversation();
        String big = new String(new char[10_000]).replace('\0', 'x');
        for (int i = 0; i < 6; i++) { c.add(Message.user(big)); c.add(Message.assistant("ok")); }
        int chars = 0;
        for (Message m : c.context()) chars += m.text.length();
        assertTrue(chars <= Conversation.CONTEXT_CHARS + 10_000);
    }

    @Test public void storeRoundTripsAndForgets() throws Exception {
        HistoryStore s = new HistoryStore(tmp.getRoot());
        Conversation c = new Conversation();
        c.add(Message.user("what is a comet?"));
        c.add(Message.assistant("A ball of ice and dust."));
        s.save(c);
        Conversation back = new HistoryStore(tmp.getRoot()).load();
        assertEquals(2, back.size());
        assertEquals("A ball of ice and dust.", back.all().get(1).text);
        s.forget();
        assertFalse(new File(tmp.getRoot(), "history.json").exists());
        assertEquals(0, new HistoryStore(tmp.getRoot()).load().size());
    }

    @Test public void corruptFileGivesEmptyConversation() throws Exception {
        java.nio.file.Files.write(new File(tmp.getRoot(), "history.json").toPath(), "{nope".getBytes());
        assertEquals(0, new HistoryStore(tmp.getRoot()).load().size());
    }
}
