# Enforcing RLS across a transaction-mode connection pooler

Research note on making Postgres row-level security hold when the application talks to the database
through PgBouncer in transaction mode — the arrangement most managed Postgres providers put in front
of you by default.

Investigated against primary sources (PostgreSQL, PgBouncer, Neon). Every non-obvious claim is cited
inline; see [Sources](#sources).

---

## Executive summary

- **Set the tenant GUC transaction-locally, never session-level.** Under a transaction-mode pooler a
  physical server connection is handed to a client only for the duration of one transaction and then
  returned to the pool. A session-level `SET` persists on that physical connection and **leaks into
  the next client's transaction**; PgBouncer explicitly lists `SET`/`RESET` as *never* compatible
  with transaction pooling. `SET LOCAL` — or `set_config(name, value, true)` — is scoped to the
  current transaction and reverted at COMMIT/ROLLBACK. That is the correct, pooler-safe mechanism.
- **The GUC and the queries that depend on it must run in the same transaction on the same
  connection.** This is the entire implementation problem: it is easy to write code that sets the
  tenant on a connection nothing subsequently queries, and nothing about that failure is loud.
- **RLS policies** use `USING` (read/visibility filter) and `WITH CHECK` (write validation)
  referencing `nullif(current_setting('app.current_tenant', true), '')`. The `missing_ok` second
  argument stops an unset GUC raising; the `nullif` is what handles a **transaction-local setting
  after COMMIT, which reverts to the empty string rather than to NULL** (measured — see §3). Both
  guards are needed for unset context to match no rows rather than error.
- **The application must connect as a dedicated, least-privileged role with no `BYPASSRLS`.** This is
  the trap on managed providers, where the default owner role you are handed frequently carries it
  and silently bypasses every policy. Migrations and seeding run as the owner (bypass by design);
  runtime queries use a separate restricted role. Also `ALTER TABLE … FORCE ROW LEVEL SECURITY` so
  even a table-owning role is subject to policies.
- **Gotchas:** SQL-level prepared statements do not work through the pooler; migrations want a direct
  (unpooled) connection; and `SET` cannot take a bind parameter, so prefer the parameterizable
  `set_config('app.current_tenant', ?, true)` over an interpolated `SET LOCAL`.

---

## 1. Setting the per-request tenant GUC so it survives the pooler

### Why session-level `SET` is unsafe under transaction pooling

In transaction mode, *"A server connection is assigned to a client only during a transaction. When
PgBouncer notices that the transaction is over, the server will be put back into the pool."*
([PgBouncer: Features / pooling modes](https://www.pgbouncer.org/features.html)).

A session-level `SET` (= `SET SESSION`) persists on the *physical* connection: per PostgreSQL, *"Once
the surrounding transaction is committed, the effects will persist until the end of the session,
unless overridden by another `SET`."* ([PostgreSQL:
SET](https://www.postgresql.org/docs/current/sql-set.html)). Because the pooler recycles that
physical connection to a *different* client after the transaction ends, the GUC set by client A is
still present for client B — a cross-tenant leak.

This is why PgBouncer's compatibility table marks **`SET`/`RESET` as "Never"** compatible with
transaction pooling ([PgBouncer](https://www.pgbouncer.org/features.html)), and why Neon lists *"SET
/ RESET (session variables)"* among features **not supported with pooled connections** ([Neon:
Connection pooling](https://neon.com/docs/connect/connection-pooling)).

### Why `SET LOCAL` is correct

`SET LOCAL` is scoped to the transaction: *"The effects of `SET LOCAL` last only till the end of the
current transaction, whether committed or not."* ([PostgreSQL:
SET](https://www.postgresql.org/docs/current/sql-set.html)). Equivalently, `set_config(name, value,
is_local => true)`: *"If `is_local` is `true`, the new value will only apply during the current
transaction."* ([PostgreSQL: system admin
functions](https://www.postgresql.org/docs/current/functions-admin.html)).

Because the GUC is torn down at COMMIT/ROLLBACK — before the pooler returns the connection to the
pool — nothing leaks to the next client.

### Comparison

| Option | Survives pooler safely? | Verdict |
|---|---|---|
| **`SET LOCAL` / `set_config(…, true)` inside the request's transaction** | Yes — transaction-scoped, reverted before the connection returns to the pool | **Correct. Use this.** |
| Session-level `SET app.current_tenant = …` | **No** — persists on the physical connection and leaks to the next pooled client | Unsafe; PgBouncer marks it "Never" |
| A direct/unpooled endpoint with session `SET` | Technically works on a dedicated connection, but throws away pooling | Reserve the direct endpoint for **migrations and admin**, not request traffic |

Neon recommends the **unpooled** connection for *"schema migrations … and admin tasks requiring
session-level features"* ([Neon: Connection
pooling](https://neon.com/docs/connect/connection-pooling)) — the right home for migrations, not for
request-path RLS context.

---

## 2. Doing this in Spring

The invariant lives in SQL. What each framework supplies is only the hook that runs it at the right
moment, on the right connection.

### The requirement

The `set_config` must execute on the JDBC `Connection` that the surrounding transaction already
holds. Anything that borrows a connection separately from the pool sets tenant context on a
connection nothing then queries — and under fail-closed policies the symptom is an empty result,
not an error.

### Candidate hooks

- **`TransactionSynchronization`**, registered so it fires at `afterBegin` — the transaction has
  started and its connection is bound, but no application query has run.
- **An AOP aspect** around the transactional boundary, issuing the statement before proceeding.
- **Hibernate's `session.doWork(…)`**, which hands you the transaction's own connection directly:

  ```java
  session.doWork(conn -> {
    try (PreparedStatement ps =
        conn.prepareStatement("SELECT set_config('app.current_tenant', ?, true)")) {
      ps.setString(1, tenantId);
      ps.execute();
    }
  });
  ```

All three are acceptable. The choice is an implementation detail; the constraint is not.

### The Spring-specific trap: Open Session In View

Spring Boot enables OSIV by default. It keeps the `EntityManager` open for the whole request, so a
lazy association loaded during response serialization is fetched **after** the transaction committed
— on a connection with no tenant context. Under fail-closed RLS that yields an empty collection
rather than an exception: silently wrong data.

Set `spring.jpa.open-in-view=false`. The same mistake then raises `LazyInitializationException`,
which is loud and fixable.

For related reasons the Hibernate **second-level cache must stay off**: a cached entity outlives the
transaction whose tenant context authorized loading it. The first-level cache is per-`EntityManager`
and therefore transaction-scoped, which is safe.

### Not Hibernate's multi-tenancy support

`MultiTenantConnectionProvider` and `CurrentTenantIdentifierResolver` target schema-per-tenant and
database-per-tenant topologies, which is a different model from a discriminator column under RLS.
Hibernate 6's `@TenantId` is closer, but it is *application-level filtering* — useful as defence in
depth, never as the barrier. The barrier is the database's.

---

## 3. RLS policies, and how migrations bypass them

### Policy definition

Enable and force RLS, then write policies that read the GUC:

```sql
ALTER TABLE ticket ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket FORCE  ROW LEVEL SECURITY;   -- also bind the table owner (see below)

CREATE POLICY tenant_isolation ON ticket
  USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
```

### Why `nullif`, and not just `missing_ok`

**Measured against PostgreSQL 16.15; this one is an observation, not a citation.** The obvious
policy — `current_setting('app.current_tenant', true)::uuid`, with no `nullif` — is wrong under
pooling, and wrong in a way that only appears on the *second* transaction a connection serves.

`current_setting(name, true)` returns `NULL` for a setting that was never set on the session, which
is the documented behaviour ([PostgreSQL: admin
functions](https://www.postgresql.org/docs/current/functions-admin.html)) and hides every row as
intended. But a setting established with `set_config(…, true)` does not revert to *unset* at COMMIT.
It reverts to the **empty string**:

| Phase | `current_setting('app.current_tenant', true)` |
|---|---|
| Fresh session, never set | `NULL` |
| Inside the transaction that set it | `'11111111-…'` |
| **After COMMIT, same session** | **`''`** — not `NULL` |

And `''::uuid` raises `invalid input syntax for type uuid: ""`.

Connection pools reuse sessions, so *every* connection that has served one tenant-scoped transaction
begins its next one in that state. The consequences without `nullif`:

- "No tenant context sees no rows" is false. It is "no tenant context raises an error" — which
  still fails closed, but as a 500 rather than an empty result, and it makes the property untestable
  in the form everyone states it.
- Any query deliberately run *without* a tenant — such as a background worker's cross-tenant claim
  — cannot run at all.

`nullif(…, '')` collapses both the never-set and the reverted-to-empty cases to `NULL`, and a `NULL`
comparison hides every row. Fail-closed in both directions.

- **`USING`** filters which existing rows are visible/updatable/deletable; a row is hidden when the
  expression is false *or null* ([PostgreSQL: CREATE
  POLICY](https://www.postgresql.org/docs/current/sql-createpolicy.html)).
- **`WITH CHECK`** validates new/updated rows on INSERT/UPDATE; false or null raises an error and
  aborts the command. Supplying both prevents a tenant writing rows they could not read. If
  `WITH CHECK` is omitted, `USING` is reused for the check.
- With RLS enabled and **no matching policy**, PostgreSQL applies a **default-deny** ([PostgreSQL:
  Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)).

### Who bypasses policies

Three mechanisms, per PostgreSQL ([Row Security
Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)):

1. **The table owner** is *"typically not subject to row security policies"* — unless you add
   `ALTER TABLE … FORCE ROW LEVEL SECURITY`.
2. **Roles with `BYPASSRLS`** — *"Superusers and roles with the `BYPASSRLS` attribute always bypass
   the row security system."* `FORCE` does **not** override this.
3. **Superusers** — always.

So migrations and seeding connect as the schema-owning role and create fixture data across tenants
without tripping policies. Request traffic connects as a **separate restricted role**.

### The managed-provider trap

Managed Postgres often hands you an owner role that carries `BYPASSRLS`. On Neon, roles created via
the Console, CLI or API are members of `neon_superuser`, which **includes `BYPASSRLS`** ([Neon:
Manage roles](https://neon.com/docs/manage/roles)) — so the default connection string **silently
bypasses every policy**. Neon's own guidance is explicit: *"If the app uses the wrong role, RLS can be
silently bypassed"*, and the app's connection string *"should always use the least-privileged role"*
([Neon: RLS for multi-tenant apps](https://neon.com/guides/rls-multi-tenant-apps)).

**The role split:**

| Role | Attributes | Used for | Connection |
|---|---|---|---|
| Owner | bypasses RLS (ownership, often `BYPASSRLS` too) | migrations, seeding | direct / unpooled |
| `app_user` | no `BYPASSRLS`, not the table owner | all request-path queries | pooled |

A SQL-created role does not inherit those privileges, so create `app_user` explicitly and grant it
exactly the table privileges it needs. Because it is not the table owner, `FORCE` is
belt-and-braces — keep it; it is what protects you if the app ever connects as the owner by mistake.

---

## 4. What RLS does not cover: foreign-key checks

**Measured against PostgreSQL 16.15. An observation, not a citation.**

Row-level security governs which rows a statement may see and write. It does **not** govern
referential integrity checks: Postgres validates a foreign key with an internal system trigger that
runs as the table owner with policies bypassed, so the check finds rows the querying role cannot
see.

A plain reference between two tenant-scoped tables is therefore satisfied by *any* tenant's row:

```sql
-- child_plain.parent_id -> parent(id)
-- As tenant A, quoting a row that belongs to tenant B:
INSERT INTO child_plain (tenant_id, parent_id) VALUES ('<tenant-A>', '<tenant-B row>');
-- INSERT 0 1      <-- accepted
```

The write succeeds. The referenced row stays invisible to every later read by either tenant, so
nothing ever surfaces the link — but it is real, and it crosses the isolation boundary. `WITH CHECK`
does not catch it, because the row being inserted carries a perfectly valid `tenant_id` of its own.
It is the *reference* that points elsewhere.

The fix is to make the tenant travel with the key:

```sql
-- child_composite (tenant_id, parent_id) -> parent (tenant_id, id)
INSERT INTO child_composite (tenant_id, parent_id) VALUES ('<tenant-A>', '<tenant-B row>');
-- ERROR: insert or update on table "child_composite" violates foreign key constraint
-- DETAIL: Key is not present in table "parent".
```

A row in another tenant cannot satisfy the constraint at all. The cost is a redundant
`(tenant_id, id)` uniqueness constraint on every referenced table, present only to be a valid
reference target.

**RLS is the read barrier; composite keys are the write barrier.** Neither substitutes for the
other. Nothing in the system reports the failure this prevents, which is why it needs a test of
exactly this shape rather than trust.

## 5. Gotchas

- **Prepared statements through the pooler.** SQL-level `PREPARE`/`DEALLOCATE` are *"Never"*
  compatible with transaction pooling ([PgBouncer](https://www.pgbouncer.org/features.html)), and
  Neon lists them as unsupported on pooled connections ([Neon](https://neon.com/docs/connect/connection-pooling)).
  The JDBC driver uses server-side prepared statements once a statement crosses
  `prepareThreshold`; against PgBouncer below 1.21 this needs `prepareThreshold=0` on the pooled URL.
  Symptom of getting it wrong: `prepared statement "S_1" already exists` (SQLSTATE 42P05).
- **Migrations want a direct connection.** Flyway and Liquibase take session-level locks, which
  transaction pooling does not support. Point them at the unpooled endpoint. This dovetails with the
  role split: migrations = owner role, direct endpoint.
- **`current_setting` missing-variable handling.** `current_setting('app.current_tenant')` **throws**
  if the GUC was never set; `current_setting('app.current_tenant', true)` returns **NULL**
  ([PostgreSQL](https://www.postgresql.org/docs/current/functions-admin.html)). Always pass `true` in
  policies — **and wrap it in `nullif(…, '')`**, because after a transaction-local set the value
  reverts to the empty string rather than to unset, and `''::uuid` raises (see §3). Registering the
  GUC as a customized option is not required — any `namespace.name` setting is accepted at runtime.
- **`SET LOCAL` cannot bind its value.** Prefer `set_config('app.current_tenant', ?, true)`, which
  takes a normal bind parameter. String-interpolating a tenant id into `SET LOCAL` makes the
  isolation mechanism itself an injection surface.
- **Owner ≠ enforced by default.** Policies do not apply to the table owner unless `FORCE` is set,
  and never apply to `BYPASSRLS`/superuser even with `FORCE`. The only reliable guarantee is:
  **runtime connects as a non-owner, non-`BYPASSRLS` role.**
- **The scheme collapses if any request-path connection skips the context.** Neon: *"you need to
  always make sure every connection (including jobs and tasks) sets the needed context or uses the
  correct restricted role, otherwise RLS can't protect your data."*
  ([Neon](https://neon.com/guides/rls-multi-tenant-apps)). Centralise the hook so no route can
  forget — and note that background workers are explicitly included in that warning.

---

## Recommended pattern

1. **Two roles, two endpoints.** Migrations and seeding → owner role over the direct endpoint.
   Runtime → dedicated `app_user` with no `BYPASSRLS` over the pooled endpoint.
2. **Every tenant-scoped table:** `ENABLE` + `FORCE ROW LEVEL SECURITY`, with `USING` and
   `WITH CHECK` policies on `nullif(current_setting('app.current_tenant', true), '')::uuid`.
3. **Every request:** one hook at the transaction boundary issuing
   `set_config('app.current_tenant', ?, true)` with a bound parameter, on the transaction's own
   connection, with the tenant taken from the validated token rather than from client input.
4. **`spring.jpa.open-in-view=false`**, and the second-level cache off, so nothing queries outside
   the transaction that established context.

---

## Sources

- [PostgreSQL — SET](https://www.postgresql.org/docs/current/sql-set.html) — `SET LOCAL` is transaction-scoped; session `SET` persists for the whole session after commit.
- [PostgreSQL — System administration functions](https://www.postgresql.org/docs/current/functions-admin.html) — `current_setting(name, missing_ok)` returns NULL vs throws; `set_config(name, value, is_local=true)` = transaction-local SET.
- [PostgreSQL — CREATE POLICY](https://www.postgresql.org/docs/current/sql-createpolicy.html) — `USING` (visibility filter, null ⇒ hidden) vs `WITH CHECK` (write validation, null ⇒ error); USING reused for check if WITH CHECK omitted.
- [PostgreSQL — Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html) — ENABLE/FORCE RLS; owner not subject by default; `BYPASSRLS`/superuser always bypass; default-deny with no policy.
- [PgBouncer — Features / pooling modes](https://www.pgbouncer.org/features.html) — transaction mode returns the server connection to the pool at transaction end; `SET`/`RESET`, `PREPARE`/`DEALLOCATE`, `LISTEN` marked "Never".
- [Neon — Connection pooling](https://neon.com/docs/connect/connection-pooling) — the pooler is PgBouncer in transaction mode; pooled vs direct host; SET/RESET, LISTEN/NOTIFY, PREPARE unsupported on pooled connections; use direct for migrations and admin.
- [Neon — Manage roles](https://neon.com/docs/manage/roles) — no real superuser; Console/CLI/API roles are `neon_superuser` members with `BYPASSRLS`; SQL-created roles get only basic privileges.
- [Neon — RLS for multi-tenant apps](https://neon.com/guides/rls-multi-tenant-apps) — owner role for migrations only, least-privileged role for app requests; every connection including jobs must set context.
