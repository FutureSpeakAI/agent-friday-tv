package ai.futurespeak.friday.tv.brain;

/** One message of the conversation, as sent to a brain. */
public final class Message {
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public final String role;
    public final String text;
    public final long at;

    public Message(String role, String text, long at) {
        if (!USER.equals(role) && !ASSISTANT.equals(role)) throw new IllegalArgumentException("role");
        this.role = role;
        this.text = text == null ? "" : text;
        this.at = at;
    }

    public static Message user(String text) { return new Message(USER, text, System.currentTimeMillis()); }
    public static Message assistant(String text) { return new Message(ASSISTANT, text, System.currentTimeMillis()); }
}
