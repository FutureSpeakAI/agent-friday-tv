package ai.futurespeak.friday.tv.channels;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.List;

public class ChannelRenameTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void renameKeepsEverythingElse() throws Exception {
        ChannelStore s = new ChannelStore(tmp.getRoot());
        ChannelSpec saved = s.save(ChannelSpec.parseProposal("{\"title\":\"News\",\"html\":\"<p>x</p>\",\"network\":[\"www.npr.org\"]}"), List.of("www.npr.org"));
        ChannelSpec r = s.rename(saved.id, "  Riverbend\u0007 News  ");
        assertEquals("Riverbend News", r.title);
        ChannelSpec back = s.get(saved.id);
        assertEquals("Riverbend News", back.title);
        assertEquals(saved.html, back.html);
        assertEquals(List.of("www.npr.org"), back.grantedHosts);
        assertEquals(saved.createdAt, back.createdAt);
        assertNull(s.rename(saved.id, "   "));
        assertNull(s.rename("../../etc", "x"));
        assertEquals(40, s.rename(saved.id, "x".repeat(60)).title.length());
        assertEquals(1, s.list().size());
        assertTrue(Collections.emptyList().isEmpty());
    }
}
