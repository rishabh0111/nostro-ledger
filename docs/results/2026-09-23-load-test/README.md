# Load test, 2026-09-23: one hot Constrained Account against the same load spread over 64

**The claim:** under 32 concurrent writers, every request against a single Constrained Account is
either recorded or refused at the floor. No lock timeout, no deadlock, no serialization failure, no
5xx. **It held:** 6,346 requests, 6,311 recorded, 35 refused as `insufficient-balance`, nothing else;
across both arms the ledger met no database error of any SQLSTATE.

The throughput and latency numbers below are specific to one laptop and one run. They are here to
be falsified, not quoted.

## The machine

| | |
| --- | --- |
| CPU | 12th Gen Intel Core i5-1245U, 10 cores, 12 threads |
| Memory | 15.8 GB; the Docker VM gets 8 GB and all 12 threads |
| OS | Windows 11 Pro; Docker Desktop 29.7.2 on WSL2 (kernel 6.18.33.2) |
| Java | Temurin 21.0.12 in every service image; the load generator on the same JDK, on the host |
| Stack | `compose.yaml` as committed: two `postgres:16-alpine` with default settings, `apache/kafka:4.2.1`, `redis:7.4-alpine`, **one** API service instance, the relay, the projection |

Everything ran on this one machine at once: Gatling, three JVMs, two Postgres servers, Kafka and
Redis. The load generator competes for the CPU it is measuring.

## The run

[`nostro-load-test/run.sh`](../../../nostro-load-test/run.sh), defaults: 32 virtual users per arm,
each looping for 60 s, the arms one after the other, each in a Tenant of its own made through the
control plane. Every request is a debit or credit of 1.00 USD between a Constrained Account and an
unconstrained counterparty, chosen at random; each Constrained Account starts with 5.00, so the walk
keeps reaching zero and the floor refuses throughout. *Contended*: one Constrained Account. *Spread*:
64. For the run only, the Tenant's rate limit is raised out of the way
([`compose.load.yaml`](../../../nostro-load-test/compose.load.yaml)), and the API service records JFR.

## Results

| | Contended (1 Account) | Spread (64 Accounts) |
| --- | ---: | ---: |
| Requests | 6,346 | 12,769 |
| Throughput | **105.8 /s** | **212.8 /s** |
| p50 | 275 ms | 142 ms |
| p95 | 550 ms | 250 ms |
| p99 | **741 ms** | **320 ms** |
| Max | 1,305 ms | 659 ms |
| `201` recorded | 6,311 | 12,445 |
| `422 insufficient-balance` | 35 | 324 |
| Anything else | **0** | **0** |

Throughput is requests over the arm's 60 s; Gatling's own "mean count/s" divides by the whole
two-minute simulation. Raw: [`outcomes.tsv`](outcomes.tsv), [`gatling-contended.tsv`](gatling-contended.tsv),
[`gatling-spread.tsv`](gatling-spread.tsv).

**The SQLSTATE histogram is empty.** `nostro.ledger.sql.failures`, which counts every database refusal
the writer meets whether or not it is retried, was never created: no `40001`, `40P01`, `55P03` or
`23505`, and no `23514` either. That last one needs saying precisely. The floor refuses first with the
guarded `UPDATE` (zero rows, answered as a value), so the `CHECK` constraint behind it — the SQLSTATE
`23514` of ADR-0004 — never had to fire. Every refusal, all 359 of them, was that floor:
`nostro_ledger_floor_rejections_total` is 359 = 35 + 324, matching the clients' count exactly. The
server timed 19,180 writes: the 19,115 requests plus the 65 Entries that funded the Accounts.
[`api-metrics-after.txt`](api-metrics-after.txt).

**What one hot row costs, on this machine: half the throughput and 2.3× the p99.** Nothing failed to
pay for it. Every write in both arms moves a Constrained Account, so the server's own timer
(`nostro.ledger.entries.write`, tagged `constrained="true"` throughout, mean 109 ms including the wait
for a pooled connection) does not split the arms; the two arms are the comparison. The
constrained/unconstrained split that metric exists for was not exercised by this run.

## JFR

A 162 s recording of the API service over the whole run, startup included
([`jfr-summary.txt`](jfr-summary.txt) and the `jfr view` outputs beside it).

- **Not CPU-bound.** No method holds more than 3% of execution samples; the top of
  [`jfr-hot-methods.txt`](jfr-hot-methods.txt) is map lookups, `ThreadLocal`s and Micrometer's
  observation-handler dispatch — framework plumbing, spread thin. The time goes to waiting on the
  database.
- **GC is negligible.** 162 young-generation pauses, 1.44 s in total over 162 s
  ([`jfr-gc-pauses.txt`](jfr-gc-pauses.txt)).
- **In-JVM contention is warm-up.** The longest monitor waits are Jackson's serializer cache filling on
  first use, tens of milliseconds, a handful of times ([`jfr-contention-by-site.txt`](jfr-contention-by-site.txt)).
- **Leads not followed.** `Class.copyMethods` in the hot methods and `java.lang.reflect.Method` near the
  top of the allocation view suggest something reflects per request; and both Jackson 2
  (`com.fasterxml`) and Jackson 3 (`tools.jackson`) serialize at runtime, so some dependency still
  brings the old line. Neither is investigated here.

## The connection pool

Hikari's default, ten connections, for 32 concurrent writers. The pool, not the row lock, is where
most requests wait: the longest wait for a connection was 0.58 s, and none timed out
(`hikaricp_connections_acquire_seconds_max`, `hikaricp_connections_timeout_total`).

That is the right place for them to wait, and the reasoning is the reason not to raise it. In the
contended arm every transaction queues on the one row, and only one holds it at a time; at 105.8
commits a second each holds it for about 9.5 ms. Ten connections means at most ten transactions in
that queue, so the last in line waits at most about 95 ms for the lock — an order of magnitude inside
the request path's `lock_timeout` of 1 s, which is why no `55P03` appeared. Thirty-two connections
would move 22 more waiters from Hikari's queue, where waiting costs nothing, into Postgres's lock
queue, where each holds a backend and an open transaction, and would put the last waiter near 300 ms:
no more throughput, because the row serializes them anyway, and a third of the way to turning lag into
lock timeouts. The spread arm could use more connections, but on this machine the database and the
load generator share twelve threads, and the pool is not what bounds it. A pool larger than the lock
can drain belongs in front of a database with more cores, measured there.

One number the arithmetic does not explain: the longest any connection was held was 2.7 s
(`hikaricp_connections_usage_seconds_max`), well past the ~95 ms queue above and past `lock_timeout`
itself, yet no `55P03` was raised. A transaction does not wait on the lock for most of its time, so the
likeliest cause is the first requests of the run meeting a cold JVM — but that is a guess, and it is
not investigated here.

## Caveats

- One run per arm, on a laptop running the load generator beside the system. Throughput here is not
  a capacity figure for anything but this laptop on this afternoon.
- One API instance. ADR-0009 separates the relay so that the API can scale out; this run did not.
- Every trace is sampled and logs are JSON, as the stack ships; both cost something.
- Postgres runs with its image's defaults (`shared_buffers` 128 MB), in a container, on a VM disk.
- Spread across 64 Accounts is not free of contention: at 212 requests a second, two writers do meet
  on the same Account sometimes.
- Rate limiting was raised out of the way; with the compose defaults (200 burst, 100 a second) the
  single Tenant each arm runs as would have been throttled first.
