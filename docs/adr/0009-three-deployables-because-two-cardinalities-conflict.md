# Three deployables, because two replica counts conflict

One Maven reactor produces three deployables: an **API service** (HTTP; records Entries; serves
reads), an **outbox relay** (drains the outbox into Kafka), and a **projection service** (consumes
Kafka, maintains Balances in its own database, answers Balance queries over gRPC).

The seam that matters is between the first two, and it was not placed on architectural taste. The
relay must be a **single writer** to preserve ordering — `SKIP LOCKED` is disqualified for this use
(`docs/research/ordering-and-watermarks.md`). The API service must **scale horizontally**, because
that is what the load test does to it. Those are contradictory replica counts in one process.
Embedding the relay in the API service would mean either running exactly one API instance, which
kills the load test, or adding leader election — a distributed-systems problem this project does not
need and cannot demonstrate well at this size.

Splitting the read API from the write API was rejected: they have no differing scaling need and it
would double the authentication surface for nothing.

## Consequences

**Maven modules are not deployables.** The reactor also carries shared library modules — the domain
types, the generated gRPC stubs — which ship inside services rather than as services. See
`docs/research/grpc-and-multi-module-layout.md` for the module list and the dependency rules that
keep the boundary real.
