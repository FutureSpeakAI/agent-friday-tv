package ai.futurespeak.friday.tv.addon;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class ProvidersTest {
    static Providers.Keys keys(String... names) { Set<String> s = new HashSet<>(Arrays.asList(names)); return s::contains; }

    @Test public void defaultsAreTheDocumentedOnes() {
        Providers p = Providers.parse(null);
        assertEquals("anthropic", p.textProvider);
        assertEquals(Providers.DEFAULT_TEXT_MODEL, p.textModel);
        assertEquals("elevenlabs", p.voiceProvider);
        assertEquals(Providers.DEFAULT_IMAGE_MODEL, p.imageModel);
        assertEquals("", p.videoModel);
    }

    @Test public void everyMissingKeyHasAFallback() {
        Providers.Plan none = Providers.defaults().plan(keys());
        assertEquals("none", none.text);
        assertEquals("tv", none.voice);
        assertEquals("none", none.sound);
        assertEquals("none", none.music);
        assertEquals("stills", none.images);
        assertEquals("stills", none.video);
        Providers.Plan all = Providers.defaults().plan(keys("anthropic", "elevenlabs", "kie"));
        assertEquals("anthropic", all.text);
        assertEquals("elevenlabs", all.voice);
        assertEquals("kie", all.images);
        assertEquals("no video model chosen: stills", "stills", all.video);
    }

    @Test public void theOwnersChoiceWinsOverAKey() {
        Providers p = Providers.parse("{\"voice\":{\"provider\":\"tv\"},\"images\":{\"provider\":\"none\"},\"text\":{\"provider\":\"openrouter\",\"model\":\"anthropic/claude-sonnet-5.5\"}}");
        Providers.Plan plan = p.plan(keys("anthropic", "openrouter", "elevenlabs", "kie"));
        assertEquals("tv", plan.voice);
        assertEquals("stills", plan.images);
        assertEquals("openrouter", plan.text);
        assertEquals("anthropic/claude-sonnet-5.5", p.textModel);
        assertEquals("openrouter without its key falls back to Claude", "anthropic", p.plan(keys("anthropic")).text);
    }

    @Test public void junkTakesTheDefault() {
        Providers p = Providers.parse("{\"text\":{\"provider\":\"evil\",\"model\":\"a b; rm -rf\"},\"voice\":{\"voice_id\":\"../../x\"}}");
        assertEquals("anthropic", p.textProvider);
        assertEquals(Providers.DEFAULT_TEXT_MODEL, p.textModel);
        assertEquals("", p.voiceId);
        assertEquals(Providers.defaults().toJson().toString(), Providers.parse("not json").toJson().toString());
    }

    @Test public void roundTrips() {
        Providers p = Providers.parse("{\"images\":{\"model\":\"some-image-model\"},\"video\":{\"model\":\"some-video-model\"}}");
        assertEquals(p.toJson().toString(), Providers.parse(p.toJson().toString()).toJson().toString());
    }
}
