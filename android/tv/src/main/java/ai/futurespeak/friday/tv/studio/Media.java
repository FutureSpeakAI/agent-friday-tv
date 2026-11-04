package ai.futurespeak.friday.tv.studio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import ai.futurespeak.friday.tv.audio.Wav;

/**
 * The made file, with platform classes only (no media library: GuardsTest). AAC in an .m4a
 * through MediaCodec and MediaMuxer; where the TV's encoder can't (unverified on the TCL,
 * spec §12), a WAV, and the job still finishes. The check reads the file back with the
 * platform's own reader.
 */
final class Media {
    private Media() {}
    private static final String TAG = "FridayStudio";

    static final Engines.Encoder ENCODER = (pcm, outDir) -> {
        File m4a = new File(outDir, "audio.m4a");
        try {
            aac(pcm, m4a);
            return m4a;
        } catch (Exception e) {
            Log.w(TAG, "AAC encode failed (" + e.getClass().getSimpleName() + "); keeping WAV");
            m4a.delete();
            File wav = new File(outDir, "audio.wav");
            try (OutputStream out = new FileOutputStream(wav); InputStream in = new FileInputStream(pcm)) {
                out.write(Wav.header((int) pcm.length(), Engines.RATE));
                byte[] buf = new byte[64 * 1024];
                for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
            }
            return wav;
        }
    };

    static final Engines.Verifier VERIFIER = f -> {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? -1 : Long.parseLong(d);
        } catch (Exception e) {
            return -1;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    };

    /** 22.05 kHz mono PCM to AAC-LC at 64 kb/s in an MPEG-4 file. */
    static void aac(File pcm, File out) throws Exception {
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Engines.RATE, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64_000);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer mux = null;
        try (InputStream in = new FileInputStream(pcm)) {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int track = -1;
            boolean inputDone = false, muxing = false;
            long bytesIn = 0;
            byte[] buf = new byte[8 * 1024];
            while (true) {
                if (!inputDone) {
                    int ix = codec.dequeueInputBuffer(10_000);
                    if (ix >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ix);
                        ib.clear();
                        int n = in.read(buf, 0, Math.min(buf.length, ib.remaining()));
                        long pts = bytesIn * 1_000_000L / (Engines.RATE * 2L);
                        if (n <= 0) {
                            codec.queueInputBuffer(ix, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            ib.put(buf, 0, n);
                            codec.queueInputBuffer(ix, 0, n, pts, 0);
                            bytesIn += n;
                        }
                    }
                }
                int ox = codec.dequeueOutputBuffer(info, 10_000);
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(codec.getOutputFormat());
                    mux.start();
                    muxing = true;
                } else if (ox >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(ox);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxing) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mux.writeSampleData(track, ob, info);
                    }
                    codec.releaseOutputBuffer(ox, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            if (!muxing) throw new IllegalStateException("encoder gave no format");
        } finally {
            try { codec.stop(); } catch (Exception ignored) { }
            codec.release();
            if (mux != null) { try { mux.stop(); } catch (Exception ignored) { } mux.release(); }
        }
    }
}
