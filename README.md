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

**[Full write-up](https://rishabh0111.github.io/blogs/multitenant-double-entry-ledger/)** ·
[Load test results](docs/results/2026-09-23-load-test/README.md) ·
[Design decisions](docs/adr/) ·
[CI runs](https://github.com/rishabh0111/nostro-ledger/actions/workflows/ci.yml)

A multitenant double-entry ledger with an HTTP API. Java 21, Spring Boot 4 and Hibernate 7, split
into three services over Postgres, Kafka, Redis and gRPC.

## Running it

```sh
docker compose up --build
```

Needs only Docker. The API comes up on `http://localhost:8080`, and the console prints API keys for two
seeded demo Tenants. OpenAPI is at [`/v3/api-docs`](http://localhost:8080/v3/api-docs) and Swagger UI at
[`/swagger-ui.html`](http://localhost:8080/swagger-ui.html).

## Try it

```sh
export ALPHA='nk_...'  BETA='nk_...'    # the two keys from the console
API=http://localhost:8080/v1
```

```sh
# Open a Constrained Account (never below zero) and an ordinary one
curl -s $API/accounts -H "Authorization: Bearer $ALPHA" -H 'Content-Type: application/json'   -d '{"code": "cash", "currency": "USD", "constrained": true}'
curl -s $API/accounts -H "Authorization: Bearer $ALPHA" -H 'Content-Type: application/json'   -d '{"code": "bank", "currency": "USD", "constrained": false}'

# Record an Entry: Postings sum to zero, amounts are decimal strings, the key makes retries safe
curl -s $API/entries -H "Authorization: Bearer $ALPHA" -H 'Content-Type: application/json' -d '{
  "idempotencyKey": "fund-1",
  "postings": [
    {"account": "<BANK>", "amount": {"amount": "-100.00", "currency": "USD"}},
    {"account": "<CASH>", "amount": {"amount": "100.00",  "currency": "USD"}}
  ]}'
# -> {"entry":"...","position":"<POSITION>"}

# Read the Balance, waiting until it includes that Entry
curl -s "$API/accounts/<CASH>/balance?minPosition=<POSITION>" -H "Authorization: Bearer $ALPHA"

# Another Tenant gets 404 for the same Account
curl -s "$API/accounts/<CASH>" -H "Authorization: Bearer $BETA"
```

Overdrawing `cash` answers `422 insufficient-balance`. Posting into Alpha's Account with Beta's key
answers `404 unknown-account`.

## API

| | |
| --- | --- |
| `POST /v1/accounts` | Open an Account: code, Currency, and whether it is Constrained |
| `GET /v1/accounts/{id}` | The Account |
| `POST /v1/entries` | Record an Entry under a required Idempotency Key |
| `POST /v1/entries/{id}/reversal` | Reverse an Entry, at most once |
| `GET /v1/accounts/{id}/balance` | The Balance and the Position it reflects; optional `?minPosition=` |
| `GET /v1/accounts/{id}/postings` | The Account's history, newest first, cursor-paged |
| `POST /v1/auth/login` | Staff username and password for a 15-minute token |
| `POST /v1/control/tenants`, `.../api-keys`, `.../staff-users` | Control plane, with the bootstrap key only |

Credentials are `Authorization: Bearer` with an API key (`nk_...`) or a staff token. Every error is
`application/problem+json` (RFC 9457).

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

CI runs these on every push and republishes the table in the run summary, ticked from the test reports.

## Development

```sh
./mvnw verify -DskipITs                     # unit tests, no Docker
./mvnw verify                               # integration tests against Testcontainers
docker compose up --build --wait && ./mvnw -Pe2e -pl nostro-system-test verify   # end to end
nostro-load-test/run.sh                     # Gatling load test, run by hand
```

Needs JDK 21 and Docker. Load test results are in [`docs/results/`](docs/results/).

## Configuration

All environment variables, with `dev-only-...` defaults in [`compose.yaml`](compose.yaml).

| Variable | |
| --- | --- |
| `NOSTRO_DB_OWNER_PASSWORD` | Ledger database owner, used only by migrations |
| `NOSTRO_APP_PASSWORD` | `nostro_app`, the request-path role |
| `NOSTRO_CONTROL_PASSWORD` | `nostro_control`, the control plane's role |
| `NOSTRO_RELAY_PASSWORD` | `nostro_relay`, the outbox relay's role |
| `NOSTRO_PROJECTION_PASSWORD` | `nostro_projection`, the projection's role |
| `NOSTRO_JWT_SECRET` | Staff token signing key, at least 32 bytes |
| `NOSTRO_CONTROL_KEY` | Control plane bootstrap key, `nc_` plus at least 32 bytes |
| `NOSTRO_SEED_DEMO_TENANTS` | `true` seeds the two demo Tenants |

Health and metrics: `/actuator/health/liveness`, `/actuator/health/readiness` and `/actuator/prometheus`
on each service's management port.

## Further reading

Why it is built this way, decision by decision: [the full write-up](https://rishabh0111.github.io/blogs/multitenant-double-entry-ledger/).

Design decisions, one per file: [`docs/adr/`](docs/adr/). Domain vocabulary: [`CONTEXT.md`](CONTEXT.md).
