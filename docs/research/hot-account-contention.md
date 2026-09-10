# Keeping a constrained Account non-negative under concurrent writers

Research note on the one place in this ledger where writes are not append-only: an Entry that touches
a **constrained Account** must be refused if it would push that Account's Balance below zero, and the
refusal has to hold when many writers hit the same Account at once.

The project's position is that the *schema* enforces invariants. This note is about whether that
position survives contact with concurrency — and it does, but only for a specific set of mechanisms.
Several plausible-looking designs are correct in a single-threaded test and wrong under load, in ways
that produce a negative Balance rather than an error.

Investigated against primary sources (PostgreSQL 16 documentation above all; Hibernate ORM 6.6 guides
and javadoc; Spring Framework, Spring Data JPA and Spring Retry reference docs; PostgreSQL and Spring
source where the documentation is silent). Every non-obvious claim is cited inline. Claims that no
document settles are marked **needs a test** or **estimate**, and are not presented as fact. See
[Sources](#sources).

Sibling notes: [`rls-pooling.md`](rls-pooling.md) (tenant context, the two-role split, the pooler) and
[`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md) (composite keys, insert-only tables,
batching). This note does not restate them; where the constrained-Account write path changes something
in one of them, it says so explicitly.

---

## Executive summary

- **A stored balance column on the Account row, updated by `UPDATE account SET balance = balance + ?`,
  is safe at `READ COMMITTED`** — and that is a documented property, not a hope. When a second writer
  finds the row locked it waits, and after the first commits it *"will attempt to apply its operation
  to the updated version of the row"*, re-evaluating the `WHERE` clause against that new version
  ([PostgreSQL: Transaction Isolation](https://www.postgresql.org/docs/16/transaction-iso.html)). The
  arithmetic is done by the server against the current value, never against a value the application
  read a round trip earlier (§1).
- **What is *not* safe is doing the arithmetic in Java.** `SELECT balance` → compute in the service →
  `UPDATE … SET balance = <literal>` is the classic lost update, and `READ COMMITTED` permits it. The
  distinction between that and the paragraph above is the whole of §2.
- **Use both a guarded `UPDATE` and a `CHECK` constraint, because they fail differently.**
  `WHERE … AND (NOT constrained OR balance + ? >= 0)` refuses by updating **zero rows** — no error, no
  SQLSTATE, and a caller that ignores the row count commits a lie.
  `CHECK (NOT constrained OR balance >= 0)` refuses with **`23514`** `check_violation`, which cannot be
  ignored. The guard gives a clean business answer; the CHECK is the barrier that still holds when
  someone writes a new query and forgets the guard (§1).
- **`SELECT … FOR UPDATE` plus a `SUM()` over Postings also works, and costs more.** Two documented
  traps: a locking clause *"cannot be used with aggregation"*, so the lock and the sum are necessarily
  two statements; and the shape is correct at `READ COMMITTED` but **breaks at `REPEATABLE READ`**,
  because the snapshot is frozen at the first statement and can predate the committed work you just
  waited for (§1c).
- **`SERIALIZABLE` gets the guarantee for free and hands you a retry loop instead.** PostgreSQL's own
  worked example for SSI is literally this shape — `SELECT SUM(value) … WHERE class = 1` followed by an
  insert — and it rolls one transaction back with `40001`. No document quantifies the false-positive
  rate; **that is a measurement, not a citation** (§2).
- **The SQLSTATEs to handle are `40001`, `40P01`, `55P03`, `57014`, `23514`, `25P03`.** Only the first
  three are safely retryable. `23514` is the business answer "insufficient funds" and must reach the
  caller, never a retry loop (§3).
- **Retry must wrap the transaction from outside it.** A serialization failure can surface at COMMIT —
  after your `@Transactional` method body has returned — and once any statement errors the transaction
  is aborted, so every further statement gets `25P02`. An inner `@Transactional` with the default
  `REQUIRED` joins the same physical transaction, so retrying *there* produces an
  `UnexpectedRollbackException` at the outer commit rather than a retry (§3).
- **Sorting the Accounts prevents deadlock only when the locks are taken by separate statements.**
  Nothing in the PostgreSQL documentation states the order in which a single multi-row `UPDATE` locks
  its rows; it is a property of the chosen plan. **This is not settled by the docs and needs a test** —
  or N ordered single-row statements, which are settled (§4).
- **A hot Account plus a connection pool is how one row becomes an outage.** Every request blocked on
  that row holds a pooled connection. `lock_timeout` is what stops the queue reaching Hikari's
  `connectionTimeout` and failing traffic that never touched the Account (§5).
- **`@Version` is available on `account` — unlike on `entry` and `posting` — and is still wrong.** Two
  writers doing `balance = balance + ?` are not in conflict; addition commutes. Optimistic locking
  manufactures a conflict, and an HQL bulk `update` does not increment the version anyway unless it is
  written `update versioned` (§7).

---

## 1. The five mechanisms

The invariant: for an Account with `constrained = true`, Balance may never be negative. Recording an
Entry writes one `entry` row and N `posting` rows; where a constrained Account is among the N, the
write must be refused rather than allowed to go negative.

Throughout, the Account row is keyed `(tenant_id, id)` per
[`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md), and every statement runs inside one
transaction on one connection with `app.current_tenant` set transaction-locally, per
[`rls-pooling.md`](rls-pooling.md).

### (a) Stored balance column + `CHECK`

```sql
ALTER TABLE account
  ADD COLUMN balance_minor bigint NOT NULL DEFAULT 0,
  ADD CONSTRAINT account_constrained_non_negative
      CHECK (NOT constrained OR balance_minor >= 0);
```

```sql
UPDATE account
   SET balance_minor = balance_minor + :delta
 WHERE tenant_id = :tenant AND id = :account;
```

**What serialises.** The `UPDATE` takes a row-level lock on the Account row. Any other transaction
attempting `UPDATE`, `DELETE` or a locking `SELECT` on that row blocks until this transaction ends, and
row locks are *"released at transaction end or during savepoint rollback"*
([PostgreSQL: Explicit Locking](https://www.postgresql.org/docs/16/explicit-locking.html)). The Account
row is the serialisation point: every Entry touching that Account is serialised against every other.

**Why it is correct with no explicit lock.** `balance_minor + :delta` is evaluated by the server against
the row version it actually updates. When a second updater finds the row already updated by a
transaction that then commits, *"it will attempt to apply its operation to the updated version of the
row"*. There is no read-then-write window, because the application never reads.

**What the caller sees on refusal.** `23514` `check_violation`
([PostgreSQL: Error Codes](https://www.postgresql.org/docs/16/errcodes-appendix.html)). It is an ERROR,
so the transaction is aborted and any further statement in it returns `25P02`
`in_failed_sql_transaction`.

**Survives `READ COMMITTED`?** Yes. The CHECK is evaluated against the row as actually written, and the
row is written while holding an exclusive row lock on it.

**The constraint on the constraint.** A CHECK can only see the row being written: *"Currently, `CHECK`
expressions cannot contain subqueries nor refer to variables other than columns of the current row"*
([PostgreSQL: CREATE TABLE](https://www.postgresql.org/docs/16/sql-createtable.html)). That single
sentence is why a stored balance column has to exist at all. There is no way to write "the sum of this
Account's Postings is non-negative" as a constraint. Note also that *"Expressions evaluating to TRUE or
UNKNOWN succeed"* — so every column the expression touches must be `NOT NULL`, or a null silently
satisfies it.

### (b) The same `UPDATE`, guarded, with no CHECK

```sql
UPDATE account
   SET balance_minor = balance_minor + :delta
 WHERE tenant_id = :tenant AND id = :account
   AND (NOT constrained OR balance_minor + :delta >= 0);
```

This is the crux, so here is the documented rule in full:

> `UPDATE`, `DELETE`, `SELECT FOR UPDATE`, and `SELECT FOR SHARE` commands behave the same as `SELECT`
> in terms of searching for target rows: they will only find target rows that were committed as of the
> command start time. However, such a target row might have already been updated (or deleted or locked)
> by another concurrent transaction by the time it is found. In this case, the would-be updater will
> wait for the first updating transaction to commit or roll back (if it is still in progress). If the
> first updater rolls back, then its effects are negated and the second updater can proceed with
> updating the originally found row. If the first updater commits, the second updater will ignore the
> row if the first updater deleted it, otherwise it will attempt to apply its operation to the updated
> version of the row. **The search condition of the command (the `WHERE` clause) is re-evaluated to see
> if the updated version of the row still matches the search condition.** If so, the second updater
> proceeds with its operation using the updated version of the row.

([PostgreSQL: Transaction Isolation](https://www.postgresql.org/docs/16/transaction-iso.html))

So **(b) is safe at `READ COMMITTED`**, and safe *because of* the re-evaluation rule rather than in
spite of it. Writer B, unblocked after A commits, does not proceed on the balance it saw at statement
start; it re-tests `balance_minor + :delta >= 0` against A's committed value, and declines to update if
that value no longer permits it.

**What serialises.** Identical to (a) — the row lock taken by the `UPDATE`.

**Failure mode.** **Zero rows updated.** `UPDATE` returns a command tag `UPDATE count`, and *"If count
is 0, no rows were updated by the query (this is not considered an error)"*
([PostgreSQL: UPDATE](https://www.postgresql.org/docs/16/sql-update.html)). No exception, no SQLSTATE.
The application *must* branch on the row count. This is the most dangerous property of mechanism (b):
the refusal is a return value, and Java makes return values easy to drop.

The zero count is also ambiguous on its own — "insufficient funds", or "no such Account", or, under
RLS, "that Account belongs to another tenant" ([`rls-pooling.md`](rls-pooling.md) §3). Either
distinguish them with a follow-up read, or accept that the API cannot tell the caller which happened.

**The limit of the rule, stated honestly.** The documentation says the *search condition* is
re-evaluated against the updated version of *the row*. It says nothing about re-reading other tables,
and PostgreSQL is explicit that the open question in this area is *"whether or not a single command
sees an absolutely consistent view of the database"*. The documented worked example makes the hazard
concrete: a `DELETE FROM website WHERE hits = 10` running concurrently with
`UPDATE website SET hits = hits + 1` *"will have no effect even though there is a `website.hits = 10`
row before and after the `UPDATE`"*.

**Therefore: keep the guard predicate entirely on columns of the Account row.** The moment it contains
a subquery over `posting`, the re-evaluation guarantee no longer covers the part that matters, and the
mechanism silently degrades into (c) *without* the lock. Do not write it.

### (c) `SUM()` over Postings under `SELECT … FOR UPDATE` on the Account row

```sql
SELECT id FROM account
 WHERE tenant_id = :tenant AND id = :account
   FOR UPDATE;                                            -- statement 1: take the lock

SELECT coalesce(sum(amount_minor), 0) FROM posting
 WHERE tenant_id = :tenant AND account_id = :account;     -- statement 2: read the truth
```

**Two statements, and that is forced.** *"The locking clauses cannot be used in contexts where returned
rows cannot be clearly identified with individual table rows; for example they cannot be used with
aggregation"* ([PostgreSQL: SELECT](https://www.postgresql.org/docs/16/sql-select.html)). You cannot
write `SELECT sum(...) … FOR UPDATE`. The Account row is being used purely as a mutex for a value that
lives somewhere else.

**What serialises.** `FOR UPDATE` *"prevents them from being locked, modified or deleted by other
transactions until the current transaction ends"*. Every writer takes the same Account row lock before
summing, so the sums are serialised.

**Survives `READ COMMITTED`?** Yes — and *only* at `READ COMMITTED`. Statement 2 takes a fresh snapshot
which includes everything committed by the transaction statement 1 waited for.

**It breaks at `REPEATABLE READ`**, and this is documented:

> Note also that if one is relying on explicit locking to prevent concurrent changes, one should either
> use Read Committed mode, or in Repeatable Read mode be careful to obtain locks before performing
> queries. A lock obtained by a repeatable read transaction guarantees that no other transactions
> modifying the table are still running, but if the snapshot seen by the transaction predates obtaining
> the lock, it might predate some now-committed changes in the table. A repeatable read transaction's
> snapshot is actually frozen at the start of its first query or data-modification command.

([PostgreSQL: Data Consistency Checks at the Application
Level](https://www.postgresql.org/docs/16/applevel-consistency.html))

An `@Transactional(isolation = REPEATABLE_READ)` added later "for safety" therefore *breaks* this
mechanism. Under Spring that is a one-word change, made by someone who has not read this note.

**Failure mode.** The application compares sum + delta against zero and refuses in Java — application
code enforcing the invariant, which is precisely what this project exists not to do, unless the result
is written back through a `CHECK`ed column, at which point you are running (a) with extra steps. The
other failure modes are lock waits: `55P03` if `lock_timeout` fires, `40P01` on deadlock.

**Cost.** Two round trips before the write, plus a `SUM` whose cost grows with the Account's Posting
count — and on a ledger that count only ever grows. An index on `(tenant_id, account_id)` keeps it a
range scan, but it is still O(history) where (a) is O(1).

### (d) `SERIALIZABLE` with no explicit locking

```sql
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT coalesce(sum(amount_minor), 0) FROM posting WHERE tenant_id = ? AND account_id = ?;
-- decide in the application; if it passes:
INSERT INTO posting …;
COMMIT;   -- may fail here
```

**What serialises.** Nothing blocks. PostgreSQL uses *predicate locking*, and *"these locks do not
cause any blocking and therefore can not play any part in causing a deadlock"*; they appear in
`pg_locks` with `mode = SIReadLock`. The engine watches for dangerous read/write dependency cycles and,
when it finds one, rolls a transaction back.

**PostgreSQL's own worked example is this exact shape.** Transaction A computes
`SELECT SUM(value) FROM mytab WHERE class = 1` and inserts the result as a new row; B does the mirror
image; and *"since there is no serial order of execution consistent with the result, using Serializable
transactions will allow one transaction to commit and will roll the other back"* with
`could not serialize access due to read/write dependencies among transactions`. Read "class" as
"Account" and it is the constrained-balance check verbatim.

**Failure mode / SQLSTATE.** `40001` `serialization_failure` — *"which always return with an SQLSTATE
value of '40001'"*. Critically, **it can be raised at COMMIT**, not at the offending statement. §3 is
about the consequences of that.

**Survives `READ COMMITTED`?** The question does not apply: the mechanism *is* the isolation level. If
any writer to a constrained Account runs at `READ COMMITTED`, the protection is gone for that Account.
PostgreSQL's guidance is to set `default_transaction_isolation` to `serializable` and *"take some action
to ensure that no other transaction isolation level is used, either inadvertently or to subvert
integrity checks, through checks of the transaction isolation level in triggers"*. That is a real cost
for this project: the guarantee becomes a property of every writer's session settings rather than of the
schema — the opposite of the thesis.

**The mandatory retry.** *"applications using this level must be prepared to retry transactions due to
serialization failures"*, and *"applications must not depend on results read during a transaction that
later aborted; instead, they should retry the transaction until it succeeds"*.

### (e) Advisory locks

```sql
SELECT pg_advisory_xact_lock(hashtextextended(:account::text, 0));
-- then read, decide, write
```

**What serialises.** A named lock in shared memory, released *"at the end of the transaction"* for the
`_xact_` variants ([PostgreSQL: Explicit Locking](https://www.postgresql.org/docs/16/explicit-locking.html);
[Advisory Lock Functions](https://www.postgresql.org/docs/16/functions-admin.html)). Nothing else.

**Why it is the wrong tool here, in the documentation's own words.** *"Advisory locks are purely
advisory — the system does not enforce their use. It is entirely up to the application to use them
correctly."* An advisory lock is application code enforcing an invariant, wearing a database costume. A
write path that forgets to take it is not refused; it simply proceeds.

**Three further sharp edges.**

- **The key space is 64 bits and this schema uses UUIDs.** Hashing an Account id into a `bigint` admits
  collisions: two unrelated Accounts serialise against each other, which is a performance bug that looks
  like nothing at all. Note also that the single-`bigint` and two-`integer` forms are separate —
  *"note that these two key spaces do not overlap"*.
- **Session-level advisory locks do not survive a transaction-mode pooler.** They are held *"until
  explicitly released or the session ends"* and *"do not honor transaction semantics: a lock acquired
  during a transaction that is later rolled back will still be held following the rollback"* — a leak of
  exactly the shape [`rls-pooling.md`](rls-pooling.md) §1 documents for session-level `SET`. Only
  `pg_advisory_xact_lock` is usable here.
- **They do not order against row locks.** A transaction holding an advisory lock and waiting for a row
  lock, crossing one doing the reverse, deadlocks. Advisory locks are ordinary participants in
  `pg_locks` (`locktype = 'advisory'`) and in deadlock detection.

**Legitimate use.** A singleton background job — end-of-day reconciliation, a migration guard — where
the thing being protected is not a row. Not the request path.

### Summary

| | Serialises on | Refusal appears as | Safe at `READ COMMITTED`? | Cost |
|---|---|---|---|---|
| **(a) stored balance + `CHECK`** | the Account row lock taken by `UPDATE` | **`23514`**, unignorable | **Yes** | one `UPDATE` per constrained Account |
| **(b) guarded `UPDATE`, no CHECK** | same | **0 rows updated** — no error at all | **Yes**, via the documented `WHERE` re-evaluation | same |
| **(c) `SUM` under `FOR UPDATE`** | the Account row lock taken by `SELECT … FOR UPDATE` | a Java `if` | Yes — and **broken at `REPEATABLE READ`** | 2 extra round trips + O(history) sum |
| **(d) `SERIALIZABLE`** | nothing; SSI detects cycles | **`40001`**, possibly at COMMIT | N/A — it replaces the level | retry loop; predicate-lock memory; every writer must opt in |
| **(e) advisory locks** | an application-chosen key | whatever the application decides | Yes, if every writer participates | not enforced; hash collisions; pooler hazards |

**(a) and (b) are the same mechanism with different failure surfaces. Do both.**

---

## 2. Isolation levels, and which anomalies can actually corrupt a balance check

### The documented table

| Isolation Level | Dirty Read | Nonrepeatable Read | Phantom Read | Serialization Anomaly |
|---|---|---|---|---|
| Read uncommitted | Allowed, but not in PG | Possible | Possible | Possible |
| **Read committed** (PG default) | Not possible | **Possible** | **Possible** | **Possible** |
| Repeatable read | Not possible | Not possible | Allowed, but not in PG | **Possible** |
| Serializable | Not possible | Not possible | Not possible | Not possible |

([PostgreSQL: Transaction Isolation](https://www.postgresql.org/docs/16/transaction-iso.html))

`default_transaction_isolation` is *"read committed"*
([PostgreSQL: Client Connection Defaults](https://www.postgresql.org/docs/16/runtime-config-client.html)),
so this is the level everything runs at unless something says otherwise.

### Which of these can actually produce a negative Balance

- **Dirty read** — not possible in PostgreSQL at any level. Irrelevant.
- **Nonrepeatable read** — the same `SELECT` returning a different Balance twice in one transaction.
  Harmful only if you *decide* on the first read and *write* after the second. Which is the next item.
- **Phantom read** — a new Posting appearing in a re-run `SUM`. Same shape, same answer.
- **Serialization anomaly** — *"The result of successfully committing a group of transactions is
  inconsistent with all possible orderings of running those transactions one at a time."* **This is the
  one that corrupts the balance check**, and it is possible at both `READ COMMITTED` and
  `REPEATABLE READ`.

### The read-modify-write / lost update shape, concretely

```java
// WRONG. Two writers, one constrained Account with balance 100.
long balance = accountRepo.balanceOf(id);        // both read 100
if (balance + delta < 0) throw new InsufficientFunds();   // both see -60 + 100 = 40, both pass
accountRepo.setBalance(id, balance + delta);     // both write 40. Two Entries of -60 applied.
                                                 // True balance should be -20. It is 40, and the
                                                 // ledger is now wrong in both directions.
```

Both reads take their own snapshot at statement start; neither sees the other; the second `UPDATE`
overwrites the first with a value computed from a stale read. `READ COMMITTED` permits this and is not
being violated — the anomaly is in the application, which used a value across a statement boundary.

PostgreSQL says this outright: *"It is very difficult to enforce business rules regarding data integrity
using Read Committed transactions because the view of the data is shifting with each statement, and even
a single statement may not restrict itself to the statement's snapshot if a write conflict occurs"*
([PostgreSQL: Data Consistency Checks at the Application
Level](https://www.postgresql.org/docs/16/applevel-consistency.html)).

**Two documented fixes, and only two:**

1. *"If the Serializable transaction isolation level is used for all writes and for all reads which need
   a consistent view of the data, no other effort is required to ensure consistency."*
2. *"When non-serializable writes are possible, to ensure the current validity of a row and protect it
   against concurrent updates one must use `SELECT FOR UPDATE`, `SELECT FOR SHARE`, or an appropriate
   `LOCK TABLE` statement."*

Mechanism (a)/(b) is a third option that the documentation does not name as such, because it sidesteps
the shape entirely: **there is no read**. The value never leaves the server, so it cannot go stale.

Note one further documented subtlety about `SELECT FOR UPDATE`, relevant if you reach for fix 2:
*"`SELECT FOR UPDATE` does not ensure that a concurrent transaction will not update or delete a selected
row. To do that in PostgreSQL you must actually update the row, even if no values need to be changed."*
The lock is released at commit; if nothing was written, the next waiter proceeds against a Balance that
your read implied was reserved. A `FOR UPDATE` read followed by "no change needed" is not a no-op — it
is a lock you gave up for nothing.

### `REPEATABLE READ` on a conflicting update

> if the first updater commits (and actually updated or deleted the row, not just locked it) then the
> repeatable read transaction will be rolled back with the message
>
> ```
> ERROR:  could not serialize access due to concurrent update
> ```
>
> because a repeatable read transaction cannot modify or lock rows changed by other transactions after
> the repeatable read transaction began.

SQLSTATE `40001` `serialization_failure`, same as SSI's. And the documented remedy:

> When an application receives this error message, it should abort the current transaction and retry the
> whole transaction from the beginning. […] Note that only updating transactions might need to be
> retried; read-only transactions will never have serialization conflicts.

So `REPEATABLE READ` converts a wait into an error. For mechanism (a)/(b) it strictly increases the
error rate on a hot Account — every writer that would have blocked and then succeeded now aborts — while
adding nothing, because (a)/(b) is already correct at `READ COMMITTED`. For mechanism (c) it is worse
than useless: it is actively wrong (§1c).

### What `SERIALIZABLE` costs

The documentation is direct about the trade:

> The monitoring of read/write dependencies has a cost, as does the restart of transactions which are
> terminated with a serialization failure, but balanced against the cost and blocking involved in use of
> explicit locks and `SELECT FOR UPDATE` or `SELECT FOR SHARE`, Serializable transactions are the best
> performance choice for some environments.

And its practical recommendations, all of which apply to a ledger: declare read-only transactions
`READ ONLY`; *"Control the number of active connections, using a connection pool if needed"*;
*"Don't put more into a single transaction than needed for integrity purposes"*; set
`idle_in_transaction_session_timeout`; and, importantly for a `SUM` over Postings,
*"A sequential scan will always necessitate a relation-level predicate lock. This can result in an
increased rate of serialization failures."* — i.e. the index on `(tenant_id, account_id)` is not just a
speed optimisation under SSI, it is a correctness-adjacent one, because a seq scan escalates the
predicate lock to the whole `posting` relation and starts failing unrelated Accounts.

Likewise: *"When the system is forced to combine multiple page-level predicate locks into a single
relation-level predicate lock because the predicate lock table is short of memory, an increase in the
rate of serialization failures may occur"* — tunable via `max_pred_locks_per_transaction` (default 64,
*"can only be set at server start"*,
[PostgreSQL: Lock Management](https://www.postgresql.org/docs/16/runtime-config-locks.html)).

### False positives

The documentation acknowledges that SSI raises failures that a true serial execution would not:

> While PostgreSQL's Serializable transaction isolation level only allows concurrent transactions to
> commit if it can prove there is a serial order of execution that would produce the same effect, it
> doesn't always prevent errors from being raised that would not occur in true serial execution.

**No PostgreSQL document states a false-positive rate**, and none could — it depends on the workload,
the plans chosen, and the predicate-lock granularity. Any figure for this project is a measurement
(§6), not a citation. What *is* documented is the consequence: you must have *"a generalized way of
handling serialization failures […] because it will be very hard to predict exactly which transactions
might contribute to the read/write dependencies and need to be rolled back"*.

---

## 3. SQLSTATEs and retry

### The set a caller must handle

| SQLSTATE | Condition name | Raised by | Retryable? |
|---|---|---|---|
| `40001` | `serialization_failure` | SSI conflict; `REPEATABLE READ` conflicting update; partition row movement | **Yes** — with backoff. The documented remedy is *"retry the whole transaction from the beginning"*. |
| `40P01` | `deadlock_detected` | deadlock detector aborting this transaction as victim | **Yes** — *"deadlocks can be handled on-the-fly by retrying transactions that abort due to deadlocks"*. |
| `55P03` | `lock_not_available` | `lock_timeout` expiring; `SELECT … NOWAIT` | **Yes, cautiously** — nothing was wrong with the work, only the wait. Retry with backoff and a cap. |
| `57014` | `query_canceled` | `statement_timeout` expiring; an explicit cancel | **No, by default.** The work was too slow; retrying repeats it. Surface it. |
| `23514` | `check_violation` | the non-negative-balance CHECK | **Never.** This is the answer, not a failure. It means "insufficient funds". |
| `25P03` | `idle_in_transaction_session_timeout` | the session held a transaction open while idle | **No** — the connection is terminated (`FATAL`). Indicates a bug: work happening between statements inside a transaction. |
| `25P02` | `in_failed_sql_transaction` | any statement issued after an error in the same transaction | **No** — it means the retry is in the wrong place. See below. |
| `23505` / `23503` | `unique_violation` / `foreign_key_violation` | idempotency key collision; a cross-tenant reference ([`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md) §1) | **No.** Both are real answers about the request. |

Codes from [PostgreSQL: Error Codes](https://www.postgresql.org/docs/16/errcodes-appendix.html). The
timeout mappings are not in the configuration documentation, so they come from the server source, which
is unambiguous:

```c
/* statement_timeout */                          /* lock_timeout */
ereport(ERROR,                                   ereport(ERROR,
  (errcode(ERRCODE_QUERY_CANCELED),                (errcode(ERRCODE_LOCK_NOT_AVAILABLE),
   errmsg("canceling statement due to             errmsg("canceling statement due to
          statement timeout")));                         lock timeout")));
```

with `idle_in_transaction_session_timeout` raising `ERRCODE_IDLE_IN_TRANSACTION_SESSION_TIMEOUT` at
`FATAL` — *"terminating connection due to idle-in-transaction timeout"*
([postgres `REL_16_STABLE`, `tcop/postgres.c`](https://github.com/postgres/postgres/blob/REL_16_STABLE/src/backend/tcop/postgres.c)).

### What these become in Java

`pgjdbc` throws `PSQLException`, and `public class PSQLException extends SQLException`
([pgjdbc, `PSQLException.java`](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/util/PSQLException.java))
— *not* one of the JDBC 4 subclasses such as `SQLTransactionRollbackException`. That matters, because
Spring's default translator since 6.0 is `SQLExceptionSubclassTranslator`, which dispatches on those
subclasses and *"Falls back to a standard `SQLStateSQLExceptionTranslator` if the JDBC driver does not
actually expose JDBC 4 compliant `SQLException` subclasses"*
([Spring: `SQLExceptionSubclassTranslator`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/support/SQLExceptionSubclassTranslator.html)).
So on Postgres the SQLSTATE path is the one that actually runs.

Reading `SQLStateSQLExceptionTranslator` (Spring Framework 6.2.x): it takes the two-character class
code, maps class `23` to `DataIntegrityViolationException` (refined to `DuplicateKeyException` for
`23505`), class `40` to a pessimistic-locking failure (refined to `CannotAcquireLockException` for
`40001`), class `57` to `DataAccessResourceFailureException` with `57014` singled out as a query
timeout — and **has no entry for class `55`**, so `55P03` arrives as an uncategorized
`DataAccessException`.

**Do not build the retry predicate out of Spring's exception types.** Unwrap to the `SQLException` and
switch on `getSQLState()`. It is the string the server sent, it is stable across Spring versions, and it
is the same string this note's table is written in. **Needs a test**: one integration test per row of
that table, asserting the SQLSTATE that actually arrives. The `55P03` and `40P01` rows in particular are
read off Spring source rather than from documentation, and the classification is not something to
discover in production.

### The idiomatic Spring retry

Spring Retry, proxied, wrapping a bean whose method is `@Transactional`:

```java
@Configuration
@EnableRetry
class RetryConfig {}

@Service
class EntryRecorder {                 // the retry boundary
    private final EntryWriter writer; // a DIFFERENT bean

    @Retryable(retryFor = TransientLedgerException.class,
               maxAttempts = 4,
               backoff = @Backoff(delay = 25, multiplier = 2.0, maxDelay = 400))
    public EntryId record(RecordEntry cmd) {
        return writer.write(cmd);     // @Transactional lives in there
    }
}

@Service
class EntryWriter {
    @Transactional
    public EntryId write(RecordEntry cmd) { … }
}
```

### The trap with retrying inside an open transaction

Three separate mechanisms make an inner retry not work, and they compound.

1. **Postgres has already aborted the transaction.** After any ERROR, every subsequent statement in that
   transaction returns `25P02` `in_failed_sql_transaction`. A `catch` around the failing statement, then
   a second attempt, does not re-run the work; it fails differently. The only recovery is a rollback and
   a fresh transaction.
2. **The failure may arrive after your method returns.** A `SERIALIZABLE` conflict can be raised at
   COMMIT, and COMMIT is issued by Spring's transaction interceptor *after* the `@Transactional` method
   body completes. Code inside the method cannot catch it. The handler must sit outside the transaction
   proxy.
3. **Spring's default propagation silently defeats an inner retry.** `PROPAGATION_REQUIRED` means an
   inner `@Transactional` *"participating in an existing 'outer' transaction"*, with *"all these scopes
   […] mapped to the same physical transaction"*. If the inner scope marks rollback-only, the outer
   commit fails: *"the outer caller still calls commit. The outer caller needs to receive an
   `UnexpectedRollbackException`"*
   ([Spring: Transaction Propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)).
   Also *"a participating transaction joins the characteristics of the outer scope, silently ignoring
   the local isolation level, timeout value, or read-only flag"* — so an inner
   `@Transactional(isolation = SERIALIZABLE)` inside an outer `READ COMMITTED` transaction is **ignored,
   with no warning**. If this project ever chooses mechanism (d), that sentence is the failure mode.

**And `REQUIRES_NEW` is not the escape.** It *"always uses an independent physical transaction"* — with
its own connection, while the outer transaction keeps holding its own:

> This may lead to exhaustion of the connection pool and potentially to a deadlock if several threads
> have an active outer transaction and wait to acquire a new connection for their inner transaction,
> with the pool not being able to hand out any such inner connection anymore.

A second connection is also a second `app.current_tenant`-less session, which under fail-closed RLS is
silent wrong data — the identical hazard [`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md) §6
documents for `openStatelessSession()`.

**Two further proxy details.** `@Retryable` and `@Transactional` are both proxy-based, so
*"self-invocation […] does not lead to an actual transaction at runtime"*
([Spring: `@Transactional`](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)),
and the same is true of Spring Retry — which is why the example above uses two beans. And the default
rollback rule is *"Any `RuntimeException` or `Error` triggers rollback, and any checked `Exception` does
not"*: a checked "insufficient funds" exception thrown after the guarded `UPDATE` returned zero rows
**will commit the transaction**. Make the domain refusal a `RuntimeException`, or set `rollbackFor`.

---

## 4. Deadlock ordering across the N Accounts of an Entry

An Entry touches N Accounts. If the write locks Account rows, two concurrent Entries touching
`{A, B}` and `{B, A}` will each hold one and wait for the other.

### What PostgreSQL documents

**Detection.** *"PostgreSQL automatically detects deadlock situations and resolves them by aborting one
of the transactions involved, allowing the other(s) to complete."*

**Victim selection.** *"(Exactly which transaction will be aborted is difficult to predict and should
not be relied upon.)"* So both sides of a conflicting pair must be able to retry; there is no "the
younger one loses" rule to design around.

**Timing.** Detection is not immediate. `deadlock_timeout` is *"the amount of time to wait on a lock
before checking to see if there is a deadlock condition. The check for deadlock is relatively expensive,
so the server doesn't run it every time it waits for a lock."* **The default is one second**, and
*"Ideally the setting should exceed your typical transaction time"*
([PostgreSQL: Lock Management](https://www.postgresql.org/docs/16/runtime-config-locks.html)).

That last point is the operational one: on defaults, a deadlocked request-path transaction **sits for a
full second before anyone notices**, then gets `40P01`, then retries. A deadlock is not a cheap error;
it is a second of latency plus the retry. Absent deadlocks, *"a transaction seeking either a table-level
or row-level lock will wait indefinitely for conflicting locks to be released"* — which is what §5's
`lock_timeout` exists to bound.

**Prevention.** *"The best defense against deadlocks is generally to avoid them by being certain that
all applications using a database acquire locks on multiple objects in a consistent order. In the
example above, if both transactions had updated the rows in the same order, no deadlock would have
occurred."*

### Does sorting inside one statement guarantee the order?

The honest answer: **the PostgreSQL documentation does not settle this, and this project should test it
rather than assume it.**

What the documentation *does* establish, for separate statements: locks are acquired as each statement
executes, so issuing N single-row `UPDATE`s in a deterministic order — sort the Entry's Postings by
`(tenant_id, account_id)` before writing — gives exactly the "consistent order" the prevention advice
asks for. This is settled and it is what to do.

What it does *not* establish is the row order of a single multi-row statement such as

```sql
UPDATE account a
   SET balance_minor = a.balance_minor + v.delta
  FROM (VALUES …) AS v(account_id, delta)
 WHERE a.tenant_id = :tenant AND a.id = v.account_id;
```

Nothing in the `UPDATE` reference, in Explicit Locking, or in Transaction Isolation states the order in
which such a statement locks the rows it modifies. It is a property of the plan the planner chose —
a hash join, a nested loop and a bitmap scan can each produce a different order, and the plan can change
with statistics. `UPDATE` has no `ORDER BY` clause to constrain it with.

The nearest documented hint points the wrong way. For `SELECT … ORDER BY … FOR UPDATE`, the docs warn
that *"`ORDER BY` is applied first. The command sorts the result, but might then block trying to obtain
a lock on one or more of the rows"* — i.e. even where an explicit sort exists, sorting and locking are
separate phases whose interaction the docs describe as surprising rather than as a guarantee.

**Conclusion for the design.** Take the Account row locks with **N separate statements in a
deterministic order** (sort by the Account key; a transaction is single-tenant so the Account id alone
suffices, though sorting on the full `(tenant_id, id)` costs nothing). Do not rely on a single
multi-row statement for lock ordering. If someone wants the single-statement form for the round-trip
saving, that is a legitimate proposal — but it needs an integration test that runs the two orderings
against each other under real concurrency and asserts `pg_stat_database.deadlocks` does not move (§6),
and that test has to be re-run whenever the plan could change.

**Cross-reference, not a substitute.** Hibernate's `hibernate.order_updates` *"Forces Hibernate to order
SQL updates by the entity type and the primary key value of the items being updated. […] It will also
result in fewer transaction deadlocks in highly concurrent systems"*
([`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md) §7). Note *fewer*, not none — and note that
it orders the updates Hibernate's dirty checking generates at flush. The balance update in §7 below is a
bulk mutation query, which `order_updates` does not touch. Ordering the statements is the application's
job here.

---

## 5. Lock waits, timeouts, and the pool

### The three settings

All three are per-session, and all three take milliseconds when no unit is given
([PostgreSQL: Client Connection Defaults](https://www.postgresql.org/docs/16/runtime-config-client.html)).

**`lock_timeout`** — *"Abort any statement that waits longer than the specified amount of time while
attempting to acquire a lock on a table, index, row, or other database object. The time limit applies
separately to each lock acquisition attempt. […] A value of zero (the default) disables the timeout."*
Protects against: unbounded queueing on a hot Account row. Raises `55P03`.

**`statement_timeout`** — *"Abort any statement that takes more than the specified amount of time. […]
The timeout is measured from the time a command arrives at the server until it is completed by the
server."* Protects against: a pathological plan, a runaway `SUM`, anything slow that is not a lock wait.
Raises `57014`.

**`idle_in_transaction_session_timeout`** — *"Terminate any session that has been idle (that is, waiting
for a client query) within an open transaction for longer than the specified amount of time. […] This
option can be used to ensure that idle sessions do not hold locks for an unreasonable amount of time."*
Protects against: an open transaction holding an Account row lock while the application does something
else — an HTTP call, a `Thread.sleep`, a debugger breakpoint. Raises `25P03` and terminates the
connection.

**A documented interaction.** *"Note that if `statement_timeout` is nonzero, it is rather pointless to
set `lock_timeout` to the same or larger value, since the statement timeout would always trigger
first."* So `lock_timeout` < `statement_timeout`, always. And both docs add: *"Setting […] in
`postgresql.conf` is not recommended because it would affect all sessions"* — these belong on the
application role or on the request-path transaction, not on the cluster.

### An undocumented interaction worth knowing

`deadlock_timeout` defaults to `1s`, and deadlock detection only runs *after* a waiter has waited that
long. So **a `lock_timeout` below one second fires before the deadlock detector ever looks**: a genuine
deadlock surfaces as `55P03` rather than `40P01`, and `pg_stat_database.deadlocks` never increments.

This is reasoned from two documented defaults, not stated anywhere as such — **it needs a test** (create
a deadlock with `lock_timeout = 200ms` and observe which SQLSTATE arrives). Behaviourally it is
harmless, since both codes are retryable, but it means the deadlock counter is not a trustworthy
deadlock signal unless `lock_timeout > deadlock_timeout`. If you want deadlocks visible in the stats,
either keep `lock_timeout` above one second or lower `deadlock_timeout` alongside it — the docs
explicitly bless the latter: *"If you are trying to investigate locking delays you might want to set a
shorter than normal `deadlock_timeout`."*

### Values

**Estimates, not documentation.** Nothing states a correct value; these are starting points to be
replaced by numbers derived from the p99 measured in §6.

| Setting | Request-path suggestion | Reasoning |
|---|---|---|
| `statement_timeout` | `3s` | An Entry write is a handful of statements. Anything past a few seconds is a bug, and the caller's HTTP client has probably given up. |
| `lock_timeout` | `1s` | Must be < `statement_timeout`. At or above `deadlock_timeout` so deadlocks still surface as `40P01`. Long enough to absorb a normal queue of a few writers on a hot Account; short enough that the pool does not fill. |
| `idle_in_transaction_session_timeout` | `10s` | Far above any legitimate request-path transaction, so it only ever fires on a bug. |
| `deadlock_timeout` | leave at `1s` | *"probably about the smallest value you would want in practice"*. |

Batch/reconciliation work should get its own, much longer, values on its own role — not the request
path's.

### The pool interaction, which is the real risk

A request blocked on a hot Account row is holding a JDBC connection for the whole wait. HikariCP's
`maximumPoolSize` **defaults to 10**, and `connectionTimeout` **defaults to 30 seconds**, after which
*"If this time is exceeded without a connection becoming available, a SQLException will be thrown"*
([HikariCP](https://github.com/brettwooldridge/HikariCP)).

So with unbounded lock waits and default pool settings, **eleven concurrent requests against one hot
Account take down every endpoint in the service**, including ones that never touch that Account or that
tenant. The blast radius of a single contended row is the entire pool.

Three consequences for the design:

1. **`lock_timeout` must be far below `connectionTimeout`.** It is the mechanism that converts "the
   service is down" into "requests against this Account are slow and some get a retryable error".
2. **The pool size is a concurrency budget for the hot row, not just a throughput knob.** At most
   `maximumPoolSize` requests can be queued on that row at once; the rest fail at the pool. Whether that
   is the right failure boundary is a design decision to make deliberately.
3. **Never `REQUIRES_NEW` on this path**, per §3 and Spring's own warning: *"Do not use
   `PROPAGATION_REQUIRES_NEW` unless your connection pool is appropriately sized, exceeding the number
   of concurrent threads by at least 1."*

Under a transaction-mode pooler ([`rls-pooling.md`](rls-pooling.md)) the same arithmetic applies one
layer out: a blocked transaction pins a *server* connection for its whole duration, since the pooler
only reclaims it *"When PgBouncer notices that the transaction is over"*.

---

## 6. Measuring it

### What Postgres exposes

**`pg_stat_activity`** — who is waiting and on what. `wait_event_type = 'Lock'` is *"The server process
is waiting for a heavyweight lock"*, and within it `wait_event` distinguishes `transactionid`
(*"Waiting for a transaction to finish"* — the signature of contention on an Account row), `tuple`,
`relation` and `advisory`. The `state` column (`active`, `idle in transaction`, …) with `xact_start`,
`query_start` and `state_change` shows how long a transaction has been open
([PostgreSQL: Cumulative Statistics](https://www.postgresql.org/docs/16/monitoring-stats.html)).

**`pg_locks`** — one row *"per active lockable object, requested lock mode, and relevant process"*.
`granted` is the column that matters: *"False indicates that this process is currently waiting to
acquire this lock"*. `waitstart` gives *"Time when the server process started waiting for this lock"*.
`locktype` distinguishes `transactionid` / `tuple` / `advisory`, and SSI predicate locks show as
`mode = 'SIReadLock'`. Do not compute blocking chains by self-joining: *"It is better to use the
`pg_blocking_pids()` function […] to identify which process(es) a waiting process is blocked behind"*
([PostgreSQL: `pg_locks`](https://www.postgresql.org/docs/16/view-pg-locks.html)).

**`pg_stat_database`** — `deadlocks` (*"Number of deadlocks detected in this database"*), `xact_commit`
(*"Number of transactions in this database that have been committed"*), `xact_rollback`
(*"…rolled back"*), plus `stats_reset` (*"Time at which these statistics were last reset"*).

**Two documented gotchas about reading these.** Counters are flushed *"not more frequently than once per
`PGSTAT_MIN_INTERVAL` milliseconds (1 second …); so a query or transaction still in progress does not
affect the displayed totals"*. And *"when a server process is asked to display any of the accumulated
statistics, accessed values are cached until the end of its current transaction"* — so a sampler that
polls inside one long transaction sees frozen numbers. Sample from short transactions, or set
`stats_fetch_consistency = none`. Reset with `pg_stat_reset()` (*"Resets all statistics counters for the
current database to zero"*, superuser by default) and work in deltas.

**`pg_stat_statements`** for per-statement latency (`calls`, `total_exec_time`, `mean_exec_time`,
`stddev_exec_time`, `min_exec_time`, `max_exec_time`, `rows`) — but note it *"must be loaded by adding
`pg_stat_statements` to `shared_preload_libraries`"*, so the `docker compose` Postgres needs a command
override, and the Testcontainers image used in CI needs the same or the numbers will not exist there.

### The minimum for a defensible "X contended, Y uncontended" claim

A throughput number is a claim about a *system*, so the claim is only as good as what was held fixed.
The minimum:

**Design**
- Both arms in the **same run**, on the same data and the same hardware, interleaved or randomised —
  not "the contended number from Tuesday and the uncontended one from Friday".
- Arm A: K concurrent writers, all recording Entries against **one** constrained Account.
  Arm B: K concurrent writers spread across **K distinct** constrained Accounts.
  Same K, same Entry shape, same number of Postings per Entry, same Amounts.
- A third arm against **unconstrained** Accounts is what isolates the cost of the constraint itself from
  the cost of contention.
- A warm-up discarded from the results, and a run long enough that autovacuum and checkpoints are inside
  the measurement window rather than lurking beside it.

**Captured**
- **Committed Entries per second** — from the application, and cross-checked against the `xact_commit`
  delta. If the two disagree, something is opening transactions you did not count.
- **Latency distribution, not a mean.** p50 / p95 / p99 / max. Contention shows up in the tail first;
  a mean hides it completely.
- **A histogram by SQLSTATE**, not an error count: `23514` (correct refusals — a *business* outcome that
  must not be counted as failure), `40001`, `40P01`, `55P03`, `57014`, and pool-exhaustion failures
  separately.
- **Retry attempts per successful Entry.** A design that reaches the same throughput with 4x the
  attempts is not the same design.
- **`pg_stat_database` deltas** — `xact_commit`, `xact_rollback`, `deadlocks` — bracketed by
  `pg_stat_reset()` and `stats_reset`.
- **A sampler over `pg_stat_activity`** (say 10 Hz) recording `wait_event_type` / `wait_event` /
  `state`, so "throughput was low" can be attributed to lock waits rather than guessed at.
- **Pool state**: active vs idle connections and connection-acquisition wait time. If the pool is the
  bottleneck the database numbers are describing something else.
- **The full configuration**: Postgres version, `lock_timeout`, `statement_timeout`, `deadlock_timeout`,
  isolation level, `maximumPoolSize`, K, machine, and whether `fsync`/`synchronous_commit` are at their
  defaults. A ledger benchmark with `synchronous_commit = off` is measuring a different product.

**And the honest caveat.** A GitHub Actions runner is not a benchmark environment — shared, virtualised,
noisy neighbours — and [`ci-and-testcontainers-budget.md`](ci-and-testcontainers-budget.md) is already
explicit that the CI job exists to demonstrate behaviour, not performance. **CI should assert
correctness under concurrency** (K writers, one constrained Account, starting Balance B: exactly the
Entries that fit succeed, the rest fail `23514`, and the final Balance is never negative and equals the
sum of the Postings) — that test is deterministic and cheap. **Throughput numbers belong to a run on
fixed local hardware**, reported with the hardware named. Quoting an Entries/sec figure obtained from a
CI runner would be exactly the kind of unsourced claim this note is written to avoid.

---

## 7. The Java side

### (a) A locking read

```java
Account a = entityManager.find(Account.class,
                               new AccountId(tenantId, accountId),
                               LockModeType.PESSIMISTIC_WRITE);
```

`LockModeType.PESSIMISTIC_WRITE` is a *"Pessimistic write lock"* enabling *"serialization among
transactions attempting to update the entity data"*
([Jakarta Persistence 3.1, `LockModeType`](https://jakarta.ee/specifications/persistence/3.1/apidocs/jakarta.persistence/jakarta/persistence/lockmodetype)).
Hibernate's own enum says what SQL that is: `PESSIMISTIC_WRITE` is *"A pessimistic upgrade lock, which
prevents concurrent transactions from reading or writing the locked object. Obtained via a `select for
update` statement."* `PESSIMISTIC_READ` is *"Obtained via a `select for share` statement in dialects
where this syntax is supported"*
([Hibernate 6.6 javadoc, `LockMode`](https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/LockMode.html)).

The Postgres translation, from the dialect source:

```java
// PostgreSQLDialect
public String getWriteLockString(int timeout) { return withTimeout( getForUpdateString(), timeout ); }
public String getReadLockString(int timeout)  { return withTimeout( " for share", timeout ); }
public String getForUpdateNowaitString()      { return supportsNoWait() ? " for update nowait" : getForUpdateString(); }
public String getForUpdateSkipLockedString()  { return supportsSkipLocked() ? " for update skip locked" : getForUpdateString(); }
public boolean supportsNoWait()               { return true; }
public boolean supportsSkipLocked()           { return true; }
public RowLockStrategy getWriteRowLockStrategy() { return RowLockStrategy.TABLE; }

// Dialect (base)
public String getForUpdateString() { return " for update"; }
public String getForUpdateString(String aliases) { return getForUpdateString(); }  // overridden in PG:
                                                  // getForUpdateString() + " of " + aliases
```

([hibernate-orm 6.6, `PostgreSQLDialect.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/dialect/PostgreSQLDialect.java);
[`Dialect.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/dialect/Dialect.java))

So `PESSIMISTIC_WRITE` on Postgres is `for update`, exactly the mechanism of §1(c). `NOWAIT` and
`SKIP LOCKED` are both supported, reachable through the standard hint — Hibernate's legacy modes name
the same thing: `UPGRADE_NOWAIT` is *"obtained using an Oracle-style `select for update nowait`. […] If
the lock is not immediately available, an exception occurs"*, and `UPGRADE_SKIPLOCKED` *"if the lock is
not immediately available, no exception occurs, but the locked row is not returned from the database"*.
The JPA hint `jakarta.persistence.lock.timeout` takes `0` for NO WAIT and `-2` for SKIP LOCKED
([Hibernate 6.6 User Guide, Locking](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#locking)).

`SKIP LOCKED` is right for a work queue and **wrong here**: skipping a locked Account row means writing
an Entry that never checked the Balance. The docs say as much — *"Skipping locked rows provides an
inconsistent view of the data, so this is not suitable for general purpose work"*.

**Two things to verify rather than assume.** `getWriteRowLockStrategy()` returns `RowLockStrategy.TABLE`,
meaning the `of` list names table aliases — on a query with joins, `for update of …` can lock more rows
than the entity you had in mind. And when the lock cannot be obtained, JPA specifies
`PessimisticLockException` where the failure *"results in transaction-level rollback"* and
`LockTimeoutException` for statement-level. **Needs a test**: turn on SQL logging, assert the emitted
`for update` text, and assert which exception a `lock_timeout` actually produces through Hibernate.

### (b) A single atomic conditional `UPDATE` that JPA will not be clever about

Do not load the Account entity and mutate it — that is dirty checking, which is a read-modify-write in
Java (§2), and it will write the whole row from a stale snapshot. Issue the statement:

```java
int updated = entityManager.createMutationQuery("""
        update Account a
           set a.balanceMinor = a.balanceMinor + :delta
         where a.id = :id
           and (a.constrained = false or a.balanceMinor + :delta >= 0)
        """)
    .setParameter("delta", delta)
    .setParameter("id", accountId)
    .executeUpdate();

if (updated == 0) throw new InsufficientBalance(accountId);   // unchecked — see §3
```

`executeUpdate()` returns *"the number of entity instances affected by the operation"*, which is the row
count §1(b) turns on. HQL's `update` maps to a single SQL `UPDATE`; nothing is read first.

**The documented cost, which is also the reason it is safe:**

> The effect of an `update` or `delete` statement is not reflected in the persistence context, nor in the
> state of entity objects held in memory at the time the statement is executed. […] It's the
> responsibility of the client program to maintain synchronization of state held in memory with the
> database after execution of an `update` or `delete` statement.

([Hibernate 6.6 Query Language guide](https://docs.hibernate.org/orm/6.6/querylanguage/html_single/Hibernate_Query_Language.html))

For this write path that is a feature, not a problem: **the Account entity should never be in the
persistence context in the first place.** If it is (a `find()` earlier in the same request), it is now
stale, and any later flush of it would write back the old balance. Either do not load it, or clear it.
Through Spring Data the equivalent is
`@Modifying(flushAutomatically = true, clearAutomatically = true)` — Spring Data documents exactly this:
*"As the `EntityManager` might contain outdated entities after the execution of the modifying query, we
do not automatically clear it […] If you wish the `EntityManager` to be cleared automatically, you can
set the `@Modifying` annotation's `clearAutomatically` attribute to `true`"*
([Spring Data JPA, Query Methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)).
Note `clearAutomatically = true` *"effectively drops all non-flushed changes still pending"*, which is
why `flushAutomatically = true` goes with it.

`spring.jpa.open-in-view=false` matters here for a second reason beyond the RLS one in
[`rls-pooling.md`](rls-pooling.md) §2: with OSIV on, a stale Account entity can be touched during
response serialization, after the transaction that updated its balance has committed.

If the guard needs SQL that HQL cannot express, `createNativeMutationQuery` takes the same shape and the
same row count. It is not a fallback to be embarrassed about — the invariant is a SQL invariant.

### (c) `@Version`, and why not

Three reasons, in order of decisiveness.

1. **The conflict it detects is not a conflict.** Optimistic locking works by
   `UPDATE … SET version = ? WHERE id = ? AND version = ?`, throwing `OptimisticLockException` when the
   version moved. But two Entries each doing `balance = balance + delta` **do not conflict** — addition
   commutes, and Postgres serialises them correctly on the row lock. `@Version` would turn a pair of
   compatible writes into a spurious failure and a retry. It converts throughput into error rate for no
   integrity gain.
2. **The bulk `update` does not maintain it.** HQL's `update` leaves version attributes untouched unless
   written `update versioned Account set …`. So the version column would go stale relative to the row it
   claims to version: writes through the mutation query bump the balance and not the version, and any
   entity loaded before that update carries a version that still "matches". A version column that is
   right some of the time is worse than none.
3. **It guards the wrong operation.** `@Version` protects a read-modify-write across a user think-time
   gap. The entire design here is that there is no read-modify-write.

**A deliberate departure from the sibling note, stated so it is not read as a contradiction.**
[`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md) §5 rejects `@Version` because it would be
*"an optimistic-lock column on a table that is never updated […] a column whose value is a lie by
construction"*. That reasoning is about `entry` and `posting`, which are insert-only. **`account` is
not insert-only** — this note's whole design updates the Account row on every constrained write — so
that particular argument does not transfer, and `@Version` is genuinely *available* on `account`. It is
still the wrong choice, for the three reasons above. Both notes reach "no `@Version`"; they reach it by
different routes, and the routes should not be mixed up.

### What carries over unchanged from the sibling note

- `EntityManager.persist()`, not `CrudRepository.save()`, for the `entry` and `posting` inserts — the
  ids are assigned UUIDs, so `save()` degrades to `merge()` and a wasted SELECT per row (§5 there).
- JDBC batching for the N Postings (§7 there). The one balance `UPDATE` per constrained Account is a
  separate statement and does not batch with them; on an Entry touching one constrained Account that is
  one extra round trip.
- `REVOKE UPDATE, DELETE ON entry, posting FROM app_user` (§4 there) — **and note that `account` must
  not be on that list**, because this design updates it. Likewise the RLS policies on `account` need a
  `FOR UPDATE` policy with a `WITH CHECK`, where `entry` and `posting` deliberately have only
  `FOR SELECT` and `FOR INSERT` ([`rls-pooling.md`](rls-pooling.md) §3). The insert-only barrier and the
  running-balance column are in tension on exactly one table, and it should be written down in the
  migration rather than discovered.
- One connection per transaction, always. A blocked row lock plus a second borrowed connection is the
  deadlock in Spring's own `REQUIRES_NEW` warning (§3).

---

## Recommendation

**Constrained Accounts: a stored `balance_minor` column, a guarded single-statement `UPDATE`, and a
`CHECK` constraint — all three.**

```sql
-- the barrier
ALTER TABLE account ADD CONSTRAINT account_constrained_non_negative
  CHECK (NOT constrained OR balance_minor >= 0);
```

```sql
-- the write, one statement per constrained Account, issued in ascending Account-id order
UPDATE account
   SET balance_minor = balance_minor + :delta
 WHERE tenant_id = :tenant AND id = :account
   AND (NOT constrained OR balance_minor + :delta >= 0);
```

- `READ COMMITTED` — the Postgres default, unchanged. Never `REPEATABLE READ` on this path.
- No explicit locking, no advisory locks, no `SERIALIZABLE`. The `UPDATE`'s own row lock is the
  serialisation point, and the documented `WHERE` re-evaluation is what makes it correct.
- The row count is the business answer (0 ⇒ insufficient Balance). The `CHECK` is the barrier that
  survives someone writing a new query without the guard, and it is the thing that makes "the schema
  enforces the invariant" literally true rather than aspirational.
- Sort the Accounts and issue **N separate statements** — that is the documented deadlock prevention.
- Retry `40001` / `40P01` / `55P03` from **outside** the transaction proxy, with backoff. Never retry
  `23514`.
- `lock_timeout` 1s, `statement_timeout` 3s, `idle_in_transaction_session_timeout` 10s on the app role
  (estimates — replace with values derived from the measured p99).

**Unconstrained Accounts: touch nothing but `posting`.** They have no invariant to enforce, so there is
no reason to serialise them on an Account row. The write stays pure append, and their Balance is derived
with `SUM()` over `posting` on read, backed by an index on `(tenant_id, account_id)`. This is the whole
payoff of the split: contention exists only where an invariant demands it, and the ledger's ordinary
traffic never queues behind anything.

The cost of the split is two different sources of truth for a Balance. Pay for it with a reconciliation
assertion — `account.balance_minor = SUM(posting.amount_minor)` for every constrained Account — run as a
test after every concurrency test, and as a periodic job in any real deployment.

**The one measurement that proves the design works.**

Run the identical Entry-recording load twice at the same concurrency K: **arm A**, every Entry touching
one constrained Account; **arm B**, Entries spread across K distinct constrained Accounts. Report
committed Entries/sec and the p99 latency for both, with a SQLSTATE histogram alongside, on named
hardware.

The design is proved if, in the same run:

1. **No Balance is ever negative**, and every constrained Account's `balance_minor` equals the sum of its
   Postings at the end — under arm A, with contention maximal.
2. **Arm B's throughput scales with K** while arm A's flattens at roughly one Account-row update per
   round trip. That flattening is the *expected* shape: it is a correctly serialised hot row, and being
   able to say so with a number is the difference between a design and a hope.
3. **Arm A's failures are `23514`** — real refusals, at the rate the starting Balance predicts — and not
   `40P01`, `55P03`, or pool-exhaustion errors. Deadlocks in arm A would mean the ordering in §4 is not
   doing its job; lock timeouts would mean K exceeds what the row can absorb within `lock_timeout`.

Item 1 is a Testcontainers test that belongs in CI on every push. Items 2 and 3 are a local run on fixed
hardware, quoted with that hardware named.

---

## Sources

- [PostgreSQL 16 — Transaction Isolation](https://www.postgresql.org/docs/16/transaction-iso.html) — the anomaly table and which levels permit which; the `READ COMMITTED` rule that a blocked `UPDATE`/`DELETE`/`SELECT FOR UPDATE` re-evaluates its `WHERE` against the updated row version and applies its operation to that version; the `website.hits` worked example; "whether or not a *single* command sees an absolutely consistent view of the database"; `REPEATABLE READ` raising `could not serialize access due to concurrent update` and the instruction to retry the whole transaction; SSI, predicate locks and `SIReadLock`; the `SUM`-then-insert worked example and `could not serialize access due to read/write dependencies among transactions`; "always return with an SQLSTATE value of '40001'"; the acknowledgement of failures "that would not occur in true serial execution"; the performance recommendations (READ ONLY, connection pool, short transactions, `idle_in_transaction_session_timeout`, `max_pred_locks_per_transaction`, sequential scans escalating predicate locks).
- [PostgreSQL 16 — Explicit Locking](https://www.postgresql.org/docs/16/explicit-locking.html) — `FOR UPDATE` / `FOR NO KEY UPDATE` / `FOR SHARE` / `FOR KEY SHARE` semantics; row locks released at transaction end; "a transaction seeking either a table-level or row-level lock will wait indefinitely"; automatic deadlock detection with one transaction aborted and "Exactly which transaction will be aborted is difficult to predict and should not be relied upon"; "acquire locks on multiple objects in a consistent order"; retrying on deadlock; session- vs transaction-level advisory locks, "purely advisory — the system does not enforce their use", and session-level locks not honouring transaction semantics.
- [PostgreSQL 16 — Data Consistency Checks at the Application Level](https://www.postgresql.org/docs/16/applevel-consistency.html) — "It is very difficult to enforce business rules regarding data integrity using Read Committed transactions"; the two documented enforcement routes (Serializable everywhere, or explicit blocking locks); "`SELECT FOR UPDATE` does not ensure that a concurrent transaction will not update or delete a selected row… you must actually update the row"; the warning that in `REPEATABLE READ` a snapshot frozen at the first query can predate a lock taken later.
- [PostgreSQL 16 — SELECT](https://www.postgresql.org/docs/16/sql-select.html) — the locking clause, `NOWAIT` ("reports an error, rather than waiting"), `SKIP LOCKED` ("provides an inconsistent view of the data… not suitable for general purpose work"); "cannot be used in contexts where returned rows cannot be clearly identified with individual table rows; for example they cannot be used with aggregation"; the `ORDER BY`-applied-first caution and its `40001` behaviour at higher isolation levels.
- [PostgreSQL 16 — UPDATE](https://www.postgresql.org/docs/16/sql-update.html) — the `UPDATE count` command tag and "If count is 0, no rows were updated by the query (this is not considered an error)"; `RETURNING`; partition row movement raising `40001`.
- [PostgreSQL 16 — CREATE TABLE](https://www.postgresql.org/docs/16/sql-createtable.html) — CHECK produces a Boolean new or updated rows must satisfy; "Expressions evaluating to TRUE or UNKNOWN succeed"; "Currently, `CHECK` expressions cannot contain subqueries nor refer to variables other than columns of the current row".
- [PostgreSQL 16 — Constraints](https://www.postgresql.org/docs/16/ddl-constraints.html) — check constraints; "PostgreSQL does not support `CHECK` constraints that reference table data other than the new or updated row being checked"; a null operand satisfies the constraint.
- [PostgreSQL 16 — Error Codes](https://www.postgresql.org/docs/16/errcodes-appendix.html) — `40001` `serialization_failure`, `40P01` `deadlock_detected`, `23514` `check_violation`, `23505` `unique_violation`, `23503` `foreign_key_violation`, `55P03` `lock_not_available`, `57014` `query_canceled`, `25P02` `in_failed_sql_transaction`, `25P03` `idle_in_transaction_session_timeout`.
- [PostgreSQL 16 — Client Connection Defaults](https://www.postgresql.org/docs/16/runtime-config-client.html) — `statement_timeout`, `lock_timeout` ("applies separately to each lock acquisition attempt"; "rather pointless to set `lock_timeout` to the same or larger value"), `idle_in_transaction_session_timeout`, `idle_session_timeout`; all default to zero/disabled; "Setting … in `postgresql.conf` is not recommended"; `default_transaction_isolation` defaults to "read committed".
- [PostgreSQL 16 — Lock Management](https://www.postgresql.org/docs/16/runtime-config-locks.html) — `deadlock_timeout` default `1s`, the deadlock check being "relatively expensive" and run only after waiting, "Ideally the setting should exceed your typical transaction time", and its interaction with `log_lock_waits`; `max_locks_per_transaction` and `max_pred_locks_per_transaction`, both server-start-only, default 64.
- [PostgreSQL 16 — Cumulative Statistics](https://www.postgresql.org/docs/16/monitoring-stats.html) — `pg_stat_activity` `wait_event_type` values with "Lock" = "waiting for a heavyweight lock" and the `transactionid` / `tuple` / `relation` / `advisory` wait events; `state`, `xact_start`, `query_start`, `state_change`; `pg_stat_database` `xact_commit`, `xact_rollback`, `deadlocks`, `stats_reset`; the once-per-second flush interval and per-transaction caching of accessed statistics, `stats_fetch_consistency`, `pg_stat_clear_snapshot()`; `pg_stat_reset()`.
- [PostgreSQL 16 — `pg_locks`](https://www.postgresql.org/docs/16/view-pg-locks.html) — one row per lockable object / mode / process; `granted` false means waiting; `locktype`, `mode`, `waitstart`; join on `pid` to `pg_stat_activity`; prefer `pg_blocking_pids()` to a self-join.
- [PostgreSQL 16 — Advisory Lock Functions](https://www.postgresql.org/docs/16/functions-admin.html) — `pg_advisory_lock`, `pg_advisory_xact_lock`, `pg_try_advisory_lock` / `_xact_` variants and their exact semantics; "note that these two key spaces do not overlap".
- [PostgreSQL 16 — pg_stat_statements](https://www.postgresql.org/docs/16/pgstatstatements.html) — must be in `shared_preload_libraries`; `calls`, `total_exec_time`, `mean_exec_time`, `stddev_exec_time`, `min_exec_time`, `max_exec_time`, `rows`; `pg_stat_statements_reset`.
- [postgres `REL_16_STABLE` — `src/backend/tcop/postgres.c`](https://github.com/postgres/postgres/blob/REL_16_STABLE/src/backend/tcop/postgres.c) — `ProcessInterrupts` raising `ERRCODE_QUERY_CANCELED` for statement timeout, `ERRCODE_LOCK_NOT_AVAILABLE` for lock timeout, and `ERRCODE_IDLE_IN_TRANSACTION_SESSION_TIMEOUT` at FATAL. The documentation does not state these mappings; the source does.
- [Hibernate 6.6 javadoc — `LockMode`](https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/LockMode.html) — `PESSIMISTIC_WRITE` "Obtained via a `select for update` statement"; `PESSIMISTIC_READ` via `select for share` where supported; `UPGRADE_NOWAIT` throws if the lock is unavailable, `UPGRADE_SKIPLOCKED` returns no row; the mapping to JPA `LockModeType`.
- [Hibernate 6.6 User Guide — Locking](https://docs.hibernate.org/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#locking) — applying locks via `find`/`lock`/`refresh`/`Query.setLockMode`; the `jakarta.persistence.lock.timeout` hint with `0` = NO WAIT and `-2` = SKIP LOCKED.
- [Hibernate 6.6 Query Language guide](https://docs.hibernate.org/orm/6.6/querylanguage/html_single/Hibernate_Query_Language.html) — HQL `update` syntax; `executeUpdate()` returning the number of affected instances; "The effect of an `update` or `delete` statement is not reflected in the persistence context, nor in the state of entity objects held in memory"; "It's the responsibility of the client program to maintain synchronization"; `update versioned` as the only form that touches version attributes.
- [hibernate-orm 6.6 — `PostgreSQLDialect.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/dialect/PostgreSQLDialect.java) — `getWriteLockString`, `getReadLockString` (`" for share"`), `getForUpdateNowaitString` (`" for update nowait"`), `getForUpdateSkipLockedString` (`" for update skip locked"`), `supportsNoWait`/`supportsSkipLocked` both true, `getWriteRowLockStrategy() == RowLockStrategy.TABLE`.
- [hibernate-orm 6.6 — `Dialect.java`](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/dialect/Dialect.java) — the base `getForUpdateString()` returning `" for update"`.
- [Jakarta Persistence 3.1 — `LockModeType`](https://jakarta.ee/specifications/persistence/3.1/apidocs/jakarta.persistence/jakarta/persistence/lockmodetype) — the constants and their definitions; `PessimisticLockException` for transaction-level lock failure, `LockTimeoutException` for statement-level.
- [Spring Framework — Declarative transaction annotations](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html) — proxy mode, "self-invocation … does not lead to an actual transaction at runtime"; default rollback on `RuntimeException`/`Error` only; default propagation `PROPAGATION_REQUIRED`; isolation "Applies only to propagation values of `REQUIRED` or `REQUIRES_NEW`".
- [Spring Framework — Transaction propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html) — `REQUIRED` maps all logical scopes to one physical transaction, and an inner rollback-only marker produces `UnexpectedRollbackException` at the outer commit; "a participating transaction joins the characteristics of the outer scope, silently ignoring the local isolation level, timeout value, or read-only flag"; `REQUIRES_NEW` takes its own connection and "may lead to exhaustion of the connection pool and potentially to a deadlock".
- [Spring Framework — DAO support](https://docs.spring.io/spring-framework/reference/data-access/dao.html) — translation of `SQLException` into the `DataAccessException` hierarchy.
- [Spring Framework — `SQLExceptionSubclassTranslator`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/support/SQLExceptionSubclassTranslator.html) — the default JDBC translator as of 6.0; "Falls back to a standard `SQLStateSQLExceptionTranslator` if the JDBC driver does not actually expose JDBC 4 compliant `SQLException` subclasses".
- [spring-framework 6.2.x — `SQLStateSQLExceptionTranslator.java`](https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-jdbc/src/main/java/org/springframework/jdbc/support/SQLStateSQLExceptionTranslator.java) — SQLSTATE class-code sets and the specific handling of `23505`, `40001` and `57014`; no entry for class `55`.
- [pgjdbc — `PSQLException.java`](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/util/PSQLException.java) — `public class PSQLException extends SQLException`, i.e. not a JDBC 4 typed subclass.
- [Spring Data JPA — Query methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html) — `@Modifying` with `@Query`; the `int` row count; `clearAutomatically` and the reason it is off by default ("effectively drops all non-flushed changes still pending"); `flushAutomatically`.
- [Spring Retry](https://github.com/spring-projects/spring-retry) — `@EnableRetry` and `@Retryable` (`retryFor`, `maxAttempts`, `@Backoff` with `delay`/`multiplier`/`maxDelay`), `@Recover` in the same class, AOP proxies and the self-invocation limitation, stateless vs stateful retry, `RetryTemplate`.
- [HikariCP](https://github.com/brettwooldridge/HikariCP) — `connectionTimeout` default 30000 ms and "If this time is exceeded without a connection becoming available, a SQLException will be thrown"; `maximumPoolSize` default 10; `leakDetectionThreshold`; `validationTimeout`.
