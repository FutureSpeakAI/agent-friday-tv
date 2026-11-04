package ai.futurespeak.friday.tv.safety;

/**
 * Friday's own grown-up unlock, used only when Ad Break Guard's shared PIN service is not on
 * the TV. Unlocked lasts until the TV sleeps (screen off), the TV restarts, kids/family mode is
 * turned back on, or a safety cap passes. It is persisted, so an app restart doesn't ask again.
 * Pure logic over a tiny store, unit-tested.
 */
public final class GrownUpSession {
    /** Upper bound even if a screen-off was missed while the app wasn't running. */
    public static final long CAP_MS = 8 * 3600_000L;
    /** Boot time computed from two clocks jitters a little; a restart moves it by far more. */
    static final long SAME_BOOT_SLACK_MS = 60_000L;

    public interface Store {
        boolean on(); long bootWall(); long capWall();
        void put(boolean on, long bootWall, long capWall);
    }

    private final Store store;
    public GrownUpSession(Store s) { store = s; }

    /** @param nowWall System.currentTimeMillis()  @param elapsed SystemClock.elapsedRealtime() */
    public boolean unlocked(long nowWall, long elapsed) {
        if (!store.on()) return false;
        long boot = nowWall - elapsed;
        boolean sameBoot = Math.abs(boot - store.bootWall()) < SAME_BOOT_SLACK_MS;
        if (!sameBoot || nowWall >= store.capWall()) { lock(); return false; }
        return true;
    }

    public void unlock(long nowWall, long elapsed) { store.put(true, nowWall - elapsed, nowWall + CAP_MS); }

    /** Screen off, kids/family mode on, or "Lock now". */
    public void lock() { if (store.on()) store.put(false, 0, 0); }
}
