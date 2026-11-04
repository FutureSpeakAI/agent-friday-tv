// === FRIDAY VIBE API ===
// Wire window.fridayVibe to Gemini's actual scene functions
window.fridayVibe = {
    setMood: function(m) {
        if (typeof setMood === 'function') setMood(m, true);
    },
    setSystemMood: function(m) {
        if (typeof setSystemMood === 'function') setSystemMood(m);
    },
    clearMoodOverride: function() {
        if (typeof clearMoodOverride === 'function') clearMoodOverride();
    },
    isMicActive: function() {
        // The scene's own microphone, or a voice session's.
        return (typeof micActive !== 'undefined' && micActive) || !!window.__fridayVoiceMicOpen;
    },
    nextStructure: function() {
        if (typeof setEvolution === 'function' && typeof currentEvolutionIdx !== 'undefined' && typeof EVOLUTION_PATH !== 'undefined') {
            setEvolution((currentEvolutionIdx + 1) % EVOLUTION_PATH.length);
        }
    },
    prevStructure: function() {
        if (typeof setEvolution === 'function' && typeof currentEvolutionIdx !== 'undefined' && typeof EVOLUTION_PATH !== 'undefined') {
            setEvolution((currentEvolutionIdx - 1 + EVOLUTION_PATH.length) % EVOLUTION_PATH.length);
        }
    },
    setStructure: function(i) {
        if (typeof setEvolution === 'function') setEvolution(i);
    },
    getStructure: function() {
        return {
            index: typeof currentEvolutionIdx !== 'undefined' ? currentEvolutionIdx : 0,
            name: typeof EVOLUTION_PATH !== 'undefined' && typeof currentEvolutionIdx !== 'undefined'
                ? EVOLUTION_PATH[currentEvolutionIdx].name
                : 'Unknown'
        };
    },
    getStructures: function() {
        return typeof EVOLUTION_PATH !== 'undefined' ? EVOLUTION_PATH : [];
    },
    getMood: function() {
        return typeof currentMood !== 'undefined' ? currentMood : 'LISTENING';
    },
    getMoods: function() {
        return typeof MOODS !== 'undefined' ? Object.keys(MOODS) : ['LISTENING','REASONING','EXECUTING','EXCITED','CALM'];
    },
    toggleHologram: function() {
        if (typeof toggleHologram === 'function') return toggleHologram();
    },
    setHologram: function(enabled, owner) { return setHologram(!!enabled, owner); },
    getHologramState: function() { return hologramState(); },
    getTrackedCameraId: function() {
        if (document.visibilityState === 'hidden') return null;
        try {
            const track = videoElement?.srcObject?.getVideoTracks?.().find(t => t.readyState === 'live' && !t.muted);
            const id = track?.getSettings?.().deviceId;
            return typeof id === 'string' && id.length > 0 && id.length <= 512 ? id : null;
        } catch (_) { return null; }
    },
    getTrackedFaceWidth: function() {
        const head = window.FridayTracking?.head;
        const width = typeof lastFaceBoxW === 'number' ? lastFaceBoxW : 0;
        const age = typeof lastFaceAt === 'number' && lastFaceAt > 0 ? performance.now() - lastFaceAt : Infinity;
        const hold = typeof FACE_HOLD_MS === 'number' && typeof faceGapMs === 'number'
            ? Math.max(FACE_HOLD_MS, 3 * faceGapMs) : 0;
        return isHologramMode && isFaceVisible && head?.seen && !head.debug
            && age >= 0 && age <= hold && Number.isFinite(width) && width > .02 ? width : null;
    },
    releaseHologram: function(owner, revision) {
        if (hologramOwner !== owner || hologramRevision !== revision) return Promise.resolve(false);
        return setHologram(false, owner);
    },
    getWorkspaceSceneState: function() {
        return { ready: !!renderer && !!scene && !sceneContextLost,
            scene: this.getStructure(),
            transitionProgress: typeof transitionProgress === 'number' && Number.isFinite(transitionProgress) ? Math.max(0, Math.min(1, transitionProgress)) : 1,
            hologram: hologramState(),
            head: { x: currFaceX, y: currFaceY, z: currFaceZ, seen: !!window.FridayTracking.head.seen },
            camera: { status: FridayCamera.state.status, detail: FridayCamera.state.detail, wanted: FridayCamera.state.wanted },
            handTracking: isHandTrackingMode, callHeld: callHold };
    },
    isHologramOn: function() {
        return typeof isHologramMode !== 'undefined' ? isHologramMode : false;
    },
    toggleHands: function() {
        if (typeof toggleHands === 'function') toggleHands();
    },
    isHandsOn: function() {
        return typeof isHandTrackingMode !== 'undefined' ? isHandTrackingMode : false;
    },
    toggleTimeLapse: function() {
        if (typeof toggleTimeLapse === 'function') return toggleTimeLapse();
    },
    isTimeLapseOn: function() {
        return typeof timeLapseActive !== 'undefined' ? timeLapseActive : false;
    }
};

// === GLOBAL MOOD SETTER (exposed for server/task-type mapping) ===
window.fridaySetMood = function(mood) {
    if (typeof setMood === 'function') setMood(mood.toUpperCase(), true);
};

// === HOLOGRAPHIC REACTIVITY API (Layer 1) ===
window.fridaySetHoloState = function(state, intensity) {
    if (typeof holoState !== 'undefined') holoState = state || 'idle';
    if (typeof holoStateIntensity !== 'undefined') holoStateIntensity = intensity || 0;
    var moodMap = {idle:'IDLE', speaking:'SPEAKING', executing:'EXECUTING', listening:'LISTENING',
                   curious:'CURIOUS', creative:'CREATIVE', protective:'PROTECTIVE',
                   focused:'FOCUSED', social:'SOCIAL', reflective:'REFLECTIVE'};
    var m = moodMap[state] || 'LISTENING';
    if (typeof setSystemMood === 'function') setSystemMood(m);
};

// === PROCESS ORB API (Layer 2) ===
window.fridayAddOrb = function(opts) {
    if (typeof processOrbManager !== 'undefined' && processOrbManager) processOrbManager.addOrb(opts);
};
window.fridayUpdateOrb = function(id, upd) {
    if (typeof processOrbManager !== 'undefined' && processOrbManager) processOrbManager.updateOrb(id, upd);
};
window.fridayRemoveOrb = function(id) {
    if (typeof processOrbManager !== 'undefined' && processOrbManager) processOrbManager.removeOrb(id);
};
window.fridayGetOrbs = function() {
    if (typeof processOrbManager !== 'undefined' && processOrbManager) return processOrbManager.getOrbs();
    return [];
};

// <orb-process-sync>
window.fridayOrbFinished = FridayOrbLife.isFinished;
// The server controls visibility. A producer that removes its record early
// leaves a completed orb on its existing five-minute clock.
window.fridaySyncOrbs = function(rows) {
    const existing = window.fridayGetOrbs?.() ?? [];
    const eIds = new Set(existing.map(o => o.id));
    const byId = new Map((rows || []).map(p => [p.id, p]));
    for (const p of rows || []) {
        if (p.orb_visible === false || p.dismissed) continue;
        const spec = Object.assign({}, p, {
            pid: p.id, label: p.label || p.name || 'Task', category: p.category || 'default',
            icon: p.orb_failed ? '⚠' : (p.icon || '⚡'), model: p.model || null,
            progress: p.progress == null ? null : p.progress, status: p.status || 'running'
        });
        if (!eIds.has(p.id)) window.fridayAddOrb?.(spec);
        window.fridayUpdateOrb?.(p.id, spec);
        if (p.steps) window.fridayUpdateThread?.(p.id, p.steps);
    }
    for (const o of existing) {
        const row = byId.get(o.id);
        if (row && (row.orb_visible === false || row.dismissed)) window.fridayRemoveOrb?.(o.id);
        else if (!row && !window.fridayOrbFinished(o)) window.fridayUpdateOrb?.(o.id, { status: 'completed', progress: 1 });
    }
};
// </orb-process-sync>

// === REASONING THREAD API (Layer 3) ===
window._fridayThreads = {};
window.fridayUpdateThread = function(orbId, steps) {
    window._fridayThreads[orbId] = steps || [];
    window.dispatchEvent(new CustomEvent('fridayThreadUpdate', {detail:{orbId:orbId, steps:steps||[]}}));
};
window.fridayAddThreadStep = function(orbId, step) {
    if (!window._fridayThreads[orbId]) window._fridayThreads[orbId] = [];
    window._fridayThreads[orbId].push(step);
    window.dispatchEvent(new CustomEvent('fridayThreadUpdate', {detail:{orbId:orbId, steps:window._fridayThreads[orbId]}}));
};

// === KEYBOARD CONTROLS ===
// Arrow keys belong to a focused control that uses them (radios, lists, menus,
// tabs, sliders: NS-33.1-2); the scene takes them only when no such control
// has focus.
var FRIDAY_ARROW_WIDGETS = 'select, [role="radio"], [role="radiogroup"], [role="listbox"], [role="option"], ' +
    '[role="menu"], [role="menuitem"], [role="menubar"], [role="tablist"], [role="tab"], [role="slider"], ' +
    '[role="spinbutton"], [role="grid"], [role="tree"], [role="treeitem"], [role="combobox"]';
document.addEventListener('keydown', function(e) {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.isContentEditable) return;
    if (e.defaultPrevented || (e.target.closest && e.target.closest(FRIDAY_ARROW_WIDGETS))) return;
    if (e.key === 'ArrowRight') { window.fridayVibe.nextStructure(); e.preventDefault(); }
    if (e.key === 'ArrowLeft')  { window.fridayVibe.prevStructure(); e.preventDefault(); }
});

// A radio group is one Tab stop's worth of choices: the arrow keys move to the
// next or previous choice and pick it, Home and End go to the first and last
// (WAI-ARIA radio group pattern; NS-33.1-2).
document.addEventListener('keydown', function(e) {
    var step = {ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1, Home: 'first', End: 'last'}[e.key];
    // A group that handles its own arrows (the display-style picker previews
    // without committing) has already taken the key: leave it alone.
    if (step === undefined || e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey) return;
    var radio = e.target && e.target.closest && e.target.closest('[role="radio"]');
    var group = radio && radio.closest('[role="radiogroup"]');
    if (!group) return;
    var all = Array.prototype.filter.call(group.querySelectorAll('[role="radio"]'), function(r) {
        return r.closest('[role="radiogroup"]') === group && r.getAttribute('aria-disabled') !== 'true'
            && !r.hasAttribute('disabled');
    });
    var at = all.indexOf(radio);
    if (at < 0 || !all.length) return;
    var to = step === 'first' ? 0 : step === 'last' ? all.length - 1 : (at + step + all.length) % all.length;
    e.preventDefault();
    all[to].focus();
    if (all[to].getAttribute('aria-checked') !== 'true') all[to].click();
});
