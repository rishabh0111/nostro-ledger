# What Kafka, Redis and gRPC are for

These three are a fixed requirement on the project rather than a requirement of the domain. The rule
adopted for resolving that tension: at most **one** may be justified as cross-cutting infrastructure
and labelled as such; the other two must answer a need the ledger actually has. Technology added
without a reason reads worse than technology absent, and each of these needs a one-sentence answer.

**Kafka is the replayable transport between the write model and the read model.** The outbox is
drained into it and the projection is built from it. What a Postgres-polling projection could not do:
rebuild from position zero without touching the write database, and fan out to further consumers
without further pollers on the write path.

**gRPC is how the API service asks the projection service for a Balance** — internal, high-frequency,
typed, and it carries the position watermark back with the answer.

**Redis holds per-Tenant rate limits**, because rate limiting across multiple API instances cannot be
done in process. This is the one cross-cutting justification the rule allows, and it is labelled as
such. It is at least the least generic version available: per-*Tenant* limiting is a noisy-neighbour
problem, which is this project's actual subject.

## Consequences

**The projection service owns its own database.** If it wrote into the API service's Postgres, the
service boundary would be decorative and gRPC a costume. The cost is one more database under
`compose`, and its own tenant isolation — the same technique applied consistently, not new work.

## Rejected, and worth recording

**Redis must not cache Balances.** It would be a third source of truth beside the stored balance and
the projection; it would need position semantics of its own or it would undo ADR-0007; and anything
cached across requests outlives the Tenant context that authorised loading it — the same reason
Hibernate's second-level cache is off (`docs/research/rls-pooling.md`).

**Redis must not hold Idempotency Keys.** ADR-0005 makes at-most-once a unique index inside the
Entry's own transaction. Moving it to a store that cannot join that transaction turns a structural
guarantee back into a race.
