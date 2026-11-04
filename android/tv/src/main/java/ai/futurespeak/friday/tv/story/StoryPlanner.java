package ai.futurespeak.friday.tv.story;

import ai.futurespeak.friday.tv.kids.KidsRules;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The prompts and the parsing (spec §4.1, §8): the start call returns the title, the cast in
 * play and page one; each next call returns one page. The model writes the words; the TV holds
 * the length, the mode, the band, the hero's name and the cast, and tells the model the rubric
 * it must keep. Pure Java.
 */
public final class StoryPlanner {
    private StoryPlanner() {}

    public static final class Start {
        public final String title;
        public final List<Cast.Member> cast;
        public final StoryPage page;
        Start(String title, List<Cast.Member> cast, StoryPage page) { this.title = title; this.cast = cast; this.page = page; }
    }

    static final String SHAPE_PAGE = "{\"title\":\"short page title\",\"lines\":[{\"who\":\"narrator\",\"text\":\"...\"},{\"who\":\"<a cast name>\",\"text\":\"...\"}],"
            + "\"picture\":\"one or two sentences describing the picture for this page, naming the cast members in it\","
            + "\"sound\":\"one of the sound names, or null\",\"choices\":[{\"label\":\"...\",\"hint\":\"what that leads to\"},{\"label\":\"...\",\"hint\":\"...\"}] or [],\"ending\":false}";

    /** The system prompt for every story call. */
    public static String system(KidsRules.Band band, String heroName, boolean bedtime, Set<String> soundNames, List<Cast.Member> cast) {
        boolean older = band == KidsRules.Band.OLDER;
        StringBuilder s = new StringBuilder();
        s.append("You are Friday, a computer program on a family's TV, telling a story together with a child. ")
         .append(KidsRules.DISCLOSURE_FOR_PROMPT).append("\n\n");
        s.append("THE CHILD\n- Age band: ").append(band == null ? "5 to 7 (assume young)" : band.ages).append(". Short sentences, simple words, warm and playful.\n");
        if (heroName != null && !heroName.trim().isEmpty())
            s.append("- The child's name is ").append(heroName.trim()).append(". When the story has a child hero, the hero is ").append(heroName.trim())
             .append(". Use the name only in the words, NEVER in a picture description (describe the hero as a drawn child, for example 'a small child in yellow boots').\n");
        s.append("\nTHE RUBRIC (never break it)\n")
         .append("- Kind, gentle, funny. Gentle suspense is fine; no violence, no weapons, no injuries, no death, no blood, no monsters that are truly scary, no real people, no brands, no romance.\n")
         .append("- Every page is one moment: 1 to 4 lines, at most ").append(StoryPage.maxWords(older)).append(" words in all. Cast members speak in their own lines with their name as \"who\"; the narrator is \"narrator\".\n")
         .append("- A clear little arc: a wish or a worry, two tries, a kind turn, a happy ending.\n")
         .append("- About every second page offers exactly two choices on what happens next, as short labels a child can read. The ending page has no choices.\n")
         .append("- Picture descriptions are of drawings for a picture book: who is in it, where, what they are doing, the mood. No text in the picture. Never a real person. Never a photo.\n")
         .append("- A page may name one sound from this list, or null: ").append(String.join(", ", soundNames)).append(".\n")
         .append("- Never ask the child for personal information. Never suggest contacting anyone. If the child asks for something that isn't for a child, turn the story gently another way.\n")
         .append(bedtime ? "- BEDTIME MODE: slow, calm, sleepy; no silly sounds; everyone settles down; the ending is sleep.\n" : "")
         .append("\nTHE CAST (use these looks exactly; a new character may be added only if the child asks for one)\n");
        if (cast == null || cast.isEmpty()) s.append("- (none yet: invent 1 to 3 with a name, a look and a voice the first time)\n");
        else for (Cast.Member m : cast) s.append("- ").append(m.name).append(": looks like ").append(m.look).append("; voice: ").append(m.voiceDesc).append("\n");
        s.append("\nANSWER WITH JSON ONLY, no words around it.\n");
        return s.toString();
    }

    /** The first call: title, cast and page one. */
    public static String startUser(String topic, int pagesTarget, List<Cast.Member> known) {
        return "Start a story about: \"" + clean(topic) + "\". It will be about " + pagesTarget + " pages. "
                + "Reply with JSON: {\"title\":\"...\",\"cast\":[{\"name\":\"...\",\"look\":\"a drawn character's look in one sentence, colours and shapes\",\"voice\":\"how they sound, in a few words\"}],\"page\":" + SHAPE_PAGE + "}. "
                + (known == null || known.isEmpty() ? "Invent one to three cast members. " : "Reuse the cast you were given where it fits; add at most one new member. ")
                + "Page one sets the scene and the wish or worry.";
    }

    /** Each next call: one page. */
    public static String nextUser(String title, List<StoryPage> sofar, String chosen, String suggestion, int pagesLeft, boolean bedtime) {
        StringBuilder s = new StringBuilder("The story so far, \"").append(title).append("\":\n");
        int from = Math.max(0, sofar.size() - 4);
        if (from > 0) s.append("(earlier pages omitted)\n");
        for (int i = from; i < sofar.size(); i++) s.append("Page ").append(i + 1).append(": ").append(sofar.get(i).text()).append("\n");
        if (chosen != null && !chosen.isEmpty()) s.append("The child chose: \"").append(clean(chosen)).append("\".\n");
        if (suggestion != null && !suggestion.isEmpty()) s.append("The child said what should happen next: \"").append(clean(suggestion)).append("\" (fold it in kindly if it fits the rubric).\n");
        s.append(Bedtime.steer(pagesLeft, bedtime)).append("\n");
        s.append("Write the next page as JSON: ").append(SHAPE_PAGE);
        return s.toString();
    }

    /** A three-sentence recap for the book's cover and for "the story so far". */
    public static String recapUser(String title, List<StoryPage> pages) {
        StringBuilder s = new StringBuilder("Tell the story \"").append(title).append("\" so far in three short sentences a young child understands, as plain text, no JSON:\n");
        for (int i = 0; i < pages.size(); i++) s.append(pages.get(i).text()).append("\n");
        return s.toString();
    }

    public static Start parseStart(String reply, Set<String> soundNames, boolean olderBand, String heroName, Cast known) throws StoryPage.Rejected {
        JSONObject o = StoryPage.json(reply);
        String title = StoryPage.cap(o.optString("title", "").trim(), StoryPage.MAX_TITLE);
        if (title.isEmpty() || !StorySafety.textOk(title)) throw new StoryPage.Rejected("the title is missing or isn't for a child");
        List<Cast.Member> cast = new ArrayList<>(known == null ? new ArrayList<>() : known.members());
        JSONArray ca = o.optJSONArray("cast");
        int added = 0;
        for (int i = 0; ca != null && i < ca.length(); i++) {
            JSONObject m = ca.optJSONObject(i);
            if (m == null) continue;
            String name = m.optString("name", "").trim(), look = m.optString("look", "").trim(), voice = m.optString("voice", "").trim();
            String problem = Cast.problem(name, look, voice);
            if (problem != null) throw new StoryPage.Rejected(problem);
            boolean exists = false;
            for (Cast.Member k : cast) if (k.name.equalsIgnoreCase(name)) exists = true;   // the known look wins (ST-D10)
            if (exists) continue;
            if (cast.size() >= Cast.MAX_MEMBERS || added >= 3) break;
            float[] st = Cast.styleFor(voice);
            cast.add(new Cast.Member(name, look, voice, "", st[0], st[1]));
            added++;
        }
        if (cast.isEmpty()) throw new StoryPage.Rejected("the story has no cast");
        Set<String> names = new java.util.HashSet<>();
        for (Cast.Member m : cast) names.add(m.name);
        StoryPage page = StoryPage.parse(o.optJSONObject("page"), names, soundNames, olderBand, heroName);
        return new Start(title, cast, page);
    }

    public static StoryPage parsePage(String reply, Set<String> castNames, Set<String> soundNames, boolean olderBand, String heroName) throws StoryPage.Rejected {
        JSONObject o = StoryPage.json(reply);
        if (o.has("page") && o.optJSONObject("page") != null) o = o.optJSONObject("page");
        return StoryPage.parse(o, castNames, soundNames, olderBand, heroName);
    }

    static String clean(String s) {
        if (s == null) return "";
        return s.replace('"', '\'').replaceAll("[\\r\\n]+", " ").trim();
    }
}
