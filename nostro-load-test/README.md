# Load test

Gatling, written in Java, run by hand against the compose stack. Never run by CI: a throughput number
from a shared runner is not defensible, and a threshold on one is a flaky test.

```sh
nostro-load-test/run.sh                      # 32 users, 60 s per arm, 64 Accounts in the spread arm
nostro-load-test/run.sh -Dusers=64 -DarmSeconds=120
```

Needs Docker and a JDK 21 on the `PATH` (for `jfr`). The script lays
[`compose.load.yaml`](compose.load.yaml) over the stack — the Tenant budget raised out of the way, and a
flight recording of the API service written when it stops — then runs
[`ContentionSimulation`](src/test/java/io/nostro/load/ContentionSimulation.java) and leaves everything
in `target/load-results/`:

| | |
| --- | --- |
| `outcomes.tsv` | every response, by arm, status and Problem type |
| `metrics-before.txt`, `metrics-after.txt` | the API's Prometheus output either side of the run, for the SQLSTATE histogram and write latency |
| `api.jfr`, `jfr-*.txt` | the recording, and `jfr summary` and `jfr view` over it |
| `../gatling/*/index.html` | Gatling's report: throughput and percentiles per arm |

**The two arms**, at the same concurrency, one after the other, each in a Tenant of its own: *contended*,
every request a debit or credit of one Constrained Account; *spread*, the same requests across 64. The
claim is not a number: it is that **every refusal in the contended arm is the floor and nothing else**.

Results are committed as dated notes under [`docs/results/`](../docs/results/), each naming the machine.
