// Runs before Friday desktop's scene code. It changes the page around the scene,
// never the scene: the TV render preset, the local copy of Friday's look (genome),
// reduce-motion, and the inputs the scene reads for speech.
(function () {
  'use strict';
  var q = new URLSearchParams(location.search);
  var reduced = q.get('reduced') === '1';

  // --- Render scale ---------------------------------------------------------
  // The scene reads devicePixelRatio once, in init(). On the TCL the page is 960x540 CSS px
  // inside a 1920x1080 app surface; scale 2 fills that surface pixel for pixel. Picture
  // quality (auto / high / ultra) is managed by tv_bridge.js after the scene starts.
  // The quality ladder, shared with tv_bridge.js. Auto starts where this TV last settled
  // (the app remembers it per device and passes ?tier=), or, the very first time, at a
  // conservative rung and climbs: starting high and stepping down meant 10-15 s of poor
  // frame rate after every launch. The scene sizes its particle systems from
  // __fridayLowCost while it builds, so a lite start also makes start-up itself lighter.
  var LADDER = window.__fridayLadder = [
    { scale: 2, lite: false, trim: false }, { scale: 1.5, lite: false, trim: false },
    { scale: 1.5, lite: true, trim: true }, { scale: 1.25, lite: true, trim: true }, { scale: 1, lite: true, trim: true }];
  var FIRST_RUN_TIER = 3;
  var quality = q.get('quality') || 'auto';
  var tier = quality === 'ultra' ? 0 : quality === 'high' ? 1 : parseInt(q.get('tier') || String(FIRST_RUN_TIER), 10);
  if (!(tier >= 0 && tier < LADDER.length)) tier = FIRST_RUN_TIER;
  window.__fridayStartTier = tier;
  var dpr = LADDER[tier].scale;
  try { Object.defineProperty(window, 'devicePixelRatio', { get: function () { return dpr; }, configurable: true }); } catch (e) {}
  window.__fridayLowCost = LADDER[tier].lite;
  window.__fridayTvTier = (LADDER[tier].lite ? 'lite' : 'full') + '@' + dpr;

  // --- Speech inputs the scene reads every frame ----------------------------
  window._fridayMoodSignals = { ttsActive: false };
  window._fridayHoloAmplitude = 0;

  // --- Reduce motion --------------------------------------------------------
  // The scene listens to matchMedia('(prefers-reduced-motion: reduce)'). The app
  // decides (Android "remove animations" or the in-app switch) and tells us.
  var rmListeners = [];
  var realMatchMedia = window.matchMedia ? window.matchMedia.bind(window) : null;
  window.matchMedia = function (query) {
    if (String(query).replace(/\s+/g, '') === '(prefers-reduced-motion:reduce)') {
      return {
        get matches() { return reduced; }, media: query, onchange: null,
        addEventListener: function (t, f) { if (t === 'change') rmListeners.push(f); },
        removeEventListener: function (t, f) { rmListeners = rmListeners.filter(function (x) { return x !== f; }); },
        addListener: function (f) { rmListeners.push(f); },
        removeListener: function (f) { rmListeners = rmListeners.filter(function (x) { return x !== f; }); },
        dispatchEvent: function () { return true; }
      };
    }
    return realMatchMedia ? realMatchMedia(query) : { matches: false, media: query, addListener: function () {}, removeListener: function () {}, addEventListener: function () {}, removeEventListener: function () {} };
  };
  window.__fridayTvSetReduced = function (on) {
    reduced = !!on;
    rmListeners.slice().forEach(function (f) { try { f({ matches: reduced, media: '(prefers-reduced-motion: reduce)' }); } catch (e) {} });
  };

  // --- The desktop's server calls --------------------------------------------
  // GET /api/avatar/genome is Friday's look (the weekly evolution). The TV serves
  // a snapshot of Friday desktop's avatar genome, or nothing
  // (Friday's first look, v1) when there is none. Every other /api/ call is
  // answered "not here"; the TV has no Friday server until pairing exists.
  var realFetch = window.fetch ? window.fetch.bind(window) : null;
  window.fetch = function (url, opts) {
    var u = String(url && url.url ? url.url : url);
    if (u.indexOf('/api/avatar/genome') === 0) {
      return realFetch('genome.json', { cache: 'no-store' }).then(function (r) {
        return r.ok ? r : new Response('null', { status: 404 });
      }).catch(function () { return new Response('null', { status: 404 }); });
    }
    if (u.indexOf('/api/') === 0) return Promise.resolve(new Response('{}', { status: 404, headers: { 'Content-Type': 'application/json' } }));
    return realFetch ? realFetch(url, opts) : Promise.reject(new Error('no fetch'));
  };

  // The scene must never ask for the camera or microphone on the TV.
  try {
    if (navigator.mediaDevices) navigator.mediaDevices.getUserMedia = function () { return Promise.reject(new Error('not on the TV')); };
  } catch (e) {}
})();
