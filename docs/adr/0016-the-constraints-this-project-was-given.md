# The constraints this project was given

These were fixed before any design work started. They are recorded because none of them is visible in
the code as a *constraint* — each one merely looks like a choice, and a later reader would otherwise
reopen decisions that were never open.

## The stack, and why it is not an implementation detail

- **Java 21, Spring Boot, Maven.** The project exists to exercise the mainstream Java backend stack
  under real constraints, so the stack is the point rather than a detail. Maven over Gradle because more readers
  can skim a `pom.xml` without learning a DSL. (Which Spring Boot version was *not* given, and is
  decided in ADR-0013.)
- **JPA/Hibernate for persistence.** Decided, and not open. jOOQ or `JdbcClient` would suit this schema
  better, but JPA is what most Spring codebases run on, and showing it handled deliberately against a
  demanding schema outranks technical fit here. Where JPA fights the schema, the answer is to handle it
  deliberately and be able to explain it — never to swap the library.
- **Postgres**, with the schema doing real work: constraints, not just storage.

## The operating constraints

- **`docker compose up` is the whole first run.** No credentials to obtain, nothing to sign up for.
  This is what keeps it reproducible by a stranger.
- **Zero budget.** Nothing that requires a paid service.
- **It has to run, with tests that would fail if the guarantees broke.** A design document is not the
  deliverable.

## The requirement that is not a requirement of the domain

**Kafka, Redis, gRPC and service-to-service calls have to appear, and more than one deployable.**
They are the everyday toolkit of a Java service estate, and a single service talking only to Postgres
does not exercise them.

This is a requirement on the *project* rather than on the *domain*, and that tension was resolved
rather than papered over: at most one of the three may be justified as cross-cutting infrastructure
and labelled as such, and the other two must answer a need the ledger actually has. See ADR-0008 for
what each one does, and ADR-0009 for why there are three deployables.

## Out of scope, deliberately

**Infrastructure and operations** — no Kubernetes, no cloud provisioning, no deployment. The
interesting problems here are in the schema, the persistence layer, the concurrency and the service
boundaries, and none of them needs a cluster to be demonstrated.

Two consequences accepted rather than worked around: nothing runs at a URL, and "it works" has to be
shown some other way.

**Continuous integration is the exception and belongs in.** It costs nothing, needs no infrastructure
to operate, and runs Testcontainers against a real Postgres on every push. That matters more here than
on a typical project: the central claims are that tenant isolation holds and that a cross-tenant write
is refused, and a pipeline is what turns those from assertions into something continuously re-checked.
Building and testing is in; shipping it somewhere is not.

## The weaknesses this design had to answer

The seven gaps the project was judged against at the outset, and where each was answered:

1. **A Postgres project that happens to compile** — nothing exercised Java itself. → ADR-0014.
2. **Kafka, Redis and gRPC had no honest reason to exist.** → ADR-0008.
3. **No JVM story** — no profiling, no GC discussion, no load test, no documented p99. →
   `docs/results/2026-09-23-load-test/`.
4. **No observability story.** → health groups, Prometheus metrics, one trace across the gRPC boundary,
   and a reconciliation check the running ledger raises about itself (`nostro-api-service/.../reconciliation/`).
5. **Nothing for a reviewer to look at but source and a green build.** → the generated OpenAPI
   document, the README's claim table, and a CI run whose summary ticks that table from the test reports.
6. **Concurrency was thin.** → ADR-0004 and `docs/research/hot-account-contention.md`.
7. **Resilience patterns and caching absent, with no decision either way.** → ADR-0011 and ADR-0008,
   both of which reject rather than adopt, and say why in a sentence each.
