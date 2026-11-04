package org.tvprivacy.adbreak;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Tiny on-device event log (this TCL drops our logcat lines). Never contains a PIN or hash.
 * files/events.log (internal), rotated at 64 KB to events.log.1; a copy is mirrored to the
 * app's external files dir so it can be pulled with adb.
 */
final class Events {
    private static final long MAX = 64 * 1024;
    private Events() {}

    static synchronized void log(Context c, String msg) {
        try {
            Context a = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg.replace('\n', ' ') + "\n";
            File f = new File(a.getFilesDir(), "events.log");
            if (f.length() > MAX) {
                File old = new File(a.getFilesDir(), "events.log.1");
                old.delete();
                f.renameTo(old);
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) { out.write(line.getBytes(StandardCharsets.UTF_8)); }
            File ext = a.getExternalFilesDir(null);
            if (ext != null) {
                File copy = new File(ext, "events.log");
                Files.copy(f.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ignored) { }
        android.util.Log.i(AdGuardService.TAG, msg);
    }

    static synchronized List<String> tail(Context c, int n) {
        List<String> out = new ArrayList<>();
        try {
            File f = new File(c.getFilesDir(), "events.log");
            if (!f.isFile()) return out;
            List<String> all = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            out.addAll(all.subList(Math.max(0, all.size() - n), all.size()));
        } catch (Throwable ignored) { }
        return out;
    }
}
