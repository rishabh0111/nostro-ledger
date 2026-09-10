# A position a caller can compare: ordering and watermarks across Postgres, an outbox and Kafka

Research note on the one thing this system currently cannot say out loud: **what a Balance reflects**.
Balances are served from a projection fed asynchronously from the write store, so every Balance
response is a statement about some past state of the ledger, and there is no vocabulary yet for
*which* past state. The requirement this note exists to inform is narrow:

- an Entry write response must hand back a **position token**, known at the moment the Entry commits;
- a Balance response must carry the position it reflects;
- a caller must be able to evaluate `balance.position >= write.position` and be right.

That demands a value that is **monotonic**, **gap-tolerant** (a hole in the number space must not mean
a lost Entry), **comparable** with a single `>=`, and — the hard part — assigned in an order that no
reader can skip past.

Investigated against primary sources (PostgreSQL 16 documentation above all; Apache Kafka 4.x
documentation; RFC 9110 for the HTTP semantics; Debezium where it is a genuine comparison rather than
decoration). Every non-obvious claim is cited inline. Claims that no document settles are marked
**needs a test**, **estimate**, or **reasoned, not documented**, and are not presented as fact. See
[Sources](#sources).

Sibling notes: [`rls-pooling.md`](rls-pooling.md) (Tenant context, the two-role split, the pooler),
[`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md) (composite keys, insert-only tables,
batching), [`hot-account-contention.md`](hot-account-contention.md) (the constrained-Account write
path, isolation, retry) and [`ci-and-testcontainers-budget.md`](ci-and-testcontainers-budget.md) (what
the suite can afford). This note assumes all four and does not restate them. It also assumes
[ADR 0004](../adr/0004-the-schema-holds-the-balance-floor.md) — Balance reads are served from the
projection, never from the stored balance — and
[ADR 0005](../adr/0005-idempotency-is-a-unique-index.md).

---

## Executive summary

- **A `bigserial` is not a position, and the reason is not the gaps.** Sequences are documented
  non-transactional — *"changes made to a sequence (and therefore the counter of a column declared
  using `serial`) are immediately visible to all other transactions and are not rolled back if the
  transaction that made the changes aborts"* ([PostgreSQL: Transaction
  Isolation](https://www.postgresql.org/docs/16/transaction-iso.html)) — but gaps are survivable. What
  is fatal is that the number is assigned when the `INSERT` executes and the row becomes visible when
  the transaction commits, and **nothing ties those two orders together**. A reader tailing
  `WHERE seq > cursor` can advance past a number belonging to a still-open transaction and never come
  back for it. That is a silently lost Entry, and it needs no rollback and no crash (§1).
- **Transaction ids are not commit order either, and PostgreSQL says so.** *"lower-numbered xids
  started writing before higher-numbered xids"* ([PostgreSQL: Transactions and
  Identifiers](https://www.postgresql.org/docs/16/transaction-id.html)) — that is first-**write**
  order, which is not even start order, let alone commit order (§2).
- **But `pg_snapshot_xmin()` closes the skip, and that is a documented property.** *"All transaction
  IDs less than `xmin` are either committed and visible, or rolled back and dead"* ([PostgreSQL:
  Information Functions](https://www.postgresql.org/docs/16/functions-info.html)). A reader that
  advances its cursor only as far as `xmin` cannot skip a row that later becomes visible. The cost is
  head-of-line blocking behind the oldest in-flight write transaction (§2).
- **The only total order PostgreSQL documents as consistent with commit order is the WAL.**
  *"Concurrent transactions are decoded in commit order"* ([PostgreSQL: Logical Decoding Output
  Plugins](https://www.postgresql.org/docs/16/logicaldecoding-output-plugin.html)). Everything else —
  sequences, xids, `clock_timestamp()` — is assignment order wearing a disguise (§2, §3).
- **A writer cannot learn its own commit LSN.** Its commit record does not exist until it commits, and
  nothing hands it back afterwards. **Reasoned, not documented** — but it settles the contract: the
  token in a write response must be assigned *inside* the transaction, so it is `xid8`, not an LSN
  (§2).
- **Drain the outbox by predicate, not by cursor.** `WHERE published_at IS NULL` cannot skip a
  late-committing row — the row is simply still unpublished at the next poll. The §1 hazard is a
  property of the *cursor*, and deleting the cursor deletes the hazard (§3).
- **`SKIP LOCKED` and global ordering are mutually exclusive.** *"Skipping locked rows provides an
  inconsistent view of the data"*
  ([PostgreSQL: SELECT](https://www.postgresql.org/docs/16/sql-select.html)). If the relay is the
  ordering authority it is single-writer, and the way to buy that with no new infrastructure is a
  session-level advisory lock (§3).
- **Kafka orders per partition. Not per topic, not per key — per partition.** A key guarantees order
  only because it maps to a partition, and *"the default partitioner's mapping logic changes when the
  partition count increases"*
  ([Kafka: Basic Operations](https://kafka.apache.org/43/operations/basic-kafka-operations/)). For a
  ledger, adding partitions is a correctness event. Fix the count once and treat it as schema (§4).
- **The producer default that will bite is a silent downgrade, not a missing setting.**
  `enable.idempotence` defaults to `true` in Kafka 4.x, but *"If conflicting configurations are set and
  idempotence is not explicitly enabled, idempotence is disabled"* — so setting `acks=1` for latency
  turns off the ordering protection with no error at all. Set `enable.idempotence=true` **explicitly**
  and the same mistake raises `ConfigException` instead (§4).
- **Partition key: `tenant_id`, one message per Entry carrying all its Postings.** Keying by Account
  fragments an Entry across partitions and lets the projection be observed mid-Entry, where the
  Postings do not sum to zero — the one invariant this project exists to demonstrate. Keying by Entry
  guarantees nothing, because each Entry has exactly one message (§4).
- **A Kafka offset cannot be the API's position token.** Offsets are per-partition; a scalar offset is
  meaningless across partitions, and a vector leaks the partition count into a contract §4 has just
  frozen. The token is the Postgres-side `xid8` (§5).
- **Kafka's exactly-once does not reach the projection's Postgres**, and the documentation is explicit
  about why: *"many of the output systems a consumer might want to write to will not support a
  two-phase commit"*, so *"let the consumer store its offset in the same place as its output"*
  ([Kafka: Design](https://kafka.apache.org/43/design/design/)). Offset and Balance move in one
  Postgres transaction, plus a unique index on the applied Entry — and those two cover *different*
  failures (§6).
- **The projection's dedupe key is not the ledger's Idempotency Key.** One stops the same Entry being
  *applied* twice; the other stops one request *recording* two Entries. Neither substitutes for the
  other, and conflating them produces a design that looks safe and is not (§6).
- **For read-your-writes, bound the wait and then tell the truth.** `409` is for a conflict the client
  can resolve; `503` asserts the server is unavailable, which is a lie that trips other people's
  circuit breakers. RFC 9110 pairs `Retry-After` with `503` and `3xx`, and with nothing else. A short
  capped wait followed by a `200` that states its own position is the only option honest at every
  outcome (§7).

---

## 1. Why a plain sequence is not a position

### What the documentation settles

`nextval` is atomic and distinct per caller:

> Advances the sequence object to its next value and returns that value. This is done atomically: even
> if multiple sessions execute `nextval` concurrently, each will safely receive a distinct sequence
> value.

([PostgreSQL: Sequence Manipulation Functions](https://www.postgresql.org/docs/16/functions-sequence.html))

It is also explicitly non-transactional, and PostgreSQL states this in two separate chapters. In the
sequence functions chapter:

> To avoid blocking concurrent transactions that obtain numbers from the same sequence, the value
> obtained by `nextval` is not reclaimed for re-use if the calling transaction later aborts. This means
> that transaction aborts or database crashes can result in gaps in the sequence of assigned values.
> That can happen without a transaction abort, too. For example an `INSERT` with an `ON CONFLICT`
> clause will compute the to-be-inserted tuple, including doing any required `nextval` calls, before
> detecting any conflict that would cause it to follow the `ON CONFLICT` rule instead. Thus,
> PostgreSQL sequence objects **cannot be used to obtain "gapless" sequences**.

and again in the transaction isolation chapter, as a caveat on isolation itself:

> In particular, changes made to a sequence (and therefore the counter of a column declared using
> `serial`) are immediately visible to all other transactions and are not rolled back if the
> transaction that made the changes aborts.

([PostgreSQL: Transaction Isolation](https://www.postgresql.org/docs/16/transaction-iso.html))

The `ON CONFLICT` sentence is not academic here. [ADR
0005](../adr/0005-idempotency-is-a-unique-index.md) makes an Idempotency Key a unique index; the moment
any write path reaches for `ON CONFLICT` on it, sequence numbers are burned on requests that never
became an Entry. Gaps are the normal case, not the failure case.

**Gaps on their own are survivable.** A watermark does not need to be dense, only monotonic and
comparable. `WHERE seq > cursor` does not care that 41 is missing.

### The part that is fatal, and how much of it is documented

The claim is that sequence values are assigned in a **different order than transactions commit**. That
decomposes into three pieces with very different evidential status, and the difference is worth being
explicit about:

| Piece | Status |
|---|---|
| The number is assigned when the `INSERT` executes, before the transaction commits | **Documented.** `nextval`'s effect is *"immediately visible to all other transactions"* and is not deferred to or undone at commit ([Transaction Isolation](https://www.postgresql.org/docs/16/transaction-iso.html)). |
| The row itself becomes visible to another session only at commit | **Documented.** *"a `SELECT` query (without a `FOR UPDATE/SHARE` clause) sees only data committed before the query began; it never sees either uncommitted data or changes committed by concurrent transactions during the query's execution"* ([Transaction Isolation](https://www.postgresql.org/docs/16/transaction-iso.html)). |
| Therefore assignment order and commit order can differ arbitrarily, and a cursor-tailing reader can skip a row permanently | **Reasoned, not documented.** No PostgreSQL page states it as a sentence. It follows from the two above: nothing bounds the interval between a session's `nextval` and its `COMMIT`, and nothing coordinates that interval across sessions. |

The third row is the important one, and it deserves to be presented as an inference rather than a
quotation. It is a sound inference — the two documented facts leave no other conclusion available —
but a reader is entitled to know which sentences came from the manual and which did not.

### The skip, concretely

Two ordinary overlapping transactions. No rollback, no crash, no unusual isolation level.

| Time | Session A | Session B | Relay tailing `WHERE seq > cursor ORDER BY seq` |
|---|---|---|---|
| t1 | `BEGIN`; `INSERT` outbox → `seq = 100` | | `cursor = 99` |
| t2 | still open — recording the rest of the Entry | `BEGIN`; `INSERT` outbox → `seq = 101` | |
| t3 | | `COMMIT` | |
| t4 | | | reads: sees **only 101** (100 is uncommitted, therefore invisible). Publishes it. **`cursor = 101`** |
| t5 | `COMMIT` | | |
| t6 | | | reads `WHERE seq > 101` → nothing. **Row 100 is now visible and permanently below the cursor.** |

Row 100 is never published. Not delayed — lost. The projection is missing an Entry, that Account's
Balance is wrong by the Entry's Amount forever, and **nothing anywhere reports an error**. The relay is
healthy, Postgres is healthy, Kafka is healthy, and the outbox row sits there looking exactly like
every row that *was* published.

This is the most important paragraph in the note, because it is the failure mode that looks like
nothing happening. Compare it with the failures this project has deliberately chosen elsewhere: a
`CHECK` violation is `23514`, a composite FK violation is `23503`, a duplicate Idempotency Key is
`23505`. All loud. A skipped watermark is silent, and silence is the one thing a ledger cannot afford.

Note also what the skip is *not* sensitive to. It does not require contention, a long transaction, a
particular isolation level, or an unusual amount of load. It requires two overlapping write
transactions and a reader that polls between them, which is the steady state of the system.

### The same objection kills the obvious alternatives

- **`now()` / `transaction_timestamp()`** — the transaction's *start* time. Two transactions can commit
  in the opposite order to their start times trivially. Strictly worse than the sequence.
- **`clock_timestamp()`** — statement execution time: the same assignment-order problem, plus no
  guarantee of distinctness and none of monotonicity across backends.
- **A sequence plus a "safe lag"** — publish only rows older than *N* seconds. The right shape of idea
  with the wrong instrument: it replaces a correctness property with a guess about how long a
  transaction can stay open, and a single transaction longer than *N* reintroduces the skip. §2 has the
  version of this idea that is actually safe, and it uses `xmin` rather than a clock.

**The general principle, worth stating once:** any position stamped *inside* a transaction records when
the transaction wrote, not when it committed. Making such a value safe for a tailing reader always
requires a second mechanism answering "can anything below this still appear?". Sequences do not come
with one. Snapshots do.

---

## 2. Transaction ids, snapshots, and commit order

### What PostgreSQL 16 documents

**Transaction ids.**

> `pg_current_xact_id () → xid8` — Returns the current transaction's ID. It will assign a new one if
> the current transaction does not have one already (because it has not performed any database
> updates) […] If executed in a subtransaction, this will return the top-level transaction ID.
>
> `pg_current_xact_id_if_assigned () → xid8` — Returns the current transaction's ID, or `NULL` if no ID
> is assigned yet. (It's best to use this variant if the transaction might otherwise be read-only, to
> avoid unnecessary consumption of an XID.)
>
> `pg_xact_status ( xid8 ) → text` — Reports the commit status of a recent transaction. The result is
> one of `in progress`, `committed`, or `aborted`, provided that the transaction is recent enough that
> the system retains the commit status of that transaction. If it is old enough that no references to
> the transaction survive in the system and the commit status information has been discarded, the
> result is `NULL`.

([PostgreSQL: Information Functions](https://www.postgresql.org/docs/16/functions-info.html))

**64-bit versus 32-bit**, which matters more than it first appears:

> The internal transaction ID type `xid` is 32 bits wide and wraps around every 4 billion transactions.
> However, the functions shown in Table 9.80, except `age`, `mxid_age`, and
> `pg_get_multixact_members`, use a 64-bit type `xid8` that does not wrap around during the life of an
> installation and can be converted to `xid` by casting if required.

([PostgreSQL: Information Functions](https://www.postgresql.org/docs/16/functions-info.html))

So `pg_current_xact_id()` returns a value that is monotonic for the life of the installation and never
reused — exactly the shape a watermark needs. **Note the asymmetry:** `pg_xact_commit_timestamp` takes
an `xid`, not an `xid8`. The commit-timestamp machinery is built on the wrapping type, and anything
built on commit timestamps inherits that. (The pre-13 `txid_*` functions returning `bigint` are still
present and documented as *"still supported for backward compatibility, but may be removed from a
future release"*. Write `pg_*`, not `txid_*`.)

**Snapshots.**

> `pg_current_snapshot () → pg_snapshot` — Returns a current snapshot, a data structure showing which
> transaction IDs are now in-progress. Only top-level transaction IDs are included in the snapshot;
> subtransaction IDs are not shown […]
>
> `pg_snapshot_xmin ( pg_snapshot ) → xid8` — Returns the `xmin` of a snapshot.
>
> `pg_visible_in_snapshot ( xid8, pg_snapshot ) → boolean` — Is the given transaction ID visible
> according to this snapshot (that is, was it completed before the snapshot was taken)?

with the components defined as:

| Component | Documented meaning |
|---|---|
| `xmin` | *"Lowest transaction ID that was still active. All transaction IDs less than `xmin` are either committed and visible, or rolled back and dead."* |
| `xmax` | *"One past the highest completed transaction ID. All transaction IDs greater than or equal to `xmax` had not yet completed as of the time of the snapshot, and thus are invisible."* |
| `xip_list` | *"Transactions in progress at the time of the snapshot."* |

([PostgreSQL: Information Functions](https://www.postgresql.org/docs/16/functions-info.html); the
textual form is `xmin:xmax:xip_list`, e.g. `10:20:10,14,15`.)

**Commit timestamps.**

> The functions shown in Table 9.83 provide information about when past transactions were committed.
> They only provide useful data when the `track_commit_timestamp` configuration option is enabled, and
> only for transactions that were committed after it was enabled. **Commit timestamp information is
> routinely removed during vacuum.**

([PostgreSQL: Information Functions](https://www.postgresql.org/docs/16/functions-info.html))

`track_commit_timestamp` — *"Record commit time of transactions"* — defaults to **`off`** and *"can
only be set at server start"*
([PostgreSQL: Replication configuration](https://www.postgresql.org/docs/16/runtime-config-replication.html)).

### Is xid order commit order? No, and the documentation says so

An xid is assigned lazily:

> This assignment happens when a transaction first writes to the database.

and the ordering it implies is spelled out, along with its limits:

> This means lower-numbered xids started writing before higher-numbered xids. Note that the order in
> which transactions perform their first database write might be different from the order in which the
> transactions started, particularly if the transaction started with statements that only performed
> database reads.

([PostgreSQL: Transactions and Identifiers](https://www.postgresql.org/docs/16/transaction-id.html))

Read that carefully. It establishes that xid order is **first-write order**, and it goes out of its way
to add that first-write order is not even *start* order. It says nothing about commit order, because
there is nothing to say: an xid is handed out before the transaction does its work, and the work takes
as long as it takes.

So `xid8` shares the §1 defect — assigned at write time — with two differences in its favour. It
belongs to the *transaction* rather than to a statement, so there is exactly one per Entry and every
statement in that transaction can obtain it; and there is a documented function that tells a reader
when it is safe to advance past it.

### Commit timestamps are not a total order

`pg_xact_commit_timestamp(xid)` returns a `timestamp with time zone` per transaction. Tempting, and
wrong for this purpose:

- It needs `track_commit_timestamp = on`, which needs a server restart — free under `docker compose`, a
  change-management event anywhere else.
- **Nothing in the documentation guarantees the values are distinct.** Two transactions committing in
  the same microsecond would collide, and a watermark whose values can tie is not a total order.
  **Needs a test** if anyone wants to rely on it; do not assume it.
- It is not durable: *"Commit timestamp information is routinely removed during vacuum."* A position
  token a caller may hold for minutes must not become unresolvable because a `VACUUM` ran.
- It is keyed on the 32-bit `xid`, which wraps.

Useful for forensics — "when did this Entry actually land?" — and unusable as the API's ordering key.

### The snapshot-based export pattern, evaluated

This is the classic, and it is the only pure-SQL construction that is actually safe.

**The construction.**

```sql
-- writer: in the same transaction as the Entry and its Postings
INSERT INTO outbox (tenant_id, id, entry_id, payload, xid)
VALUES (:tenant, :id, :entry, :payload, pg_current_xact_id());
```

```sql
-- reader: publish only what can no longer be overtaken
SELECT * FROM outbox
 WHERE xid < pg_snapshot_xmin(pg_current_snapshot())
   AND xid > :cursor
 ORDER BY xid;
```

**The guarantee, and it is documented.** By the definition of `xmin` — *"All transaction IDs less than
`xmin` are either committed and visible, or rolled back and dead"* — no transaction with an `xid8`
below `xmin` can still be in progress, so no row below `xmin` can appear *later*. A cursor that never
advances beyond the `xmin` observed at read time therefore cannot skip a row. The §1 hazard is closed,
not mitigated.

Note what it does *not* claim: it does not deliver rows in commit order. It delivers them in `xid8`
order — first-write order — and guarantees only that the set is complete. For this ledger that
distinction is survivable (see §4 on why a sum projection is order-insensitive), but it must be said
rather than glossed.

**What it costs.**

1. **Head-of-line blocking on the oldest in-flight write transaction.** `xmin` cannot advance past an
   open transaction, so one slow writer stalls the *entire* feed, including Entries from unrelated
   Tenants that committed seconds ago. Projection lag becomes a function of the worst transaction in
   the system. The mitigation is `idle_in_transaction_session_timeout`, which
   [`hot-account-contention.md` §5](hot-account-contention.md) already recommends for a different
   reason — it turns out to be load-bearing twice. **Estimate:** with that timeout at 10 s, worst-case
   projection lag from this cause is ~10 s; typical lag is the poll interval. Measure rather than
   quote.
2. **XID consumption on read-only paths.** `pg_current_xact_id()` *"will assign a new one if the
   current transaction does not have one already"*. On the write path this is free — the Entry
   `INSERT` has already assigned one. Anywhere a read-only transaction might call it, use
   `pg_current_xact_id_if_assigned()`, exactly as the documentation advises.
3. **Type handling in the ORM.** **Needs a test:** whether an `xid8` column round-trips and range-scans
   cleanly under Hibernate 6, given [`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md)'s
   existing warnings about mapping fidelity. The fallback is a `numeric(20,0)` column populated by the
   same expression, which sidesteps the driver's type mapping entirely at the cost of a wider index.
4. **One extra function call and a `pg_snapshot` parse per poll.** Negligible.

**What it buys, and this is why it wins:** the value is known *inside the writing transaction*, so an
Entry write response can return it synchronously with no coordination with the relay, Kafka, or the
projection. Nothing else on the list can say that.

### Is there a documented total order consistent with commit order?

Yes — exactly one, and it is not reachable from SQL functions:

> Concurrent transactions are decoded in **commit order**, and only changes belonging to a specific
> transaction are decoded between the `begin` and `commit` callbacks.

([PostgreSQL: Logical Decoding Output
Plugins](https://www.postgresql.org/docs/16/logicaldecoding-output-plugin.html))

Logical decoding is where commit order lives, and every decoded change carries an LSN — a `pg_lsn`,
totally ordered by construction. That is the honest answer to "is there a supported way?": the WAL, via
a replication slot, and nothing else.

**But the writer cannot learn its own commit LSN.** `pg_current_wal_insert_lsn()` and
`pg_current_wal_lsn()` report the server's current insert and write positions — *"The insertion
location is the 'logical' end of the write-ahead log at any instant, while the write location is the
end of what has actually been written out from the server's internal buffers"* ([PostgreSQL: System
Administration Functions](https://www.postgresql.org/docs/16/functions-admin.html)) — not the position
of a commit record that has not been written yet. **Reasoned, not documented:** no page says "you
cannot obtain your own commit LSN", but the commit record demonstrably does not exist during the
transaction that will produce it, and no function returns it afterwards.

That single fact decides the contract. **The position in an Entry write response must be assigned
inside the transaction, so it is `xid8`.** An LSN can be an excellent internal ordering authority (§3);
it cannot be the token a writer hands back.

### One more property the token needs: an installation namespace

`xid8` *"does not wrap around during the life of an installation"* — the qualifier is doing work. A
restored dump, a recreated compose volume, or a `pg_resetwal` starts the counter over, and a token
issued before the reset would compare as newer than Entries recorded after it. The fix is to namespace
the token with the cluster's identity: `pg_control_system()` returns a `system_identifier bigint`
([PostgreSQL: Information Functions](https://www.postgresql.org/docs/16/functions-info.html)), and a
token of the form `<system_identifier>:<xid8>` is comparable only when the prefixes match, which is the
correct semantics — after a rebuild, an old token is not stale, it is *meaningless*, and the API should
say so rather than compare it.

Read the identifier once during migration (as the owner role, per
[`rls-pooling.md` §3](rls-pooling.md)) and hold it in configuration. **Needs a check:** whether the
runtime role can execute `pg_control_system()` at all — control-data functions are privilege-restricted
by default, which is another reason to read it at migration time rather than per request.

---

## 3. The outbox as the ordering authority

Two candidate designs, and the choice is less about ordering strength than about what each one costs to
run inside a single `docker compose up`.

### (a) A single-threaded relay that assigns its own sequence at publish time

The relay drains the outbox and stamps a strictly increasing `publish_seq` as it produces to Kafka. The
appeal is obvious: publication order *is* the order, by definition, because one thread did both.

**The guarantee.** A total order over published events, monotonic, controlled entirely by code you own.
It is genuinely total — unlike `xid8` order, it is not merely safe, it is the order the downstream sees.

**But it only holds if the drain query cannot skip**, and this is where §1 comes back. If the relay
tails `WHERE outbox_seq > cursor`, everything in §1 applies and the `publish_seq` is a beautifully
ordered sequence over an incomplete set. Two ways out:

- **Gate the cursor on `xmin`** (§2), or
- **drain by predicate rather than by cursor:**

  ```sql
  SELECT ... FROM outbox WHERE published_at IS NULL ORDER BY xid LIMIT :batch;
  -- ... produce to Kafka ...
  UPDATE outbox SET published_at = now(), publish_seq = :seq WHERE (tenant_id, id) = ...;
  ```

  A late-committing row is not skipped — it simply becomes visible on a subsequent poll, still
  unpublished, and gets picked up. **The skip hazard is a property of the cursor, not of the sequence.**
  Removing the cursor removes it.

  The cost is that the drain query re-scans the unpublished set each poll, which needs a partial index
  (`CREATE INDEX ... ON outbox (xid) WHERE published_at IS NULL`) to stay cheap, and it means the
  publish order is `xid` order, not commit order — a late committer publishes *after* rows with higher
  `xid`, so `publish_seq` and `xid` order disagree for exactly those rows. Both are monotonic; they are
  monotonic in different things. Pick one as the contract and never mix them.

**What it costs.**

- **A single writer, mandatory.** Two relay instances draining concurrently interleave their publishes,
  and `publish_seq` stops meaning anything. `FOR UPDATE ... SKIP LOCKED` — the reflex for a queue table
  — is explicitly disqualified: *"With `SKIP LOCKED`, any selected rows that cannot be immediately
  locked are skipped. Skipping locked rows provides an inconsistent view of the data, so this is not
  suitable for general purpose work, but can be used to avoid lock contention with multiple consumers
  accessing a queue-like table"* ([PostgreSQL:
  SELECT](https://www.postgresql.org/docs/16/sql-select.html)). It is the right tool for a work queue
  and the wrong tool for an ordered log. If the relay ever needs parallelism, shard it by the *same*
  key Kafka partitions by (§4) — one worker per partition — so each worker is still a single writer for
  everything whose order matters.
- **Leader election.** The zero-dependency mechanism is a session-level advisory lock:
  `pg_try_advisory_lock(key)` *"Obtains an exclusive session-level advisory lock if available. This
  will either obtain the lock immediately and return `true`, or return `false` without waiting if the
  lock cannot be acquired immediately"* ([PostgreSQL: System Administration
  Functions](https://www.postgresql.org/docs/16/functions-admin.html)). Session-level, so it is
  released when the session ends — including when the relay's process dies, which is the property that
  makes failover automatic. [`hot-account-contention.md` §1(e)](hot-account-contention.md) evaluates
  advisory locks for a different job and its caveats carry over: the lock must be held on a dedicated
  connection that nothing else borrows, and under the transaction-mode pooler
  ([`rls-pooling.md`](rls-pooling.md)) a session-level lock and a pooled connection are incompatible —
  the relay takes a direct connection, not a pooled one. **This is easy to get wrong and it is the
  whole guarantee**; if the lock is not held the way it is assumed to be, ordering is silently gone.
- **Failover has a gap.** Between the old leader dying and the new one acquiring the lock, nothing is
  published. Lag, not loss.
- **Duplicate publication after a crash is unavoidable.** The relay produces to Kafka, then marks the
  row published — two systems, no shared transaction. A crash between them republishes on restart.
  There is no configuration that fixes this: it is the absence of a distributed transaction, and the
  answer is idempotent apply downstream (§6), not a cleverer relay.
  - Worth killing a specific misconception: **Kafka's idempotent producer does not deduplicate this.**
    `enable.idempotence` *"will ensure that exactly one copy of each message is written in the stream"*
    for retries within a producer session; a republish after a restart comes from a new producer
    instance with a new producer id and is a genuinely new message. A fixed `transactional.id` fences
    zombie producers — it *"allows the client to guarantee that transactions using the same
    TransactionalId have been completed prior to starting any new transactions"* ([Kafka: Producer
    Configs](https://kafka.apache.org/40/generated/producer_config.html)) — which is worth having for
    the failover window, but it still cannot make the Postgres `UPDATE ... SET published_at` atomic
    with the Kafka write.

### (b) Logical decoding / CDC

**What PostgreSQL guarantees.** Commit order by construction, as quoted in §2. Two further properties
matter here:

> Transactions that were rolled back explicitly or implicitly never get decoded.

> Only transactions that have already safely been flushed to disk will be decoded.

([PostgreSQL: Logical Decoding Output
Plugins](https://www.postgresql.org/docs/16/logicaldecoding-output-plugin.html))

Between them, the §1 problem does not exist in this design. There is no uncommitted row to skip and no
aborted row to gap over — the reader is shown a stream of *committed* changes, in commit order, and the
LSN on each is a total order that is already consistent with it.

**What a slot guarantees, and what it does not.**

> In the context of logical replication, a slot represents a stream of changes that can be replayed to
> a client in the order they were made on the origin server.
>
> A logical slot will emit each change just once in normal operation.
>
> The current position of each slot is persisted only at checkpoint, so in the case of a crash the slot
> may return to an earlier LSN, which will then cause recent changes to be sent again when the server
> restarts. **Logical decoding clients are responsible for avoiding ill effects from handling the same
> message more than once.**

([PostgreSQL: Logical Decoding
Concepts](https://www.postgresql.org/docs/16/logicaldecoding-explanation.html))

So CDC is also at-least-once. It buys ordering, not deduplication — §6 is required either way.

**What it requires.**

- *"Before you can use logical decoding, you must set `wal_level` to `logical` and
  `max_replication_slots` to at least 1"* ([PostgreSQL: Logical Decoding
  Examples](https://www.postgresql.org/docs/16/logicaldecoding-example.html)). `wal_level` is a
  restart-level setting. Debezium's own checklist adds `max_wal_senders`
  ([Debezium: PostgreSQL connector](https://debezium.io/documentation/reference/stable/connectors/postgresql.html)).
- **A slot is an operational liability.** *"Replication slots persist across crashes and know nothing
  about the state of their consumer(s). They will prevent removal of required resources even when there
  is no connection using them. This consumes storage because neither required WAL nor required rows
  from the system catalogs can be removed by `VACUUM` as long as they are required by a replication
  slot"* ([PostgreSQL: Logical Decoding
  Concepts](https://www.postgresql.org/docs/16/logicaldecoding-explanation.html)). A stopped consumer
  fills the disk. On a machine running the whole system under `docker compose`, "the disk fills up
  because I stopped the projection overnight" is a real and unpleasant failure.
- **`REPLICA IDENTITY` — and here the outbox shape pays off.** *"A published table must have a replica
  identity configured in order to be able to replicate `UPDATE` and `DELETE` operations"*, and *"If a
  table without a replica identity is added to a publication that replicates `UPDATE` or `DELETE`
  operations then subsequent `UPDATE` or `DELETE` operations will cause an error on the publisher.
  **`INSERT` operations can proceed regardless of any replica identity.**"* ([PostgreSQL: Logical
  Replication — Publication](https://www.postgresql.org/docs/16/logical-replication-publication.html)).
  An outbox is insert-only, so the entire `REPLICA IDENTITY` question — including `REPLICA IDENTITY
  FULL`, which the same page warns *"should only be used as a fallback if no other solution is
  possible"* — never arises. That is a genuine, concrete advantage of capturing the outbox rather than
  capturing `entry`/`posting` directly. It also means the marking step disappears: with CDC there is no
  `published_at` to update, so the outbox table is insert-only in the same sense `entry` and `posting`
  are, and inherits the same `REVOKE UPDATE, DELETE` barrier from
  [`hibernate-vs-the-schema.md` §4](hibernate-vs-the-schema.md).
- **Debezium's delivery claim, in its own words:** *"When the system is operating normally or being
  managed carefully then Debezium provides exactly once delivery of every change event record. If a
  fault does happen then the system does not lose any events. However, while it is recovering from the
  fault, it's possible that the connector might emit some duplicate change events. In these abnormal
  situations, Debezium, like Kafka, provides at least once delivery of change events."*
  ([Debezium: PostgreSQL connector](https://debezium.io/documentation/reference/stable/connectors/postgresql.html))

**And note where Debezium lands on §4's question.** Its outbox event router uses the `aggregateid`
column as the Kafka message key: *"Contains the event key, which provides an ID for the payload. The
SMT uses this value as the key in the emitted outbox message. **This is important for maintaining
correct order in Kafka partitions.**"* ([Debezium: Outbox Event
Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)).
So even the commit-ordered route must still choose a partition key, and gets exactly the same ordering
downstream that a hand-rolled relay gets. **Commit order upstream buys nothing that survives Kafka
unless the partition key preserves it.** That observation deflates most of the apparent advantage.

### Verdict

**Hand-roll the relay, for this project, with the predicate-driven drain and the advisory lock.** The
reasoning, in order of weight:

1. **The extra guarantee CDC provides does not survive the transport.** Kafka reduces any upstream
   order to per-partition order (§4). Once the partition key is `tenant_id`, the relay's per-Tenant
   publish order is exactly as strong as commit order would have been, because a Tenant's Entries are
   published by one thread in `xid` order into one partition. Paying for commit order and then
   discarding it at the partition boundary is paying for nothing.
2. **Operational surface.** CDC adds `wal_level=logical` (a restart), a replication slot that can fill
   the disk while unattended, and — for Debezium specifically — a Kafka Connect worker, its REST API
   and its three internal topics to a compose file whose entire selling point is `docker compose up`.
   The relay adds a thread and a partial index.
3. **It is Java.** ADR-0016 names this project's biggest weakness explicitly: *"It is a Postgres
   project that happens to compile."* A relay with a leader election, a bounded drain, ordered
   publication and an idempotent apply is a piece of concurrent Java worth reading and worth
   discussing. Debezium moves that logic into connector configuration. For a project whose point is
   the Java, that is the difference between demonstrating the skill and demonstrating the ability to
   fill in a JSON file.

**And be equally honest about the other direction.** CDC is the only mechanism here whose ordering is
*documented*, and the relay's ordering is only as good as the advisory lock is correct — a property no
document will guarantee for you. Two things should follow from that: build the relay so the ordering
authority is one small, testable object rather than a property of the deployment, and know that if the
requirement ever hardens to true commit order (a second consumer that must see Tenants in a globally
consistent order, a reconciliation feed) the migration is to logical decoding and it is not
unreasonable. Say so plainly rather than defending the hand-rolled version as universally
better; it is better *here*, for stated reasons.

---

## 4. Kafka ordering, and what the partition key should be

### The guarantee, exactly

> Events with the same event key (e.g., a customer or vehicle ID) are written to the same partition,
> and Kafka guarantees that any consumer of a given topic-partition will always read that partition's
> events in exactly the same order as they were written.

([Kafka: Introduction](https://kafka.apache.org/intro))

and the mechanism:

> We expose the interface for semantic partitioning by allowing the user to specify a key to partition
> by and using this to hash to a partition (there is also an option to override the partition function
> if need be). For example if the key chosen was a user id then all data for a given user would be sent
> to the same partition.

([Kafka: Design](https://kafka.apache.org/43/design/design/))

Parse that precisely, because the popular summary is wrong in a way that matters:

- **Per partition: yes.** This is the guarantee.
- **Per key: only derivatively.** A key is ordered because it hashes to one partition. The guarantee is
  about the partition; the key is how you land in it. When the mapping changes, the key's ordering
  evaporates and nothing errors.
- **Per topic: no.** There is no such thing. A topic with more than one partition has no total order,
  and any design that assumes one is wrong before it starts.

### What breaks ordering

| Cause | What the documentation says | Effect |
|---|---|---|
| Retries with `enable.idempotence=false` and in-flight > 1 | *"if this configuration is set to be greater than 1 and `enable.idempotence` is set to false, there is a risk of message reordering after a failed send due to retries (i.e., if retries are enabled); if retries are disabled or if `enable.idempotence` is set to true, ordering will be preserved"* | A retried batch lands **after** a later batch that succeeded first. Silent reorder within a partition. |
| Partition count increase | *"If data is partitioned by `hash(key) % number_of_partitions`, the default partitioner's mapping logic changes when the partition count increases. This means that messages with the same key may be routed to different partitions after the expansion, potentially affecting message ordering guarantees for existing keys."* | A Tenant's future Entries go to a different partition from its past ones. Two partitions now hold that Tenant's history with no order between them. |
| Two producers for one key | Not addressed as a failure; it follows from the guarantee being about arrival at the partition | Order between them is decided by the network. This is the second reason the relay is single-writer (§3). |
| Producer batching (`linger.ms`, `batch.size`) | *"Requests sent to brokers will contain multiple batches, one for each partition with data available to be sent."* | **Does not reorder** with idempotence on — batches for a partition are sequenced. It costs latency, not order. |

([Kafka: Producer Configs](https://kafka.apache.org/40/generated/producer_config.html);
[Kafka: Basic Operations](https://kafka.apache.org/43/operations/basic-kafka-operations/))

**Partition expansion is a correctness event for a ledger, not an ops event.** Fix the partition count
at topic creation, record it in the compose file and the migration notes as if it were schema, and if
capacity is ever genuinely exhausted, create a new topic and cut over deliberately rather than running
`--alter --partitions`. There is no mechanism that repairs the ordering afterwards.

### The producer defaults in current Kafka, and the one that is a trap

| Config | Default (4.x) | Documented note that matters |
|---|---|---|
| `enable.idempotence` | **`true`** | *"Idempotence is enabled by default if no conflicting configurations are set. **If conflicting configurations are set and idempotence is not explicitly enabled, idempotence is disabled.** If idempotence is explicitly enabled and conflicting configurations are set, a `ConfigException` is thrown."* |
| `acks` | **`all`** | *"the leader will wait for the full set of in-sync replicas to acknowledge the record […] This is the strongest available guarantee."* |
| `max.in.flight.requests.per.connection` | **`5`** | *"enabling idempotence requires the value of this configuration to be less than or equal to 5, because broker only retains at most 5 batches for each producer"* |
| `retries` | `2147483647` | *"Enabling idempotence requires this config value to be greater than 0."* Prefer `delivery.timeout.ms` for control. |
| `linger.ms` | `5` | Latency/throughput knob. Ordering-neutral with idempotence on. |
| `delivery.timeout.ms` | `120000` | *"An upper bound on the time to report success or failure after a call to `send()` returns."* |

([Kafka: Producer Configs](https://kafka.apache.org/40/generated/producer_config.html))

**The trap is the silent downgrade.** The defaults are already correct — the danger is somebody
tuning for latency later. Setting `acks=1` conflicts with idempotence, and because idempotence was not
*explicitly* enabled, it is **turned off silently**; combined with the default in-flight of 5 and
effectively infinite retries, reordering within a partition becomes possible with no error, no log line
and no failing test.

So: **set `enable.idempotence=true` explicitly in the producer configuration, even though it is the
default.** The value does not change; what changes is that the same edit now raises `ConfigException`
at startup instead of quietly weakening the system. That is a one-line change that converts a silent
correctness regression into a boot failure, which is precisely the trade this project makes everywhere
else.

### An Entry touches N Accounts. Say plainly what that means

An Entry is a set of Postings against N Accounts. If the partition key is derived from the Account,
those N Postings hash to up to N different partitions. **There is no join point downstream.** Kafka
offers no cross-partition transaction visible to an ordinary consumer, no barrier, and no way to
observe "the Entry" as a unit. A consumer reading partitions independently will observe, routinely,
that 3 of an Entry's 5 Postings have been applied.

For a double-entry ledger the consequence is exact and severe: **the projection has states in which
money does not sum to zero.** A Tenant-wide read across Accounts during that window returns a non-zero
total. That is not a stale read — stale is fine and expected — it is an *impossible* read, a state the
write store never occupied. This project's stated thesis is that a balanced Entry is structurally
guaranteed; a projection that can be observed unbalanced gives that away at the last step.

Reassembling the Entry downstream (buffer Postings, apply when all N have arrived) requires a
per-Entry completion count with no barrier and no ordering between partitions — a distributed
transaction reimplemented in a consumer. Do not.

### The three candidate keys

| Key | Ordering it buys | Parallelism | Correctness cost |
|---|---|---|---|
| **`account_id`** (with `tenant_id`) | Per Account — the exact unit the projection mutates | Highest: Accounts ≫ partitions | **An Entry is fragmented across partitions.** Observable mid-Entry, sums-to-zero broken transiently. The hot Account of [`hot-account-contention.md`](hot-account-contention.md) becomes a hot *partition*, so the contention is not removed, only moved. |
| **`entry_id`** | Nothing. One message per Entry means the per-partition guarantee has nothing to order. | Highest | Two Entries touching the same Account can be applied in either order. Harmless for a sum, fatal for anything order-sensitive. |
| **`tenant_id`** | Total order **within a Tenant**: Entry atomicity and cross-Account consistency both hold at every observable point | Capped by partition count and skewed by Tenant size | A Tenant's throughput ceiling is one partition. Head-of-line blocking: one stuck message stalls that Tenant. |

**Two observations that should change the reflex answer.**

First: **per-Account ordering is not actually required by this projection.** A Balance is a sum, the
apply is `balance += delta`, and addition commutes. Reordering two Entries against one Account produces
the same Balance. Per-Account ordering only becomes necessary if the projection stores something
order-sensitive — a "last Entry applied" pointer, a per-Account version, a running-balance-at-time
history. So the usual argument for keying by Account ("we must apply Postings to an Account in order")
does not hold here, and the strongest apparent reason to fragment the Entry evaporates.

Second: **what the projection actually needs to preserve is the Entry**, because that is where the
invariant lives. Ordering matters less than atomicity of application.

### Verdict: key by `tenant_id`, one message per Entry

Publish **one message per Entry**, keyed on `tenant_id`, carrying every Posting in the Entry. Reasons,
in order:

1. **The Entry stays whole.** The projection applies all N Postings in one Postgres transaction, so the
   sums-to-zero invariant holds at every point a reader can observe. This is the ledger's defining
   property surviving all the way to the read model.
2. **The watermark becomes trivially answerable, and this is a bigger deal than it looks.** Every
   Balance read is Tenant-scoped (there is no cross-Tenant read in this system — see
   [`rls-pooling.md`](rls-pooling.md)), and a Tenant lives on exactly **one** partition. So "how fresh
   is this Tenant's projection?" is a single scalar on a single partition. No vector, no minimum across
   partitions, no idle-partition heartbeat to stop an unrelated quiet partition pinning the watermark.
   §5's whole problem disappears as a side effect of this key choice.
3. **The ordering unit and the isolation unit coincide.** The Tenant is already the RLS boundary, the
   composite-key prefix, and the Idempotency Key's scope. Making it the partition key too means the
   system has one boundary concept rather than two.

**The cost, stated plainly.** One Tenant's projection throughput is one partition's throughput and one
consumer thread. A Tenant large enough to saturate that is a real limit, and the escape hatch —
`tenant_id:bucket` where `bucket = hash(account_id) % k` — reintroduces exactly the fragmentation
described above, *within* that Tenant. It should be a deliberate per-Tenant decision with the
invariant-visibility consequence written down, never a default and never a reflex under load.

**Also fix in configuration:** partition count at creation (**estimate:** 12 is a reasonable start for
a demo — enough to show parallelism, small enough to reason about), `min.insync.replicas` consistent
with `acks=all`, and the producer's `enable.idempotence=true` written out explicitly per the trap
above.

---

## 5. Can a consumer offset be the position token?

**No.** Not because offsets are unreliable — they are the most reliable number in Kafka — but because
they are the wrong *shape* for the contract.

### What an offset is

> All replicas have the exact same log with the same offsets. The consumer controls its position in
> this log.

([Kafka: Design](https://kafka.apache.org/43/design/design/))

An offset identifies a position in **one partition of one topic**. Offset 5,000 on partition 3 has no
relationship whatsoever to offset 5,000 on partition 7 — not "approximately equal", not "comparable
with care": no relationship. There is no defined arithmetic between them and no meaningful ordering.
Handing a caller one number and calling it "the position" is handing them a number that is true about
one seventh of the system.

Two further properties disqualify it even per-partition:

- **Offsets are not a dense count of applied records.** With a transactional producer, commit and abort
  markers occupy offsets in the log, so consecutive records do not carry consecutive offsets.
  (**Reasoned from the documented behaviour** that markers are written into the log and read-committed
  consumers filter them — worth a **test** before any code does offset arithmetic. The safe rule: never
  compute with offsets, only compare them.)
- **Offsets are not stable across a topic rebuild.** Recreate the topic, replay from a rebuilt outbox,
  change the partition count, and every previously issued token is nonsense. A position token that a
  caller may hold for the length of a retry loop should not be invalidated by an ops action.

### The three real options

| Option | Comparable with a single `>=`? | Known at Entry-write time? | Survives topology change? | Verdict |
|---|---|---|---|---|
| **A vector of per-partition offsets** | No — pointwise comparison, and two vectors can be *incomparable* (a partial order, not a total one) | No | No | Reject. It also leaks the partition count into the API contract, which §4 has just frozen as schema; the first partition change breaks every stored token. |
| **The projection's own applied-sequence** (a `bigserial` in the projection's Postgres) | Yes — dense, monotonic, single writer per partition | **No.** The write path cannot know it without waiting for the projection, which defeats the asynchrony | Yes | Keep it — as an internal replay cursor and a debugging handle. Not as the API token. |
| **The Postgres-side `xid8`** (§2) | Yes — a 64-bit integer, numerically ordered | **Yes**, from `pg_current_xact_id()` inside the Entry's transaction | Yes — it is a property of the ledger, not of the transport | **This is the token.** |

### The contract

- **Representation:** `<system_identifier>:<xid8>`, with the `xid8` zero-padded to 20 digits so that
  lexicographic and numeric comparison agree. Callers compare with `>=` after checking the prefix
  matches; a differing prefix means "these tokens are from different ledgers" and must be an error, not
  a `false`.
- **Opacity:** documented as an opaque, monotonically increasing, comparable token. Callers may compare
  and store it. They may not parse it, do arithmetic on it, or infer a rate from it. That freedom is
  what allows the internals — relay, CDC, transport — to change later without an API version.
- **Where each side gets it.** The write response returns the Entry's own token. The Balance response
  returns the Tenant's projection watermark: the largest `xid8` the projection has applied for that
  Tenant. Because a Tenant maps to one partition and that partition is consumed in publish order, "the
  largest applied" and "everything up to" are the same statement — which is exactly the gap-tolerance
  the requirement asked for. Holes in the `xid8` space are irrelevant; what matters is that the stream
  is ordered and the watermark advances along it.
- **Expose the offsets as metrics, not as API fields.** Consumer lag per partition is the right
  operational signal and the wrong contractual one.

**One honest caveat:** `xid8` is a PostgreSQL implementation detail surfacing in a public contract. The
mitigations are the opacity rule above and the `system_identifier` prefix, which together mean the
value can be re-based onto something else later without callers noticing. It is a real wart and it buys
something no alternative offers: a position known synchronously at write time.

---

## 6. Exactly-once versus idempotent apply

### What Kafka's exactly-once covers, in its own words

> So what about exactly-once semantics? When consuming from a Kafka topic and producing to another
> topic (as in a Kafka Streams application), we can leverage the new transactional producer
> capabilities […] The consumer's position is stored as a message in an internal topic, so we can write
> the offset to Kafka in the same transaction as the output topics receiving the processed data.

([Kafka: Design](https://kafka.apache.org/43/design/design/))

That is the whole scope: **Kafka in, Kafka out.** Both the offset and the output live in Kafka, so one
Kafka transaction covers both.

### What it explicitly does not cover — the projection

> **When writing to an external system, the limitation is in the need to coordinate the consumer's
> position with what is actually stored as output.** The classic way of achieving this would be to
> introduce a two-phase commit between the storage of the consumer position and the storage of the
> consumers output. This can be handled more simply and generally by **letting the consumer store its
> offset in the same place as its output.** This is better because many of the output systems a
> consumer might want to write to will not support a two-phase commit.

and:

> Exactly-once delivery for other destination systems generally requires cooperation with such systems,
> but Kafka provides the primitives which makes implementing this feasible […] Otherwise, Kafka
> guarantees **at-least-once delivery by default**.

([Kafka: Design](https://kafka.apache.org/43/design/design/))

The projection writes to its own Postgres. That is "an external system". Kafka's exactly-once does not
reach it, the documentation says so directly, and it hands over the pattern in the same paragraph.

### The documented pattern, applied here

**Store the offset in the same place as the output**, which for this projection means: one Postgres
transaction that writes the Balance deltas *and* the consumed position, commits once, and seeds its
offsets from that table rather than from Kafka on assignment.

```sql
-- one transaction, one commit
UPDATE   projected_balance SET amount_minor = amount_minor + :delta
 WHERE   tenant_id = :tenant AND account_id = :account;      -- once per Posting
INSERT   INTO applied_entry (tenant_id, entry_id, xid)
VALUES   (:tenant, :entry, :xid);                            -- unique (tenant_id, entry_id)
INSERT   INTO consumer_position (topic, partition, next_offset)
VALUES   (:topic, :partition, :offset + 1)
    ON   CONFLICT (topic, partition) DO UPDATE SET next_offset = excluded.next_offset;
```

with the consumer configured `enable.auto.commit=false` and a rebalance listener that `seek`s each
assigned partition to the `next_offset` held in `consumer_position`.

**`enable.auto.commit` defaults to `true`** ([Kafka: Consumer
Configs](https://kafka.apache.org/43/generated/consumer_config.html)) — *"If true the consumer's offset
will be periodically committed in the background"* — which commits positions on a timer with no
relationship to whether the Balance write succeeded. It must be turned off, explicitly, and the
projection must not rely on Kafka's offset store at all.

**`isolation.level` defaults to `read_uncommitted`** — *"If set to `read_committed`, consumer.poll()
will only return transactional messages which have been committed"*. If the relay ever uses a
transactional producer (§3), the projection must be `read_committed` or it will apply records from
aborted transactions. Set it now; it costs nothing while the relay is non-transactional and prevents a
subtle failure if that changes.

### Why the unique index is needed *as well*

The two mechanisms look redundant and are not. They cover disjoint failures:

| Failure | Offset stored with output | Unique `(tenant_id, entry_id)` |
|---|---|---|
| Projection crashes between applying and committing the offset | **Covered** — the offset never advanced, the apply is redone from the same offset | Covered (belt) |
| Relay republishes after crashing between produce and mark-as-published (§3) | **Not covered** — the redelivery arrives at a *new, higher* offset. The offset store has no idea it is the same Entry | **Covered** |
| Manual replay from `earliest`, topic rebuild, outbox re-drain | **Not covered** — every offset is new | **Covered** |
| Partition reassignment mid-batch | Covered | Covered |
| Consumer bug that applies twice within one poll | Not covered | **Covered** |

**The distinction to be able to state:** an offset is a position in a stream; the unique key is a fact
about the ledger. Only the second survives the stream being rebuilt. A design with only the offset
looks correct until the first replay, which is exactly the operation someone will perform under
pressure.

Mechanically, a redelivered Entry raises `23505` on `applied_entry`; the handler treats that SQLSTATE
as "already applied", rolls back the apply, advances the offset and moves on. This is the same shape as
[ADR 0005](../adr/0005-idempotency-is-a-unique-index.md) — *"The index is the concurrency control"* —
which is a pleasing consistency: the same technique guards the write store and the read model, and a
reader who understands one understands the other.

Kafka itself endorses the shape: *"In many cases messages have a primary key and so the updates are
idempotent (receiving the same message twice just overwrites a record with another copy of itself)."*
Note the ledger cannot use the *overwrite* form of that idea — a Balance apply is `+= delta`, not a
set — which is precisely why the explicit dedupe row is required rather than optional.

### How this relates to the ledger's Idempotency Key — a different concern at a different layer

They are easy to conflate and must not be:

| | Idempotency Key (ADR 0005) | `applied_entry` (this note) |
|---|---|---|
| Supplied by | the caller | the system (the Entry's own id) |
| Scope | unique within a Tenant | unique within a Tenant |
| Lives in | the **write** store, in the Entry's transaction | the **projection's** store, in the apply transaction |
| Prevents | one request **recording** two Entries | one Entry being **applied** twice to the projection |
| Visible to callers | yes — a replay returns the original response | no |
| Retention | 30 days, then removed | for as long as the projection can be replayed from |

Neither substitutes for the other. An Idempotency Key cannot stop a redelivery — there is one Entry,
delivered twice, and the write store is not involved. An `applied_entry` row cannot stop a caller
double-posting — those are two different Entries, both legitimately recorded, and the projection must
apply both. The layers are: *at most once recorded* (caller-facing, write store) and *at most once
applied* (internal, read model).

The retention rows deserve a second look together. Idempotency records are dropped at 30 days by ADR
0005; `applied_entry` must live at least as long as any replay window, and a replay from the beginning
of the outbox needs it to be complete. **Needs a decision, not a test:** either `applied_entry` is
retained indefinitely (a row per Entry forever — the cheapest correct answer, and small: two ids and an
`xid8`), or the projection's replay is bounded by a snapshot/checkpoint below which replay never goes.
Retaining it is the honest default; a projection that cannot be rebuilt from zero is a projection whose
correctness cannot be demonstrated.

---

## 7. Read-your-writes, bounded

The caller passes a minimum position on a Balance read and the projection has not reached it. Three
families of answer.

### What HTTP actually offers

- **`409 Conflict`** — *"indicates that the request could not be completed due to a conflict with the
  current state of the target resource. This code is used in situations where the user might be able to
  resolve the conflict and resubmit the request."*
  ([RFC 9110 §15.5.10](https://www.rfc-editor.org/rfc/rfc9110.html)). Lag is not a conflict, and there
  is nothing for the user to resolve. Wrong semantics.
- **`503 Service Unavailable`** — *"indicates that the server is currently unable to handle the request
  due to a temporary overload or scheduled maintenance, which will likely be alleviated after some
  delay. The server MAY send a `Retry-After` header field […] to suggest an appropriate amount of time
  for the client to wait before retrying the request."*
  ([RFC 9110 §15.6.4](https://www.rfc-editor.org/rfc/rfc9110.html)). The projection being 200 ms behind
  is neither overload nor maintenance, and a `503` is a statement that **the server** is unavailable —
  it will trip client circuit breakers, load-balancer health logic and alerting that have nothing to do
  with this request. Reserve `503` for what it means: the projection is actually down.
- **`Retry-After`** — *"Servers send the 'Retry-After' header field to indicate how long the user agent
  ought to wait before making a follow-up request. When sent with a 503 (Service Unavailable) response,
  Retry-After indicates how long the service is expected to be unavailable to the client. When sent
  with any 3xx (Redirection) response, Retry-After indicates the minimum time that the user agent is
  asked to wait before issuing the redirected request."*
  ([RFC 9110 §10.2.3](https://www.rfc-editor.org/rfc/rfc9110.html)). Values are an HTTP-date or
  `delay-seconds`. **RFC 9110 pairs it with `503` and `3xx`, and nothing else** — so putting
  `Retry-After` on a `200` or a `409` is inventing semantics. If a suggested wait is worth
  communicating, it belongs in the response body where it is honestly the application's own advice.
- **`202 Accepted`** — *"indicates that the request has been accepted for processing, but the
  processing has not been completed."* ([RFC 9110 §15.3.3](https://www.rfc-editor.org/rfc/rfc9110.html))
  This is the right status for the **Entry write** — the Entry is recorded, its projection is not — and
  it is the natural carrier for the position token. It is not an answer for the *read*: the read has
  nothing accepted for later processing.

`delay-seconds` being an integer number of seconds is worth noticing on its own: HTTP's retry vocabulary
has one-second granularity, and the lag being negotiated here is tens to hundreds of milliseconds. The
protocol's own instrument is too coarse for the problem, which is a further argument for handling it in
the body rather than the status line.

### The three designs

| Design | Honest? | Ties up a request thread? | Caller work |
|---|---|---|---|
| **Block until `watermark >= minPosition` or timeout** | Yes, if the timeout outcome is honest | Yes, bounded | None in the happy case |
| **Return the stale value plus its position** | Always | No | Must poll to get freshness |
| **Refuse with a status code** | Only if the code is truthful — and none of them is | No | Must retry, with no value returned |

### Recommendation

**A bounded wait, then a truthful `200`.**

```
GET /tenants/{t}/accounts/{a}/balance?minPosition=<token>&maxWaitMs=250

200 OK
{
  "accountId": "...",
  "currency": "GBP",
  "amountMinor": 125000,
  "position": "7261...:00000000000000481902",
  "reflectsMinPosition": true
}
```

- **Always `200`, always with a `position`.** The response states what it reflects, at every outcome.
  `reflectsMinPosition` is a convenience — the caller can compute it — and its value is that a caller
  who ignores it still gets a number that is true rather than a number that is a lie.
- **`minPosition` is optional and off by default.** A plain read returns immediately with its position;
  freshness is opt-in and its latency cost is opt-in with it. Nobody pays for a guarantee they did not
  ask for.
- **`maxWaitMs` is capped server-side** — accept the caller's value, clamp it to a ceiling
  (**estimate: 1000 ms**, default **250 ms**; replace both with values derived from measured projection
  lag). A caller cannot pin a request thread for longer than the ceiling, whatever they send.
- **Wait on a condition, not a poll loop.** The projection publishes its per-Tenant watermark; the API
  parks on it and is signalled on advance. Java 21 virtual threads (`spring.threads.virtual.enabled`)
  make a parked request cheap — and this is one of the few places in this project where the Java 21
  requirement in ADR-0016 earns its keep for a real reason rather than a stated one. **Needs a test:**
  that a few thousand concurrently parked balance reads do not exhaust the Tomcat/connection budget;
  the JDBC connection in particular must be **released before parking**, not held across the wait, or
  the design converts a projection lag into a pool exhaustion — the same failure
  [`hot-account-contention.md` §5](hot-account-contention.md) describes for lock waits.
- **`503` stays available and stays truthful** — for "the projection is not reachable / has no
  watermark", which *is* an availability statement, and there it may carry `Retry-After` exactly as
  RFC 9110 describes.
- **The write response is `201` or `202` with the position token**, and `202` is defensible precisely
  because the projection has not caught up: the Entry is recorded, the read model is pending.

### The option this note is deliberately not taking

For a constrained Account the write store already carries a stored `balance_minor`
([ADR 0004](../adr/0004-the-schema-holds-the-balance-floor.md)), so a linearizable Balance read is
*technically* available. ADR 0004 forecloses it: *"The stored balance exists only to enforce the
constraint, never to serve a read."* That decision should not be reopened quietly here. It is worth
knowing the escape hatch exists — if a caller ever genuinely needs a linearizable read, the mechanism
is present and the price is contending on the hot row that the whole design exists to keep out of the
read path, plus a Balance API that behaves differently for constrained and unconstrained Accounts. Both
prices are high, and the position token exists so that they need not be paid.

---

## Recommendation

**The position token.**

- **What it is:** `<system_identifier>:<xid8>`, the `xid8` zero-padded to 20 digits. Opaque to callers;
  comparable with `>=` when the prefixes match, an error when they do not.
- **Where it is generated:** in PostgreSQL, by `pg_current_xact_id()`, evaluated in the **same
  transaction as the Entry**, stored on the outbox row (and worth storing on `entry` too, so the
  ledger can answer "what was this Entry's position?" without the outbox). `system_identifier` comes
  from `pg_control_system()` read once at migration time and held in configuration.
- **Where it is returned:** the Entry write response (`201`/`202`) carries the Entry's token; the
  Balance response carries the Tenant's projection watermark. One field name, one comparison.

**The pipeline.**

1. **Outbox**, insert-only, written in the Entry's transaction, carrying `tenant_id`, `entry_id`, the
   payload, and `xid`.
2. **Relay**: single writer, elected by `pg_try_advisory_lock` on a dedicated **direct** (unpooled)
   connection; drains `WHERE published_at IS NULL ORDER BY xid` behind a partial index — **by predicate,
   never by cursor**; produces to Kafka; marks published. At-least-once by construction.
3. **Kafka**: one topic, partition count fixed at creation and treated as schema. **Key = `tenant_id`.
   One message per Entry, carrying all its Postings.** Producer: `enable.idempotence=true` written out
   explicitly, `acks=all`, `max.in.flight.requests.per.connection=5`.
4. **Projection**: `enable.auto.commit=false`, `isolation.level=read_committed`, offsets seeded from
   its own `consumer_position` table on partition assignment. One Postgres transaction per Entry
   writing every Posting's delta, an `applied_entry(tenant_id, entry_id)` row under a unique
   constraint, the new `consumer_position`, and the Tenant's watermark `xid8`. `23505` on
   `applied_entry` means "already applied": skip and advance.
5. **Read API**: optional `minPosition` + capped `maxWaitMs`, always `200`, always carrying the
   position it reflects.

**The one test that proves the projection cannot double-count or reorder.**

One integration test, real Postgres and a real `KafkaContainer` in KRaft mode as a suite singleton, per
[`ci-and-testcontainers-budget.md` §6](ci-and-testcontainers-budget.md). It runs on every push.

1. Record **N** Entries for one Tenant across a set of Accounts, some constrained, via the real write
   path, so the write store holds the truth.
2. Drive the relay so that **every outbox row is published twice** — simulating a crash between
   produce and mark-as-published, which is the failure §3 says cannot be designed away.
3. **Restart the projection consumer mid-stream**, with its Kafka-side offsets reset to the beginning
   of the partition, forcing a full replay over rows it has already applied.
4. Wait for the watermark to reach the last Entry's token, then assert:
   - **No double-count.** For every Account, `projected_balance.amount_minor` equals
     `SUM(posting.amount_minor)` computed from the write store. This is the assertion that fails if the
     unique index is dropped, if the offset is committed outside the apply transaction, or if the
     watermark is written before the apply.
   - **No lost Entry.** `count(applied_entry) == N`, and every recorded `entry_id` appears exactly
     once. This is the assertion that fails if anyone reintroduces a cursor-based drain (§1).
   - **No reorder.** The Tenant's watermark was non-decreasing throughout — asserted from a violation
     counter the projection increments whenever it is asked to apply an Entry whose `xid8` is below the
     current watermark. That counter must be zero, and it must exist in production too, because it is
     the only thing that will ever tell you the ordering assumption has broken.
   - **Entry atomicity.** At no sampled point during the run did a Tenant-wide sum over
     `projected_balance` differ from zero. This is the assertion that fails the moment somebody
     "improves" throughput by keying on `account_id` (§4).

Assertion one alone is worth the test. Assertions two through four are what make it a test of the
*design* rather than of the arithmetic.

---

## Sources

- [PostgreSQL 16 — Sequence Manipulation Functions](https://www.postgresql.org/docs/16/functions-sequence.html) — `nextval` is atomic and yields distinct values per session; values are not reclaimed on abort; gaps arise from aborts, crashes and `ON CONFLICT`; sequences *"cannot be used to obtain 'gapless' sequences"*; `setval` effects are not undone by rollback.
- [PostgreSQL 16 — Transaction Isolation](https://www.postgresql.org/docs/16/transaction-iso.html) — sequence changes *"are immediately visible to all other transactions and are not rolled back if the transaction that made the changes aborts"*; Read Committed is the default and a `SELECT` *"sees only data committed before the query began"*.
- [PostgreSQL 16 — Information Functions](https://www.postgresql.org/docs/16/functions-info.html) — `pg_current_xact_id`, `pg_current_xact_id_if_assigned`, `pg_xact_status`; `pg_current_snapshot`, `pg_snapshot_xmin/xmax/xip`, `pg_visible_in_snapshot`; snapshot component definitions and the `xmin:xmax:xip_list` textual form; `xid` is 32-bit and wraps while `xid8` *"does not wrap around during the life of an installation"*; commit-timestamp functions require `track_commit_timestamp` and are *"routinely removed during vacuum"*; deprecated `txid_*` aliases; `pg_control_system()` and its `system_identifier`.
- [PostgreSQL 16 — Transactions and Identifiers](https://www.postgresql.org/docs/16/transaction-id.html) — an xid is assigned *"when a transaction first writes to the database"*; *"lower-numbered xids started writing before higher-numbered xids"*, and first-write order may differ from start order; virtual transaction ids; the 32-bit epoch and `xid8`.
- [PostgreSQL 16 — Replication configuration](https://www.postgresql.org/docs/16/runtime-config-replication.html) — `track_commit_timestamp` records commit time, defaults to `off`, and can only be set at server start.
- [PostgreSQL 16 — Logical Decoding: Output Plugins](https://www.postgresql.org/docs/16/logicaldecoding-output-plugin.html) — *"Concurrent transactions are decoded in commit order"*; rolled-back transactions *"never get decoded"*; only transactions flushed to disk are decoded; streaming callbacks for in-progress transactions.
- [PostgreSQL 16 — Logical Decoding Concepts](https://www.postgresql.org/docs/16/logicaldecoding-explanation.html) — a slot *"represents a stream of changes that can be replayed to a client in the order they were made"*; *"A logical slot will emit each change just once in normal operation"*; slot position persisted only at checkpoint, so changes may be resent and *"clients are responsible for avoiding ill effects from handling the same message more than once"*; slots retain WAL and catalog rows even with no consumer connected.
- [PostgreSQL 16 — Logical Decoding Examples](https://www.postgresql.org/docs/16/logicaldecoding-example.html) — *"you must set `wal_level` to `logical` and `max_replication_slots` to at least 1"*; the SQL interface versus the streaming replication protocol.
- [PostgreSQL 16 — Logical Replication: Publication](https://www.postgresql.org/docs/16/logical-replication-publication.html) — a replica identity is required to replicate `UPDATE`/`DELETE`, defaults to the primary key, `FULL` as a last-resort fallback, and *"`INSERT` operations can proceed regardless of any replica identity"*.
- [PostgreSQL 16 — SELECT](https://www.postgresql.org/docs/16/sql-select.html) — `NOWAIT` versus `SKIP LOCKED`; *"Skipping locked rows provides an inconsistent view of the data"*; locking clauses cannot be used with aggregation; `ORDER BY` with a locking clause can return rows out of order at Read Committed.
- [PostgreSQL 16 — System Administration Functions](https://www.postgresql.org/docs/16/functions-admin.html) — `pg_current_wal_lsn` / `pg_current_wal_insert_lsn` / `pg_current_wal_flush_lsn` and what the insert, write and flush locations mean; `pg_logical_slot_get_changes` / `pg_logical_slot_peek_changes` returning `(lsn, xid, data)`; `pg_try_advisory_lock` obtains a session-level lock or returns false without waiting.
- [PostgreSQL 16 — MVCC Introduction](https://www.postgresql.org/docs/16/mvcc-intro.html) — each statement *"sees a snapshot of data (a database version) as it was some time ago"*; reading never blocks writing and writing never blocks reading.
- [Apache Kafka — Introduction](https://kafka.apache.org/intro) — *"Events with the same event key … are written to the same partition, and Kafka guarantees that any consumer of a given topic-partition will always read that partition's events in exactly the same order as they were written."*
- [Apache Kafka 4.3 — Design](https://kafka.apache.org/43/design/design/) — semantic partitioning by key hash; at-most-once / at-least-once / exactly-once; the idempotent producer and the transactional producer; consumer position and offsets; *"When writing to an external system, the limitation is in the need to coordinate the consumer's position with what is actually stored as output"* and *"letting the consumer store its offset in the same place as its output"*; Kafka *"guarantees at-least-once delivery by default"*; Using Transactions (`isolation.level=read_committed`, `enable.auto.commit=false`, `transactional.id`).
- [Apache Kafka 4.3 — Basic Operations](https://kafka.apache.org/43/operations/basic-kafka-operations/) — *"the default partitioner's mapping logic changes when the partition count increases … messages with the same key may be routed to different partitions after the expansion, potentially affecting message ordering guarantees for existing keys."*
- [Apache Kafka — Producer Configs](https://kafka.apache.org/40/generated/producer_config.html) — `enable.idempotence` (default `true`; requires in-flight ≤ 5, `retries` > 0, `acks=all`; silently disabled on conflict unless explicitly enabled, in which case `ConfigException`); `acks` (default `all`); `max.in.flight.requests.per.connection` (default `5`, reordering risk without idempotence); `retries`; `transactional.id`; `partitioner.class` key-hash behaviour; `linger.ms` (default `5`); `batch.size`; `delivery.timeout.ms`.
- [Apache Kafka 4.3 — Consumer Configs](https://kafka.apache.org/43/generated/consumer_config.html) — `enable.auto.commit` (default `true`), `isolation.level` (default `read_uncommitted`), `auto.offset.reset`, `max.poll.records`.
- [Debezium — Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html) — the expected outbox columns (`id`, `aggregatetype`, `aggregateid`, `type`, `payload`); `id` usable *"to remove duplicate messages"*; `aggregateid` becomes the Kafka message key and *"This is important for maintaining correct order in Kafka partitions."*
- [Debezium — PostgreSQL connector](https://debezium.io/documentation/reference/stable/connectors/postgresql.html) — `wal_level=logical`, `max_wal_senders`, `max_replication_slots`; change events carry the LSN and Kafka Connect writes them *"in the same order in which they were generated"*; exactly-once in normal operation degrading to *"at least once delivery of change events"* during fault recovery; slot growth monitoring via `confirmed_flush_lsn` / `restart_lsn`.
- [RFC 9110 — HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110.html) — §10.2.3 `Retry-After` (HTTP-date or `delay-seconds`; defined for `503` and `3xx`); §15.3.3 `202 Accepted`; §15.5.10 `409 Conflict`; §15.6.4 `503 Service Unavailable`.
