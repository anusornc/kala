"use strict";
/* Kala snapshot explorer — the monitor's three view modes over a
   pre-baked store export. Static by design (ADR-0012): no server, no
   polling — one JSON per store, drilldowns pre-baked by site/export.clj.
   Session filtering and the "turns up to t" slider run client-side. */

const $ = (sel) => document.querySelector(sel);

const SESSION_COLORS = ["#e0a458", "#7bc47f", "#6ea8dc", "#c792ea", "#d7ba7d", "#4ec9b0", "#61afef", "#e5c07b"];

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

// ---- fetching -------------------------------------------------------------------

async function loadStore(name) {
  if (state.payloads[name]) return state.payloads[name];
  const res = await fetch(STORES[name].file);
  const body = await res.json();
  state.payloads[name] = body;
  return body;
}

// ---- scoping: session filter + "turns up to t" (both client-side) ---------------

function scopePayload(payload, sessionLabel, asOf) {
  const kw = (s) => (s && s[0] === ":") ? s.slice(1) : s;
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

// ---- view model -------------------------------------------------------------------

function buildVm(payload) {
  const kw = (s) => (s && s[0] === ":") ? s.slice(1) : s;
  const turns = payload.turns;
  const entities = payload.entities;
  const edges = payload.edges.map((e) => ({ ...e, kind: kw(e.kind) }));
  const byT = new Map(turns.map((t) => [t.id, t]));
  const entById = new Map(entities.map((e) => [e.id, e]));
  const sessionColor = new Map();
  payload.sessions.forEach((s, i) => sessionColor.set(s.id, SESSION_COLORS[i % SESSION_COLORS.length]));
  const colorOf = (session) => (session != null && sessionColor.has(session))
    ? sessionColor.get(session) : "#8a8f98";
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

function sessionLabel(sessionEid) {
  const s = state.vm.sessions.find((x) => x.id === sessionEid);
  return s ? s.label : "session " + sessionEid;
}

// ---- svg helpers -------------------------------------------------------------------

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

const detailEl = $("#detail");

// drilldowns read the pre-baked maps, exactly what the live endpoints return
function drillTurn(t) {
  state.pinned = true;
  const datoms = (state.payload.turnsDatoms || {})["t" + t] || [];
  const lines = datoms.map(([e, a, v, added]) =>
    `${esc(a)} ${added ? '<span class="good">add</span>' : '<span class="bad">retract</span>'} → ${e} ${esc(v)}`);
  detailEl.innerHTML =
    `<b>turn node · t = ${t}</b> <span class="dim">${datoms.length} datoms</span><br>` +
    (lines.length ? lines.join("<br>") : '<span class="dim">no datoms</span>');
  detailEl.hidden = false;
}

function drillEntity(id) {
  state.pinned = true;
  const trail = (state.payload.trails || {})[id] || [];
  const ent = state.vm.entById.get(id);
  const lines = trail.map(([t, a, v, added, session]) =>
    `t${t}${session != null ? ` · ${esc(sessionLabel(session))}` : ""} · ` +
    `${esc(a)} ${added ? '<span class="good">+</span>' : '<span class="bad">−</span>'} ${esc(v)}`);
  detailEl.innerHTML =
    `<b>entity node · ${ent ? esc(ent.label) : id}</b> <span class="dim">correction trail · ${trail.length} datoms</span><br>` +
    (lines.length ? lines.join("<br>") : '<span class="dim">no history</span>');
  detailEl.hidden = false;
}

const drill = (id) => id.startsWith("t") ? drillTurn(id.slice(1)) : drillEntity(id);

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

function wireHover(svg) {
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
  const incidentSummary = (id) => {
    const inc = (adj.get(id) || []).map(({ i }) => state.vm.edges[i]);
    const byKind = {};
    for (const e of inc) (byKind[e.kind] = byKind[e.kind] || new Set()).add(e.attr);
    return Object.entries(byKind)
      .map(([kind, attrs]) => `${esc(kind)}: ${[...attrs].map(esc).join(", ")}`)
      .join("<br>");
  };
  const clear = () => {
    if (state.pinned) return;
    svg.classList.remove("hl");
    svg.querySelectorAll(".on,.nbr").forEach((el) => el.classList.remove("on", "nbr"));
    detailEl.hidden = true;
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
    if (!t) return;
    const id = t.dataset.node;
    if (id.startsWith("t") || state.vm.entById.has(id)) drill(id);
  });
}

const byFirstTouch = (vm) => (a, b) => {
  const sa = vm.span.get(a.id), sb = vm.span.get(b.id);
  return (sa ? sa.first : 1e18) - (sb ? sb.first : 1e18) || a.id.localeCompare(b.id);
};

// ---- view mode A: force soup --------------------------------------------------------

function renderForce(vm, stage) {
  const W = Math.max(900, stage.clientWidth || 900);
  const H = Math.max(560, stage.clientHeight || 560);
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
  wireHover(svg);
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

// ---- view mode B: turn rail -----------------------------------------------------------

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
  const nodeEls = new Map();
  vm.edges.forEach((e) => {
    if (e.kind !== "cite") return;
    const from = vm.byT.get(e.from), to = vm.byT.get(e.to);
    if (!from || !to) return;
    const x1 = X(from.t), x2 = X(to.t);
    const h = Math.min(56, 12 + Math.abs(x2 - x1) / 6);
    svg.append(svgEl("path", {
      d: `M ${x1} ${RAIL_Y - 12} Q ${(x1 + x2) / 2} ${RAIL_Y - 12 - h} ${x2} ${RAIL_Y - 12}`,
      class: "edge cite", "data-edge": e.from,
    }));
  });
  svg.append(svgEl("line", { x1: X(1), y1: RAIL_Y, x2: X(maxT), y2: RAIL_Y, class: "railline" }));
  for (let t = 1; t <= maxT; t += 10)
    svg.append(svgEl("text", { x: X(t), y: RAIL_Y + 26, class: "tlab", "text-anchor": "middle" }, "t" + t));
  for (const e of ents) {
    const y = rowY(e), sp = vm.span.get(e.id);
    let dot;
    if (sp) {
      svg.append(svgEl("line", { x1: X(sp.first), y1: y, x2: X(sp.last), y2: y, class: "lane" }));
      dot = svgEl("circle", { class: "node entity", "data-node": e.id, cx: X(sp.first), cy: y, r: 4.5 });
      svg.append(svgEl("text", { x: X(sp.first) - 8, y: y + 3.5, class: "elab", "text-anchor": "end" }, e.label));
    } else {
      dot = svgEl("circle", { class: "node entity", "data-node": e.id, cx: 60, cy: y, r: 4.5 });
      svg.append(svgEl("text", { x: 52, y: y + 3.5, class: "elab", "text-anchor": "end" }, e.label));
    }
    svg.append(dot);
  }
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
  for (const t of vm.turns) {
    const el = svgEl("rect", {
      class: "node turn", "data-node": t.id,
      x: -4.5, y: -4.5, width: 9, height: 9,
      transform: `translate(${X(t.t)},${RAIL_Y}) rotate(45)`,
    });
    el.style.fill = vm.colorOf(t.session);
    svg.append(el);
  }
  stage.append(svg);
  wireHover(svg);
}

// ---- view mode C: bipartite columns -----------------------------------------------------

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
        class: "edge ref", "data-edge": e.from,
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
  for (const t of vm.turns) {
    const y = turnY(t.t);
    const el = svgEl("rect", {
      class: "node turn", "data-node": t.id,
      x: -4, y: -4, width: 8, height: 8,
      transform: `translate(${XL},${y}) rotate(45)`,
    });
    el.style.fill = vm.colorOf(t.session);
    svg.append(el);
    if (t.t % 10 === 1)
      svg.append(svgEl("text", { x: XL + 14, y: y + 3.5, class: "tlab" }, "t" + t.t));
  }
  ents.forEach((e) => {
    const y = entY.get(e.id);
    const el = svgEl("circle", { class: "node entity", "data-node": e.id, cx: XR, cy: y, r: 4.5 });
    svg.append(el);
    svg.append(svgEl("text", { x: XR + 12, y: y + 3.5, class: "elab" }, e.label));
  });
  stage.append(svg);
  wireHover(svg);
}

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
  state.variant.render(state.vm, stage);
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
