package org.tvprivacy.adbreak;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;

/**
 * Keeps Google Cast ("Chromecast built-in") awake (fix for 2026-11-02 "TV missing from Cast").
 *
 * Root cause: with Google's home screen no longer in front (Projectivy, then Friday Home), nothing
 * kept the cast receiver (com.google.android.apps.mediashell) above "cached". Android 14's cached-app
 * freezer then froze it (isFrozen=true, oom adj 965), so it stopped answering mDNS and cast requests,
 * and it was killed for "undelivered broadcast" (18:50, 19:35).
 *
 * Fix: Ad Break Guard (an accessibility service, oom adj 100) holds a plain binding to the receiver's
 * exported CastReceiverService. The binding carries our priority, so the receiver is never cached or
 * frozen. We never call into the binder: no transactions, no data. If the binding drops, retry with backoff.
 */
final class CastKeepAlive implements ServiceConnection {
    static final String CAST_PKG = "com.google.android.apps.mediashell";
    static final String CAST_ACTION = "com.google.cast.action.BIND";

    private final Context ctx;
    private final Handler handler;
    private boolean bound, stopped;
    private long backoffMs = 5_000;

    CastKeepAlive(Context ctx, Handler handler) { this.ctx = ctx; this.handler = handler; }

    void start() {
        stopped = false;
        bind();
    }

    private void bind() {
        if (stopped || bound) return;
        try {
            Intent i = new Intent(CAST_ACTION).setPackage(CAST_PKG);
            bound = ctx.bindService(i, this, Context.BIND_AUTO_CREATE);
            Events.log(ctx, bound ? "cast: keep-alive bound" : "cast: keep-alive bind refused");
        } catch (Throwable t) {
            bound = false;
            Events.log(ctx, "cast: keep-alive bind failed (" + t.getClass().getSimpleName() + ")");
        }
        if (!bound) retryLater();
    }

    private void retryLater() {
        if (stopped) return;
        long d = backoffMs;
        backoffMs = Math.min(backoffMs * 2, 10 * 60_000L);
        handler.postDelayed(this::bind, d);
    }

    void stop() {
        stopped = true;
        if (bound) { try { ctx.unbindService(this); } catch (Throwable ignored) { } }
        bound = false;
    }

    @Override public void onServiceConnected(ComponentName name, IBinder service) { backoffMs = 5_000; }
    @Override public void onServiceDisconnected(ComponentName name) { Events.log(ctx, "cast: receiver went away (it will be restarted)"); }
    @Override public void onBindingDied(ComponentName name) {
        Events.log(ctx, "cast: keep-alive binding died; re-binding");
        try { ctx.unbindService(this); } catch (Throwable ignored) { }
        bound = false;
        retryLater();
    }
    @Override public void onNullBinding(ComponentName name) { /* still a binding; priority still applies */ }
}
