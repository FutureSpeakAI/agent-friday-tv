package ai.futurespeak.friday.tv.channels;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ChannelsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final String WEATHER = "{\"title\":\"Weather board\",\"emoji\":\"🌤️\",\"description\":\"Today and tomorrow\","
            + "\"html\":\"<!doctype html><html><head></head><body>hi</body></html>\",\"network\":[\"https://api.open-meteo.com/v1\"]}";

    @Test public void proposalIsCheckedAndHostsNormalised() throws Exception {
        ChannelSpec c = ChannelSpec.parseProposal(WEATHER);
        assertEquals("Weather board", c.title);
        assertEquals(Collections.singletonList("api.open-meteo.com"), c.requestedHosts);
        assertTrue("nothing is granted by a proposal", c.grantedHosts.isEmpty());
    }

    @Test public void badProposalsAreRejected() throws Exception {
        for (String bad : new String[]{
                "{\"title\":\"x\",\"html\":\"<p>\",\"network\":[\"*.example.com\"]}",
                "{\"title\":\"x\",\"html\":\"<p>\",\"network\":[\"192.168.1.1\"]}",
                "{\"title\":\"x\",\"html\":\"<p>\",\"network\":[\"router.lan\"]}",
                "{\"title\":\"x\",\"html\":\"<p>\",\"network\":[\"a.com\",\"b.com\",\"c.com\",\"d.com\",\"e.com\",\"f.com\",\"g.com\",\"h.com\",\"i.com\"]}",
                "{\"title\":\"\",\"html\":\"<p>\"}",
                "{\"title\":\"x\",\"html\":\"\"}",
                "[1,2]"}) {
            try { ChannelSpec.parseProposal(bad); fail("accepted: " + bad); } catch (ChannelSpec.Rejected expected) { }
        }
    }

    @Test public void grantsCanOnlyNarrowTheRequest() throws Exception {
        ChannelSpec c = ChannelSpec.parseProposal(WEATHER).withId("abcdefabcdef", Arrays.asList("api.open-meteo.com", "evil.example.com"));
        assertEquals(Collections.singletonList("api.open-meteo.com"), c.grantedHosts);
    }

    @Test public void storeSavesListsAndDeletes() throws Exception {
        ChannelStore s = new ChannelStore(tmp.getRoot());
        ChannelSpec saved = s.save(ChannelSpec.parseProposal(WEATHER), Collections.singletonList("api.open-meteo.com"));
        List<ChannelSpec> all = new ChannelStore(tmp.getRoot()).list();
        assertEquals(1, all.size());
        assertEquals(saved.id, all.get(0).id);
        assertEquals(Collections.singletonList("api.open-meteo.com"), all.get(0).grantedHosts);
        assertFalse("ids are checked before touching files", s.delete("../../etc"));
        assertTrue(s.delete(saved.id));
        assertTrue(s.list().isEmpty());
    }

    @Test public void netPolicy() throws Exception {
        List<String> g = Collections.singletonList("api.open-meteo.com");
        assertEquals(NetPolicy.Decision.FETCH, NetPolicy.decide("https://api.open-meteo.com/v1/forecast?x=1", "GET", g));
        assertEquals(NetPolicy.Decision.BLOCK, NetPolicy.decide("https://api.open-meteo.com/v1", "POST", g));
        assertEquals(NetPolicy.Decision.BLOCK, NetPolicy.decide("http://api.open-meteo.com/v1", "GET", g));
        assertEquals(NetPolicy.Decision.BLOCK, NetPolicy.decide("https://evil.example.com/", "GET", g));
        // A sibling of a granted host (same organisation) is allowed for pictures only, never data (SandboxView enforces image/*).
        assertEquals(NetPolicy.Decision.FETCH_IMAGE, NetPolicy.decide("https://sub.api.open-meteo.com/", "GET", g));
        assertEquals(NetPolicy.Decision.BLOCK, NetPolicy.decide("https://api.open-meteo.com:8080/", "GET", g));
        assertEquals(NetPolicy.Decision.BLOCK, NetPolicy.decide("https://api.anthropic.com/v1/messages", "GET", g));
        assertEquals(NetPolicy.Decision.BLOCK, NetPolicy.decide("intent://scan#Intent;end", "GET", g));
        assertEquals(NetPolicy.Decision.BLOCK, NetPolicy.decide("file:///data/data/x", "GET", g));
        assertEquals(NetPolicy.Decision.LOCAL, NetPolicy.decide("https://ch-abc.friday.local/", "GET", g));
        assertEquals(NetPolicy.Decision.BLOCK, NetPolicy.decide("https://api.open-meteo.com/", "GET", Collections.emptyList()));
    }

    @Test public void cspAllowsOnlyGrantedHosts() throws Exception {
        String none = NetPolicy.csp(Collections.emptyList());
        // No internet without approval: the only thing an unapproved page may call is Friday's voice on the TV itself.
        assertTrue(none.contains("connect-src https://voice.friday.local;"));
        assertTrue(none.contains("default-src 'none'"));
        assertTrue(none.contains("frame-src 'none'"));
        String one = NetPolicy.csp(Collections.singletonList("api.open-meteo.com"));
        assertTrue(one.contains("connect-src https://voice.friday.local https://feeds.friday.local https://api.open-meteo.com;"));
        String connect = one.substring(one.indexOf("connect-src"), one.indexOf(';', one.indexOf("connect-src")));
        assertFalse("never a wildcard for data", connect.contains("*"));
    }

    @Test public void privateAddressesAreRecognised() throws Exception {
        for (String a : new String[]{"127.0.0.1", "10.1.2.3", "192.168.1.1", "172.16.5.4", "169.254.1.1", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:192.168.1.1"})
            assertTrue(a, NetPolicy.isPrivate(InetAddress.getByName(a)));
        for (String a : new String[]{"8.8.8.8", "1.1.1.1", "2606:4700::1111"})
            assertFalse(a, NetPolicy.isPrivate(InetAddress.getByName(a)));
    }
}
