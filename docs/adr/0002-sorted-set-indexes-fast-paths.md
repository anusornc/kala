# Indexes stay sorted-set; the query-engine gap closes with fast paths

The benchmark showed join cost is dominated by a flat per-slice overhead (~10–20µs) paid once per
solution, plus per-solution re-preparation of patterns — not by intermediate blowup on the bench
workloads. For this optimization wave we keep the sorted-set index representation in core.clj and
close the gap with targeted work: hoisting pattern preparation out of the per-solution loop, cheaper
point-lookup slices (no sentinel low-bound allocation, no per-call comparator/predicate closures),
and eager materialization of slice results.

Rejected for this wave: replacing sorted-set-by with DataScript-style sorted arrays. The indexes are
mutated by the transaction path and shared by datoms/seek-datoms/time-travel, so a representation
swap has much larger blast radius than the ≤2x-of-DataScript bar requires. Revisit if a later wave
targets outright wins at large n.
