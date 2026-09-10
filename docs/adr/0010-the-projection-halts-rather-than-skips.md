# The projection halts rather than skips

Kafka redelivers, so applying a Posting must be idempotent. Two mechanisms, kept together because
they cover disjoint failures: an `applied_entry` table in the projection's own store, unique on Entry
id and written in the same transaction as the Balance update; and the consumed offset stored with
that same write. Kafka's exactly-once semantics do not cover a consumer writing into an external
store, which is precisely what this is, so they are not relied upon.

**A message the projection cannot apply halts the partition.** It is not dead-lettered.

A dead-letter queue is the reflex and it is wrong here. Skipping a Posting means continuing to serve
a Balance that is quietly incorrect, indefinitely, while the DLQ makes that feel handled. Halting is
loud, and a ledger should stop rather than lie. A metric makes the halt visible immediately.

## A word that means two things

This is **not** the Idempotency Key of ADR-0005. That one stops a *caller* recording an Entry twice.
This one stops the *transport* applying one twice. Different layers, different mechanisms, and
conflating them in conversation will cost you.
