# Outcomes are values; errors are Problem Details

The domain returns a **sealed interface of outcomes**. It does not throw for expected failures: an
unbalanced Entry and a breached floor are not exceptional, they are answers. Exceptions are reserved
for programmer error and genuinely broken infrastructure.

One exhaustive `switch` with pattern matching maps every outcome to an RFC 9457
`application/problem+json` response, each with a stable machine-readable `type`. Because the switch is
exhaustive over a sealed type, **adding a failure mode without mapping it is a compile error**.

This is also the answer to the question of what makes this project recognisably Java rather than a
Postgres project that happens to compile. Sealed interfaces, records and exhaustive pattern matching
are load-bearing here — they are what makes the error surface impossible to leave incomplete — rather
than sprinkled on to have been used.

## Status codes

- **400** — malformed or unparseable.
- **422** — well formed but refused by the domain: unbalanced, floor breached, Idempotency Key reused
  with a different request fingerprint.
- **404**, not 403 — a reference to an Account belonging to another Tenant. A 403 confirms that it
  exists.
- **409** — a name the caller chose is already taken within its scope: an Account code, a Tenant
  name, a staff username. The request is well formed and the domain has nothing against it; only
  the name is.
