package ai.futurespeak.friday.tv.pair;

import java.net.InetAddress;
import java.util.List;

/**
 * Pairing, step by step, with no screen in it ({@link PairLayer} draws it). Runs on one
 * background thread; every change is reported to {@link View} with a fixed {@link Step} and,
 * where there is one, the six digits. The words are the layer's, never a brain's.
 *
 * SEARCHING → FOUND (one or more computers) or NONE
 * FOUND, choose(i) → CODE (show the digits; the owner presses on the PC) → PAIRED, REFUSED or EXPIRED
 * any network or protocol failure → FAILED, with the plain reason
 */
public final class PairFlow {
    public enum Step { SEARCHING, NONE, FOUND, CONNECTING, CODE, PAIRED, REFUSED, EXPIRED, FAILED }

    public interface View { void show(Step step, String detail, List<DesktopLink.Hint> found); }

    /** How long the TV waits for the press on the PC (the PC's own window is five minutes). */
    static final long WAIT_MS = 5 * 60_000L;
    static final long POLL_MS = 2_000L;

    public interface Discover { List<DesktopLink.Hint> find(); }
    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    private final DesktopLink link;
    private final View view;
    private final Discover discover;
    private final Sleeper sleeper;
    private final String tvName;
    private volatile boolean cancelled;
    private volatile List<DesktopLink.Hint> found;

    public PairFlow(DesktopLink link, View view, Discover discover, Sleeper sleeper, String tvName) {
        this.link = link;
        this.view = view;
        this.discover = discover;
        this.sleeper = sleeper;
        this.tvName = tvName;
    }

    /** The household's broadcast: every Friday computer on this network may answer. */
    public static Discover lan() {
        return () -> {
            try { return DesktopLink.discover(InetAddress.getByName("255.255.255.255"), DesktopLink.DISCOVERY_PORT, 2500); }
            catch (Exception e) { return java.util.Collections.emptyList(); }
        };
    }

    public void cancel() { cancelled = true; }

    /** Look for computers. Call from a background thread. */
    public void search() {
        view.show(Step.SEARCHING, null, null);
        found = discover.find();
        if (cancelled) return;
        if (found == null || found.isEmpty()) view.show(Step.NONE, null, null);
        else view.show(Step.FOUND, null, found);
    }

    /** Pair with the i-th computer found. Call from a background thread. */
    public void choose(int i) {
        List<DesktopLink.Hint> f = found;
        if (f == null || i < 0 || i >= f.size()) return;
        DesktopLink.Hint h = f.get(i);
        view.show(Step.CONNECTING, h.host, null);
        DesktopLink.Attempt a;
        try {
            a = link.startPairing(h.host, h.port, tvName);
        } catch (DesktopLink.LinkException e) {
            view.show(Step.FAILED, e.getMessage(), null);
            return;
        }
        view.show(Step.CODE, a.code, null);
        long waited = 0;
        while (!cancelled && waited < WAIT_MS) {
            try { sleeper.sleep(POLL_MS); } catch (InterruptedException e) { return; }
            waited += POLL_MS;
            if (cancelled) return;
            String state;
            try { state = link.pollPairing(a); }
            catch (DesktopLink.LinkException e) { continue; }          // a dropped poll is not an answer
            switch (state) {
                case "paired": view.show(Step.PAIRED, null, null); return;
                case "refused": view.show(Step.REFUSED, null, null); return;
                case "expired": case "unknown": view.show(Step.EXPIRED, null, null); return;
                default: break;                                          // still waiting for the press
            }
        }
        if (!cancelled) view.show(Step.EXPIRED, null, null);
    }
}
