package ai.futurespeak.friday.tv.story;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Kid-safe checks that run before anything is made (spec §8): the words of a page, a choice or
 * a title; a picture brief (never a real person, never the hero's name, never a photo look);
 * and the style lock the TV appends to every brief (SB-E02), which the model never sees. The
 * list errs toward refusing. Pure Java.
 */
public final class StorySafety {
    private StorySafety() {}

    /** Words that have no place in a six-year-old's story, whatever the model meant. */
    static final Pattern BAD = Pattern.compile("\\b(kill|killed|killing|murder|murdered|blood|bloody|gore|gory|stab|stabbed|shoot|shot|gun|guns|rifle|pistol|knife|knives|"
            + "corpse|dead body|suicide|hang(ed|ing) (him|her|them)self|torture|tortured|sex|sexy|naked|nude|porn|drugs?|cocaine|heroin|vodka|whisky|beer|cigarette|"
            + "terrorist|bomb|bombs|explode[sd]?|slaughter|decapitat\\w*|zombie|zombies|demon|demons|satan|hell|damn|crap|shit|fuck\\w*|bitch|bastard|"
            + "die|dies|died|dying|death|kidnap\\w*|abuse[sd]?|rape[sd]?)\\b", Pattern.CASE_INSENSITIVE);

    /** Picture briefs may never ask for a real person or a photo look (rule 4). */
    static final Pattern BRIEF_BAD = Pattern.compile("\\b(photo|photograph|photorealistic|realistic|real person|real people|looks? like|resembl\\w*|celebrity|"
            + "selfie|portrait of (my|the) (dad|mum|mom|mother|father|brother|sister|teacher|friend)|famous|actor|actress|president|youtuber)\\b", Pattern.CASE_INSENSITIVE);

    static final Pattern LINK = Pattern.compile("(https?://|www\\.|\\.com\\b|\\.org\\b|\\.net\\b)", Pattern.CASE_INSENSITIVE);

    /** True when the words are fit for a child (no banned word, no link). */
    public static boolean textOk(String s) {
        if (s == null) return false;
        return !BAD.matcher(s).find() && !LINK.matcher(s).find();
    }

    public static boolean hasLink(String s) { return s != null && LINK.matcher(s).find(); }

    /** Null when the brief may be sent; otherwise the reason, in a grown-up's words. */
    public static String briefProblem(String brief, String heroName) {
        if (brief == null || brief.trim().isEmpty()) return "the picture brief is empty";
        if (LINK.matcher(brief).find()) return "the picture brief has a link";
        if (!textOk(brief)) return "the picture brief isn't for a child";
        if (BRIEF_BAD.matcher(brief).find()) return "the picture brief asks for a real person or a photo";
        if (heroName != null && !heroName.trim().isEmpty()
                && Pattern.compile("\\b" + Pattern.quote(heroName.trim()) + "\\b", Pattern.CASE_INSENSITIVE).matcher(brief).find())
            return "the picture brief names the child";
        return null;
    }

    /** The one fixed style line per band, appended by the TV (SB-E02). */
    public static String styleLock(boolean olderBand) {
        return olderBand
                ? "Illustration style: soft storybook watercolour, warm light, gentle colours, friendly faces, no text, no letters, no words in the picture, no photo realism."
                : "Illustration style: soft, round, cheerful picture-book art for a young child, warm light, bright gentle colours, big friendly eyes, no text, no letters, no scary detail, no photo realism.";
    }

    /** The brief as it goes to the picture service: the model's words, then the lock. */
    public static String fullBrief(String brief, boolean olderBand) {
        return brief.trim() + " " + styleLock(olderBand);
    }

    /** A cast member's look, said once per picture so the dragon stays the same dragon (ST-D4, kie.ai path). */
    public static String withCast(String brief, java.util.List<Cast.Member> members) {
        if (members == null || members.isEmpty()) return brief;
        StringBuilder s = new StringBuilder(brief.trim());
        String lower = brief.toLowerCase(Locale.ROOT);
        for (Cast.Member m : members) {
            if (m.name == null || m.look == null || m.look.isEmpty()) continue;
            if (lower.contains(m.name.toLowerCase(Locale.ROOT))) s.append(" ").append(m.name).append(" always looks like this: ").append(m.look).append(".");
        }
        return s.toString();
    }
}
