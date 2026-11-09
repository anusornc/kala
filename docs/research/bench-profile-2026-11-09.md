# Bench profile 2026-11-09 — where kala's time goes against DataScript

Question: can kala beat DataScript on the bench, and if not, where does
the time actually go? Method: the project's own bench harness for
standings, then Java Flight Recorder (`settings=profile`, JDK 26,
dumponexit) over a kala-only run at n=50000 — 4,123 execution samples,
58 s recording, plus `jdk.ObjectAllocationSample` aggregation. No code
was changed for this profile; all numbers reproduce with the commands at
the end.

## Standings (memory storage)

n=200 (core suite, all three engines — CI's smoke size):

| workload | kala | DataScript | Datahike | verdict |
|---|---|---|---|---|
| bulk ingest | 13.1 ms | 15.5 ms | 22.4 ms | kala wins |
| 100 updates (single-entity txs) | 12.7 ms | 5.2 ms | 91.9 ms | DS 2.4× faster |
| simple pattern | 109 µs | 78 µs | 249 µs | DS 1.4× faster |
| filtered + input | 32 µs | 76 µs | 101 µs | kala 2.4× faster |
| join (friend→name) | 156 µs | 112 µs | 284 µs | DS 1.4× faster |
| point read | 12 µs | 31 µs | 35 µs | kala 2.6× faster |

n=5000 (kala and DataScript run separately, same harness):

| workload | kala | DataScript | gap at scale |
|---|---|---|---|
| bulk ingest | 193 ms | 156 ms | DS 1.2× faster |
| 100 updates | 13.5 ms | 4.7 ms | DS 2.9× faster |
| simple pattern | 2.7 ms | 1.0 ms | DS 2.7× faster |
| filtered + input | 73 µs | 213 µs | kala 2.9× faster |
| join | 3.6 ms | 1.4 ms | DS 2.6× faster |
| point read | 10 µs | 36 µs | kala 3.6× faster |

Reading: kala already wins ingest, filtered+input (avet selectivity) and
point reads (the weak cache), and the wins GROW with size. The losses —
simple pattern, join, single-entity updates — all grow with size too
(1.4×→2.7× on simple). Beating DataScript means closing exactly those
three, without giving back the wins.

## Where the CPU goes (4,123 samples)

Top of stack (where execution was):

| samples | frame |
|---|---|
| 377 | clojure.lang.Util.dohasheq |
| 321 | clojure.lang.Util.equiv |
| 271 | clojure.lang.PersistentArrayMap.indexOfObject |
| 217 | clojure.lang.Util.hasheq |
| 206 | PersistentHashMap$BitmapIndexedNode.find |
| 110 | PersistentHashMap.assoc |
| 100 | PersistentVector$TransientVector.conj |
| 85 | clojure.lang.RT.contains |
| 75 | PersistentVector$TransientVector.persistent |
| 68 | PersistentArrayMap.createHT |
| 51 | kala.core$cmp_by$fn (index comparator) |
| 51 | java.lang.String.equals |

First kala frame per stack (the kala code under the Clojure runtime):

| samples | kala frame |
|---|---|
| 209 | query$match_pattern |
| 199 | query$project$tuple (147 + 52 inner) |
| 179 | core$cmp_by (128) + cmp_val (16) + cmp_field (3) — index comparator |
| 81 | query$slot_value |
| 43 | weak_cache$fetch |
| 27 | core (Datom.valAt, hashCode, slice, entity-datoms) |
| 14 | query$choose_datoms |
| 5 | tx$resolve_e / tx$update_indexes |

Allocation (~103 GB sampled, dominated by):

| share | class | meaning |
|---|---|---|
| 29.9% | java.lang.Object[] | map/array building of every kind |
| 16.4% | PersistentHashMap$INode[] | HAMT node copies — map assoc |
| ~12% | LazySeq + ChunkedCons + ChunkedSeq | lazy-seq churn |
| ~6% | PersistentVector + TransientVector | result tuples |
| 4.0% | ReentrantLock$NonfairSync | the transactor's write lock |

## Root causes, in the code

1. **A bindings map per datom.** `query/match-pattern`'s `:bind` paths do
   `(assoc bindings var val)` for EVERY datom, then `(into [] ...)` —
   50k single-entry HAMT copies for one clause of a simple pattern; each
   assoc hashes the key, clones nodes, and the next clause re-reads them
   with `(get bindings slot)`. This is the hasheq/assoc storm and it is
   the simple-pattern and update gap in one.
2. **Per-clause materialization.** Every clause returns `(into [] ...)`
   even when nothing binds (`:skip :skip` builds a 50k-element vector of
   the SAME map), and the join path is mapcat over these vectors — the
   LazySeq/ChunkedCons churn plus intermediate vectors.
3. **Result dedup by tuple hashing.** `project`'s relational case does
   `(vec (distinct (map tuple solutions)))` — hash every result vector
   (`Util.hasheq`/`dohasheq` over strings, `PersistentHashSet.cons`) for
   set semantics. Correct, but it is the projection half of the profile.
4. **Index comparator is a closure.** `core/cmp-by` builds a comparator
   that reads a fields vector per comparison; every slice/seek pays an
   indirection per field. ~4% of samples, cheap to make static.
5. Small stuff: transactor lock 4% (fine — single-writer is the design),
   `weak_cache$fetch` 43 samples on the point-read path (it's why point
   reads win; leave it).

## Candidate optimizations (ranked, all grill-level decisions)

- **C1 — stop materializing non-binding clauses.** `:skip :skip` and
  `:skip :check` return counts/lazy repeated-map instead of `(into [] ...)`;
  the join pipeline only needs the solution multiset. Cheapest change,
  attacks the wildcard-pattern cost directly. Risk: low (pure eval-path).
- **C2 — clause ordering by selectivity.** Estimate `choose-datoms` size
  per pattern (attr-datoms count) and start from the tightest, so later
  clauses bind on small intermediate sets instead of scanning 50k. This
  is what DataScript does and likely the join gap's core. Risk: medium
  (changes evaluation order; rules and aggregates must stay correct).
- **C3 — bindings representation.** The deep one: per-datom `assoc` on a
  persistent map is the architecture; columnar/tuple bindings or a
  transients-per-clause strategy would cut the HAMT churn at its root.
  Hardest to reverse — this is ADR territory.
- **C4 — static index comparator.** Replace the `cmp-by` closure with a
  fixed comparator per index (long compare for e/tx, keyword for a).
  ~4%, trivial, low risk.
- **C5 — projection dedup**: dedupe via sorted tuples or dedupe only when
  the plan knows duplicates are possible; needs care with `:find`
  semantics. Risk: medium.

Honest framing: DataScript has years of optimization in exactly these
paths; C1+C2+C4 are a realistic shot at parity on simple/join, and the
point-read/filtered/ingest wins stay. "Winning" everywhere is not a
single profile away — it is a sequence of measured, guarded steps.

## Guardrails for any optimization work

- Correctness gate: the bench cross-check (`MATCH across N engines`) plus
  the full test matrix — both already in CI.
- Perf gate: the tables above at n=200 and n=5000; a change must move its
  target workload without regressing filtered/point/ingest by >10%.
- The file-storage durability cost (updates 525 ms–1.1 s at 5k — fsync
  per transaction) is priced separately by design and out of scope here.

## Reproduce

```bash
clojure -M:bench 200                                    # standings, all engines
JAVA_OPTS="-XX:StartFlightRecording=filename=/tmp/kala-profile2.jfr,settings=profile,dumponexit=true" \
  clojure -Sdeps '{:paths ["src" "bench"] :deps {org.clojure/clojure {:mvn/version "1.12.1"} datascript/datascript {:mvn/version "1.7.4"}}}' \
  -M -e "(require 'kala.bench) (@#'kala.bench/run-bench [(@#'kala.bench/os-engine)] 50000) (System/exit 0)"
jfr print --events jdk.ExecutionSample /tmp/kala-profile2.jfr   # hot methods
```

Mimosa scan note: this doc is measurement only; no code changed.
