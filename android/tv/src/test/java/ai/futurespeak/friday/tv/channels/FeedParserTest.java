package ai.futurespeak.friday.tv.channels;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class FeedParserTest {
    private static JSONObject parse(String xml, String url) throws Exception {
        return FeedParser.parse(xml.getBytes(StandardCharsets.UTF_8), url);
    }

    @Test public void rssWithMediaAndHtmlSummary() throws Exception {
        JSONObject f = parse("<?xml version=\"1.0\"?><rss version=\"2.0\" xmlns:media=\"http://search.yahoo.com/mrss/\">"
                + "<channel><title>Channel 9 Riverton</title><link>https://www.example-news.com/</link>"
                + "<item><title>Council votes &amp; more</title><link>https://www.example-news.com/a</link>"
                + "<description><![CDATA[<p>The <b>city</b> voted&nbsp;today.</p>]]></description>"
                + "<pubDate>Mon, 02 Nov 2026 15:04:05 +0000</pubDate>"
                + "<media:content url=\"https://img.example-news.com/small.jpg\" width=\"100\" medium=\"image\"/>"
                + "<media:content url=\"https://img.example-news.com/big.jpg\" width=\"1200\" medium=\"image\"/></item>"
                + "</channel></rss>", "https://www.example-news.com/local/feed/");
        assertEquals("Channel 9 Riverton", f.getString("title"));
        assertEquals("www.example-news.com", f.getString("source"));
        JSONObject it = f.getJSONArray("items").getJSONObject(0);
        assertEquals("Council votes & more", it.getString("title"));
        assertEquals("The city voted today.", it.getString("summary"));
        assertEquals("https://img.example-news.com/big.jpg", it.getString("image"));   // the widest picture
        assertEquals(1793631845000L, it.getLong("published"));
    }

    @Test public void atomEntries() throws Exception {
        JSONObject f = parse("<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Ars</title>"
                + "<link rel=\"self\" href=\"https://x/feed\"/><link href=\"https://arstechnica.com/\"/>"
                + "<entry><title>Chips</title><link rel=\"alternate\" href=\"https://arstechnica.com/c\"/>"
                + "<updated>2026-11-02T10:00:00Z</updated><content type=\"html\">&lt;img src=\"https://cdn.arstechnica.net/p.jpg\"&gt; Faster chips.</content></entry></feed>",
                "https://feeds.arstechnica.com/arstechnica/index");
        JSONObject it = f.getJSONArray("items").getJSONObject(0);
        assertEquals("https://arstechnica.com/c", it.getString("link"));
        assertEquals("https://cdn.arstechnica.net/p.jpg", it.getString("image"));
        assertEquals("Faster chips.", it.getString("summary"));
        assertTrue(it.getLong("published") > 0);
    }

    @Test public void dropsUnsafeLinksAndImages() throws Exception {
        JSONObject f = parse("<rss><channel><title>t</title><item><title>x</title><link>javascript:alert(1)</link>"
                + "<enclosure url=\"http://plain.example/i.jpg\" type=\"image/jpeg\"/></item></channel></rss>", "https://a.example/feed");
        JSONObject it = f.getJSONArray("items").getJSONObject(0);
        assertEquals("", it.getString("link"));
        assertEquals("", it.getString("image"));
    }

    @Test(expected = FeedParser.BadFeed.class)
    public void refusesDoctypeEntities() throws Exception {
        // XXE: an external entity must never be read.
        parse("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<rss><channel><title>&x;</title><item><title>a</title></item></channel></rss>", "https://a.example/f");
    }

    @Test(expected = FeedParser.BadFeed.class)
    public void htmlPageIsNotAFeed() throws Exception {
        parse("<html><body>hello</body></html>", "https://a.example/");
    }

    @Test public void capsItemsAndSummary() throws Exception {
        StringBuilder sb = new StringBuilder("<rss><channel><title>t</title>");
        String longText = "word ".repeat(300);
        for (int i = 0; i < 60; i++) sb.append("<item><title>n").append(i).append("</title><description>").append(longText).append("</description></item>");
        sb.append("</channel></rss>");
        JSONArray items = parse(sb.toString(), "https://a.example/f").getJSONArray("items");
        assertEquals(FeedParser.MAX_ITEMS, items.length());
        String s = items.getJSONObject(0).getString("summary");
        assertTrue(s.length() <= FeedParser.MAX_SUMMARY + 1);
        assertTrue(s.endsWith("…"));
    }
}
