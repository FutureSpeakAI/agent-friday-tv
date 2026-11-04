package ai.futurespeak.friday.tv.brain;

/**
 * Server-sent events, one line at a time (text/event-stream). A blank line ends an event.
 * Multi-line data fields are joined with "\n"; comment lines (":") are ignored.
 */
public final class SseParser {
    public static final class Event {
        public final String name;
        public final String data;
        Event(String name, String data) { this.name = name; this.data = data; }
    }

    private String name = null;
    private StringBuilder data = null;

    /** Feeds one line (without its line ending). Returns a finished event, or null. */
    public Event line(String line) {
        if (line == null) return null;
        if (line.isEmpty()) {
            if (data == null && name == null) return null;
            Event e = new Event(name == null ? "message" : name, data == null ? "" : data.toString());
            name = null; data = null;
            return e;
        }
        if (line.startsWith(":")) return null;
        int c = line.indexOf(':');
        String field = c < 0 ? line : line.substring(0, c);
        String value = c < 0 ? "" : line.substring(c + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        if ("event".equals(field)) name = value;
        else if ("data".equals(field)) {
            if (data == null) data = new StringBuilder(value);
            else data.append('\n').append(value);
        }
        return null;
    }
}
