package org.tvprivacy.adbreak;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kids slice 1 (K-D4): what the daily clock counts. Watching counts; talking and learning don't.
 * The clock advances only while kids mode is on, the screen is interactive, and an app on the
 * kids list other than Friday is in front. With the grown-up switch "Count everything" on,
 * Friday in front counts too. Plain Java for unit tests.
 */
final class KidsClock {
    private KidsClock() {}

    static boolean counts(boolean kidsOn, boolean interactive, String topPkg, boolean countEverything) {
        if (!kidsOn || !interactive || topPkg == null) return false;
        if (!HelperPolicy.KIDS_APPS.contains(topPkg)) return false;
        if (HelperPolicy.FRIDAY.equals(topPkg)) return countEverything;
        return true;
    }

    /** Daily remaining time: limit + extensions - used; Long.MAX_VALUE with no limit; never below 0. */
    static long remainingMs(int limitMin, long bonusMs, long usedMs) {
        if (limitMin <= 0) return Long.MAX_VALUE;
        return Math.max(0, limitMin * 60_000L + Math.max(0, bonusMs) - usedMs);
    }

    /** The plan for "kids mode on" (TCL Home or Friday's kidsEnter): close the grown-up window first. */
    static List<String> enterPlan(boolean hasPin, boolean grownUpOpen, boolean kidsOn) {
        if (!hasPin) return Collections.singletonList("needPin");
        List<String> steps = new ArrayList<>();
        if (grownUpOpen) steps.add("lockGrownUp");
        if (!kidsOn) steps.add("kidsOn");
        steps.add("bounce");
        return steps;
    }
}
