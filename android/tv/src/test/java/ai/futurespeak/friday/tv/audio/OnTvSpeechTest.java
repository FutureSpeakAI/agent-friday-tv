package ai.futurespeak.friday.tv.audio;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** design decision, (2026-11-02): Friday's own on-TV speech. Model integrity, routing, and the voice never leaving the TV. */
public class OnTvSpeechTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private void model(File files, boolean withManifest, String tamper) throws Exception {
        File d = OnTvModel.dir(files);
        d.mkdirs();
        JSONObject m = new JSONObject();
        for (String f : OnTvModel.FILES) {
            if (f.equals("manifest.json")) continue;
            byte[] b = ("model bytes of " + f).getBytes(StandardCharsets.UTF_8);
            Files.write(new File(d, f).toPath(), b);
            m.put(f, new JSONObject().put("size", b.length).put("sha256", OnTvModel.sha256(new File(d, f))));
        }
        if (tamper != null) Files.write(new File(d, tamper).toPath(), ("model bytes of " + tamper).replace('m', 'M').getBytes(StandardCharsets.UTF_8));
        if (withManifest) Files.write(new File(d, "manifest.json").toPath(), m.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test public void theModelCountsOnlyWhenEveryFileMatchesItsManifest() throws Exception {
        File files = tmp.newFolder();
        assertEquals(OnTvModel.State.MISSING, OnTvModel.check(files, true).state);
        model(files, false, null);
        assertEquals("no manifest yet: not trusted", OnTvModel.State.MISSING, OnTvModel.check(files, true).state);
        model(files, true, null);
        OnTvModel.Status s = OnTvModel.check(files, true);
        assertTrue(s.line(), s.ready());
        assertTrue(s.line().startsWith("On-TV speech: ready (Zipformer English"));
        new File(OnTvModel.dir(files), "joiner.int8.onnx").delete();
        assertEquals(OnTvModel.State.INCOMPLETE, OnTvModel.check(files, true).state);
    }

    @Test public void aTamperedFileIsRefused() throws Exception {
        File files = tmp.newFolder();
        model(files, true, "encoder.int8.onnx");   // same size, different bytes
        assertTrue("sizes alone can't see it", OnTvModel.check(files, false).ready());
        assertEquals(OnTvModel.State.CORRUPT, OnTvModel.check(files, true).state);
    }

    @Test public void onlyTheModelsOwnNamesMayBeWritten() {
        assertTrue(OnTvModel.allowedName("encoder.int8.onnx"));
        for (String bad : new String[]{"../keys.xml", "encoder.onnx", "", null, "manifest.json/../x", "shared_prefs"})
            assertFalse(String.valueOf(bad), OnTvModel.allowedName(bad));
    }

    @Test public void wordErrorRate() {
        assertEquals(0, OnTvModel.wer("Open Plex", "open plex."), 1e-9);
        assertEquals(0.5, OnTvModel.wer("pause the show", "pause show the"), 0.34);
        assertEquals(1.0 / 3, OnTvModel.wer("what's the weather", "what's the whether"), 1e-9);
        assertEquals("Open plex", SherpaSpeechIn.tidy("OPEN PLEX"));
        assertEquals("Can I watch bluey", SherpaSpeechIn.tidy("CAN I WATCH BLUEY"));
    }

    @Test public void onTvIsTheFirstChoiceAndOffline() {
        assertTrue(OfflineSpeech.offline(OfflineSpeech.Route.ON_TV));
        assertTrue(OfflineSpeech.explain(OfflineSpeech.Route.ON_TV).contains("never leaves the TV"));
        assertEquals(OfflineSpeech.Route.ON_TV, OfflineSpeech.Route.values()[0]);
    }

    private static String main() throws Exception {
        return new String(Files.readAllBytes(new File("src/main/java/ai/futurespeak/friday/tv/MainActivity.java").toPath()), StandardCharsets.UTF_8);
    }

    /** The rule that fails if audio could leave the TV while on-TV speech is chosen: that route builds only Friday's own recognizer, before any Google check. */
    @Test public void withOnTvChosenNothingButFridaysOwnRecognizerHearsTheVoice() throws Exception {
        String m = main();
        int make = m.indexOf("void makeSpeech() {");
        int onTv = m.indexOf("if (prefs.sttOnTv() && ai.futurespeak.friday.tv.audio.OnTvModel.check(getFilesDir(), false).ready()) { useRoute(OfflineSpeech.Route.ON_TV, ev); return; }", make);
        int google = m.indexOf("RecognizerSpeechIn.onDeviceAvailable(this)", make);
        assertTrue("on-TV is chosen before any Google recognizer is even looked at", onTv > make && google > onTv);
        int use = m.indexOf("private void useRoute(OfflineSpeech.Route r, SpeechIn.Events ev) {");
        int branch = m.indexOf("if (r == OfflineSpeech.Route.ON_TV) {", use);
        int next = m.indexOf("} else if", branch);
        String body = m.substring(branch, next);
        assertTrue(body.contains("new ai.futurespeak.friday.tv.audio.SherpaSpeechIn(this, ev)"));
        assertFalse("no Google recognizer on the on-TV route", body.contains("RecognizerSpeechIn"));
        assertFalse("no network on the on-TV route", body.contains("http") || body.contains("ElevenLabs") || body.contains("Live"));
        assertFalse("the broken pack offer is gone", m.contains("if (packFlow().offerOnTalk(sttRoute)) { offerPack(); return; }"));
    }

    @Test public void theNativeLibraryIsFridaysOwnCopy() throws Exception {
        String loader = new String(Files.readAllBytes(new File("src/main/java/com/k2fsa/sherpa/onnx/LibraryLoader.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(loader.contains("System.loadLibrary(\"sherpa-onnx-jni\")"));
        assertFalse("nothing downloaded or extracted at runtime", loader.contains("openConnection") || loader.contains("new java.net.URL") || loader.contains("FileOutputStream") || loader.contains("System.load("));
        assertTrue(new File("src/main/jniLibs/armeabi-v7a/libsherpa-onnx-jni.so").length() > 1_000_000);
    }
}
