package ai.futurespeak.friday.tv.ambient;

import android.service.dreams.DreamService;

import ai.futurespeak.friday.tv.FridayApp;
import ai.futurespeak.friday.tv.cards.PinnedCards;
import ai.futurespeak.friday.tv.channels.ChannelStore;

/** Friday as the TV's screensaver (Android's "dream"). Any key wakes the TV as usual. */
public final class FridayDream extends DreamService {
    private AmbientView view;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        FridayApp app = (FridayApp) getApplication();
        setScreenBright(app.prefs().ssDim() == 0);
        view = new AmbientView(this, app.prefs(), new PinnedCards(getFilesDir()), new ChannelStore(getFilesDir()));
        setContentView(view);
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); if (view != null) view.start(); }

    @Override public void onDreamingStopped() { if (view != null) view.stop(); super.onDreamingStopped(); }

    @Override public void onDetachedFromWindow() { if (view != null) view.stop(); view = null; super.onDetachedFromWindow(); }
}
