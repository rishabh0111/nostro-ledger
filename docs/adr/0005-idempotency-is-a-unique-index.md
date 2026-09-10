# Idempotency is a unique index, not a state machine

Recording an Entry requires an idempotency key, scoped to the Tenant. The idempotency record — key,
a fingerprint of the request body, and the response to replay — is written **in the same transaction
as the Entry**, under a unique constraint on `(tenant_id, idempotency_key)`.

This means there is no in-flight state to model. A duplicate request arriving while the original is
still open blocks on the unique index until the original commits, and then fails the constraint; the
handler reads the stored response and replays it verbatim. The index is the concurrency control.

A key is **required** rather than optional because a ledger with an opt-out from at-most-once has a
duplicate-payment hole switched on by default. A retry receives the original response replayed
rather than a bare conflict, because the retry usually happened precisely because the caller never
saw the first response.

## Consequences

The same key presented with a different body is a loud failure, not a silently wrong replay — that
is what the fingerprint is for.

Idempotency records are retained for 30 days and then removed. This is a deliberate trade: unbounded
retention is unbounded growth, and the cost is that a retry arriving after 30 days will record a
second Entry.
