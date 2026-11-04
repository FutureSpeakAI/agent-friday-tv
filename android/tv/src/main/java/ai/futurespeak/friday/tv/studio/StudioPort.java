package ai.futurespeak.friday.tv.studio;

import java.util.List;

/**
 * What {@code ActionGate} asks of Studio for {@code studio.make}. The gate holds the request,
 * writes the receipts and decides; the port reads the sources, plans, shows the TV's fixed
 * card and builds. A port can't widen anything: it gets the request the gate parsed.
 */
public interface StudioPort {
    /** Claude is set up on this TV (the writer). */
    boolean ready();

    /**
     * The length picker, when the person didn't say one: one of {@code lengths} (what the material supports;
     * 2 alone when it's thin), or 0 for "not now". {@code builtFrom} says plainly what it would be made from.
     */
    int pickLength(StudioRequest r, int[] lengths, String builtFrom);

    /**
     * Plans (one small Claude turn) and estimates, from the sources the gate gathered ({@link Gatherer}: only the
     * gate calls the web).
     * @param spentBefore what the gate already spent reading and searching, carried into the job's meter
     * @param builtFrom   plain words for the card: what it is built from, and any length scaled to the material
     */
    Prepared prepare(StudioRequest r, List<StudioSource> sources, Spent spentBefore, String builtFrom);

    /** The fixed card (spec §8.3); true only for an explicit Do it. Focus starts on Not now. */
    boolean approve(Prepared p);

    /** Starts the build in the background; the job writes the outcome receipt when it ends. */
    void start(Prepared p, String decisionId, String principal);

    final class Prepared {
        public final StudioRequest request;
        public final List<StudioSource> sources;
        public final Storyboard plan;
        /** Why it can't be made (fixed words), or null. */
        public final String problem;
        public final Estimate estimate;
        /** Already spent before the card: reading the page and the plan's Claude turn. */
        public final Spent spentBefore;
        /** A source tried to change the plan (TV-E08): it changed nothing, and the card says so. */
        public final boolean tainted;
        public final String taintSource;
        /** "Friday's voice (ElevenLabs)" or "the TV's own voice". */
        public final String voice;
        /** What it is built from, in plain words (the gatherer's note), or null. */
        public final String builtFrom;

        public Prepared(StudioRequest request, List<StudioSource> sources, Storyboard plan, String problem, Estimate estimate,
                        Spent spentBefore, boolean tainted, String taintSource, String voice) {
            this(request, sources, plan, problem, estimate, spentBefore, tainted, taintSource, voice, null);
        }

        public Prepared(StudioRequest request, List<StudioSource> sources, Storyboard plan, String problem, Estimate estimate,
                        Spent spentBefore, boolean tainted, String taintSource, String voice, String builtFrom) {
            this.request = request; this.sources = sources; this.plan = plan; this.problem = problem; this.estimate = estimate;
            this.spentBefore = spentBefore == null ? new Spent() : spentBefore; this.tainted = tainted; this.taintSource = taintSource; this.voice = voice;
            this.builtFrom = builtFrom;
        }

        public static Prepared failed(StudioRequest r, String problem, Spent spentBefore) {
            return new Prepared(r, java.util.Collections.<StudioSource>emptyList(), null, problem, null, spentBefore, false, null, "");
        }

        /** The whole job's estimate: the plan already spent plus what's to come. */
        public Spent total() {
            Spent s = new Spent();
            s.addAll(spentBefore);
            if (estimate != null) s.addAll(estimate.cost);
            return s;
        }
    }
}
