# PROTOTYPE — context-doc shapes for `kala.memory` (wayfinder ticket #42)

> Throwaway artifact to react to. **Not the assembler** — nothing here runs; these are
> hand-rendered samples of the *same* data in three different document shapes.
> The decision this settles: what the context document looks like when a harness
> stuffs it into the prompt.

## The one dataset (canonical scenario)

An MCP-client coding agent ("kala-main") has recorded two sessions. Vocabulary is
developer-owned; the spine (`:memory/session` tx-meta, session id, turn listing)
is built-in. Head of the log is t=59.

**Session `kala-main-1016`** (2026-10-16), turn listing:
`t41=turn1  t42=turn2  t43=turn3  t44=turn4  t45=turn5  t46=turn6`

| t | turn | write |
|---|------|-------|
| 41 | 1 | `:proj/kala :proj/deploy-command "./deploy.sh staging"` |
| 42 | 2 | `:proj/kala :proj/test-command "clojure -M:test"` |
| 43 | 3 | `:proj/kala :proj/build-tool :boot` |
| 44 | 4 | `:env/local :pref/shell :zsh` |
| 45 | 5 | `:env/local :note/flaky-test "wire/handshake-timeout"` |
| 46 | 6 | retract `:env/local :note/flaky-test` (resolved) |

**Session `kala-main-1017`** (2026-10-17), turn listing:
`t57=turn1  t58=turn2  t59=turn3`

| t | turn | write |
|---|------|-------|
| 57 | 1 | `:proj/kala :proj/deploy-command "make deploy"` ← **correction** of t41 |
| 58 | 2 | `:proj/kala :proj/deploy-target :staging` |
| 59 | 3 | `:proj/kala :proj/docs-host "kala.dev"` |

Canonical question: *"what did I believe about the deploy command at turn 3 of
kala-main-1016?"* → turn listing resolves turn 3 to t=43 → belief state as-of
t=43 says `"./deploy.sh staging"`.

Budget illustration: every variant below is shown **over cap** (cap = 5 fact
lines; there are 6 current beliefs) so the truncation display is exercised.

---

## Variant A — flat facts block (the Zep-reactive shape)

One prose-ish block, most-recent-first, corrections annotated inline.
What a harness pastes today; minimum tokens, minimum structure.

```text
<memory as-of t=59 — 5 of 6 facts shown; 1 dropped by cap (see below)>
proj deploy-command: "make deploy" (since t57 kala-main-1017 turn1; was "./deploy.sh staging" until t56)
proj docs-host: "kala.dev" (t59 kala-main-1017 turn3)
proj deploy-target: :staging (t58 kala-main-1017 turn2)
proj build-tool: :boot (t43 kala-main-1016 turn3)
env shell: :zsh (t44 kala-main-1016 turn4)
proj test-command: "clojure -M:test" (t42 kala-main-1016 turn2)  [DROPPED — over 5-line cap]
note flaky-test: retracted t46 (was "wire/handshake-timeout", t45)  [DROPPED — over cap]
</memory>
```

- **Wins:** cheapest per fact; ordering = recency, visible; corrections read as prose.
- **Costs:** attribute names abbreviated (full idents don't survive a line format);
  joins are inexpressible; the consumer can't distinguish a keyword from a string
  except by the leading `:`.

## Variant B — EDN belief-state map (kala-native)

The document **is** the serialized door outputs — one readable-EDN map, sections
named for the door that produced them. Zero new serialization layer; lossless.

```clojure
{:as-of 59
 :truncated {:capped? true :shown 5 :dropped 1 :cap 5}
 :current   [[:proj/kala :proj/deploy-command "make deploy"]      ;; pull result, head
             [:proj/kala :proj/docs-host "kala.dev"]
             [:proj/kala :proj/deploy-target :staging]
             [:proj/kala :proj/build-tool :boot]
             [:env/local :pref/shell :zsh]
             ;; dropped over cap:
             #_[:proj/kala :proj/test-command "clojure -M:test"]]
 :corrections [{:entity :proj/kala :attr :proj/deploy-command
                :was "./deploy.sh staging" :asserted 43 :until 56}]
 :turns {"kala-main-1017" [[57 1] [58 2] [59 3]]
         "kala-main-1016" [[41 1] [42 2] [43 3] [44 4] [45 5] [46 6]]}}
```

- **Wins:** fidelity (keywords stay keywords; the as-of answer is mechanically
  derivable — pin `:as-of 43` and re-read); the turn listing rides inside, so
  "turn N" addressing costs nothing extra; no formatting code to build or trust.
- **Costs:** heaviest in tokens; parens hostile to skim-reading; presumes an
  EDN-literate consumer (MCP clients are not, by default).

## Variant C — sectioned timeline (structured plain text, middle path)

Grouped by entity; each attribute gets a mini-timeline; provenance and
truncation live in a footer, not inline.

```text
# memory — as of t=59 (head)
## :proj/kala
proj/deploy-command = "make deploy"
    ← was "./deploy.sh staging"  t43..t56  (kala-main-1016 turn3 → kala-main-1017 turn1)
proj/deploy-target = :staging            (t58, kala-main-1017 turn2)
proj/docs-host      = "kala.dev"         (t59, kala-main-1017 turn3)
proj/build-tool     = :boot              (t43, kala-main-1016 turn3)
## :env/local
pref/shell = :zsh                        (t44, kala-main-1016 turn4)
note/flaky-test = "wire/handshake-timeout"  t45..t46  [RETRACTED — resolved]
## over cap, omitted from sections above
proj/test-command = "clojure -M:test"    (t42, kala-main-1016 turn2)
# footer: 5/6 facts shown · cap=5 · dropped 1 · budget knob: :cap 5
```

- **Wins:** structure without EDN syntax; corrections and retractions carry
  their full t-range; group headers make joins *look* possible even though the
  flat doc doesn't run them.
- **Costs:** most verbose per fact of the three; the renderer is real code to
  specify (indentation rules, tag grammar); footer conventions are one more
  thing to lock.

---

## Decision axes the three variants differ on

| axis | A flat block | B EDN map | C sectioned timeline |
|---|---|---|---|
| serialization | formatted lines | verbatim readable EDN | formatted lines + footer grammar |
| fact ordering | recency only | as-returned by the door | entity-grouped, recency inside |
| corrections | inline `(was X until tN)` | `:corrections` section | attribute mini-timeline |
| retraction display | inline `[RETRACTED]` | absent from `:current` | timeline entry with t-range |
| turn listing | not present (t tags only) | `:turns` section | per-line turn tags, no listing |
| truncation display | `[DROPPED]` inline lines | `:truncated` map | footer + `over cap` section |
| token cost | lowest | highest | middle |
| as-of re-derivable? | no | yes (`:as-of` + listing) | partially (t-ranges) |

## Open questions (grilling order)

1. **Base shape** — A, B, C, or a merge (e.g. C's sections with A's compactness)?
2. **Ordering under budget** — recency-first is the only ranking a structured
   store gives for free; is that acceptable, or does the developer declare a
   priority order over attributes?
3. **Corrections** — always inline, only recent ones, or opt-in flag?
4. **Wall clock** — `:db/txInstant` appears nowhere above except in listings;
   should it appear at all?
5. **Budget knob** — unit (facts vs chars vs tokens), name (`:cap`? `:budget`?),
   and does the cap live on the doc call or per-section?

---

## LOCKED — wayfinder #42 resolution (2026-10-17)

All four questions answered: **base shape B**, recency + `:cap` facts,
full-trail corrections, `:as-of t` on the call with no wall clock in the doc.
The same dataset renders as:

```clojure
;; (kala.memory/context {:cap 5})   — head; add :as-of 43 for the pinned belief state
{:as-of 59
 :truncated {:capped? true :shown 5 :dropped 1 :cap 5
             :dropped-attrs [:proj/test-command]}          ;; names only, not re-listed
 :current   [[:proj/kala :proj/docs-host "kala.dev"]          ;; t59
             [:proj/kala :proj/deploy-target :staging]        ;; t58
             [:proj/kala :proj/deploy-command "make deploy"]  ;; t57
             [:proj/kala :proj/build-tool :boot]              ;; t43
             [:env/local :pref/shell :zsh]]                   ;; t44
 :corrections
 [{:entity :proj/kala :attr :proj/deploy-command
   :was "./deploy.sh staging" :asserted 43 :until 56 :now "make deploy"}
  {:entity :env/local :attr :note/flaky-test
   :was "wire/handshake-timeout" :asserted 45 :until 46 :now nil}] ;; retracted-resolved
 :turns {"kala-main-1016" [[41 1] [42 2] [43 3] [44 4] [45 5] [46 6]]
         "kala-main-1017" [[57 1] [58 2] [59 3]]}}

;; (kala.memory/context {:as-of 43 :cap 5})
{:as-of 43
 :truncated {:capped? false}
 :current   [[:proj/kala :proj/deploy-command "./deploy.sh staging"]
             [:proj/kala :proj/test-command "clojure -M:test"]
             [:proj/kala :proj/build-tool :boot]
             [:env/local :pref/shell :zsh]]
 :corrections []                                  ;; nothing superseded as-of t=43
 :turns {"kala-main-1016" [[41 1] [42 2] [43 3]]}} ;; listing bounded at :as-of
```

Locked details: `:turns` pairs are `[t turn]` — `:db/txInstant` stays on the
turn-listing door and never enters the doc. The v0 doc is store-wide (sessions
are provenance, not partitions); session-narrowed context sits with the
multi-agent-identity fog if it ever lands. Variants A and C above are dead.
