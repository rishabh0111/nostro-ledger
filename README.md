# nostro-ledger

[![CI](https://github.com/rishabh0111/nostro-ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/rishabh0111/nostro-ledger/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java_21-ED8B00?style=flat&logo=openjdk&logoColor=white)
![Spring Boot 4](https://img.shields.io/badge/Spring_Boot_4-6DB33F?style=flat&logo=springboot&logoColor=white)
![Hibernate 7](https://img.shields.io/badge/Hibernate_7-59666C?style=flat&logo=hibernate&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=flat&logo=postgresql&logoColor=white)
![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-231F20?style=flat&logo=apachekafka&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-FF4438?style=flat&logo=redis&logoColor=white)
![gRPC](https://img.shields.io/badge/gRPC-244C5A?style=flat&logo=grpc&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat&logo=docker&logoColor=white)
![OpenAPI](https://img.shields.io/badge/OpenAPI-6BA539?style=flat&logo=openapiinitiative&logoColor=white)

[Load test results](docs/results/2026-09-23-load-test/README.md) ·
[Design decisions](docs/adr/) ·
[CI runs](https://github.com/rishabh0111/nostro-ledger/actions/workflows/ci.yml)

Nostro is a multitenant double-entry ledger with an HTTP API. It runs as three Spring Boot services
on Java 21, with Postgres, Kafka, Redis and gRPC between them.

A ledger can get money wrong in four ways. It can record a movement that does not balance, lose one,
apply one twice, or let one tenant touch another tenant's money. Nostro refuses all four in the
Postgres schema itself, using constraints, row-level security and a request-path role that owns no
tables and bypasses no policy. The application checks too, but the database would refuse the write
even if the application tried to make it.

Twenty-five claims below each name the test that proves them. CI runs those tests against real
Postgres, Kafka and Redis containers on every push, then republishes the list as the run summary,
ticked from the test reports. In the load test, 32 concurrent writers hit one hot account. All 6,346
requests were either recorded or refused at the balance floor, with zero database errors.

## Running it

```sh
docker compose up --build
```

That is the whole first run. Compose starts two Postgres servers (the ledger's and the
projection's), Kafka, Redis, a migration job for each database, and the three services. The API
listens on `http://localhost:8080`. When it is up, two demo Tenants are seeded and their API keys are
printed to the console. There is nothing to sign up for and no key to obtain.

You need Docker. To run the tests you also need JDK 21.

The OpenAPI document is at [`/v3/api-docs`](http://localhost:8080/v3/api-docs) and the Swagger UI is
at [`/swagger-ui.html`](http://localhost:8080/swagger-ui.html). Both are public. Every operation needs
a credential.

## The seeded demo

The console shows:

```
================================ demo Tenants ================================

demo-alpha  tenant 7c9e...
  Authorization: Bearer nk_...

demo-beta  tenant 2f41...
  Authorization: Bearer nk_...
```

Each key reads and writes its own Tenant's ledger and sees nothing of the other's. These six
requests go from an empty ledger to a refused cross-tenant write.

```sh
export ALPHA='nk_...'   # demo-alpha's key
export BETA='nk_...'    # demo-beta's key
API=http://localhost:8080/v1
req() { curl -s -w ' [%{http_code}]
' "$@"; }   # curl, with the status after the body
```

**1. Alpha opens two Accounts.** `constrained: true` declares an Account that may never go below zero,
and the declaration is permanent.

```sh
req $API/accounts -H "Authorization: Bearer $ALPHA" -H 'Content-Type: application/json' \
  -d '{"code": "cash", "currency": "USD", "constrained": true}'
# {"id":"<CASH>","code":"cash","currency":"USD","constrained":true} [201]

req $API/accounts -H "Authorization: Bearer $ALPHA" -H 'Content-Type: application/json' \
  -d '{"code": "bank", "currency": "USD", "constrained": false}'
# {"id":"<BANK>","code":"bank","currency":"USD","constrained":false} [201]
```

**2. Alpha records an Entry.** Postings must sum to zero within each Currency. Amounts are decimal
strings, never floats. The Idempotency Key is the caller's, and presenting it again returns this same
answer.

```sh
req $API/entries -H "Authorization: Bearer $ALPHA" -H 'Content-Type: application/json' -d '{
  "idempotencyKey": "fund-1",
  "description": "fund cash",
  "postings": [
    {"account": "<BANK>", "amount": {"amount": "-100.00", "currency": "USD"}},
    {"account": "<CASH>", "amount": {"amount": "100.00",  "currency": "USD"}}
  ]}'
# {"entry":"...","position":"<POSITION>"} [201]
```

**3. Alpha reads the Balance.** Balances come from a separate projection service, which can lag the
write. Each Balance reports the Position it reflects. Pass the Position the write returned as
`minPosition` and the read waits for it, up to a server-side cap.

```sh
req "$API/accounts/<CASH>/balance?minPosition=<POSITION>" -H "Authorization: Bearer $ALPHA"
# {"account":"<CASH>","balance":{"amount":"100.00","currency":"USD"},"position":"<POSITION>"} [200]
```

**4. Beta cannot see Alpha's Account.** The answer is `404`, because a `403` would confirm the Account
exists.

```sh
req "$API/accounts/<CASH>" -H "Authorization: Bearer $BETA"
# {"type":"https://nostro.dev/problems/unknown-account","title":"Unknown account","status":404,
#  "detail":"account <CASH> is not an account of this tenant", ...} [404]
```

**5. Beta cannot post to it either.** Beta opens an Account of its own and tries to move money from it
into Alpha's `cash`. The Entry balances, and it is still refused with the answer an Account that does
not exist would get. Below the API, composite foreign keys and row-level security would refuse the
row anyway.

```sh
req $API/accounts -H "Authorization: Bearer $BETA" -H 'Content-Type: application/json' \
  -d '{"code": "cash", "currency": "USD", "constrained": false}'
# {"id":"<BETA_CASH>", ...} [201]

req $API/entries -H "Authorization: Bearer $BETA" -H 'Content-Type: application/json' -d '{
  "idempotencyKey": "steal-1",
  "postings": [
    {"account": "<BETA_CASH>", "amount": {"amount": "-1.00", "currency": "USD"}},
    {"account": "<CASH>",      "amount": {"amount": "1.00",  "currency": "USD"}}
  ]}'
# {"type":"https://nostro.dev/problems/unknown-account", ...} [404]
```

**6. The floor holds.** Alpha's `cash` holds 100.00 and is constrained. An Entry that would take it to
-50.00 is refused, and the balance does not move.

```sh
req $API/entries -H "Authorization: Bearer $ALPHA" -H 'Content-Type: application/json' -d '{
  "idempotencyKey": "overdraw-1",
  "postings": [
    {"account": "<CASH>", "amount": {"amount": "-150.00", "currency": "USD"}},
    {"account": "<BANK>", "amount": {"amount": "150.00",  "currency": "USD"}}
  ]}'
# {"type":"https://nostro.dev/problems/insufficient-balance", ...} [422]
```

## What is here

| | |
| --- | --- |
| **Accounts** | A code unique within the Tenant, one Currency for life, and an optional floor at zero. |
| **Entries** | Postings that must balance within each Currency, recorded in one transaction under a required Idempotency Key. |
| **Reversals** | An ordinary Entry that negates an earlier one, at most once per Entry, and subject to the floor like any other. |
| **Balances** | Served from a projection over gRPC, with the Position they reflect and a bounded wait for read-your-writes. |
| **History** | An Account's Postings, newest first, paged by an opaque keyset cursor. |
| **Auth** | Hashed API keys for machine callers and 15-minute HS256 tokens for staff. The Tenant always comes from the credential. |
| **Control plane** | Creates Tenants and issues credentials under a separate bootstrap key, which can reach nothing else. |
| **Errors** | RFC 9457 Problem Details for every refusal, with a `type` from a closed catalog listed in the OpenAPI document. |
| **Rate limits** | A per-Tenant token bucket in Redis, shared by every API instance. |
| **Observability** | Liveness and readiness probes, Prometheus metrics, one trace across the gRPC boundary, JSON logs carrying the Tenant, and a scheduled check that stored balances agree with their Postings. |

Three services, one Maven build:

| | |
| --- | --- |
| **API service** | The HTTP API. Records Entries and writes an outbox row in the same transaction. Scales out. |
| **Outbox relay** | Exactly one instance. Drains the outbox into Kafka in Position order, keyed by Tenant. |
| **Projection service** | Consumes Kafka into Balances in a database of its own, applies each Entry at most once, and answers the API over gRPC. |

## The invariants, and the test that proves each

| Claim | Proof |
| --- | --- |
| A cross-tenant write is refused by the schema, before any application code | [`SchemaInvariantsIT.aCrossTenantReferenceIsRefusedByTheForeignKey`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java) |
| A cross-tenant read returns no rows, never an error that confirms the row exists | [`SchemaInvariantsIT.aCrossTenantReadReturnsNothing`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java), and over HTTP [`EntriesIT.anotherTenantsAccountIsAbsent`](nostro-api-service/src/test/java/io/nostro/api/ledger/EntriesIT.java) |
| The request-path role is not a superuser and cannot bypass row-level security | [`SchemaInvariantsIT.theRequestPathRoleBypassesNothing`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java) |
| With no Tenant bound, reads see nothing and writes are refused | [`SchemaInvariantsIT.noTenantContextFailsClosed`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java), [`TenantContextIT.noTenantBoundFailsClosedTwice`](nostro-api-service/src/test/java/io/nostro/api/tenant/TenantContextIT.java) |
| A valid credential for one Tenant, on a request naming another's Account, finds nothing | [`AuthenticationIT.anotherTenantsAccountIsAbsentNotForbidden`](nostro-api-service/src/test/java/io/nostro/api/auth/AuthenticationIT.java) |
| An unbalanced Entry is refused by the domain as a value, and by the schema at `COMMIT` even from raw SQL | [`EntriesIT.anUnbalancedEntryIsRefused`](nostro-api-service/src/test/java/io/nostro/api/ledger/EntriesIT.java), [`SchemaInvariantsIT.anUnbalancedEntryIsRefusedByTheSchema`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java) |
| A Constrained Account never goes negative: 40 concurrent writers against one Account produce only floor refusals | [`RecordEntryIT.concurrentWritersToOneConstrainedAccount`](nostro-api-service/src/test/java/io/nostro/api/ledger/RecordEntryIT.java), and the `CHECK` behind the guard, [`SchemaInvariantsIT.theFloorIsACheckConstraint`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java) |
| Writers touching two Constrained Accounts in opposite orders never deadlock | [`RecordEntryIT.oppositeOrderWritersDoNotDeadlock`](nostro-api-service/src/test/java/io/nostro/api/ledger/RecordEntryIT.java) |
| A replayed Idempotency Key returns the first answer verbatim, and concurrent replays record once | [`EntriesIT.anIdempotencyKeyIsHonoured`](nostro-api-service/src/test/java/io/nostro/api/ledger/EntriesIT.java), [`RecordEntryIT.concurrentDuplicatesRecordOnce`](nostro-api-service/src/test/java/io/nostro/api/ledger/RecordEntryIT.java) |
| An Entry is immutable once recorded, and reversed at most once | [`SchemaInvariantsIT.entryAndPostingCannotBeUpdatedOrDeleted`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java), [`EntriesIT.anEntryIsReversedAtMostOnce`](nostro-api-service/src/test/java/io/nostro/api/ledger/EntriesIT.java) |
| A Reversing Entry is subject to the floor, so a correction can be refused | [`EntriesIT.aReversalIsRefusedAtTheFloor`](nostro-api-service/src/test/java/io/nostro/api/ledger/EntriesIT.java) |
| An endpoint added without a permission declaration stops the application from starting | [`RequiredPermissionsTest.anUndeclaredEndpointFailsStartup`](nostro-api-service/src/test/java/io/nostro/api/auth/RequiredPermissionsTest.java) |
| A refusal the ledger learns to express without a response mapped is a compile error | [`LedgerRefusals`](nostro-api-service/src/main/java/io/nostro/api/ledger/LedgerRefusals.java) is an exhaustive switch over a sealed type, and [`LedgerRefusalsTest`](nostro-api-service/src/test/java/io/nostro/api/ledger/LedgerRefusalsTest.java) pins each case |
| Every refusal is an RFC 9457 body with a `type` from a closed catalog, the framework's own refusals included | [`EntriesIT.frameworkRefusalsCarryTheMalformedType`](nostro-api-service/src/test/java/io/nostro/api/ledger/EntriesIT.java), [`AuthenticationIT.noCredentialIsUnauthenticated`](nostro-api-service/src/test/java/io/nostro/api/auth/AuthenticationIT.java) |
| The whole catalog is in the generated OpenAPI document, and every endpoint is | [`OpenApiIT.everyProblemTypeAppears`](nostro-api-service/src/test/java/io/nostro/api/docs/OpenApiIT.java), [`OpenApiIT.everyHandlerIsAnOperation`](nostro-api-service/src/test/java/io/nostro/api/docs/OpenApiIT.java) |
| Nothing in the request path borrows a second connection inside a transaction | [`TenantContextIT.nothingInTheRequestPathOpensASecondConnectionInsideATransaction`](nostro-api-service/src/test/java/io/nostro/api/tenant/TenantContextIT.java) |
| A Tenant's Entries are published in the order they were recorded, a late committer is never overtaken, and a relay killed mid-drain loses none | [`OutboxRelayIT.aKilledLeaderIsReplacedAndNothingIsLost`](nostro-outbox-relay/src/test/java/io/nostro/relay/OutboxRelayIT.java), [`OutboxDrainIT.aLateCommitterIsNotOvertaken`](nostro-outbox-relay/src/test/java/io/nostro/relay/OutboxDrainIT.java) |
| The producer's ordering protection cannot be switched off by tuning: a conflicting setting fails at startup | [`ProducerSettingsTest.aConflictingSettingFailsLoudly`](nostro-outbox-relay/src/test/java/io/nostro/relay/ProducerSettingsTest.java) |
| Every Entry published twice and then replayed from offset zero is applied once, and a Tenant's Balances sum to zero at every moment | [`EntryApplyIT.publishedTwiceAndReplayedFromZero`](nostro-projection-service/src/test/java/io/nostro/projection/EntryApplyIT.java) |
| A message the projection cannot apply halts its partition, is never skipped or dead-lettered, and the halt is a metric at once | [`EntryApplyIT.anUnappliableMessageHaltsItsPartition`](nostro-projection-service/src/test/java/io/nostro/projection/EntryApplyIT.java) |
| The projection isolates Tenants by the same technique, over a real socket: a call without a Tenant is refused, another Tenant's Account is empty | [`BalanceServiceIT.aCallWithNoTenantIsRefused`](nostro-projection-service/src/test/java/io/nostro/projection/BalanceServiceIT.java), [`BalanceServiceIT.anotherTenantsAccountIsAnAccountWithNoPostings`](nostro-projection-service/src/test/java/io/nostro/projection/BalanceServiceIT.java), [`ProjectionIsolationIT.aCrossTenantReadReturnsNothing`](nostro-projection-service/src/test/java/io/nostro/projection/ProjectionIsolationIT.java) |
| A read waiting for its Position holds no connection, so a lagging projection under load cannot exhaust either service's pool | [`BalanceServiceIT.parkedCallsHoldNoConnection`](nostro-projection-service/src/test/java/io/nostro/projection/BalanceServiceIT.java), [`BalanceIT.aLaggingProjectionDoesNotExhaustThePool`](nostro-api-service/src/test/java/io/nostro/api/ledger/BalanceIT.java) |
| Every API instance draws on the same per-Tenant request budget, and a throttled caller gets a Problem Detail | [`TenantRateLimiterTest.twoInstancesShareOneBudget`](nostro-api-service/src/test/java/io/nostro/api/ratelimit/TenantRateLimiterTest.java), [`RateLimitIT.aThrottledCallerGetsAProblemDetail`](nostro-api-service/src/test/java/io/nostro/api/ratelimit/RateLimitIT.java) |
| A stored balance that disagrees with its Postings is a metric the running system raises about itself | [`ObservabilityIT.reconciliationFailureIsAMetric`](nostro-api-service/src/test/java/io/nostro/api/ObservabilityIT.java) |
| An Entry recorded over HTTP reaches the projected Balance through every service, and there no Tenant can see or reference another's money | [`EndToEndIT.anEntryReachesTheProjectedBalance`](nostro-system-test/src/test/java/io/nostro/system/EndToEndIT.java), [`EndToEndIT.aTenantCannotSeeOrReferenceAnothersMoney`](nostro-system-test/src/test/java/io/nostro/system/EndToEndIT.java) |

[`ReadmeClaimsTest`](nostro-api-service/src/test/java/io/nostro/api/docs/ReadmeClaimsTest.java) parses
this table and fails if a proof names a file or a method that does not exist. CI then ticks each row
from the test reports, so a claim whose test was renamed, removed or skipped fails the build.

## Conventions worth knowing before you call it

- The Tenant comes from the credential. It is never a header, a path segment or a body field.
- Another Tenant's Account answers **404, never 403**, so a status code cannot confirm it exists.
- Amounts are decimal strings in the Account's Currency. Currencies never sum together, and the ledger
  never converts between them.
- `POST /v1/entries` requires an Idempotency Key. A retry gets the first response replayed verbatim.
  The same key with a different body is a `422`.
- A Balance always answers `200` with the Position it reflects. `minPosition` waits up to a server cap
  and never turns lag into an error.
- Every refusal is `application/problem+json`. Branch on `type`, never on `title` or `detail`.
- `400` means malformed, `422` means the ledger refused it (unbalanced, floor, key reuse), and `409`
  means a name is already taken.
- Each Tenant gets a burst of 200 requests, refilled at 100 a second. Past that, the answer is `429`
  with a Problem Detail.

## Development

```sh
./mvnw verify -DskipITs                     # compile everything, unit tests only, no Docker
./mvnw verify                               # every service against real Postgres, Kafka and Redis
docker compose up --build --wait            # then the one end-to-end test, against the stack
./mvnw -Pe2e -pl nostro-system-test verify
nostro-load-test/run.sh                     # Gatling, by hand, results in target/load-results/
```

Tests do not mock the database. The invariants live in SQL, so a test that mocks Postgres proves
nothing about them. Each service is tested at its own boundary against Testcontainers, with one
Spring context per module: HTTP for the API, outbox in and Kafka out for the relay, Kafka in and gRPC
out for the projection. One end-to-end test then follows an Entry through every service.

CI never runs the load test. A throughput number from a shared runner proves nothing, so results are
committed as dated notes under [`docs/results/`](docs/results/), each naming the machine it ran on.

## Configuration

Everything is environment-driven. The defaults in [`compose.yaml`](compose.yaml) are for local use,
and each is named `dev-only-...` so nobody mistakes it for a real secret.

| Variable | What it is |
| --- | --- |
| `NOSTRO_DB_OWNER_PASSWORD` | The ledger database's owner, used only by migrations |
| `NOSTRO_APP_PASSWORD` | `nostro_app`, the request-path role, which owns nothing and bypasses no policy |
| `NOSTRO_CONTROL_PASSWORD` | `nostro_control`, the control plane's role |
| `NOSTRO_RELAY_PASSWORD` | `nostro_relay`, the outbox relay's role |
| `NOSTRO_PROJECTION_PASSWORD` | `nostro_projection`, the projection service's role in its own database |
| `NOSTRO_JWT_SECRET` | Signs staff tokens (HS256, at least 32 bytes) |
| `NOSTRO_CONTROL_KEY` | The control plane's bootstrap key, `nc_` plus at least 32 bytes |
| `NOSTRO_SEED_DEMO_TENANTS` | `true` seeds the two demo Tenants at startup |

Migrations run as their own compose jobs with the owner credential, before any service starts, and no
service is given that credential. Each service serves `/actuator/health/liveness`,
`/actuator/health/readiness` and `/actuator/prometheus` on its management port.

Nothing here deploys. There is no Kubernetes or cloud configuration, by decision
([ADR-0016](docs/adr/0016-the-constraints-this-project-was-given.md)). The problems worth showing are
in the schema, the concurrency and the service boundaries, and none of them needs a cluster.

## Modules

| | |
| --- | --- |
| [`nostro-domain`](nostro-domain/) | Pure Java: `Money`, `Entry`, `Posting`, `Position`, the sealed `RecordOutcome`, and the `EntryRecorder` and `BalanceReader` ports. Depends on the JDK alone. |
| [`nostro-ledger-schema`](nostro-ledger-schema/) | The ledger database's Flyway migrations, and nothing else. |
| [`nostro-outbox`](nostro-outbox/) | The contract between the services: the `EntryRecorded` message and its topic. |
| [`nostro-persistence`](nostro-persistence/) | The JPA mapping, the Tenant context hook, the Entry writer and the reads. |
| [`nostro-balance-proto`](nostro-balance-proto/) | The gRPC contract between the API and the projection, and the stubs generated from it. |
| [`nostro-grpc-common`](nostro-grpc-common/) | How the Tenant crosses a gRPC boundary: one metadata key and the interceptors that write and read it. |
| [`nostro-api-service`](nostro-api-service/) | The HTTP API, authentication, the control plane, the error model and the OpenAPI document. |
| [`nostro-outbox-relay`](nostro-outbox-relay/) | The single-writer relay from the outbox to Kafka. |
| [`nostro-projection-service`](nostro-projection-service/) | Balances in a database of its own, applied at most once, halting rather than skipping. |
| [`nostro-test-support`](nostro-test-support/) | The shared Testcontainers: both Postgres servers, Kafka and Redis. |
| [`nostro-system-test`](nostro-system-test/) | The one end-to-end test, over HTTP against the compose stack. |
| [`nostro-load-test`](nostro-load-test/) | Gatling, two arms, run by hand. |

## Where the reasoning is

Each decision behind the awkward parts is one file in [`docs/adr/`](docs/adr/), with the options it
rejected: why the balance floor is a `CHECK` constraint, why the relay is a single writer, why the
projection halts instead of dead-lettering, and why there is no circuit breaker and no cache.
[`CONTEXT.md`](CONTEXT.md) is the vocabulary the code is written in, including the words it refuses to
use. [`docs/research/`](docs/research/) holds the source-cited notes those decisions rest on:
row-level security under connection pooling, hot-account contention, ordering and watermarks, and
Hibernate against an insert-only schema.
