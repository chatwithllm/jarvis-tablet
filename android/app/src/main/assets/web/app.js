/* Jarvis tablet HUD — Phase 1 (PRD v2.2). Bridge client + rendering.
   Events use the PRD v2.2 names: talk_pressed, confirm_yes, confirm_no,
   cancel, ui_ready, data_request (WebView -> native) and the 13 native ->
   WebView events. There is NO data_response event in the PRD: native
   answers data_request by calling window.Jarvis.onData({scope, payload})
   directly (see docs/bridge-contract.md). */
(function () {
  "use strict";

  var VERSION = "0.1.0-phase1";
  var VIEWS = ["overview", "ha", "pve", "updates", "cams", "bots"];
  var STALE_MS = 5 * 60 * 1000;    // FR-16: stale mark after 5 min
  var OFFLINE_MS = 60 * 60 * 1000; // FR-16: offline badge after 60 min

  var state = {
    view: "overview",
    haOk: false,
    quiet: false,
    listening: false,
    states: [],
    statesAt: 0,
    confirmId: null,
    confirmEndsAt: 0,
  };

  function $(id) { return document.getElementById(id); }

  /* -- bridge -------------------------------------------------------- */
  function send(type, payload) {
    if (!window.JarvisBridge || !window.JarvisBridge.postMessage) return;
    var msg = payload || {};
    msg.type = type;
    window.JarvisBridge.postMessage(JSON.stringify(msg));
  }

  window.Jarvis = {
    version: VERSION,
    onNativeEvent: function (envelope) {
      var type = envelope.type;
      var p = envelope.payload || {};
      if (type === "wake_detected") { setListening(true); setTranscript("Listening…"); }
      else if (type === "listening_level") { pulseOrb(p.level || 0); }
      else if (type === "transcript_partial") { setTranscript(p.text || ""); }
      else if (type === "transcript_final") { setTranscript(p.text || ""); }
      else if (type === "thinking") { setTranscript(thinkingText(p.step)); }
      else if (type === "tts_started") { setTranscript(p.text || ""); }
      else if (type === "tts_finished") { setListening(false); }
      else if (type === "confirm_requested") { showConfirm(p); }
      else if (type === "confirm_resolved") { hideConfirm(); }
      else if (type === "refused") { setTranscript(p.message || "That is not allowed by voice."); }
      else if (type === "error") { onError(p); }
      else if (type === "status") { onStatus(p); }
      else if (type === "navigate") { if (p.view) showView(p.view); }
    },
    /* Data reply: NOT a bridge event (the PRD has no data_response).
       Native calls this directly via evaluateJavascript. */
    onData: function (envelope) {
      onData(envelope.scope, envelope.payload || {});
    },
  };

  function thinkingText(step) {
    if (step === "voice_phase2_pending") return "Voice arrives in Phase 2 — tap Talk again then.";
    if (step === "cancelled") return "Cancelled.";
    return "Thinking…";
  }

  function onError(p) {
    setListening(false);
    setTranscript(p.message || "Something went wrong.");
    if (p.code === "ha_unreachable") markOffline(true);
    if (p.code === "auth_required") {
      setTranscript("ContextForge sign-in expired. Voice is off until you re-enter the bearer token.");
    }
  }

  function onStatus(p) {
    state.haOk = !!p.ha;
    state.quiet = !!p.quiet;
    $("ha-dot").className = "dot" + (state.haOk ? "" : " off");
    $("status-text").textContent = state.haOk
      ? (state.quiet ? "Connected · quiet hours" : "Connected")
      : "ContextForge unreachable";
    $("quiet-badge").classList.toggle("hidden", !state.quiet);
    $("orb").classList.toggle("offline", !state.haOk);
    markOffline(!state.haOk);
  }

  /* -- views ---------------------------------------------------------- */
  function showView(name) {
    if (VIEWS.indexOf(name) === -1) return;
    state.view = name;
    VIEWS.forEach(function (v) {
      $("view-" + v).classList.toggle("active", v === name);
    });
    document.querySelectorAll("#rail button").forEach(function (b) {
      b.classList.toggle("active", b.getAttribute("data-view") === name);
    });
    document.querySelectorAll("#dock button[data-dock]").forEach(function (b) {
      b.classList.toggle("active", b.getAttribute("data-dock") === name);
    });
  }

  /* -- transcript / orb ------------------------------------------------ */
  function setTranscript(text) {
    $("transcript-text").textContent = text;
    $("transcript-text").classList.remove("muted");
  }
  function setListening(on) {
    state.listening = on;
    $("orb").classList.toggle("listening", on);
  }
  function pulseOrb(level) {
    var ring = $("orb-ring");
    ring.classList.toggle("live", level > 0.05);
    ring.style.transform = "scale(" + (1 + level * 0.25).toFixed(2) + ")";
  }

  /* -- confirm card (FR-24: 10 s, then it cancels) --------------------- */
  var confirmTimer = null;
  function showConfirm(p) {
    state.confirmId = p.id;
    state.confirmEndsAt = Date.now() + (p.expiry || 10000);
    var a = p.action || {};
    $("confirm-summary").textContent = a.summary || "Confirm this action?";
    $("confirm-card").classList.remove("hidden");
    clearInterval(confirmTimer);
    confirmTimer = setInterval(function () {
      var left = Math.max(0, state.confirmEndsAt - Date.now());
      $("confirm-timer").textContent = (left / 1000).toFixed(0) + "s";
      if (left <= 0) { hideConfirm(); }
    }, 250);
  }
  function hideConfirm() {
    clearInterval(confirmTimer);
    state.confirmId = null;
    $("confirm-card").classList.add("hidden");
  }

  /* -- data: HA states + pickup (PRD data_request, scope-based) -------- */
  function fetchStates() { send("data_request", { scope: "ha" }); }
  function fetchPickup() {
    // Overview scope carries the calendar events for the pickup banner;
    // from/to bound the -30..+15 min pickup window.
    var now = Date.now();
    send("data_request", {
      scope: "overview",
      from: new Date(now - 30 * 60000).toISOString(),
      to: new Date(now + 15 * 60000).toISOString(),
    });
  }

  function onData(scope, p) {
    if (!p.ok) {
      if (scope === "grocery") return; // Phase 3; nothing to show yet.
      markOffline(true);
      return;
    }
    if (p.states) {
      state.states = p.states;
      state.statesAt = Date.now();
      markOffline(false);
      renderAll();
    }
    if (p.events) renderBanner(p.events);
  }

  function markOffline(off) {
    $("offline-badge").classList.toggle("hidden", !off);
    $("orb").classList.toggle("offline", off);
  }

  function fmtTime(d) {
    return d.toTimeString().slice(0, 5);
  }
  function stamp() {
    if (!state.statesAt) return "";
    var el = $("updated-stamp");
    el.textContent = "Updated " + fmtTime(new Date(state.statesAt));
    el.classList.toggle("stale", Date.now() - state.statesAt > STALE_MS);
    return el.textContent;
  }

  function entityCards(filter) {
    return state.states.filter(filter).map(function (e) {
      var name = (e.attributes && e.attributes.friendly_name) || e.entity_id;
      return '<div class="card"><div class="card-title">' + escapeHtml(name) + '</div>' +
        '<div class="big">' + escapeHtml(String(e.state)) + '</div>' +
        '<div class="muted">' + escapeHtml(e.entity_id) + '</div></div>';
    }).join("");
  }
  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  function renderAll() {
    stamp();
    var lights = state.states.filter(function (e) { return e.entity_id.indexOf("light.") === 0; });
    var on = lights.filter(function (e) { return e.state === "on"; }).length;
    var weather = state.states.find(function (e) { return e.entity_id.indexOf("weather.") === 0; });
    $("overview-cards").innerHTML =
      '<div class="card"><div class="card-title">Lights</div><div class="big">' + on + " / " + lights.length +
      ' on</div><div class="muted">Tap actions arrive in Phase 2</div></div>' +
      (weather
        ? '<div class="card"><div class="card-title">Weather</div><div class="big">' +
          escapeHtml(String((weather.attributes && weather.attributes.temperature) || "—")) + '°</div><div class="muted">' +
          escapeHtml(String(weather.state)) + "</div></div>"
        : '<div class="card"><div class="card-title">Weather</div><div class="big">—</div><div class="muted">No weather entity yet</div></div>');
    $("ha-cards").innerHTML = entityCards(function (e) {
      return /^(light|switch|sensor|binary_sensor|climate|fan)\./.test(e.entity_id);
    }) || '<div class="card"><div class="card-title">No entities</div><div class="muted">Waiting for Home Assistant…</div></div>';
    $("pve-cards").innerHTML = entityCards(function (e) {
      return e.entity_id.indexOf("sensor.pve") === 0 || e.entity_id.indexOf("binary_sensor.pve") === 0;
    }) || '<div class="card"><div class="card-title">Proxmox</div><div class="muted">No PVE sensors exposed yet — Phase 3 polish.</div></div>';
    $("updates-cards").innerHTML = entityCards(function (e) {
      return e.entity_id.indexOf("update.") === 0;
    }) || '<div class="card"><div class="card-title">Updates</div><div class="muted">No update entities yet.</div></div>';
    $("cams-cards").innerHTML = entityCards(function (e) {
      return e.entity_id.indexOf("camera.") === 0;
    }) || '<div class="card"><div class="card-title">Cameras</div><div class="muted">Live streams arrive in Phase 3 polish.</div></div>';
    $("bots-cards").innerHTML = entityCards(function (e) {
      return e.entity_id.indexOf("sensor.") === 0 && /bot|grok|agent/i.test(e.entity_id);
    }) || '<div class="card"><div class="card-title">Bots</div><div class="muted">No bot sensors yet.</div></div>';
  }

  /* FR-11 pickup banner: -30 show, cyan to -10, amber -10..start, red start..+15 */
  function renderBanner(events) {
    var banner = $("banner");
    var now = Date.now();
    var best = null;
    var bestStart = Infinity;
    events.forEach(function (e) {
      var a = e.attributes || {};
      if (!a.start_time) return;
      var start = new Date(a.start_time).getTime();
      if (start < bestStart) { bestStart = start; best = e; }
    });
    if (!best) { banner.classList.add("hidden"); return; }
    var a = best.attributes || {};
    var start = new Date(a.start_time).getTime();
    var mins = (start - now) / 60000;
    if (mins > 30 || mins < -15) { banner.classList.add("hidden"); return; }
    banner.classList.remove("hidden");
    banner.classList.toggle("amber", mins <= 10 && mins > 0);
    banner.classList.toggle("red", mins <= 0);
    $("banner-text").textContent = "Pickup: " + (a.message || a.summary || "Kids") + " · " + fmtTime(new Date(start));
    $("banner-clock").textContent = mins > 0 ? "in " + Math.round(mins) + " min" : "now";
  }

  /* -- clock ------------------------------------------------------------ */
  function tickClock() {
    var now = new Date();
    $("clock").textContent = fmtTime(now);
    $("date").textContent = now.toLocaleDateString(undefined, {
      weekday: "long", month: "long", day: "numeric",
    });
    if (state.statesAt && Date.now() - state.statesAt > OFFLINE_MS) markOffline(true);
  }

  /* -- wiring ----------------------------------------------------------- */
  /* View switching is local: the PRD has no WebView -> Native navigate
     event (navigate is Native -> WebView only). */
  document.querySelectorAll("#rail button").forEach(function (b) {
    b.addEventListener("click", function () {
      showView(b.getAttribute("data-view"));
    });
  });
  document.querySelectorAll("#dock button[data-dock]").forEach(function (b) {
    b.addEventListener("click", function () {
      showView(b.getAttribute("data-dock"));
    });
  });
  $("orb").addEventListener("click", function () {
    if (state.listening) {
      // Cancel the turn — or the pending confirm when one is showing.
      send("cancel", state.confirmId ? { id: state.confirmId } : {});
      setListening(false);
    } else {
      send("talk_pressed", {});
      setListening(true);
    }
  });
  $("confirm-yes").addEventListener("click", function () {
    if (state.confirmId) send("confirm_yes", { id: state.confirmId });
    hideConfirm();
  });
  $("confirm-no").addEventListener("click", function () {
    if (state.confirmId) send("confirm_no", { id: state.confirmId });
    hideConfirm();
  });

  tickClock();
  setInterval(tickClock, 1000);
  send("ui_ready", {});
  fetchStates();
  fetchPickup();
  setInterval(fetchStates, 30000);
  setInterval(fetchPickup, 60000);
})();
