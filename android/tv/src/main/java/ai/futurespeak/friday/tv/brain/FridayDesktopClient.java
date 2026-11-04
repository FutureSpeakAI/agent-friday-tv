package ai.futurespeak.friday.tv.brain;

import org.json.JSONArray;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.function.Supplier;

import ai.futurespeak.friday.tv.pair.DesktopLink;

/**
 * Friday desktop on the home network as the TV's brain (pairing slice T2).
 *
 * The desktop's TV gateway answers {@code POST /tv/v1/messages} in the same streamed format
 * as the Claude Messages API, so this brain is {@link ClaudeDirect}'s tested stream reader and
 * tool loop over a different {@link ClaudeDirect.Transport}: TLS 1.3 to the pinned key only,
 * authenticated by a device session ({@link DesktopLink}), with no key of the TV's.
 *
 * What does not change: a proposal from the desktop is a {@code tool_use} block, which goes to
 * the TV's own {@code ActionGate} through {@link Listener#onTool}, with the same fixed card,
 * PIN and receipts as one from Claude. Pairing gives the desktop no more power over the TV.
 * The TV tells the desktop who is watching (grown-up, family or kids, never a name); the PIN
 * and its hash never leave the TV.
 */
public final class FridayDesktopClient implements Brain {
    static final String PATH = "/tv/v1/messages";

    private final DesktopLink link;
    private final ClaudeDirect inner;

    /** @param principal "grownup", "family" or "kids", read from the helper at each turn */
    public FridayDesktopClient(DesktopLink link, Supplier<String> principal, int maxTokens) {
        this.link = link;
        this.inner = new ClaudeDirect(new ClaudeDirect.Transport() {
            @Override public boolean ready() { return link.paired(); }

            @Override public HttpURLConnection open(String path) throws IOException {
                if (!"/v1/messages".equals(path)) throw new IOException("not a desktop route");
                try {
                    HttpURLConnection c = link.connection("POST", PATH);
                    String p = principal == null ? null : principal.get();
                    c.setRequestProperty("X-Friday-Principal", p == null ? "family" : p);
                    return c;
                } catch (DesktopLink.LinkException e) {
                    link.recheck();
                    throw new IOException(e.getMessage(), e);
                }
            }
        }, "friday-desktop", maxTokens);
    }

    @Override public String displayName() { return "Friday desktop"; }

    @Override public boolean ready() { return link.paired(); }

    @Override
    public Turn send(String systemPrompt, List<Message> history, Listener listener) {
        return inner.send(systemPrompt, history, listener);
    }

    @Override
    public Turn send(String systemPrompt, List<Message> history, JSONArray tools, Listener listener) {
        return inner.send(systemPrompt, history, tools, new Listener() {
            @Override public void onText(String d) { listener.onText(d); }
            @Override public void onDone(String full, String stop) { listener.onDone(full, stop); }
            @Override public ToolReply onTool(String name, org.json.JSONObject input) { return listener.onTool(name, input); }
            @Override public void onError(BrainException e) {
                if (e.kind == BrainException.Kind.AUTH) link.dropSession();   // the next turn signs again
                link.recheck();
                listener.onError(e);
            }
        });
    }
}
