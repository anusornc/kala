"use strict";
/* Kala snapshot explorer — the frozen half of the graph view: the
   monitor's three shared view modes over a pre-baked store export.
   Static by design (ADR-0012): no server, no polling — one JSON per
   store, drilldowns pre-baked by site/export.clj. Session filtering and
   the "turns up to t" slider run client-side. */

const { $, esc, kw, fetchJson, buildVm, SESSION_COLORS, renderForce, renderRail, renderColumns } = GraphView;

const STORES = {
  "showcase": { file: "data/showcase.json", label: "showcase (recorded live)" },
  "trial-3b": { file: "data/trial-3b.json", label: "multi-agent trial 3b (real)" },
};

const state = {
  storeName: "showcase",
  payloads: {},   // storeName -> raw JSON payload
  variant: null,
  asOf: null,     // null = head
  session: "",    // session label string; "" = all
};

const detailEl = $("#detail");

// ---- fetching -------------------------------------------------------------------

async function loadStore(name) {
  if (state.payloads[name]) return state.payloads[name];
  const body = await fetchJson(STORES[name].file);
  state.payloads[name] = body;
  return body;
}

// ---- scoping: session filter + "turns up to t" (both client-side) ---------------

function scopePayload(payload, sessionLabel, asOf) {
  const edges = payload.edges.map((e) => ({ ...e, kind: kw(e.kind) }));
  const sessionEid = sessionLabel
    ? (payload.sessions.find((s) => s.label === sessionLabel) || {}).id : null;
  const kept = payload.turns.filter((t) =>
    (sessionEid == null || t.session === sessionEid) &&
    (asOf == null || t.t <= asOf));
  const keptIds = new Set(kept.map((t) => t.id));
  const entById = new Map(payload.entities.map((e) => [e.id, e]));
  const keptEnts = new Set();
  const edges2 = edges.filter((e) => {
    if (e.kind === "cite") return keptIds.has(e.from) && keptIds.has(e.to);
    if (e.kind === "ref") {
      const ok = entById.has(e.from) && entById.has(e.to);
      if (ok) { keptEnts.add(e.from); keptEnts.add(e.to); }
      return ok;
    }
    // assert/retract: from is a turn, to an entity
    if (!keptIds.has(e.from)) return false;
    if (entById.has(e.to)) { keptEnts.add(e.to); return true; }
    if (keptIds.has(e.to)) return true;
    return false;
  });
  return {
    t: asOf != null ? asOf : payload.t,
    sessions: payload.sessions,
    turns: kept,
    entities: payload.entities.filter((e) => keptEnts.has(e.id)),
    edges: edges2,
    turnsDatoms: payload["turns-datoms"] || {},
    trails: payload.trails || {},
  };
}

// ---- the graph view's ctx ---------------------------------------------------------

// drilldowns read the pre-baked maps, exactly what the live endpoints return
function drillTurn(t) {
  state.pinned = true;
  const datoms = (state.payload.turnsDatoms || {})["t" + t] || [];
  detailEl.innerHTML = GraphView.turnDetailHtml(state.vm, t, datoms);
  detailEl.hidden = false;
}

function drillEntity(id) {
  state.pinned = true;
  const trail = (state.payload.trails || {})[id] || [];
  detailEl.innerHTML = GraphView.entityDetailHtml(state.vm, id, trail);
  detailEl.hidden = false;
}

const drill = (id) => id.startsWith("t") ? drillTurn(id.slice(1)) : drillEntity(id);

const ctx = {
  vm: () => state.vm,
  pinned: () => state.pinned,
  detail: detailEl,
  drill,
};

// ---- switchers + render -------------------------------------------------------------------

const VARIANTS = [
  { key: "force", label: "force soup", render: renderForce },
  { key: "rail", label: "turn rail", render: renderRail },
  { key: "columns", label: "columns", render: renderColumns },
];

function currentVariant() {
  const v = new URLSearchParams(location.search).get("view");
  return VARIANTS.find((x) => x.key === v) || VARIANTS[0];
}

function cycleVariant(d) {
  const i = VARIANTS.indexOf(state.variant);
  state.variant = VARIANTS[(i + d + VARIANTS.length) % VARIANTS.length];
  try { history.replaceState(null, "", "?view=" + state.variant.key); } catch (_) {}
  document.getElementById("vlabel").textContent = state.variant.label;
  render();
}

function render() {
  const stage = $("#stage");
  stage.innerHTML = "";
  detailEl.hidden = true;
  state.pinned = false;
  const raw = state.payloads[state.storeName];
  if (!raw) { $("#stat").textContent = "loading…"; return; }
  const scoped = scopePayload(raw, state.session, state.asOf);
  state.vm = buildVm(scoped);
  state.payload = scoped;
  const nNodes = state.vm.turns.length + state.vm.entities.length;
  const scopeTxt = [
    state.session ? "session " + state.session : "all sessions",
    state.asOf != null ? "turns up to t" + state.asOf : "head t" + raw.t,
  ].join(" · ");
  $("#stat").innerHTML =
    `<b>${nNodes}</b> nodes (turn ${state.vm.turns.length} · entity ${state.vm.entities.length})` +
    ` · <b>${state.vm.edges.length}</b> edges · ${scopeTxt}`;
  renderLegend();
  state.variant.render(ctx, state.vm, $("#stage"));
}

function renderLegend() {
  const el = $("#legend");
  el.innerHTML = "";
  state.vm.sessions.forEach((s, i) => {
    const chip = document.createElement("span");
    chip.innerHTML = `<span class="chip" style="background:${SESSION_COLORS[i % SESSION_COLORS.length]}"></span>${esc(s.label)}`;
    el.appendChild(chip);
  });
  const shapes = document.createElement("span");
  shapes.innerHTML =
    `<span>·</span> <span class="dot"></span> entity` +
    ` <span class="dia"></span> turn — assert · <span style="color:var(--fail)">✕</span> retract` +
    ` · <span style="color:var(--acc)">⌒</span> citation · <span style="color:var(--ok)">—</span> ref — hover anything to trace it`;
  el.appendChild(shapes);
}

// ---- wiring -------------------------------------------------------------------------------

$("#prev").addEventListener("click", () => cycleVariant(-1));
$("#next").addEventListener("click", () => cycleVariant(1));
addEventListener("keydown", (e) => {
  if (e.target.closest && e.target.closest("input,textarea,select,[contenteditable]")) return;
  if (e.key === "ArrowLeft") cycleVariant(-1);
  if (e.key === "ArrowRight") cycleVariant(1);
  if (e.key === "Escape") { state.pinned = false; detailEl.hidden = true; }
});

$("#store").addEventListener("change", async (e) => {
  state.storeName = e.target.value;
  state.session = "";
  state.asOf = null;
  state.payloads[state.storeName] = await loadStore(state.storeName);
  await hydrateSelectors();
  render();
});
$("#session").addEventListener("change", (e) => {
  state.session = e.target.value;
  render();
});
let uptoTimer = 0;
$("#upto").addEventListener("input", (e) => {
  const t = parseInt(e.target.value, 10);
  const head = state.payloads[state.storeName].t;
  $("#upto-label").textContent = t >= head ? "head" : "t" + t;
  clearTimeout(uptoTimer);
  uptoTimer = setTimeout(() => {
    state.asOf = t >= head ? null : t;
    render();
  }, 120);
});

async function hydrateSelectors() {
  const raw = state.payloads[state.storeName];
  const sel = $("#session");
  sel.innerHTML = '<option value="">all sessions</option>';
  for (const s of raw.sessions) {
    const opt = document.createElement("option");
    opt.value = s.label;
    opt.textContent = `${s.label} (${s.turns})`;
    sel.appendChild(opt);
  }
  state.session = "";
  const slider = $("#upto");
  slider.max = Math.max(1, raw.t);
  slider.value = raw.t;
  $("#upto-label").textContent = "head";
  state.asOf = null;
}

(async function boot() {
  state.variant = currentVariant();
  document.getElementById("vlabel").textContent = state.variant.label;
  state.payloads[state.storeName] = await loadStore(state.storeName);
  await hydrateSelectors();
  render();
})();
