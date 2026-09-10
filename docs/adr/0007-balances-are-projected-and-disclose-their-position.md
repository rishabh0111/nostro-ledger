# Balances are projected, and every Balance discloses its position

Balance reads are served from a projection, never from the write tables — including for Constrained
Accounts, whose stored running balance exists only to enforce the floor (ADR-0004). A projection can
lag, so every Balance response carries the **position** it reflects, and recording an Entry returns
the position it created. A caller that needs read-your-writes passes that position back as a minimum
on the read.

Presenting a lagging figure as authoritative would be worse than the contention avoided by not
storing it. A position token is what turns lag from a lie into a disclosed fact.

## The token

`<system_identifier>:<xid8>`, obtained inside the Entry's own transaction and opaque to callers, who
compare it only by passing it back. The `system_identifier` prefix exists so that a token from a
different database — a restore, a rebuild — fails loudly instead of comparing as a plausible number.

A `bigserial` cannot play this role. Sequences are documented as non-transactional, so they gap; the
disqualifying property is that assignment order is not commit order, so a reader tailing by sequence
value can step past a row that is still uncommitted and never return to it — with no rollback, no
crash and no error anywhere. See `docs/research/ordering-and-watermarks.md`.

## Why the projection's watermark can be believed

A Position is assigned at an Entry's first write, not at its commit, so an Entry can commit after
Entries with higher Positions. If the relay published those first, the projection would report a
watermark above an Entry it had not applied, and a caller holding that Entry's Position would be
told the Balance includes it. So the relay publishes a row only once its Position is below
`pg_snapshot_xmin` of its own snapshot, below which every transaction has either committed or
rolled back. Publish order is then exactly Position order, and the watermark — the largest
Position applied — means everything up to it. The cost is that one long-running write transaction
holds back the feed for every Tenant, bounded by the request path's
`idle_in_transaction_session_timeout`: lag, which the Position discloses, rather than a lie.

## The read contract

A minimum position on a Balance read produces a **bounded wait, capped by the server rather than by
the caller**, and the response is always `200` carrying the position actually reflected — so a caller
that asked for freshness and did not get it can see precisely that and decide for itself. `409` and
`503` were rejected against RFC 9110's wording, and `Retry-After` is not defined for `200`.

**The JDBC connection must be released before a waiter parks.** Otherwise projection lag converts
into connection-pool exhaustion, and the freshness feature becomes an outage under exactly the
conditions it exists for.

## Explicitly out of scope

"Balance as of time T" cannot be served by a running projection at all — only by a `SUM` over bounded
history — so it would quietly reintroduce the mechanism ADR-0004 rejected.
