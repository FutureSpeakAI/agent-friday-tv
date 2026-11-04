package ai.futurespeak.friday.tv.actions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Pictures Friday makes. Only {@link ActionGate} calls
 * {@link #create}. The picture is saved on the TV ({@link Store}) and shown as a card that
 * refers to it by id, so a card can never point the TV at an outside address this way.
 */
public interface Images {
    final class Result {
        public final boolean ok;
        public final String error;
        public final byte[] bytes;
        public final String mime;
        public final double credits;
        public final String model;
        public Result(boolean ok, String error, byte[] bytes, String mime, double credits, String model) {
            this.ok = ok; this.error = error; this.bytes = bytes; this.mime = mime; this.credits = credits; this.model = model;
        }
        public static Result fail(String why, double credits) { return new Result(false, why, null, null, credits, ""); }
    }

    /** Called while the picture is being made: 0..100, or -1 when unknown. */
    interface Progress { void at(int percent, String words); }

    boolean ready();
    /** Blocks until the picture is ready or failed (it can take a minute). */
    Result create(String prompt, Progress progress);
    /** "kie.ai · Nano Banana 2" */
    String service();

    /** Estimated credits for one 1K picture, shown on the approval card before anything is spent. */
    double EST_CREDITS = 8;
    /** Dollars per kie.ai credit, for an estimate only (kie.ai sells credits at about $5 per 1,000). */
    double USD_PER_CREDIT = 0.005;
    int MAX_PROMPT = 1000;

    /** Where pictures live: app-private files/images/&lt;id&gt;.png|jpg. Ids are ours, never the model's. */
    final class Store {
        public static final Pattern ID = Pattern.compile("^[a-z0-9-]{6,64}$");
        private final File dir;
        public Store(File filesDir) { dir = new File(filesDir, "images"); }

        public String save(String id, byte[] bytes, String mime) throws IOException {
            if (!ID.matcher(id).matches()) throw new IOException("bad id");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no images dir");
            File f = new File(dir, id + ("image/jpeg".equals(mime) ? ".jpg" : ".png"));
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
            return id;
        }

        /** The file for a card's id, or null (unknown id, or anything that isn't a plain id). */
        public File file(String id) {
            if (id == null || !ID.matcher(id).matches()) return null;
            for (String ext : new String[]{".png", ".jpg"}) {
                File f = new File(dir, id + ext);
                if (f.isFile()) return f;
            }
            return null;
        }
    }

    /** True when the bytes really are a PNG or JPEG (checked before anything is saved or shown). */
    static String sniff(byte[] b) {
        if (b == null || b.length < 8) return null;
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "image/jpeg";
        return null;
    }
}
