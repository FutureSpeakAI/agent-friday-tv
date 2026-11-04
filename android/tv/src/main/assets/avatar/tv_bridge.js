// Runs after Friday desktop's scene code. The Android app drives the avatar only
// through window.fridayTV (WebView.evaluateJavascript); the page has no bridge back
// into the app. Each call maps onto the desktop's own API (window.fridayVibe and the
// speech inputs its render loop reads), so the states look and move as on desktop.
(function () {
  'use strict';
  // Friday's four presence states -> the desktop moods that draw them.
  var MOOD = { idle: 'IDLE', listening: 'LISTENING', thinking: 'REASONING', speaking: 'SPEAKING' };
  var state = 'idle';

  function setState(s) {
    if (!MOOD[s]) return state;
    state = s;
    window._fridayMoodSignals.ttsActive = (s === 'speaking');
    if (s !== 'speaking') window._fridayHoloAmplitude = 0;
    if (window.fridayVibe) window.fridayVibe.setMood(MOOD[s]);
    return state;
  }

  // Voice level 0..1 from the audio the TV is playing. The scene smooths it itself
  // (rise ~0.5 s, fall ~1 s: the desktop's photosensitivity envelope).
  function setAmplitude(a) {
    a = +a;
    window._fridayHoloAmplitude = state === 'speaking' && isFinite(a) ? Math.max(0, Math.min(1, a)) : 0;
  }

  function setStructure(i) {
    i = i | 0;
    if (typeof EVOLUTION_PATH === 'undefined' || i < 0 || i >= EVOLUTION_PATH.length) return -1;
    if (typeof setEvolution === 'function') setEvolution(i);
    return i;
  }

  // --- Picture quality ------------------------------------------------------
  // The render scale is the canvas backing resolution relative to the page's CSS pixels.
  // On the TCL the app surface is 1920x1080 at density 320, so the page is 960x540 CSS px:
  // scale 2 = 1920x1080 (every pixel the app can put on the panel), 1 = 960x540.
  // Bloom and the holographic pass stay on as long as possible: resolution is given up
  // before glow, because blocky edges read worse than a slightly softer picture.
  //   ultra: scale 2, full pipeline.   high: scale 1.5, full pipeline.
  //   auto:  measured on this GPU. Starts at 2; every 4 s the frame rate is checked.
  //          Under 45 fps it steps down (2, 1.75, 1.5, 1.25, 1, then the desktop's
  //          low-cost path); a level that failed is never retried; above 57 fps for
  //          12 s it steps back up to a level that has not failed.
  // Measured on a TCL Google TV (2026-11-02): the full pipeline ran at 3 fps at scale 2 and
  // 7 fps at scale 1; the low-cost path at scale 1 reached about 21 fps. So the ladder trades
  // bloom away early and trims the large additive sprites before giving up resolution.
  // Since 2026-11-02 auto starts where this TV last settled (tv_shim.js, ?tier=) or, the first
  // time, at a conservative rung, and climbs while there is headroom. When a rung has held
  // for HOLD_MS the page logs "settled level=N"; the app stores it for the next launch.
  var LADDER = window.__fridayLadder;
  var SLOW = 24, FAST = 40, SETTLE_MS = 4000, HOLD_MS = 20000, startAt = 0;
  var q = new URLSearchParams(location.search);
  var quality = q.get('quality') || 'auto';
  var level = window.__fridayStartTier | 0;
  var failed = {}, good = 0, frames = 0, windowStart = 0, lastFps = 0, changedAt = 0, reported = -1;
  // Home v2 (the rule for missed 60 fps): while the remote moves focus, the avatar holds
  // its last frame so the tiles get the GPU. fridayTV.hold(ms) defers every animation frame until
  // the hold ends; the fps window restarts after it, so a hold never reads as "too slow".
  var holdUntil = 0, held = [], rawRaf = window.requestAnimationFrame.bind(window);
  window.requestAnimationFrame = function (cb) {
    if (performance.now() < holdUntil) { held.push(cb); return 0; }
    return rawRaf(cb);
  };
  function release() {
    var left = holdUntil - performance.now();
    if (left > 0) { setTimeout(release, left + 1); return; }
    var cbs = held; held = []; windowStart = 0; frames = 0;
    cbs.forEach(function (cb) { rawRaf(cb); });
  }
  function hold(ms) {
    ms = Math.max(0, Math.min(ms | 0, 2000));
    var was = performance.now() < holdUntil;
    holdUntil = Math.max(holdUntil, performance.now() + ms);
    if (!was) setTimeout(release, ms + 1);
    return true;
  }

  // The desktop's big additive nebula sprites and energy lines cost the most fill on a TV GPU.
  function trim(on) {
    try {
      if (typeof nebulaClouds !== 'undefined') nebulaClouds.forEach(function (c) { if (c) c.visible = !on; });
      if (typeof energyLines !== 'undefined') energyLines.forEach(function (l) { if (l) l.visible = !on; });
    } catch (e) {}
  }

  function applyLevel(why) {
    var v = LADDER[level];
    var lite = v.lite, scale = v.scale;
    window.__fridayLowCost = lite;
    trim(v.trim);
    if (window.__fridayRenderer) {
      window.__fridayRenderer.setPixelRatio(scale);
      try {
        if (typeof composer !== 'undefined' && composer) { composer.setPixelRatio(scale); composer.setSize(innerWidth, innerHeight); }
      } catch (e) {}
    }
    window.__fridayTvTier = (lite ? 'lite' : 'full') + '@' + scale + (v.trim ? '+trim' : '');
    changedAt = performance.now();
    console.log('fridaytv: quality=' + quality + ' tier=' + window.__fridayTvTier + ' backing=' +
      Math.round(innerWidth * scale) + 'x' + Math.round(innerHeight * scale) + ' fps=' + lastFps.toFixed(1) + ' (' + why + ')');
  }

  function tick(now) {
    requestAnimationFrame(tick);
    if (!windowStart) { windowStart = now; return; }
    frames++;
    var span = now - windowStart;
    if (span < 4000) return;
    lastFps = frames * 1000 / span;
    frames = 0; windowStart = now;
    if (document.hidden || quality !== 'auto' || now - startAt < SETTLE_MS) return;
    if (reported !== level && now - changedAt >= HOLD_MS && lastFps >= SLOW) {
      reported = level;
      console.log('fridaytv: settled level=' + level + ' tier=' + window.__fridayTvTier + ' fps=' + lastFps.toFixed(1));
    }
    if (lastFps < SLOW && level < LADDER.length - 1) {
      failed[level] = true; good = 0; level++;
      applyLevel('too slow');
      // Remember the step down at once, so a slow TV never starts too high again.
      reported = level;
      console.log('fridaytv: settled level=' + level + ' tier=' + window.__fridayTvTier + ' fps=' + lastFps.toFixed(1));
    } else if (lastFps > FAST) {
      if (++good >= 3 && level > 0 && !failed[level - 1]) { good = 0; level--; applyLevel('headroom'); }
    } else good = 0;
    if ((now / 1000 | 0) % 20 < 4) console.log('fridaytv: fps=' + lastFps.toFixed(1) + ' tier=' + window.__fridayTvTier);
  }

  function setQuality(qq) {
    quality = qq === 'ultra' || qq === 'high' ? qq : 'auto';
    level = quality === 'ultra' ? 0 : quality === 'high' ? 1 : (window.__fridayStartTier | 0);
    failed = {}; good = 0; startAt = performance.now();
    applyLevel('set ' + quality);
    return quality;
  }

  // Show the avatar only once it renders smoothly: compile the shaders while the canvas is
  // still transparent (the app's own UI is already up), then fade in over a few frames.
  function reveal() {
    var c = document.querySelector('canvas');
    if (!c) return;
    try { if (typeof renderer !== 'undefined' && renderer && typeof scene !== 'undefined' && typeof camera !== 'undefined') renderer.compile(scene, camera); } catch (e) {}
    var n = 0;
    (function wait() { if (++n < 4) return requestAnimationFrame(wait); c.style.opacity = '1'; })();
  }

  window.addEventListener('load', function () {
    setTimeout(function () { startAt = performance.now(); applyLevel('start'); requestAnimationFrame(tick); reveal(); }, 0);
  });

  window.fridayTV = {
    state: setState,
    amp: setAmplitude,
    structure: setStructure,
    hold: hold,
    reduced: function (on) { window.__fridayTvSetReduced(!!on); return !!on; },
    quality: setQuality,
    structures: function () {
      return typeof EVOLUTION_PATH === 'undefined' ? [] : EVOLUTION_PATH.map(function (s) { return s.name; });
    },
    status: function () {
      var ok = typeof renderer !== 'undefined' && !!renderer && typeof scene !== 'undefined' && !!scene;
      return JSON.stringify({ ready: ok, state: state, tier: window.__fridayTvTier, fps: +lastFps.toFixed(1),
        structure: typeof currentEvolutionIdx === 'undefined' ? -1 : currentEvolutionIdx,
        reduced: typeof SceneMotion !== 'undefined' && SceneMotion.reduced() });
    }
  };

  // Start state and structure from the page address (the app remembers both).
  window.addEventListener('load', function () {
    setTimeout(function () {
      var st = parseInt(q.get('structure') || '0', 10);
      if (st > 0) setStructure(st);
      setState(q.get('state') || 'idle');
    }, 0);
  });
})();
