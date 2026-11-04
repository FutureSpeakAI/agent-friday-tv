package ai.futurespeak.friday.tv.audio;

/**
 * The offline-English pack, offered once (bug of 2026-11-02: Friday asked Google's speech app for
 * the pack every time she started or resumed; its download screen covered Friday, closing it
 * resumed Friday, and Friday asked again, a loop the person couldn't get out of).
 *
 * Rules, all unit-tested:
 *  - Starting or resuming Friday never asks for the pack and never shows anything.
 *  - The offer is Friday's own card, shown once, on a Talk press, only while recognition isn't
 *    confirmed offline. Any answer (Download, Online for now, or Back) ends the offering.
 *  - Google's download screen opens only from an explicit choice (the card's Download, or
 *    Settings → Listening → Download), and never again while a request is in flight.
 *  - Back on the card means "online for now": Friday listens with Google's online recognition
 *    (still preferring offline, so the pack is used as soon as it's on the TV).
 *
 * Google's speech app on this TV reports neither its offline languages nor download progress,
 * so "progress" is what Friday knows: when the download was requested.
 */
public final class PackFlow {
    public enum State { NEW, DECLINED, REQUESTED }

    /** A request this recent is treated as still downloading: no second request. */
    public static final long IN_FLIGHT_MS = 60 * 60_000L;

    public interface Store {
        State packState();
        long packRequestedAt();
        void setPack(State s, long requestedAt);
    }
    public interface Clock { long now(); }

    private final Store store;
    private final Clock clock;
    private final Runnable openDownload;   // opens Google's download screen
    private int requests = 0;

    public PackFlow(Store store, Clock clock, Runnable openDownload) {
        this.store = store; this.clock = clock; this.openDownload = openDownload;
    }

    /** Friday started or came back to the front: nothing is asked or shown, ever. */
    public void onResume() { }

    /** Talk was pressed. True: show the offer card (once), instead of listening this time. */
    public boolean offerOnTalk(OfflineSpeech.Route route) {
        return route == OfflineSpeech.Route.CLOUD && store.packState() == State.NEW;
    }

    /** The card's Download, or Settings' Download. False when a request is still in flight (nothing opens). */
    public boolean requestDownload() {
        long now = clock.now();
        if (inFlight(now)) return false;
        store.setPack(State.REQUESTED, now);
        requests++;
        openDownload.run();
        return true;
    }

    /** "Online for now", or Back on the card. */
    public void decline() {
        if (store.packState() == State.NEW) store.setPack(State.DECLINED, 0);
    }

    public boolean inFlight(long now) {
        return store.packState() == State.REQUESTED && now - store.packRequestedAt() < IN_FLIGHT_MS;
    }

    /** What Friday can honestly say about the download. */
    public String progress() {
        long now = clock.now();
        switch (store.packState()) {
            case REQUESTED: {
                long min = Math.max(0, (now - store.packRequestedAt()) / 60_000);
                String ago = min == 0 ? "just now" : min + " min ago";
                return inFlight(now)
                        ? "Requested " + ago + " in Google's speech app. It doesn't report progress; it's usually ready within a few minutes."
                        : "Requested " + ago + ". If Friday still uses online speech, download it again.";
            }
            case DECLINED: return "Not downloaded. Friday uses Google's online speech recognition.";
            default: return "Not offered yet.";
        }
    }

    int requests() { return requests; }
}
