"use strict";
/* Kala monitor UI — the live half of the graph view: fetching, the
   follow/freeze posture, the session/window/as-of filters, and the
   app chrome around GraphView's three shared view modes. Follow polls
   /api/head and refetches on change; freeze pins a belief state and
   ignores commits; the as-of slider travels the total order. Click a
   turn for its datoms, an entity for its correction trail. The monitor
   is read-only — every read travels the wire (ADR-0012). */

const { $, esc, fetchJson, renderForce, renderRail, renderColumns } = GraphView;

// ---- state -----------------------------------------------------------------

const state = {
  variant: null,
  payload: null,   // the last /api/graph payload
  head: 1,         // the t the listener last saw
  asOf: null,      // pinned belief state; null = live head
  session: "",     // :memory.session/id string filter; "" = all
  window: "",      // recent-t window; "" = all
  follow: true,    // the live posture
  pinned: false,   // the detail card is pinned by a click
  vm: null,        // the current view model
};

const detailEl = $("#detail");

// ---- fetching -----------------------------------------------------------------

async function loadHead() {
  const body = await fetchJson("/api/head");
  state.head = body.t;
  const slider = $("#asof");
  slider.max = Math.max(1, state.head);
  if (state.asOf == null) slider.value = state.head;
  else slider.value = state.asOf;
}

async function loadSessions() {
  const body = await fetchJson("/api/sessions");
  const sel = $("#session");
  const current = state.session;
  sel.innerHTML = '<option value="">all sessions</option>';
  for (const s of body.sessions) {
    const opt = document.createElement("option");
    opt.value = s.label;
    opt.textContent = `${s.label} (${s.turns})`;
    sel.appendChild(opt);
  }
  sel.value = current;
}

async function loadGraph() {
  const q = new URLSearchParams();
  if (state.session) q.set("session", state.session);
  if (state.asOf != null) q.set("as-of", String(state.asOf));
  if (state.window) q.set("window", state.window);
  const body = await fetchJson("/api/graph" + (q.toString() ? "?" + q : ""));
  state.payload = body;
  render();
}

async function pollTick() {
  if (!state.follow || state.asOf != null) return;
  try {
    const body = await fetchJson("/api/head");
    if (body.t !== state.head || !state.payload || state.payload.t !== body.t) {
      state.head = body.t;
      $("#asof").max = Math.max(1, state.head);
      $("#asof").value = state.head;
      await loadGraph();
    }
  } catch (_) { /* the transactor hiccuped; try again next tick */ }
}

// ---- the graph view's ctx ---------------------------------------------------------

async function drillTurn(t) {
  state.pinned = true;
  try {
    const body = await fetchJson(`/api/turn?t=${t}`);
    detailEl.innerHTML = GraphView.turnDetailHtml(state.vm, t, body.datoms);
  } catch (err) {
    detailEl.innerHTML = `<span class="bad">${esc(err.kind || "")}</span> ${esc(err.message)}`;
  }
  detailEl.hidden = false;
}

async function drillEntity(id) {
  state.pinned = true;
  try {
    const body = await fetchJson(`/api/entity?id=${id}`);
    detailEl.innerHTML = GraphView.entityDetailHtml(state.vm, id, body.trail);
  } catch (err) {
    detailEl.innerHTML = `<span class="bad">${esc(err.kind || "")}</span> ${esc(err.message)}`;
  }
  detailEl.hidden = false;
}

// the shared click-through: turn ids drill into their datoms, entity ids
// into their correction trail
const drill = (id) => id.startsWith("t") ? drillTurn(id.slice(1)) : drillEntity(id);

const ctx = {
  vm: () => state.vm,
  pinned: () => state.pinned,
  detail: detailEl,
  drill,
};

// ---- switcher ---------------------------------------------------------------------------

const VARIANTS = [
  { key: "force", label: "force soup", render: renderForce },
  { key: "rail", label: "turn rail", render: renderRail },
  { key: "columns", label: "columns", render: renderColumns },
];

function currentVariant() {
  const v = new URLSearchParams(location.search).get("view");
  return VARIANTS.find((x) => x.key === v) || VARIANTS[0];
}

function showVariant(v) {
  state.variant = v;
  document.getElementById("vlabel").textContent = v.label;
  document.title = "Kala · monitor · " + v.label;
  try { history.replaceState(null, "", "?view=" + v.key); } catch (_) { /* file:// */ }
  render();
}

function cycleVariant(d) {
  const i = VARIANTS.indexOf(state.variant);
  showVariant(VARIANTS[(i + d + VARIANTS.length) % VARIANTS.length]);
}

function render() {
  const stage = $("#stage");
  stage.innerHTML = "";
  detailEl.hidden = true;
  state.pinned = false;
  if (!state.payload) return;
  state.vm = GraphView.buildVm(state.payload);
  const nNodes = state.vm.turns.length + state.vm.entities.length;
  const scope = [
    state.session ? "session " + state.session : "all sessions",
    state.asOf != null ? "as-of t" + state.asOf : "head t" + state.vm.t,
  ].join(" · ");
  $("#stat").innerHTML =
    `<b>${nNodes}</b> nodes (turn ${state.vm.turns.length} · entity ${state.vm.entities.length})` +
    ` · <b>${state.vm.edges.length}</b> edges · ${scope}`;
  state.variant.render(ctx, state.vm, $("#stage"));
}

// ---- posture: follow / freeze -------------------------------------------------------------

function setPosture(follow) {
  state.follow = follow;
  const btn = $("#posture-btn");
  if (follow) {
    state.asOf = null;
    btn.textContent = "freeze";
    btn.classList.remove("frozen");
    $("#asof").value = state.head;
    $("#asof-label").textContent = "now";
  } else {
    state.asOf = state.asOf != null ? state.asOf : state.head;
    btn.textContent = "resume";
    btn.classList.add("frozen");
    $("#asof").value = state.asOf;
    $("#asof-label").textContent = "t" + state.asOf;
  }
  updatePostureBadge();
  loadGraph();
}

function updatePostureBadge() {
  $("#posture").innerHTML = state.follow
    ? '<b>follow</b> — each commit renders as the transactor lands it'
    : `<b>freeze</b> — pinned as-of t${state.asOf}; commits accumulate unseen`;
}

// ---- wiring ---------------------------------------------------------------------------

$("#prev").addEventListener("click", () => cycleVariant(-1));
$("#next").addEventListener("click", () => cycleVariant(1));
addEventListener("keydown", (e) => {
  if (e.target.closest && e.target.closest("input,textarea,select,[contenteditable]")) return;
  if (e.key === "ArrowLeft") cycleVariant(-1);
  if (e.key === "ArrowRight") cycleVariant(1);
  if (e.key === "Escape") { state.pinned = false; detailEl.hidden = true; }
});

$("#session").addEventListener("change", (e) => {
  state.session = e.target.value;
  loadGraph();
});
$("#window").addEventListener("change", (e) => {
  state.window = e.target.value;
  loadGraph();
});
let asofTimer = 0;
$("#asof").addEventListener("input", (e) => {
  const t = parseInt(e.target.value, 10);
  $("#asof-label").textContent = t >= state.head ? "now" : "t" + t;
  clearTimeout(asofTimer);
  asofTimer = setTimeout(() => {
    if (t >= state.head) {
      if (state.asOf != null) setPosture(true);
    } else {
      state.asOf = t;
      state.follow = false;
      $("#posture-btn").textContent = "resume";
      $("#posture-btn").classList.add("frozen");
      updatePostureBadge();
      loadGraph();
    }
  }, 120);
});
$("#posture-btn").addEventListener("click", () => setPosture(!state.follow));

setInterval(pollTick, 1000);

(async function boot() {
  state.variant = currentVariant();
  document.getElementById("vlabel").textContent = state.variant.label;
  try {
    await loadHead();
    await loadSessions();
    await loadGraph();
    updatePostureBadge();
  } catch (err) {
    $("#stat").innerHTML = `<span style="color:var(--fail)">${esc(err.kind || "")}</span> ${esc(err.message)}`;
    $("#posture").textContent = "no store drawn — the monitor shows nothing without a live transactor (ADR-0012)";
  }
  showVariant(state.variant);
})();
