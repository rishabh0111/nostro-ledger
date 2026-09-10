# No circuit breaker

The only remote call in the system is the API service asking the projection service for a Balance.
It gets a timeout and bounded retries on transient status codes. It does not get a circuit breaker.

A circuit breaker's value is proportional to the fallback it enables, and there is no honest
fallback for a Balance: one cannot be invented, and a stale one cannot be served without reinventing
the position semantics of ADR-0007. What remains is failing faster, which the timeout already does.

The stronger half of the argument is that the containment is already architectural. **The write path
never calls the projection.** ADR-0004 puts the floor check on the stored balance in the API
service's own database, so a projection outage costs reads, not writes: money can still be recorded
correctly while the read model is unavailable.

## Consequences

Caching was rejected separately and for different reasons (ADR-0008). Between them, the two
application-layer patterns a service usually reaches for first are absent from this project by
decision rather than by oversight — and each rejection is defensible in one sentence, which was the
test applied.
