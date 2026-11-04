package ai.futurespeak.friday.tv.addon;

/**
 * The Friday TV add-on contract, version 1. Every name an add-on and the core app share lives
 * here; docs/ADDON-CONTRACT.md explains each one. Version 1 is stable: later versions only add.
 *
 * An add-on is its own APK. It never holds an API key, never reads the core's storage, and asks
 * the core for everything it shares: the household's state (kids mode, reduce motion, place),
 * Friday's voice, a question to Friday, a Studio hand-off, an approval, and a receipt.
 */
public final class Contract {
    private Contract() {}

    public static final int VERSION = 1;

    /** The core app. */
    public static final String CORE_PACKAGE = "ai.futurespeak.friday.tv";

    // ---- How an add-on declares itself (in its manifest) -----------------------------------------

    /** The add-on's one entry activity handles this action (exported; DEFAULT category). */
    public static final String ACTION_OPEN = "ai.futurespeak.friday.tv.addon.OPEN";
    /** Meta-data on that activity: an XML resource with a {@code <friday-addon>} element (see {@link AddonSpec}). */
    public static final String META_DECLARATION = "ai.futurespeak.friday.tv.addon";
    /** Optional: the words that opened the add-on, when Friday routed a spoken request to it. */
    public static final String EXTRA_UTTERANCE = "ai.futurespeak.friday.tv.addon.UTTERANCE";
    /** Optional: extras the add-on's own {@code match} returned, handed back on open. */
    public static final String EXTRA_MATCH = "ai.futurespeak.friday.tv.addon.MATCH";

    /**
     * The add-on's own provider, authority {@code <applicationId>.friday} (exported). The core
     * calls {@link #M_MATCH} on it to ask "is this spoken request yours?". Extend
     * {@link FridayAddonProvider}, which answers only the core app.
     */
    public static final String ADDON_AUTHORITY_SUFFIX = ".friday";
    public static final String M_MATCH = "match";
    public static final String K_MATCHED = "matched";

    // ---- What the core offers (provider ai.futurespeak.friday.tv.addon) -------------------------

    public static final String CORE_AUTHORITY = "ai.futurespeak.friday.tv.addon";

    /** Anyone: {@link #K_VERSION}. Lets an add-on tell "core too old" from "not enabled". */
    public static final String M_CONTRACT = "contract";
    /** Enabled add-ons: kids mode, grown-up unlocked, reduce motion, the place for weather. */
    public static final String M_STATE = "state";
    /**
     * Enabled add-ons: which service does each job with the keys on this TV, so the add-on can
     * pick its fallback (the TV's voice, still pictures). {@link #K_PLAN}: text, voice, sound,
     * music, images and video, each a provider name or "tv" / "stills" / "none". Never a key.
     */
    public static final String M_CAPABILITIES = "capabilities";
    /** Enabled add-ons: Friday's voice for a short text, as 22.05 kHz 16-bit mono PCM on a pipe, or nothing. */
    public static final String M_VOICE = "voice";
    /** Enabled add-ons: open Friday with a question already asked (arg = the question, {@link #K_LABEL} = what the screen shows). */
    public static final String M_ASK = "ask";
    /** Enabled add-ons: hand a source to Friday Studio; Studio's own card asks the person before anything is made. */
    public static final String M_STUDIO = "studio";
    /** Enabled add-ons: add a line to Friday's receipts (arg = what happened, in plain words). */
    public static final String M_RECEIPT = "receipt";

    /** Enabled add-ons: start this for a result to ask the person yes or no on Friday's own approval card. */
    public static final String ACTION_APPROVE = "ai.futurespeak.friday.tv.addon.APPROVE";
    public static final String EXTRA_QUESTION = "question";
    public static final String EXTRA_DETAIL = "detail";

    // Bundle keys
    public static final String K_VERSION = "version";
    public static final String K_KIDS = "kids";
    public static final String K_GROWN_UP = "grown_up";
    public static final String K_MOTION = "motion";          // "auto" | "on" | "off"
    public static final String K_PLACE_NAME = "place_name";
    public static final String K_PLACE_LAT = "place_lat";    // absent when no place is set
    public static final String K_PLACE_LON = "place_lon";
    public static final String K_PLACE_F = "place_f";        // true = Fahrenheit
    public static final String K_PCM = "pcm";                // ParcelFileDescriptor
    public static final String K_LABEL = "label";
    public static final String K_TITLE = "title";
    public static final String K_SOURCE = "source";
    public static final String K_LINK = "link";
    public static final String K_TEXT = "text";
    public static final String K_RELATED = "related";        // JSON array of {title, source, link, summary}
    public static final String K_OK = "ok";
    public static final String K_WHY = "why";
    public static final String K_PLAN = "plan";              // Bundle of job -> provider (see M_CAPABILITIES)

    /** Longest text {@link #M_VOICE} speaks in one call. */
    public static final int MAX_VOICE_CHARS = 1500;
    /** Longest question {@link #M_ASK} takes. */
    public static final int MAX_ASK_CHARS = 6000;
}
