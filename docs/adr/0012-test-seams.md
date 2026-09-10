# One seam per deployable, and exactly one end-to-end path

Each deployable is tested at its own boundary — HTTP for the API service, Kafka in and gRPC out for
the projection service, outbox in and Kafka out for the relay — against real containers, with no
mocked collaborators. On top of that sits **exactly one** end-to-end test: record an Entry over HTTP,
then poll the Balance until the Position catches up.

Per-deployable seams alone never prove the wiring — proto compatibility, Tenant metadata
propagation, compose configuration — and wiring is where three deployables actually break. A
system-wide seam alone is slow and makes every failure a whodunnit. One of each, and the end-to-end
test earns its cost by being the only thing that would catch the integration failures the research
notes warn about.

## Two constraints that are not obvious

**Minimise the number of distinct Spring context configurations.** Context cache keys dominate suite
runtime far more than container startup does, and `@DynamicPropertySource` and bean overrides are
part of the cache key. One base configuration per service; no per-test property tweaking; no mocked
beans in integration tests. See `docs/research/ci-and-testcontainers-budget.md`.

**The tenant-isolation test must not use gRPC in-process transport**, which skips metadata
serialization by default — and metadata is what carries the Tenant. The test would pass while
proving nothing. See `docs/research/grpc-and-multi-module-layout.md`.

## The tests the claims depend on

A cross-tenant composite-foreign-key write refused; a cross-tenant read returning no rows rather
than an error; an unbalanced Entry refused; a floor breach refused as SQLSTATE 23514 and nothing
else; a replayed Idempotency Key returning the original response verbatim; the projection rebuilt
from position zero agreeing with `SUM` over Postings; and a Reversing Entry refused at the floor.

Without these, every claim this project makes is an assertion.
