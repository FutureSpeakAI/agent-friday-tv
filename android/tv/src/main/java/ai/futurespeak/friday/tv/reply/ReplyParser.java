package ai.futurespeak.friday.tv.reply;

/**
 * Splits a streamed reply into what is said, what is shown, and the blocks the TV acts on.
 *
 * Claude is told to put rich content in {@code <card>{json}</card>} and channel proposals in
 * {@code <channel>{json}</channel>}. Text outside the blocks is captioned and spoken a sentence
 * at a time (so speech starts before the reply ends). Block bodies are never spoken or captioned.
 * Not thread-safe: feed it from one thread.
 */
public final class ReplyParser {
    public interface Sink {
        void caption(String text);
        void sentence(String speakable);
        /**
         * The same sentence, with where it ends in the caption text (characters of caption()
         * so far), so captions can follow the voice. Defaults to {@link #sentence(String)}.
         */
        default void sentence(String speakable, int captionEnd) { sentence(speakable); }
        void block(String tag, String body);
        /** A block started arriving (a channel can take a while: show that it's being built). */
        default void blockStarted(String tag) { }
        /** The reply ended inside a block: it was cut off and is not usable. */
        default void blockCutOff(String tag, int chars) { }
        /**
         * A report marker {@code <mark kind="…"/>} (kids slice 1, KM-E07): never captioned or
         * spoken. {@code kind} is whatever the reply said (or "" if it had none); the receiver
         * keeps only the known kinds.
         */
        default void mark(String kind) { }
    }

    static final String[] TAGS = { "card", "channel" };
    private static final String MARK = "<mark";
    private static final int MAX_MARK = 160;
    private static final java.util.regex.Pattern MARK_KIND = java.util.regex.Pattern.compile("kind\\s*=\\s*[\"']([A-Za-z_]{1,40})[\"']");
    private static final int MAX_BLOCK = 400_000;   // a channel's HTML is capped well below this
    private static final int LONG_SENTENCE = 240;

    private final Sink sink;
    private final StringBuilder pending = new StringBuilder();   // unparsed input
    private final StringBuilder speech = new StringBuilder();    // caption text not yet spoken
    private String inTag = null;
    private boolean overflow = false;
    private int captionChars = 0;

    public ReplyParser(Sink sink) { this.sink = sink; }

    public void feed(String delta) {
        if (delta == null || delta.isEmpty()) return;
        pending.append(delta);
        drain(false);
    }

    /** The block being received right now ("card"/"channel"), or null. */
    public String openBlock() { return inTag; }
    /** Characters of the open block received so far. */
    public int openBlockChars() { return inTag == null ? 0 : pending.length(); }

    /** End of the reply: flush everything. An unclosed block is reported and dropped. */
    public void finish() {
        drain(true);
        if (inTag != null) sink.blockCutOff(inTag, pending.length());
        if (inTag == null && pending.length() > 0) { text(pending.toString()); pending.setLength(0); }
        pending.setLength(0);
        inTag = null;
        flushSpeech(true);
    }

    private void drain(boolean end) {
        while (pending.length() > 0) {
            if (inTag != null) {
                String close = "</" + inTag + ">";
                int at = pending.indexOf(close);
                if (at < 0) {
                    if (pending.length() > MAX_BLOCK) { overflow = true; pending.setLength(0); }
                    return;
                }
                String body = pending.substring(0, at);
                pending.delete(0, at + close.length());
                String tag = inTag;
                inTag = null;
                if (!overflow) sink.block(tag, body.trim());
                overflow = false;
                continue;
            }
            int lt = pending.indexOf("<");
            if (lt < 0) { text(pending.toString()); pending.setLength(0); return; }
            if (lt > 0) { text(pending.substring(0, lt)); pending.delete(0, lt); }
            // pending starts with '<'
            if (startsWith(pending, MARK) && (pending.length() == MARK.length() || !Character.isLetterOrDigit(pending.charAt(MARK.length())))) {
                int gt = pending.indexOf(">");
                if (gt < 0) {
                    if (pending.length() <= MAX_MARK && !end) return;   // wait for the rest of the tag
                    text(pending.toString());                          // not a real marker: ordinary text
                    pending.setLength(0);
                    return;
                }
                java.util.regex.Matcher m = MARK_KIND.matcher(pending.substring(0, gt));
                sink.mark(m.find() ? m.group(1) : "");
                pending.delete(0, gt + 1);
                continue;
            }
            if (!end && MARK.startsWith(pending.toString())) return;   // "<ma…": wait for more input
            String opened = null;
            boolean maybe = false;
            for (String t : TAGS) {
                String open = "<" + t + ">";
                if (startsWith(pending, open)) { opened = t; break; }
                if (open.startsWith(pending.toString())) maybe = true;
            }
            if (opened != null) {
                flushSpeech(true);   // speak what came before the card first
                pending.delete(0, opened.length() + 2);
                inTag = opened;
                sink.blockStarted(opened);
                continue;
            }
            if (maybe && !end) return;   // wait for more input
            text("<");
            pending.deleteCharAt(0);
        }
    }

    private static boolean startsWith(StringBuilder sb, String s) {
        if (sb.length() < s.length()) return false;
        for (int i = 0; i < s.length(); i++) if (sb.charAt(i) != s.charAt(i)) return false;
        return true;
    }

    private void text(String s) {
        if (s.isEmpty()) return;
        sink.caption(s);
        captionChars += s.length();
        speech.append(s);
        flushSpeech(false);
    }

    /** Emits whole sentences from {@link #speech}; with {@code all}, the remainder too. */
    private void flushSpeech(boolean all) {
        while (true) {
            int cut = sentenceEnd(speech);
            if (cut < 0 && speech.length() > LONG_SENTENCE) cut = softBreak(speech);
            if (cut < 0) break;
            String raw = speech.substring(0, cut);
            speech.delete(0, cut);
            emit(raw, captionChars - speech.length());
        }
        if (all && speech.length() > 0) { String raw = speech.toString(); speech.setLength(0); emit(raw, captionChars); }
    }

    private void emit(String raw, int captionEnd) {
        String s = Speakable.clean(raw);
        if (!s.isEmpty()) sink.sentence(s, captionEnd);
    }

    /** Index just past the first sentence end that is followed by whitespace; -1 if none yet. */
    static int sentenceEnd(CharSequence s) {
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i), n = s.charAt(i + 1);
            if (c == '\n' && n == '\n') return i + 2;
            if ((c == '.' || c == '!' || c == '?' || c == ':') && Character.isWhitespace(n)) {
                // "e.g. " and "Dr. " style abbreviations: a single letter or a short capitalised word before the dot
                if (c == '.' && isAbbrev(s, i)) continue;
                return i + 1;
            }
            if (c == '\n' && i > 0 && isListLine(s, i)) return i + 1;
        }
        return -1;
    }

    private static boolean isAbbrev(CharSequence s, int dot) {
        int j = dot - 1;
        while (j >= 0 && Character.isLetter(s.charAt(j))) j--;
        int len = dot - 1 - j;
        if (len == 1) return true;                                  // "e.g." "U.S."
        String w = s.subSequence(j + 1, dot).toString();
        return w.equals("Dr") || w.equals("Mr") || w.equals("Mrs") || w.equals("Ms") || w.equals("St") || w.equals("vs");
    }

    /** A newline that ends a list item ("- milk\n") is a pause. */
    private static boolean isListLine(CharSequence s, int nl) {
        int start = nl - 1;
        while (start >= 0 && s.charAt(start) != '\n') start--;
        String line = s.subSequence(start + 1, nl).toString().trim();
        return line.startsWith("- ") || line.startsWith("* ") || line.matches("^\\d+[.)] .*");
    }

    private static int softBreak(CharSequence s) {
        for (int i = Math.min(s.length(), LONG_SENTENCE) - 1; i > 40; i--) {
            char c = s.charAt(i);
            if ((c == ',' || c == ';') && i + 1 < s.length() && s.charAt(i + 1) == ' ') return i + 1;
        }
        for (int i = Math.min(s.length(), LONG_SENTENCE) - 1; i > 40; i--) if (s.charAt(i) == ' ') return i + 1;
        return LONG_SENTENCE;
    }
}
