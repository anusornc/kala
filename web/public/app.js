/* Kala web demo frontend — plain JS, no build step.
   Talks text/EDN to the JVM server; responses are JSON envelopes whose
   values are EDN strings. */

const $ = (s) => document.querySelector(s);

async function postJSON(url, body) {
  const res = await fetch(url, { method: "POST", body });
  return res.json();
}

async function getJSON(url) {
  return (await fetch(url)).json();
}

function esc(s) {
  const d = document.createElement("div");
  d.textContent = s;
  return d.innerHTML;
}

function table(el, headers, rows) {
  if (!rows || !rows.length) {
    el.innerHTML = '<p class="hint">no results</p>';
    return;
  }
  el.innerHTML =
    "<table><thead><tr>" +
    headers.map((h) => `<th>${esc(h)}</th>`).join("") +
    "</tr></thead><tbody>" +
    rows
      .map((r) => "<tr>" + r.map((c) => `<td><code>${esc(c)}</code></td>`).join("") + "</tr>")
      .join("") +
    "</tbody></table>";
}

function showError(status, resultEl, data, extraHint) {
  status.innerHTML =
    `<span class="fail">${esc(String(data.kind))}</span> ${esc(data.message || "")}` +
    (extraHint ? ` <span class="hint">${esc(extraHint)}</span>` : "");
  resultEl.innerHTML = data.data ? `<pre>${esc(String(data.data))}</pre>` : "";
}

async function refreshMeta() {
  const m = await getJSON("/api/meta");
  if (!m.ok) return;
  $("#t").textContent = m.t;
  $("#datoms").textContent = m.datoms;
  $("#dir").textContent = m.dir;
  const sel = $("#asof");
  const cur = sel.value;
  sel.innerHTML = '<option value="">now</option>';
  const labels = m["timeline-labels"] || [];
  (m["timeline-t"] || []).forEach((t, i) => {
    const label = labels[i] ? `${labelText(labels[i])}` : `t = ${t}`;
    sel.insertAdjacentHTML("beforeend", `<option value="${t}">${esc(label)}</option>`);
  });
  sel.value = cur || "";
}

function labelText(s) {
  return s; // labels arrive preformatted from /api/meta
}

async function runQuery() {
  const status = $("#q-status");
  status.textContent = "running…";
  const asOf = $("#asof").value;
  const data = await postJSON("/api/query" + (asOf ? "?as-of=" + asOf : ""), $("#q").value);
  if (!data.ok) {
    const hint =
      data.kind === ":query/unknown-attribute" && asOf
        ? "this attribute may not exist at that t yet — the as-of dropdown labels what each t added; try now or a later t"
        : null;
    showError(status, $("#q-result"), data, hint);
    return;
  }
  status.innerHTML =
    `${data.rows.length} row(s) in ${data.ms.toFixed(1)} ms — ` +
    (data["as-of"] ? `as of t=${data["as-of"]}` : `current t=${data.t}`);
  table($("#q-result"), data.columns, data.rows);
}

async function applyTx() {
  const status = $("#tx-status");
  status.textContent = "applying…";
  const data = await postJSON("/api/transact", $("#tx").value);
  if (!data.ok) {
    showError(status, $("#tx-result"), data);
    return;
  }
  status.innerHTML = `<span class="ok">transacted</span> — db now at t=${data.t}`;
  $("#tx-result").innerHTML =
    (data["tx-data"].length ? "<h3>tx-data</h3><pre>" + esc(data["tx-data"].join("\n")) + "</pre>" : "") +
    (data.tempids.length ? "<h3>tempids</h3><pre>" + esc(data.tempids.join("\n")) + "</pre>" : "");
  await refreshMeta();
  showDatoms();
}

async function showDatoms() {
  const data = await getJSON("/api/datoms");
  table($("#datoms-table"), ["e", "a", "v", "tx"], data.rows || []);
}

async function showHistory() {
  const data = await getJSON("/api/history");
  table($("#history-table"), ["e", "a", "v", "tx", "+"], data.rows || []);
}

async function reopen() {
  const note = $("#reopen-note");
  const before = $("#t").textContent;
  note.classList.add("hidden");
  const data = await postJSON("/api/reopen", "");
  if (data.ok) {
    await refreshMeta();
    await showDatoms();
    note.textContent =
      `the peer threw away its in-memory state and reopened from the storage ` +
      `directory: t was ${before}, the rebuilt db is at t=${data.t} — every acked transaction survived the reopen.`;
    note.classList.remove("hidden");
  } else {
    note.textContent = "reopen failed: " + data.message;
    note.classList.remove("hidden");
  }
}

const EXAMPLES = [
  '[:find ?n :where [_ :person/name ?n]]',
  '[:find ?n ?a :where [?p :person/name ?n] [?p :person/age ?a]]',
  '[:find ?n :where [?p :person/age ?a] [(>= ?a 30)] [?p :person/name ?n]]',
  '[:find ?fn :where [?a :person/name "Alice"] [?a :person/friend ?f] [?f :person/name ?fn]]',
  '[:find (count ?p) :where [?p :person/name]]',
];

function init() {
  $("#q").value = EXAMPLES[1];
  $("#tx").value = '[[:db/add "eve" :person/name "Eve"]]';
  $("#run").addEventListener("click", runQuery);
  $("#apply").addEventListener("click", applyTx);
  $("#reopen").addEventListener("click", reopen);

  document.querySelectorAll(".tab").forEach((b) =>
    b.addEventListener("click", () => {
      document.querySelectorAll(".tab").forEach((x) => x.classList.remove("active"));
      document.querySelectorAll(".panel").forEach((x) => x.classList.remove("active"));
      b.classList.add("active");
      $("#panel-" + b.dataset.tab).classList.add("active");
      if (b.dataset.tab === "datoms") showDatoms();
      if (b.dataset.tab === "history") showHistory();
    })
  );

  const ul = $("#examples");
  EXAMPLES.forEach((q) => {
    const li = document.createElement("li");
    const a = document.createElement("a");
    a.href = "#";
    a.textContent = q;
    a.addEventListener("click", (e) => {
      e.preventDefault();
      $("#q").value = q;
      $("#asof").value = "";
      runQuery();
    });
    li.appendChild(a);
    ul.appendChild(li);
  });

  refreshMeta();
}

init();
