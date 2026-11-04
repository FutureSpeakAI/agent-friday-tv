package ai.futurespeak.friday.tv.channels;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * RSS 2.0 / Atom / RSS 1.0 to a small, safe JSON shape that channel pages can use:
 * {"title","link","items":[{"title","link","summary","published"(epoch ms),"image","source"}]}.
 * Text only (HTML stripped, entities decoded); links and images https only. No DTDs or
 * external entities are ever resolved. Pure Java, so it is unit-tested on the JVM.
 */
public final class FeedParser {
    public static final int MAX_ITEMS = 40;
    public static final int MAX_SUMMARY = 420;

    private FeedParser() {}

    public static final class BadFeed extends Exception { public BadFeed(String m) { super(m); } }

    public static JSONObject parse(byte[] xml, String feedUrl) throws BadFeed {
        Document d;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setExpandEntityReferences(false);
            trySet(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySet(f, "http://xml.org/sax/features/external-general-entities", false);
            trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
            trySet(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((p, s) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
            d = b.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new BadFeed("not a news feed");
        }
        Element root = d.getDocumentElement();
        String rootName = local(root);
        try {
            JSONObject out = new JSONObject();
            JSONArray items = new JSONArray();
            String source = host(feedUrl);
            if ("feed".equals(rootName)) {                      // Atom
                out.put("title", clean(text(child(root, "title")), 120));
                out.put("link", https(atomLink(root), feedUrl));
                for (Element e : children(root, "entry")) {
                    if (items.length() >= MAX_ITEMS) break;
                    String sum = text(child(e, "summary"));
                    String content = text(child(e, "content"));
                    if (sum.isEmpty()) sum = content;
                    String img = media(e);
                    if (img.isEmpty()) img = firstImg(content.isEmpty() ? sum : content);
                    String date = text(child(e, "published"));
                    if (date.isEmpty()) date = text(child(e, "updated"));
                    add(items, text(child(e, "title")), atomLink(e), sum, date, img, source, feedUrl);
                }
            } else if ("rss".equals(rootName) || "RDF".equals(rootName)) {
                Element ch = child(root, "channel");
                if (ch == null) throw new BadFeed("not a news feed");
                out.put("title", clean(text(child(ch, "title")), 120));
                out.put("link", https(text(child(ch, "link")), feedUrl));
                Element itemsParent = "RDF".equals(rootName) ? root : ch;
                for (Element e : children(itemsParent, "item")) {
                    if (items.length() >= MAX_ITEMS) break;
                    String desc = text(child(e, "description"));
                    String encoded = text(childNs(e, "encoded"));
                    String img = media(e);
                    if (img.isEmpty()) img = firstImg(encoded.isEmpty() ? desc : encoded);
                    String date = text(child(e, "pubDate"));
                    if (date.isEmpty()) date = text(childNs(e, "date"));
                    add(items, text(child(e, "title")), text(child(e, "link")), desc, date, img, source, feedUrl);
                }
            } else {
                throw new BadFeed("not a news feed");
            }
            out.put("source", source);
            out.put("items", items);
            return out;
        } catch (JSONException e) {
            throw new BadFeed("not a news feed");
        }
    }

    private static void add(JSONArray items, String title, String link, String summary, String date, String img,
                            String source, String base) throws JSONException {
        String t = clean(title, 200);
        if (t.isEmpty()) return;
        JSONObject o = new JSONObject();
        o.put("title", t);
        o.put("link", https(link, base));
        String s = clean(summary, MAX_SUMMARY);
        if (s.equals(t)) s = "";
        o.put("summary", s);
        long when = date(date);
        if (when > 0) o.put("published", when);
        o.put("image", https(decode(img), base));
        o.put("source", source);
        items.put(o);
    }

    // ---- text ------------------------------------------------------------------
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern IMG = Pattern.compile("<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /** HTML to plain text: tags dropped, entities decoded, whitespace collapsed, capped at a word. */
    public static String clean(String html, int max) {
        if (html == null) return "";
        String s = TAG.matcher(html.replaceAll("(?i)<(br|/p|/div|/li)[^>]*>", " ")).replaceAll(" ");
        s = decode(s).replaceAll("[\\s\\u00a0]+", " ").trim();
        if (s.length() > max) {
            int cut = s.lastIndexOf(' ', max - 1);
            s = s.substring(0, cut > max / 2 ? cut : max - 1).trim() + "…";
        }
        return s;
    }

    static String decode(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            String e = m.group(1);
            String r = null;
            try {
                if (e.startsWith("#x")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
            } catch (Exception ignored) { }
            if (r == null) switch (e) {
                case "amp": r = "&"; break; case "lt": r = "<"; break; case "gt": r = ">"; break;
                case "quot": r = "\""; break; case "apos": r = "'"; break; case "nbsp": r = " "; break;
                case "hellip": r = "…"; break; case "mdash": r = "—"; break; case "ndash": r = "–"; break;
                case "rsquo": r = "’"; break; case "lsquo": r = "‘"; break; case "rdquo": r = "”"; break; case "ldquo": r = "“"; break;
                default: r = m.group(0);
            }
            out.append(r);
            last = m.end();
        }
        out.append(s.substring(last));
        return out.toString();
    }

    static String firstImg(String html) {
        if (html == null) return "";
        Matcher m = IMG.matcher(html);
        return m.find() ? m.group(1) : "";
    }

    /** Absolute https URL, or "". */
    static String https(String u, String base) {
        if (u == null) return "";
        u = u.trim();
        if (u.isEmpty()) return "";
        try {
            URI r = base == null ? new URI(u) : new URI(base).resolve(u.replace(" ", "%20"));
            if (!"https".equalsIgnoreCase(r.getScheme()) || r.getHost() == null || r.getRawUserInfo() != null) return "";
            return r.toString();
        } catch (Exception e) { return ""; }
    }

    static String host(String u) {
        try { String h = new URI(u).getHost(); return h == null ? "" : h.toLowerCase(Locale.ROOT); } catch (Exception e) { return ""; }
    }

    // ---- dates -------------------------------------------------------------------
    private static final String[] FORMATS = {
            "EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz", "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd"
    };

    static long date(String s) {
        if (s == null || (s = s.trim()).isEmpty()) return 0;
        for (String f : FORMATS) {
            SimpleDateFormat df = new SimpleDateFormat(f, Locale.US);
            df.setTimeZone(TimeZone.getTimeZone("UTC"));
            df.setLenient(false);
            ParsePosition p = new ParsePosition(0);
            Date d = df.parse(s, p);
            if (d != null && p.getIndex() >= s.length() - 1) return d.getTime();
        }
        return 0;
    }

    // ---- DOM helpers ----------------------------------------------------------------
    private static void trySet(DocumentBuilderFactory f, String feature, boolean v) {
        try { f.setFeature(feature, v); } catch (Exception ignored) { }
    }

    private static String local(Node n) {
        String l = n.getLocalName();
        if (l == null) { l = n.getNodeName(); int c = l.indexOf(':'); if (c >= 0) l = l.substring(c + 1); }
        return l;
    }

    /** First child element with this local name and no namespace prefix of its own (title, link…). */
    private static Element child(Element p, String name) {
        if (p == null) return null;
        for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && name.equals(local(n)) && !n.getNodeName().contains(":")) return (Element) n;
        return null;
    }

    /** First child element with this local name in any namespace (content:encoded, dc:date). */
    private static Element childNs(Element p, String name) {
        if (p == null) return null;
        for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && name.equals(local(n))) return (Element) n;
        return null;
    }

    private static java.util.List<Element> children(Element p, String name) {
        java.util.List<Element> out = new java.util.ArrayList<>();
        for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && name.equals(local(n))) out.add((Element) n);
        return out;
    }

    private static String text(Element e) { return e == null ? "" : e.getTextContent().trim(); }

    private static String atomLink(Element e) {
        String alt = "", any = "";
        for (Element l : children(e, "link")) {
            String rel = l.getAttribute("rel");
            String href = l.getAttribute("href");
            if (href.isEmpty()) href = l.getTextContent().trim();
            if (any.isEmpty()) any = href;
            if (rel.isEmpty() || "alternate".equals(rel)) { alt = href; break; }
        }
        return alt.isEmpty() ? any : alt;
    }

    /** media:content / media:thumbnail / an image enclosure, largest width first. */
    private static String media(Element e) {
        String best = ""; int bestW = -1;
        NodeList all = e.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element m = (Element) all.item(i);
            String n = local(m);
            String url = m.getAttribute("url");
            if (url.isEmpty()) continue;
            boolean image;
            if ("enclosure".equals(n)) image = m.getAttribute("type").startsWith("image/");
            else if ("content".equals(n) || "thumbnail".equals(n)) {
                String type = m.getAttribute("type"), medium = m.getAttribute("medium");
                image = type.isEmpty() ? (medium.isEmpty() || "image".equals(medium)) : type.startsWith("image/");
            } else continue;
            if (!image) continue;
            int w = 0;
            try { w = Integer.parseInt(m.getAttribute("width")); } catch (Exception ignored) { }
            if (w > bestW) { best = url; bestW = w; }
        }
        return best;
    }
}
