package ai.futurespeak.friday.tv.audio;

import android.content.Context;

import java.util.List;
import java.util.function.Supplier;

/**
 * ElevenLabs when a key is present, the TV's own voice otherwise. If ElevenLabs fails
 * mid-reply, the unspoken sentences move to the TV voice and the rest of the session uses it.
 */
public final class FallbackVoice implements Voice {
    private final AndroidVoice tv;
    private final ElevenLabsVoice eleven;
    private volatile boolean useTv;
    private volatile String lastError = null;

    public FallbackVoice(Context c, Supplier<String> elevenKey, String voiceId, String modelId, Events events) {
        tv = new AndroidVoice(c, events);
        String k = elevenKey.get();
        if (k != null && !k.isEmpty() && !"tv".equals(voiceId)) {
            Events guarded = new Events() {
                @Override public void onSpeaking() { if (!useTv) events.onSpeaking(); }
                @Override public void onQuiet() { if (!useTv) events.onQuiet(); }
                @Override public void onVoiceError(String why) { events.onVoiceError(why); }
                @Override public void onSentence(int i) { if (!useTv) events.onSentence(i); }
            };
            eleven = new ElevenLabsVoice(elevenKey, voiceId, modelId, guarded, this::failover);
            useTv = false;
        } else {
            eleven = null;
            useTv = true;
        }
    }

    private void failover(String why, List<String> unspoken, List<Integer> indexes, boolean ended) {
        lastError = why;
        useTv = true;
        for (int i = 0; i < unspoken.size(); i++) tv.speak(unspoken.get(i), indexes.get(i));
        if (ended) tv.endOfReply();
    }

    private Voice active() { return useTv || eleven == null ? tv : eleven; }

    @Override public String label() { return active().label(); }
    public void setVolume(float v) { tv.setVolume(v); if (eleven != null) eleven.setVolume(v); }
    public void setSpeed(float v) { tv.setSpeed(v); if (eleven != null) eleven.setSpeed(v); }
    public String lastError() { return lastError; }
    @Override public void speak(String sentence, int index) { active().speak(sentence, index); }
    @Override public void endOfReply() { active().endOfReply(); }
    @Override public void stop() { if (eleven != null) eleven.stop(); tv.stop(); }
    @Override public boolean busy() { return (eleven != null && eleven.busy()) || tv.busy(); }
    @Override public double level() { return Math.max(eleven == null ? 0 : eleven.level(), tv.level()); }
    @Override public void release() { if (eleven != null) eleven.release(); tv.release(); }
}
