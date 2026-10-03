# Where clauses execute in analyzed order; result row order is unspecified

Query profiling (2026-10-03) showed the naive written-order clause evaluation pays per-solution
costs repeatedly and blows up intermediate solution sets when a broad clause precedes a selective
one. We decided the query engine may execute where clauses in an order chosen by binding-dependency
analysis rather than written order — with the hard constraint that predicate and function-expression
clauses still see their arguments bound (they keep raising :query/unbound-var otherwise, exactly as
before).

Consequently, the engine's contract is the result SET, not row order: the row order of :relation and
:coll results, and which solution a :scalar spec sees first, may change between engine versions.
This matches Datomic and DataScript. Tests must assert sets for multi-row results.

Considered options: preserving today's row order byte-for-byte (rejected — it neuters reordering,
since preserving solution order means barely reordering at all).
