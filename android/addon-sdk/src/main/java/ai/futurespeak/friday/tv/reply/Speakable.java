package ai.futurespeak.friday.tv.reply;

import java.util.regex.Pattern;

/**
 * Text as it should be said. Markdown marks, links and symbols are dropped; the ™ and ®
 * marks are never spoken (Friday's brand rule: audio says "Agent Friday", never "T M").
 */
public final class Speakable {
    private Speakable() {}

    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)\\]\\((?:[^)]+)\\)");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern CODE = Pattern.compile("`+([^`]*)`+");
    private static final Pattern EMPH = Pattern.compile("(\\*\\*|__|\\*|_|~~)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern LINE_MARK = Pattern.compile("(?m)^\\s*(#{1,6}\\s+|>\\s?|[-*•]\\s+|\\d+[.)]\\s+)");
    private static final Pattern TABLE_PIPE = Pattern.compile("\\s*\\|\\s*");

    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw;
        s = MD_LINK.matcher(s).replaceAll("$1");
        s = URL.matcher(s).replaceAll("a link");
        s = CODE.matcher(s).replaceAll("$1");
        s = EMPH.matcher(s).replaceAll("$2");
        s = LINE_MARK.matcher(s).replaceAll("");
        s = TABLE_PIPE.matcher(s).replaceAll(", ");
        s = s.replace("™", "").replace("®", "").replace("©", "");
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.OTHER_SYMBOL || type == Character.SURROGATE || cp == 0xFE0F || cp == 0x200D) continue; // emoji
            if (cp == '*' || cp == '#' || cp == '`' || cp == '~' || cp == '_') { out.append(' '); continue; }
            out.appendCodePoint(cp);
        }
        return out.toString().replaceAll("\\s+", " ").replaceAll(" ([,.!?;:])", "$1").trim();
    }
}
