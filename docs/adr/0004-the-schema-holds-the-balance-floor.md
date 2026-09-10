# The schema holds the balance floor

An Account may be declared **constrained** at creation, meaning its Balance may never go below zero.
A constrained Account carries a stored running balance, moved in the same database transaction that
records the Entry, guarded by a `CHECK` constraint on that column and by a predicate on the `UPDATE`
that moves it. An unconstrained Account carries no running balance at all: its writes are pure
append and take no row lock.

Deriving the balance with `SUM()` under `SELECT … FOR UPDATE` was rejected because its cost grows
with the Account's entire history, and the fix for that is a checkpoint table — which is a stored
running balance with more moving parts. `SERIALIZABLE` isolation as the primary mechanism was
rejected because it buys correctness with retry storms under exactly the contention this design
means to demonstrate. Neither alternative can express the invariant in the schema; both leave the
check in application code, where it holds only for the write paths that remembered to perform it.

## Consequences

**The stored balance exists only to enforce the constraint, never to serve a read.** Balance reads
are served from the projection, for constrained and unconstrained Accounts alike. That the two must
agree — and that both must agree with `SUM()` over Postings — is an invariant worth testing rather
than a redundancy worth removing.

**A reversing Entry is subject to the floor and can therefore be refused.** Exempting reversals was
considered and rejected on structural grounds: an exemption cannot be expressed as a `CHECK`
constraint, so granting one would move the invariant back into application code and give away the
whole point. Where an unwinding correction is refused, the remedy is a compensating Entry funded
from an Account that has the money.

**`account` is not insert-only, and `entry` and `posting` are.** The runtime role's `REVOKE UPDATE,
DELETE` therefore covers `entry` and `posting` only, and `account` needs an RLS policy `FOR UPDATE`
where those two need only `FOR SELECT` and `FOR INSERT`. See `docs/research/hot-account-contention.md`.
