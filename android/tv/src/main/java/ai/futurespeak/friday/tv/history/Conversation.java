package ai.futurespeak.friday.tv.history;

import ai.futurespeak.friday.tv.brain.Message;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The conversation kept on the TV. Only the most recent turns go to the brain as context
 * ({@link #context}); the stored log is capped too, so it cannot grow without bound.
 * "Forget" empties it ({@link #clear}).
 */
public final class Conversation {
    public static final int KEEP = 200;               // messages stored on the TV
    public static final int CONTEXT_MESSAGES = 24;    // sent with each question
    public static final int CONTEXT_CHARS = 24_000;

    private final List<Message> log = new ArrayList<>();

    public synchronized void add(Message m) {
        log.add(m);
        while (log.size() > KEEP) log.remove(0);
    }

    public synchronized void clear() { log.clear(); }

    public synchronized int size() { return log.size(); }

    public synchronized List<Message> all() { return Collections.unmodifiableList(new ArrayList<>(log)); }

    /** The newest messages that fit both caps, oldest first, starting with a user message. */
    public synchronized List<Message> context() {
        List<Message> out = new ArrayList<>();
        int chars = 0;
        for (int i = log.size() - 1; i >= 0 && out.size() < CONTEXT_MESSAGES; i--) {
            Message m = log.get(i);
            if (chars + m.text.length() > CONTEXT_CHARS && !out.isEmpty()) break;
            chars += m.text.length();
            out.add(0, m);
        }
        while (!out.isEmpty() && !Message.USER.equals(out.get(0).role)) out.remove(0);
        return out;
    }

    public synchronized String toJson() {
        JSONArray a = new JSONArray();
        try {
            for (Message m : log) a.put(new JSONObject().put("r", m.role).put("t", m.text).put("at", m.at));
        } catch (JSONException e) { throw new IllegalStateException(e); }
        return a.toString();
    }

    public static Conversation fromJson(String s) {
        Conversation c = new Conversation();
        if (s == null || s.isEmpty()) return c;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                try { c.add(new Message(o.getString("r"), o.optString("t", ""), o.optLong("at", 0))); }
                catch (IllegalArgumentException ignored) { }
            }
        } catch (JSONException e) { return new Conversation(); }
        return c;
    }
}
