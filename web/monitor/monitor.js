"use strict";
/* Kala monitor UI — hand-rolled SVG, zero dependencies. Three view modes
   over one shared view (selection, filter, as-of): force soup, turn rail,
   bipartite columns. Follow polls /api/head and refetches on change;
   freeze pins a belief state and ignores commits; the as-of slider
   travels the total order. Click a turn for its datoms, an entity for its
   correction trail. The monitor is read-only — every read travels the
   wire (ADR-0012). */

const $ = (sel) => document.querySelector(sel);

function svgEl(name, attrs, text) {
  const n = document.createElementNS("http://www.w3.org/2000/svg", name);
  if (attrs) for (const k in attrs) n.setAttribute(k, attrs[k]);
  if (text != null) n.textContent = text;
  return n;
}

function esc(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;"); }

function svgPoint(svg, e) {
  const r = svg.getBoundingClientRect();
  return { x: e.clientX - r.left, y: e.clientY - r.top };
}

const SESSION_COLORS = ["#e0a458", "#7bc47f", "#6ea8dc", "#c792ea", "#d7ba7d", "#4ec9b0", "#61afef", "#e5c07b"];

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

// ---- fetching -----------------------------------------------------------------

async function fetchJson(path) {
  const res = await fetch(path);
  const body = await res.json();
  if (!body.ok) {
    const err = new Error(body.message || "request failed");
    err.kind = body.kind;
    throw err;
  }
  return body;
}

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

// ---- view model -----------------------------------------------------------------

function buildVm(payload) {
  // the payload speaks EDN — keywords arrive with their leading colon
  // (":assert", ":memory/source"). Edge kinds normalize once here so the
  // renderers compare plain words; attributes keep the colon (they are
  // EDN idents and display as such).
  const kw = (s) => (s && s[0] === ":") ? s.slice(1) : s;
  const turns = payload.turns;
  const entities = payload.entities;
  const edges = payload.edges.map((e) => ({ ...e, kind: kw(e.kind) }));
  const byT = new Map(turns.map((t) => [t.id, t]));
  const entById = new Map(entities.map((e) => [e.id, e]));
  // session colors: the menu order (sorted by label) is the palette order;
  // unattributed turns — the bootstrap, the schema — stay neutral
  const sessionColor = new Map();
  payload.sessions.forEach((s, i) => sessionColor.set(s.id, SESSION_COLORS[i % SESSION_COLORS.length]));
  const colorOf = (session) => (session != null && sessionColor.has(session))
    ? sessionColor.get(session) : "#8a8f98";
  // entity touch spans for the rail and the columns
  const touches = new Map(entities.map((e) => [e.id, []]));
  for (const e of edges) {
    if (e.kind === "assert" || e.kind === "retract") {
      const t = byT.get(e.from);
      if (touches.has(e.to) && t) touches.get(e.to).push(t.t);
    }
  }
  const span = new Map();
  for (const [id, ts] of touches) {
    span.set(id, ts.length ? { first: Math.min(...ts), last: Math.max(...ts) } : null);
  }
  return { t: payload.t, turns, entities, edges, byT, entById, colorOf, span,
           sessions: payload.sessions };
}

// ---- hover + click -----------------------------------------------------------------

const detailEl = $("#detail");

function wireHover(svg, onNodeClick) {
  const adj = new Map();
  state.vm.edges.forEach((e, i) => {
    if (!adj.has(e.from)) adj.set(e.from, []);
    if (!adj.has(e.to)) adj.set(e.to, []);
    adj.get(e.from).push({ i, other: e.to });
    adj.get(e.to).push({ i, other: e.from });
  });
  const nodeEls = new Map(), edgeEls = [];
  svg.querySelectorAll("[data-node]").forEach((el) => nodeEls.set(el.dataset.node, el));
  svg.querySelectorAll("[data-edge]").forEach((el) => edgeEls.push(el));
  const clear = () => {
    if (state.pinned) return;
    svg.classList.remove("hl");
    svg.querySelectorAll(".on,.nbr").forEach((el) => el.classList.remove("on", "nbr"));
    detailEl.hidden = true;
  };
  const incidentSummary = (id) => {
    const inc = (adj.get(id) || []).map(({ i }) => state.vm.edges[i]);
    const byKind = {};
    for (const e of inc) (byKind[e.kind] = byKind[e.kind] || new Set()).add(e.attr);
    return Object.entries(byKind)
      .map(([kind, attrs]) => `${kind}: ${[...attrs].join(", ")}`)
      .join("<br>");
  };
  const hover = (id) => {
    if (state.pinned) return;
    svg.classList.add("hl");
    const nEl = nodeEls.get(id);
    if (nEl) nEl.classList.add("on");
    (adj.get(id) || []).forEach(({ i, other }) => {
      if (edgeEls[i]) edgeEls[i].classList.add("on");
      const o = nodeEls.get(other);
      if (o) o.classList.add("nbr");
    });
    quickDetail(id, incidentSummary(id));
  };
  const hoverEdge = (i) => {
    if (state.pinned) return;
    const e = state.vm.edges[i];
    if (!e) return;
    svg.classList.add("hl");
    edgeEls[i] && edgeEls[i].classList.add("on");
    [nodeEls.get(e.from), nodeEls.get(e.to)].forEach((o) => o && o.classList.add("nbr"));
    detailEl.innerHTML =
      `<b>${esc(e.kind)} edge</b> <span class="dim">${esc(e.attr)}</span><br>` +
      `${esc(e.from)} → ${esc(e.to)}`;
    detailEl.hidden = false;
  };
  svg.addEventListener("pointerover", (e) => {
    const n = e.target.closest("[data-node]");
    if (n) { hover(n.dataset.node); return; }
    const el = e.target.closest("[data-edge]");
    if (el) hoverEdge(edgeEls.indexOf(el));
  });
  svg.addEventListener("pointerleave", clear);
  svg.addEventListener("click", (e) => {
    const t = e.target.closest("[data-node]");
    if (!t || !t.dataset.node.startsWith("t") && !state.vm.entById.has(t.dataset.node)) return;
    onNodeClick(t.dataset.node);
  });
}

function quickDetail(id, incident) {
  const vm = state.vm;
  if (id.startsWith("t")) {
    const turn = vm.byT.get(id);
    if (!turn) return;
    detailEl.innerHTML =
      `<b>turn node · t = ${turn.t}</b> <span class="dim">${turn.session != null ? esc(sessionLabel(turn.session)) : "unattributed"}</span><br>` +
      (incident || "") + `<br><span class="dim">click for the datoms it transacted</span>`;
  } else {
    const ent = vm.entById.get(id);
    if (!ent) return;
    detailEl.innerHTML =
      `<b>entity node · ${esc(ent.label)}</b> <span class="dim">${esc(ent.kind || "")}</span><br>` +
      (incident || "") + `<br><span class="dim">click for its correction trail</span>`;
  }
  detailEl.hidden = false;
}

function sessionLabel(sessionEid) {
  const s = state.vm.sessions.find((x) => x.id === sessionEid);
  return s ? s.label : "session " + sessionEid;
}

async function drillTurn(t) {
  state.pinned = true;
  try {
    const body = await fetchJson(`/api/turn?t=${t}`);
    const lines = body.datoms.map(([e, a, v, added]) =>
      `${esc(a)} ${added ? '<span class="good">add</span>' : '<span class="bad">retract</span>'} → ${e} ${esc(v)}`);
    detailEl.innerHTML =
      `<b>turn node · t = ${t}</b> <span class="dim">${body.datoms.length} datoms</span><br>` +
      (lines.length ? lines.join("<br>") : '<span class="dim">no datoms</span>');
  } catch (err) {
    detailEl.innerHTML = `<span class="bad">${esc(err.kind || "")}</span> ${esc(err.message)}`;
  }
  detailEl.hidden = false;
}

async function drillEntity(id) {
  state.pinned = true;
  try {
    const body = await fetchJson(`/api/entity?id=${id}`);
    const ent = state.vm.entById.get(id);
    const lines = body.trail.map(([t, a, v, added, session]) =>
      `t${t}${session != null ? ` · ${esc(sessionLabel(session))}` : ""} · ` +
      `${esc(a)} ${added ? '<span class="good">+</span>' : '<span class="bad">−</span>'} ${esc(v)}`);
    detailEl.innerHTML =
      `<b>entity node · ${ent ? esc(ent.label) : id}</b> <span class="dim">correction trail · ${body.trail.length} datoms</span><br>` +
      (lines.length ? lines.join("<br>") : '<span class="dim">no history</span>');
  } catch (err) {
    detailEl.innerHTML = `<span class="bad">${esc(err.kind || "")}</span> ${esc(err.message)}`;
  }
  detailEl.hidden = false;
}

// the shared click-through: turn ids drill into their datoms, entity ids
// into their correction trail
const drill = (id) => id.startsWith("t") ? drillTurn(id.slice(1)) : drillEntity(id);

// entities sort by their first touch — the rail lanes and the columns
// both read first-touch order
const byFirstTouch = (vm) => (a, b) => {
  const sa = vm.span.get(a.id), sb = vm.span.get(b.id);
  return (sa ? sa.first : 1e18) - (sb ? sb.first : 1e18) || a.id.localeCompare(b.id);
};

// ---- view mode A: force soup -------------------------------------------------------

function renderForce(vm, stage) {
  const W = Math.max(900, stage.clientWidth || 900);
  const H = Math.max(600, stage.clientHeight || 600);
  const svg = svgEl("svg", { width: W, height: H });
  svg.append(svgEl("text", { x: 16, y: 22, class: "cap" },
    "force soup — physics only, drag any node; t order is nowhere"));
  const nodes = [], geo = {};
  vm.turns.forEach((t) => { const n = { id: t.id, kind: "turn", x: 0, y: 0, vx: 0, vy: 0 }; nodes.push(n); geo[t.id] = n; });
  vm.entities.forEach((e) => { const n = { id: e.id, kind: "entity", x: 0, y: 0, vx: 0, vy: 0 }; nodes.push(n); geo[e.id] = n; });
  nodes.forEach((n, i) => {
    const a = (i / nodes.length) * 2 * Math.PI, r = 170 + ((i * 37) % 60);
    n.x = W / 2 + r * Math.cos(a); n.y = H / 2 + r * Math.sin(a);
  });
  const gE = svgEl("g"), gN = svgEl("g");
  svg.append(gE, gN);
  const edgeEls = vm.edges.map((e) => {
    const l = svgEl("line", { class: "edge " + e.kind, "data-edge": e.from });
    if (e.kind === "assert") l.style.stroke = vm.colorOf(vm.byT.get(e.from) ? vm.byT.get(e.from).session : null);
    gE.append(l);
    return l;
  });
  const nodeEls = new Map();
  nodes.forEach((n) => {
    const el = n.kind === "turn"
      ? svgEl("rect", { class: "node turn", "data-node": n.id, x: -4.5, y: -4.5, width: 9, height: 9 })
      : svgEl("circle", { class: "node entity", "data-node": n.id, r: 5 });
    if (n.kind === "turn") el.style.fill = vm.colorOf(vm.byT.get(n.id) ? vm.byT.get(n.id).session : null);
    nodeEls.set(n.id, el);
    gN.append(el);
  });
  stage.append(svg);
  wireHover(svg, drill);

  let alpha = 1, raf = 0, dragging = null, moved = 0;
  function paint() {
    vm.edges.forEach((e, i) => {
      const l = edgeEls[i], a = geo[e.from], b = geo[e.to];
      if (!a || !b) return;
      l.setAttribute("x1", a.x); l.setAttribute("y1", a.y);
      l.setAttribute("x2", b.x); l.setAttribute("y2", b.y);
    });
    nodes.forEach((n) => {
      const el = nodeEls.get(n.id);
      if (n.kind === "turn") el.setAttribute("transform", `translate(${n.x},${n.y}) rotate(45)`);
      else { el.setAttribute("cx", n.x); el.setAttribute("cy", n.y); }
    });
  }
  function step() {
    alpha = Math.max(0.012, alpha * 0.988);
    for (let i = 0; i < nodes.length; i++) {
      const a = nodes[i];
      for (let j = i + 1; j < nodes.length; j++) {
        const b = nodes[j];
        let dx = a.x - b.x, dy = a.y - b.y, d2 = dx * dx + dy * dy;
        if (d2 < 0.5) d2 = 0.5;
        if (d2 > 40000) continue;
        const d = Math.sqrt(d2), f = Math.min(4, 1600 * alpha / d2);
        dx /= d; dy /= d;
        a.vx += dx * f; a.vy += dy * f;
        b.vx -= dx * f; b.vy -= dy * f;
      }
    }
    for (const e of vm.edges) {
      const a = geo[e.from], b = geo[e.to];
      if (!a || !b) continue;
      const rest = e.kind === "cite" ? 42 : 72;
      let dx = b.x - a.x, dy = b.y - a.y;
      const d = Math.hypot(dx, dy) || 1;
      const f = (d - rest) * 0.0045 * (e.kind === "cite" ? 0.5 : 1);
      dx /= d; dy /= d;
      a.vx += dx * f; a.vy += dy * f;
      b.vx -= dx * f; b.vy -= dy * f;
    }
    const cap = Math.max(1, alpha * 40);
    for (const n of nodes) {
      n.vx += (W / 2 - n.x) * 0.0015;
      n.vy += (H / 2 - n.y) * 0.0015;
      n.vx *= 0.82; n.vy *= 0.82;
      n.x = Math.max(20, Math.min(W - 20, n.x + Math.max(-cap, Math.min(cap, n.vx))));
      n.y = Math.max(20, Math.min(H - 20, n.y + Math.max(-cap, Math.min(cap, n.vy))));
    }
  }
  function loop() {
    step(); paint();
    raf = (alpha > 0.013 || dragging) ? requestAnimationFrame(loop) : 0;
  }
  loop();
  svg.addEventListener("pointerdown", (e) => {
    const t = e.target.closest("[data-node]");
    if (!t) return;
    dragging = geo[t.dataset.node];
    moved = 0;
    svg.setPointerCapture(e.pointerId);
    const p = svgPoint(svg, e);
    dragging.x = p.x; dragging.y = p.y; dragging.vx = dragging.vy = 0;
    alpha = Math.max(alpha, 0.35);
    if (!raf) loop();
  });
  svg.addEventListener("pointermove", (e) => {
    if (!dragging) return;
    const p = svgPoint(svg, e);
    moved += Math.abs(p.x - dragging.x) + Math.abs(p.y - dragging.y);
    dragging.x = p.x; dragging.y = p.y;
  });
  svg.addEventListener("pointerup", () => { dragging = null; });
  svg.addEventListener("click", (e) => {
    if (moved > 4) { e.stopPropagation(); moved = 0; }
  }, true);
}

// ---- view mode B: turn rail ----------------------------------------------------------

function renderRail(vm, stage) {
  const STEP = 16, PADL = 240, PADR = 40, RAIL_Y = 120, ROW_H = 17, GAP = 9;
  const X = (t) => PADL + (t - 1) * STEP;
  const ents = [...vm.entities].sort(byFirstTouch(vm));
  const rowLast = [];
  for (const e of ents) {
    const sp = vm.span.get(e.id);
    const first = sp ? sp.first : 1e18, last = sp ? sp.last : -1;
    let r = -1;
    for (let i = 0; i < rowLast.length; i++) if (rowLast[i] + GAP < first) { r = i; break; }
    if (r < 0) { r = rowLast.length; rowLast.push(-99); }
    rowLast[r] = last;
    e.row = r;
  }
  const rowY = (e) => RAIL_Y + 44 + e.row * ROW_H;
  const maxT = Math.max(1, vm.t);
  const W = PADL + maxT * STEP + PADR;
  const H = RAIL_Y + 44 + rowLast.length * ROW_H + 70;
  const svg = svgEl("svg", { width: W, height: H });
  svg.append(svgEl("text", { x: 16, y: 24, class: "cap" },
    "turn rail — one diamond per turn, left→right = t; entity lanes below, dots at touching turns"));
  const nodeEls = new Map(), edgeEls = [];
  // citation arcs above the rail
  vm.edges.forEach((e) => {
    if (e.kind !== "cite") return;
    const from = vm.byT.get(e.from), to = vm.byT.get(e.to);
    if (!from || !to) return;
    const x1 = X(from.t), x2 = X(to.t);
    const h = Math.min(56, 12 + Math.abs(x2 - x1) / 6);
    const el = svgEl("path", {
      d: `M ${x1} ${RAIL_Y - 12} Q ${(x1 + x2) / 2} ${RAIL_Y - 12 - h} ${x2} ${RAIL_Y - 12}`,
      class: "edge cite", "data-edge": e.from,
    });
    svg.append(el);
  });
  svg.append(svgEl("line", { x1: X(1), y1: RAIL_Y, x2: X(maxT), y2: RAIL_Y, class: "railline" }));
  for (let t = 1; t <= maxT; t += 10)
    svg.append(svgEl("text", { x: X(t), y: RAIL_Y + 26, class: "tlab", "text-anchor": "middle" }, "t" + t));
  // entity lanes
  for (const e of ents) {
    const y = rowY(e), sp = vm.span.get(e.id);
    if (sp) {
      svg.append(svgEl("line", { x1: X(sp.first), y1: y, x2: X(sp.last), y2: y, class: "lane" }));
      const dot = svgEl("circle", { class: "node entity", "data-node": e.id, cx: X(sp.first), cy: y, r: 4.5 });
      nodeEls.set(e.id, dot);
      svg.append(dot);
      svg.append(svgEl("text", { x: X(sp.first) - 8, y: y + 3.5, class: "elab", "text-anchor": "end" }, e.label));
    } else {
      const dot = svgEl("circle", { class: "node entity", "data-node": e.id, cx: 60, cy: y, r: 4.5 });
      nodeEls.set(e.id, dot);
      svg.append(dot);
      svg.append(svgEl("text", { x: 52, y: y + 3.5, class: "elab", "text-anchor": "end" }, e.label));
    }
  }
  // touch dots
  vm.edges.forEach((e) => {
    if (e.kind === "cite" || e.kind === "ref") return;
    const turn = vm.byT.get(e.from);
    if (!turn) return;
    const ent = vm.entById.get(e.to);
    if (!ent) return;
    const x = X(turn.t), y = rowY(ent);
    let el;
    if (e.kind === "assert") {
      el = svgEl("circle", { class: "edge touch assert", "data-edge": e.from, cx: x, cy: y, r: 3 });
      el.style.fill = vm.colorOf(turn.session);
    } else {
      el = svgEl("rect", { class: "edge touch retract", "data-edge": e.from, x: x - 3, y: y - 3, width: 6, height: 6 });
    }
    svg.append(el);
  });
  // ref edges: entity-to-entity, drawn as arcs between the lanes
  vm.edges.forEach((e) => {
    if (e.kind !== "ref") return;
    const sf = vm.span.get(e.from), st = vm.span.get(e.to);
    const ef = vm.entById.get(e.from), et = vm.entById.get(e.to);
    if (!sf || !st || !ef || !et) return;
    const x1 = X(sf.last), y1 = rowY(ef), x2 = X(st.last), y2 = rowY(et);
    svg.append(svgEl("path", {
      d: `M ${x1} ${y1} Q ${(x1 + x2) / 2} ${(y1 + y2) / 2 - 14} ${x2} ${y2}`,
      class: "edge ref", "data-edge": e.from,
    }));
  });
  // turn diamonds on the rail (last, so they sit on top)
  for (const t of vm.turns) {
    const el = svgEl("rect", {
      class: "node turn", "data-node": t.id,
      x: -4.5, y: -4.5, width: 9, height: 9,
      transform: `translate(${X(t.t)},${RAIL_Y}) rotate(45)`,
    });
    el.style.fill = vm.colorOf(t.session);
    nodeEls.set(t.id, el);
    svg.append(el);
  }
  stage.append(svg);
  wireHover(svg, drill);
}

// ---- view mode C: bipartite columns ----------------------------------------------------

function renderColumns(vm, stage) {
  const ROW_T = 13.5, TOP = 46, XL = 130, XR = 880, W = 1060;
  const H = TOP + Math.max(1, vm.t) * ROW_T + 30;
  const turnY = (t) => TOP + (t - 1) * ROW_T;
  const ents = [...vm.entities].sort(byFirstTouch(vm));
  const ROW_E = (H - TOP - 30) / Math.max(1, ents.length);
  const entY = new Map(ents.map((e, i) => [e.id, TOP + i * ROW_E + ROW_E / 2]));
  const svg = svgEl("svg", { width: W, height: H });
  svg.append(svgEl("text", { x: XL - 20, y: 24, class: "cap" }, "turns — t order ↓"));
  svg.append(svgEl("text", { x: W - 12, y: 24, class: "cap", "text-anchor": "end" }, "entities — first-touch order ↓"));
  vm.edges.forEach((e) => {
    const from = vm.byT.get(e.from);
    let el;
    if (e.kind === "cite") {
      const to = vm.byT.get(e.to);
      if (!from || !to) return;
      const y1 = turnY(from.t), y2 = turnY(to.t);
      el = svgEl("path", {
        d: `M ${XL + 8} ${y1} C ${XL - 36} ${y1} ${XL - 36} ${y2} ${XL + 8} ${y2}`,
        class: "edge cite", "data-edge": e.from,
      });
    } else if (e.kind === "ref") {
      if (!entY.has(e.from) || !entY.has(e.to)) return;
      const y1 = entY.get(e.from), y2 = entY.get(e.to);
      el = svgEl("path", {
        d: `M ${XR + 10} ${y1} C ${XR + 60} ${y1} ${XR + 60} ${y2} ${XR + 10} ${y2}`,
        class: "edge ref " + e.kind, "data-edge": e.from,
      });
    } else {
      if (!from || !entY.has(e.to)) return;
      const y1 = turnY(from.t), y2 = entY.get(e.to), mid = (XL + XR) / 2;
      el = svgEl("path", {
        d: `M ${XL + 8} ${y1} C ${mid} ${y1} ${mid} ${y2} ${XR - 10} ${y2}`,
        class: "edge " + e.kind, "data-edge": e.from,
      });
      if (e.kind === "assert") el.style.stroke = vm.colorOf(from.session);
    }
    svg.append(el);
  });
  const nodeEls = new Map();
  for (const t of vm.turns) {
    const y = turnY(t.t);
    const el = svgEl("rect", {
      class: "node turn", "data-node": t.id,
      x: -4, y: -4, width: 8, height: 8,
      transform: `translate(${XL},${y}) rotate(45)`,
    });
    el.style.fill = vm.colorOf(t.session);
    nodeEls.set(t.id, el);
    svg.append(el);
    if (t.t % 10 === 1)
      svg.append(svgEl("text", { x: XL + 14, y: y + 3.5, class: "tlab" }, "t" + t.t));
  }
  ents.forEach((e) => {
    const y = entY.get(e.id);
    const el = svgEl("circle", { class: "node entity", "data-node": e.id, cx: XR, cy: y, r: 4.5 });
    nodeEls.set(e.id, el);
    svg.append(el);
    svg.append(svgEl("text", { x: XR + 12, y: y + 3.5, class: "elab" }, e.label));
  });
  stage.append(svg);
  wireHover(svg, drill);
}

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
  state.vm = buildVm(state.payload);
  const nNodes = state.vm.turns.length + state.vm.entities.length;
  const scope = [
    state.session ? "session " + state.session : "all sessions",
    state.asOf != null ? "as-of t" + state.asOf : "head t" + state.vm.t,
  ].join(" · ");
  $("#stat").innerHTML =
    `<b>${nNodes}</b> nodes (turn ${state.vm.turns.length} · entity ${state.vm.entities.length})` +
    ` · <b>${state.vm.edges.length}</b> edges · ${scope}`;
  state.variant.render(state.vm, stage);
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
