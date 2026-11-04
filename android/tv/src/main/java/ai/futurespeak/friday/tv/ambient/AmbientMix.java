package ai.futurespeak.friday.tv.ambient;

/**
 * Decides what each screensaver turn shows: a Friday News slide, or the next of the owner's
 * pinned cards and chosen channels. Mix 0 = mostly news (3 news : 1 card), 1 = even (1 : 1),
 * 2 = mostly cards and channels (1 news : 3 cards). If only one kind is available, that kind
 * always plays. Pure logic, unit-tested.
 */
public final class AmbientMix {
    public enum Kind { NEWS, OTHER, NOTHING }

    private final int news, other;
    private int inCycle = 0;

    public AmbientMix(int mix) {
        int m = Math.max(0, Math.min(2, mix));
        news = m == 0 ? 3 : 1;
        other = m == 2 ? 3 : 1;
    }

    public Kind next(boolean haveNews, boolean haveOther) {
        if (!haveNews && !haveOther) return Kind.NOTHING;
        if (!haveNews) return Kind.OTHER;
        if (!haveOther) return Kind.NEWS;
        int slot = inCycle % (news + other);
        inCycle++;
        return slot < news ? Kind.NEWS : Kind.OTHER;
    }
}
