package ai.futurespeak.friday.tv.studio;

import java.util.List;

/**
 * The plan: one Claude call with a fixed output shape (spec §8.2). A plan with the wrong shape
 * is asked for once more, then refused with the problem named. Whatever the plan says, the
 * request's length and kind stand (AC-S1). Pure Java.
 */
public final class Planner {
    public static final class Result {
        public final Storyboard plan; public final String problem; public final Spent spent;
        Result(Storyboard plan, String problem, Spent spent) { this.plan = plan; this.problem = problem; this.spent = spent; }
    }

    /** The chapter count asked for: the middle of the length's range, leaning long enough to stay under 220 words a chapter. */
    public static int chaptersFor(int lengthMin) {
        switch (lengthMin) {
            case 2: return 2;
            case 5: return 5;
            case 10: return 8;
            case 15: return 12;
            default: return 1;
        }
    }

    public static Result plan(StudioRequest r, List<StudioSource> sources, Engines.Writer w, Prices prices) {
        Spent spent = new Spent();
        String problem = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Engines.Reply reply;
            try {
                String user = Prompts.planUser(r, sources, chaptersFor(r.lengthMin))
                        + (problem == null ? "" : "\nYour last plan was refused: " + problem + ". Follow the shape exactly.");
                reply = w.write(Prompts.planSystem(), user, 4000);
            } catch (Exception e) {
                return new Result(null, "I couldn't reach Claude to plan it", spent);
            }
            spent.add("claude", "tokens", reply.in + reply.out + reply.cacheRead + reply.cacheWrite,
                    prices.claude(w.model()).usd(reply.in, reply.out, reply.cacheRead, reply.cacheWrite));
            Storyboard s;
            try { s = Storyboard.parse(reply.text); }
            catch (IllegalArgumentException e) { problem = e.getMessage(); continue; }
            if (s.paramsChanged(r) != null) return new Result(s, null, spent);   // the gate refuses it (AC-S1)
            problem = s.problem(r, sources.size());
            if (problem == null) return new Result(s, null, spent);
        }
        return new Result(null, "the plan didn't fit (" + problem + ")", spent);
    }
}
