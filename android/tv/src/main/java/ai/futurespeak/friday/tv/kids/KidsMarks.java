package ai.futurespeak.friday.tv.kids;

import org.json.JSONObject;

import ai.futurespeak.friday.tv.actions.Receipts;

/**
 * The report markers ({@code <mark kind="…"/>}, KM-E07): the reply parser strips them from
 * speech and captions; here a known one becomes a receipt on the turn, so the grown-up report
 * can be built from receipts alone. An unknown one is dropped and logged, never receipted.
 */
public final class KidsMarks {
    private KidsMarks() {}

    public static boolean known(String kind) { return kind != null && KidsRules.MARKS.contains(kind); }

    /**
     * Records a marker. Returns true if a receipt was written.
     * @param principal who was talking ({@code kids} in kids mode)
     */
    public static boolean record(Receipts receipts, String principal, String kind) {
        if (!known(kind)) {
            System.err.println("FridayTV kids: dropped an unknown marker (" + (kind == null ? "none" : kind.length() > 24 ? kind.substring(0, 24) : kind) + ")");
            return false;
        }
        if (receipts == null) return false;
        try {
            String id = receipts.decision(principal, new JSONObject().put("action", "kids.mark").put("kind", kind).put("origin", "model")
                    .put("tier", 0).put("policy", "record"));
            receipts.outcome(id, principal, "recorded", kind);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
