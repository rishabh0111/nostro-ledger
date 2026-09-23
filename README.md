# Nostro

[![CI](https://github.com/rishabh0111/nostro-lab/actions/workflows/ci.yml/badge.svg)](https://github.com/rishabh0111/nostro-lab/actions/workflows/ci.yml)

A multitenant double-entry ledger, exposed as an HTTP API. Tenants record movements of money; the
system's job is to make it structurally impossible to record one that does not balance, to lose one,
to apply one twice, or to let one tenant's money touch another's. Every one of those guarantees is
held by the Postgres schema — constraints, row-level security, a restricted request-path role — and
each has a test that would fail if it broke.

```sh
docker compose up --build
```

That is the whole first run: Postgres and the API on `localhost:8080`, two demo Tenants seeded, their
API keys printed to the console. Nothing to sign up for, nothing to configure. The walkthrough below
goes from there to a refused cross-tenant write in six requests.

## The invariants, and the test that proves each

| Claim | Proof |
| --- | --- |
| A cross-tenant write is refused by the schema, before any application code | [`SchemaInvariantsIT.aCrossTenantReferenceIsRefusedByTheForeignKey`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java) |
| A cross-tenant read returns no rows, never an error that confirms the row exists | [`SchemaInvariantsIT.aCrossTenantReadReturnsNothing`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java), and over HTTP [`EntriesIT.anotherTenantsAccountIsAbsent`](nostro-api-service/src/test/java/io/nostro/api/ledger/EntriesIT.java) |
| The request-path role is not a superuser and cannot bypass row-level security | [`SchemaInvariantsIT.theRequestPathRoleBypassesNothing`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java) |
| With no Tenant bound, reads see nothing and writes are refused | [`SchemaInvariantsIT.noTenantContextFailsClosed`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java), [`TenantContextIT.noTenantBoundFailsClosedTwice`](nostro-api-service/src/test/java/io/nostro/api/tenant/TenantContextIT.java) |
| A valid credential for one Tenant, on a request naming another's Account, finds nothing | [`AuthenticationIT.anotherTenantsAccountIsAbsentNotForbidden`](nostro-api-service/src/test/java/io/nostro/api/auth/AuthenticationIT.java) |
| An unbalanced Entry is refused — by the domain as a value, and by the schema at `COMMIT` even from raw SQL | [`EntriesIT.anUnbalancedEntryIsRefused`](nostro-api-service/src/test/java/io/nostro/api/ledger/EntriesIT.java), [`SchemaInvariantsIT.anUnbalancedEntryIsRefusedByTheSchema`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java) |
| A Constrained Account never goes negative: 40 concurrent writers against one Account produce only floor refusals | [`RecordEntryIT.concurrentWritersToOneConstrainedAccount`](nostro-api-service/src/test/java/io/nostro/api/ledger/RecordEntryIT.java), and the `CHECK` behind the guard, [`SchemaInvariantsIT.theFloorIsACheckConstraint`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java) |
| Writers touching two Constrained Accounts in opposite orders never deadlock | [`RecordEntryIT.oppositeOrderWritersDoNotDeadlock`](nostro-api-service/src/test/java/io/nostro/api/ledger/RecordEntryIT.java) |
| A replayed Idempotency Key returns the first answer verbatim; concurrent replays record once | [`EntriesIT.anIdempotencyKeyIsHonoured`](nostro-api-service/src/test/java/io/nostro/api/ledger/EntriesIT.java), [`RecordEntryIT.concurrentDuplicatesRecordOnce`](nostro-api-service/src/test/java/io/nostro/api/ledger/RecordEntryIT.java) |
| An Entry is immutable once recorded, and reversed at most once | [`SchemaInvariantsIT.entryAndPostingCannotBeUpdatedOrDeleted`](nostro-api-service/src/test/java/io/nostro/api/schema/SchemaInvariantsIT.java), [`EntriesIT.anEntryIsReversedAtMostOnce`](nostro-api-service/src/test/java/io/nostro/api/ledger/EntriesIT.java) |
| A Reversing Entry is subject to the floor: a correction can be refused | [`EntriesIT.aReversalIsRefusedAtTheFloor`](nostro-api-service/src/test/java/io/nostro/api/ledger/EntriesIT.java) |
| An endpoint added without a permission declaration stops the application from starting | [`RequiredPermissionsTest.anUndeclaredEndpointFailsStartup`](nostro-api-service/src/test/java/io/nostro/api/auth/RequiredPermissionsTest.java) |
| A refusal the ledger learns to express without a response mapped is a compile error | [`LedgerRefusals`](nostro-api-service/src/main/java/io/nostro/api/ledger/LedgerRefusals.java) is an exhaustive switch over a sealed type; [`LedgerRefusalsTest`](nostro-api-service/src/test/java/io/nostro/api/ledger/LedgerRefusalsTest.java) pins each case |
| Every refusal is an RFC 9457 body with a `type` from a closed catalog — the framework's own refusals included | [`EntriesIT.frameworkRefusalsCarryTheMalformedType`](nostro-api-service/src/test/java/io/nostro/api/ledger/EntriesIT.java), [`AuthenticationIT.noCredentialIsUnauthenticated`](nostro-api-service/src/test/java/io/nostro/api/auth/AuthenticationIT.java) |
| The whole catalog is in the generated OpenAPI document, and every endpoint is | [`OpenApiIT.everyProblemTypeAppears`](nostro-api-service/src/test/java/io/nostro/api/docs/OpenApiIT.java), [`OpenApiIT.everyHandlerIsAnOperation`](nostro-api-service/src/test/java/io/nostro/api/docs/OpenApiIT.java) |
| Nothing in the request path borrows a second connection inside a transaction | [`TenantContextIT.nothingInTheRequestPathOpensASecondConnectionInsideATransaction`](nostro-api-service/src/test/java/io/nostro/api/tenant/TenantContextIT.java) |

Run them yourself with `./mvnw verify` (Docker required; the suite starts one Postgres container and
one Spring context and runs everything against them — see
[ADR-0012](docs/adr/0012-test-seams.md)).

Every push runs them against a real Postgres on GitHub Actions, and the run's summary is this table
again, ticked from the test reports. The table is not maintained by hand on either side: a claim
whose proof is renamed, removed or skipped fails the build rather than quietly going unproved
([`ci.yml`](.github/workflows/ci.yml),
[`ReadmeClaimsTest`](nostro-api-service/src/test/java/io/nostro/api/docs/ReadmeClaimsTest.java)).

## Walkthrough: from the console to a refused cross-tenant write

When the API is up, the console shows:

```
================================ demo Tenants ================================

demo-alpha  tenant 7c9e...
  Authorization: Bearer nk_...

demo-beta  tenant 2f41...
  Authorization: Bearer nk_...
```

Copy the two keys. Each reads and writes its own Tenant's ledger and sees nothing of the other's.

```sh
export ALPHA='nk_...'   # demo-alpha's key
export BETA='nk_...'    # demo-beta's key
API=http://localhost:8080/v1
req() { curl -s -w ' [%{http_code}]
' "$@"; }   # curl, with the status after the body
```

**1. Alpha opens two Accounts.** `constrained: true` declares an Account that may never go below
zero; the declaration is permanent.

```sh
req $API/accounts -H "Authorization: Bearer $ALPHA" -H 'Content-Type: application/json' \
  -d '{"code": "cash", "currency": "USD", "constrained": true}'
# {"id":"<CASH>","code":"cash","currency":"USD","constrained":true} [201]

req $API/accounts -H "Authorization: Bearer $ALPHA" -H 'Content-Type: application/json' \
  -d '{"code": "bank", "currency": "USD", "constrained": false}'
# {"id":"<BANK>","code":"bank","currency":"USD","constrained":false} [201]
```

**2. Alpha records an Entry.** Postings must sum to zero within each Currency. Amounts are decimal
strings, never floats. The Idempotency Key is yours; present it again and you get this answer again.

```sh
req $API/entries -H "Authorization: Bearer $ALPHA" -H 'Content-Type: application/json' -d '{
  "idempotencyKey": "fund-1",
  "description": "fund cash",
  "postings": [
    {"account": "<BANK>",   "amount": {"amount": "-100.00", "currency": "USD"}},
    {"account": "<CASH>", "amount": {"amount": "100.00",  "currency": "USD"}}
  ]}'
# {"entry":"...","position":"7318...:00000000000000000812"} [201]
```

**3. Read the Balance.** It reports the Position it reflects — a marker of how much of the Tenant's
history the number includes — so a caller who just recorded an Entry can tell whether a read has
caught up ([ADR-0007](docs/adr/0007-balances-are-projected-and-disclose-their-position.md)).

```sh
req "$API/accounts/<CASH>/balance" -H "Authorization: Bearer $ALPHA"
# {"account":"<CASH>","balance":{"amount":"100.00","currency":"USD"},"position":"..."} [200]
```

**4. Beta cannot see Alpha's Account.** Not forbidden — absent. A `403` would confirm it exists.

```sh
req "$API/accounts/<CASH>" -H "Authorization: Bearer $BETA"
# {"type":"https://nostro.dev/problems/unknown-account","title":"Unknown account","status":404,
#  "detail":"account <CASH> is not an account of this tenant", ...} [404]
```

**5. Beta cannot post to it either.** This is the cross-tenant write. Beta opens an Account of its
own and tries to move money from it into Alpha's `cash`. The Entry balances; it is still refused,
with the same answer as for an Account that does not exist at all. Beneath the API, the schema's
composite foreign keys and row-level security would refuse the row even if the application tried
to write it.

```sh
req $API/accounts -H "Authorization: Bearer $BETA" -H 'Content-Type: application/json' \
  -d '{"code": "cash", "currency": "USD", "constrained": false}'
# {"id":"<BETA_CASH>", ...} [201]

req $API/entries -H "Authorization: Bearer $BETA" -H 'Content-Type: application/json' -d '{
  "idempotencyKey": "steal-1",
  "postings": [
    {"account": "<BETA_CASH>", "amount": {"amount": "-1.00", "currency": "USD"}},
    {"account": "<CASH>",    "amount": {"amount": "1.00",  "currency": "USD"}}
  ]}'
# {"type":"https://nostro.dev/problems/unknown-account", ...} [404]
```

**6. The floor holds.** Alpha's `cash` holds 100.00 and is constrained; an Entry taking it to
-50.00 is refused as a value, and the balance is untouched.

```sh
req $API/entries -H "Authorization: Bearer $ALPHA" -H 'Content-Type: application/json' -d '{
  "idempotencyKey": "overdraw-1",
  "postings": [
    {"account": "<CASH>", "amount": {"amount": "-150.00", "currency": "USD"}},
    {"account": "<BANK>",   "amount": {"amount": "150.00",  "currency": "USD"}}
  ]}'
# {"type":"https://nostro.dev/problems/insufficient-balance", ...} [422]
```

Every refusal is `application/problem+json` with a `type` from a closed catalog; the catalog is
enumerated in the generated OpenAPI document at
[`/v3/api-docs`](http://localhost:8080/v3/api-docs) (YAML at `/v3/api-docs.yaml`), browsable at
[`/swagger-ui.html`](http://localhost:8080/swagger-ui.html). Both are public; the operations need a
credential.

## The API

| | |
| --- | --- |
| `POST /v1/accounts` | Open an Account: a code unique within the Tenant, a Currency it is denominated in forever, and whether it is Constrained. |
| `GET /v1/accounts/{id}` | The Account. |
| `POST /v1/entries` | Record an Entry: Postings that balance within each Currency, under an Idempotency Key. Answers the Entry's id and the Position it created. |
| `POST /v1/entries/{id}/reversal` | Record a Reversing Entry: an ordinary Entry whose Postings negate the original's, subject to every rule including the floor. At most once per Entry. |
| `GET /v1/accounts/{id}/balance` | The Balance, from the projection, with the Position it reflects; `?minPosition=` names a Position the caller needs it to include, which is waited for up to a server-side cap and then answered `200` with whatever the Balance reflects. |
| `GET /v1/accounts/{id}/postings` | The Account's history, newest first, keyset-paged by an opaque `cursor`. |
| `POST /v1/auth/login` | A staff username and password for a short-lived token. |
| `POST /v1/control/tenants`, `.../api-keys`, `.../staff-users` | The control plane ([ADR-0015](docs/adr/0015-the-control-plane-is-a-separate-authority.md)): reachable only with the bootstrap key (`NOSTRO_CONTROL_KEY` in [compose.yaml](compose.yaml)), which can reach nothing else. |

Credentials are `Authorization: Bearer ...`: an API key (`nk_...`, machine callers, stored hashed) or
a staff token (HS256, fifteen minutes). The Tenant is derived from the credential and is never a
header, a path segment or a body field
([ADR-0006](docs/adr/0006-the-tenant-comes-from-the-credential.md)).

## How it is built

- **One deployable and Postgres** in this milestone; the outbox table is written on every Entry and
  nothing drains it yet. The second milestone adds a
  projection service and a relay, fed by that outbox, and swaps the Balance read's implementation
  without changing its contract ([ADR-0009](docs/adr/0009-three-deployables-because-two-cardinalities-conflict.md)).
- **The schema holds the invariants.** Composite tenant-scoped foreign keys, row-level security
  under a request-path role that owns nothing and bypasses nothing, a `CHECK` on every Constrained
  Account's balance, insert-only `entry` and `posting`
  ([`V1__ledger_core.sql`](nostro-ledger-schema/src/main/resources/db/migration/V1__ledger_core.sql),
  [ADR-0004](docs/adr/0004-the-schema-holds-the-balance-floor.md)).
- **Recording an Entry is one transaction**: the Entry and its Postings, a guarded single-statement
  `UPDATE` per Constrained Account in id order, the Idempotency Key record, the outbox row
  ([`EntryWriter`](nostro-persistence/src/main/java/io/nostro/persistence/ledger/EntryWriter.java)).
  Transient SQLSTATEs are retried from outside the transaction
  ([`JpaEntryRecorder`](nostro-persistence/src/main/java/io/nostro/persistence/ledger/JpaEntryRecorder.java)).
- **Outcomes are values.** Recording returns a sealed interface; an unbalanced Entry is an answer,
  not an exception. One exhaustive switch maps every refusal to Problem Details, so an unmapped
  refusal does not compile ([ADR-0014](docs/adr/0014-outcomes-are-values-errors-are-problem-details.md)).
- **Java 21, Spring Boot 4, Hibernate ORM 7 through a `StatelessSession`, Flyway, Maven.**
  Why each, and what was rejected: [ADR-0013](docs/adr/0013-spring-boot-4-on-java-21.md) and
  [ADR-0016](docs/adr/0016-the-constraints-this-project-was-given.md).

## Reading

- [`CONTEXT.md`](CONTEXT.md) — the domain's language: Tenant, Account, Entry, Posting, Position, and
  the words this domain refuses to use.
- [`docs/adr/`](docs/adr/) — sixteen decisions, each with what it rejected.
- [`docs/research/`](docs/research/) — the notes the decisions rest on: row-level security under
  connection pooling, hot-account contention, ordering and watermarks, Hibernate against this schema.

## Modules

| | |
| --- | --- |
| [`nostro-domain`](nostro-domain/) | Pure Java: `Money`, `Entry`, `Posting`, `Position`, the sealed `RecordOutcome`, and the `EntryRecorder` and `BalanceReader` ports. Depends on the JDK alone. |
| [`nostro-ledger-schema`](nostro-ledger-schema/) | The ledger database's Flyway migrations, and nothing else. |
| [`nostro-outbox`](nostro-outbox/) | The contract between the deployables: the `EntryRecorded` message, and the topic it travels on. |
| [`nostro-persistence`](nostro-persistence/) | The JPA mapping, the Tenant context hook, the Entry writer and the reads. |
| [`nostro-balance-proto`](nostro-balance-proto/) | The gRPC contract between the API service and the projection: one `.proto`, and the stubs generated from it. |
| [`nostro-grpc-common`](nostro-grpc-common/) | How the Tenant crosses a gRPC boundary: one metadata key, and the interceptors that write and read it. |
| [`nostro-api-service`](nostro-api-service/) | The HTTP API: authentication, the control plane, the controllers, the error model, the OpenAPI document; a gRPC client of the projection. |
| [`nostro-outbox-relay`](nostro-outbox-relay/) | The single-writer relay that drains the outbox into Kafka, in Position order. |
| [`nostro-projection-service`](nostro-projection-service/) | Consumes Entries into Balances in a database of its own, at most once each, halting rather than skipping. |
| [`nostro-test-support`](nostro-test-support/) | The suite's singleton containers: the ledger's Postgres, the projection's Postgres, Kafka. |
