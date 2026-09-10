# What the test suite can afford on free CI

Research note on the compute budget available to a zero-cost GitHub Actions pipeline for a public
repository, and what a Testcontainers-based Spring Boot suite actually spends against it.

The decision this note exists to inform: **how much can the suite demonstrate on every push, and does
the "Postgres + Kafka + Redis + more than one deployable" requirement survive contact with a free CI
runner?**

Investigated against primary sources (GitHub Actions docs, `actions/runner-images`, Testcontainers,
Spring Boot, Spring Framework, Spring for Apache Kafka). Every non-obvious claim is cited inline;
timings and memory figures that no document states are **marked as estimates**. See
[Sources](#sources).

---

## Executive summary

- **Yes, it survives — with room to spare.** The binding constraint is not memory and not minutes.
  Standard GitHub-hosted runners are *"free and unlimited on public repositories"*, and a public-repo
  `ubuntu-latest` runner has **4 CPU / 16 GB RAM / 14 GB SSD** — double the private-repo runner.
  Postgres + Kafka + Redis together are an estimated **~0.5–0.9 GB** resident. That is roughly 5% of
  the runner's RAM.
- **The real costs are image pull and Spring context rebuilds, not container RAM.** The runner image
  ships **no cached Docker images**, so every image is pulled cold on every run. And a Spring Boot
  suite that accidentally builds eight application contexts pays eight full application startups —
  which will exceed the container bill several times over.
- **`withReuse(true)` is explicitly not for CI.** Testcontainers states plainly: *"Reusable containers
  are not suited for CI usage"*. It also requires opt-in through the developer's
  `~/.testcontainers.properties`, which cannot be committed, and the runner is a fresh VM anyway. Use
  **singleton containers** instead — that is the mechanism that actually helps on CI.
- **Kafka is the expensive one. Buy it once.** A JVM broker is an estimated 5–15s to start and
  ~350–600 MB resident. Start it once for the whole suite via a singleton, not per test class, and
  consider the native image (`apache/kafka-native`) if startup ever becomes irritating.
- **Recommended shape: 2 jobs, ~6–9 minutes wall clock (estimate).** One fast `build + unit` job and
  one `integration` job that starts all three containers once. Cache `~/.m2` via `setup-java`. Do not
  try to cache Docker layers — see §4.
- **One free lunch, deliberately declined:** the runner image already has **PostgreSQL 16.15 and JDK
  21 preinstalled**. The JDK is welcome. The Postgres is not — using it would diverge from what runs
  locally under `docker compose`, which is the exact thing the CI job exists to demonstrate.

---

## 1. The free budget

### What is free

> *"GitHub Actions usage is **free** for **self-hosted runners** and for **public repositories** that
> use standard GitHub-hosted runners."*
> — [GitHub — About billing for GitHub Actions](https://docs.github.com/en/billing/concepts/product-billing/github-actions)

Restated in the runner reference, unambiguously:

> *"Use of the standard GitHub-hosted runners is free and unlimited on public repositories."*
> — [GitHub — GitHub-hosted runners reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)

The familiar per-plan allowances — GitHub Free 2,000 minutes/month, Pro and Team 3,000, Enterprise
Cloud 50,000 — are **quota for private repositories**. A public repo on standard runners does not draw
from them.

### When a public repo *is* billed

There is exactly one documented circumstance:

> *"Larger runners are always charged for, even when used by public repositories or when you have
> quota available from your plan."*
> — [GitHub — About billing for GitHub Actions](https://docs.github.com/en/billing/concepts/product-billing/github-actions)

**Practical rule: never put a `runs-on` label other than a standard one in this repo's workflows.**
That single line is the whole cost-control policy. Anything reading `ubuntu-latest`, `ubuntu-24.04`,
`ubuntu-22.04` is free; anything naming a larger runner is billed at the per-minute rate (Linux 2-core
is `$0.006`/min).

### The limits that actually bind

| Limit | Documented value | Relevance here |
|---|---|---|
| Job execution time (GitHub-hosted) | *"Each job in a workflow can run for up to 6 hours of execution time."* | Irrelevant — we need minutes, not hours |
| Workflow run time | 35 days; *"This period includes execution duration, and time spent on waiting and approval."* | Irrelevant |
| Concurrent jobs (Free plan, standard runners) | **20** | The real cap. Fan-out is limited to 20 in flight |
| Job matrix | *"A job matrix can generate a maximum of 256 jobs per workflow run."* | Irrelevant |
| `GITHUB_TOKEN` API requests | *"1,000 requests per hour per repository"* | Watch it only if the workflow scripts against the API |
| Cache storage | *"By default, the limit is 10 GB per repository"*, and *"GitHub will remove any cache entries that have not been accessed in over 7 days."* | Bounds the `~/.m2` cache strategy |

— [GitHub — Usage limits](https://docs.github.com/en/actions/reference/limits),
[GitHub — Dependency caching](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)

Cache restore is also branch-scoped: *"Workflow runs can restore caches created in either the current
branch or the default branch (usually `main`)"*, and *"Workflow runs cannot restore caches created for
child branches or sibling branches."* So the first CI run on a new feature branch restores `main`'s
`~/.m2` — which is the behaviour you want, and it needs no configuration.

**Verdict on §1:** minutes are a non-issue. Design for developer patience (a push should turn green in
under ten minutes), not for a quota.

---

## 2. Runner specs

Documented specs for the standard `ubuntu-latest` GitHub-hosted runner
([GitHub — GitHub-hosted runners reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)):

| | Public repository | Private repository |
|---|---|---|
| CPU | **4 cores** | 2 cores |
| RAM | **16 GB** | 8 GB |
| Storage | **14 GB SSD** | 14 GB SSD |
| Cost | free and unlimited | draws on plan minutes, then billed per minute |

Being public is worth **2× the CPU and 2× the RAM**, at zero cost. This project is public, so the
budget below is the 4-core / 16 GB column.

### Docker

Docker is preinstalled. The runner reference page does not say so, but the image manifest does — the
Ubuntu 24.04 runner image lists
([`actions/runner-images` — Ubuntu2404-Readme.md](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)):

- Docker Client **28.0.4**
- Docker Server **28.0.4**
- Docker Compose **2.38.2**
- Docker-Buildx **0.36.1**
- Docker Amazon ECR Credential Helper 0.12.0

GitHub's workflow docs make the platform requirement explicit: *"If your workflows use Docker container
actions, job containers, or service containers, then you must use a Linux runner"* and *"If you are
using GitHub-hosted runners, you must use an Ubuntu runner."*
([GitHub — Communicating with Docker service containers](https://docs.github.com/en/actions/tutorials/communicating-with-docker-service-containers)).
Testcontainers needs a Docker daemon and therefore inherits that constraint: **Ubuntu runners only**.

### Two things already on the runner

Also from the Ubuntu 24.04 manifest:

- **JDK 21.0.12+1** is preinstalled at `JAVA_HOME_21_X64` (17 is the image default). Still use
  `setup-java` for reproducibility and for its Maven cache (§7), but this is why the JDK step costs
  seconds rather than minutes.
- **PostgreSQL 16.15**, with the note *"PostgreSQL service is disabled by default. Use the following
  command as a part of your job to start the service: `sudo systemctl start postgresql.service`"*.

That second one is a genuine free lunch that this project should **decline**. Using the runner's system
Postgres means CI exercises a different Postgres, configured differently, than the one `docker compose`
gives a developer locally. The point of the CI job is "here it is, working" — same images, same
versions, same wiring. Pay the Testcontainers cost and keep parity.

### What is *not* on the runner

The Ubuntu 24.04 image manifest has **no "Cached Docker images" section** — the image ships no
prepulled container images. Every Testcontainers image, plus the Ryuk reaper, is pulled from a registry
on every run. This is the largest fixed cost of the integration job (§3).

---

## 3. Container cost

### Which images

| Service | Testcontainers class | Image the docs use |
|---|---|---|
| Postgres | `PostgreSQLContainer` | docs example is `postgres:9.6.12`; compatible variants listed include `pgvector/pgvector:pg16`, `postgis/postgis:16-3.4-alpine`. **Use `postgres:16-alpine`** — pin the same tag the compose file uses |
| Kafka (KRaft) | **`KafkaContainer`** for `apache/kafka` and `apache/kafka-native`; **`ConfluentKafkaContainer`** for `confluentinc/cp-kafka` 7.4.0+ | docs examples: `apache/kafka-native:3.8.0`, `confluentinc/cp-kafka:7.4.0` |
| Redis | **no first-party Java module** — the docs' own quickstart uses `GenericContainer` | `redis:6-alpine` with `.withExposedPorts(6379)` |

— [Testcontainers — Kafka module](https://java.testcontainers.org/modules/kafka/),
[Testcontainers — PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/),
[Testcontainers — JUnit 5 Quickstart](https://java.testcontainers.org/quickstart/junit_5_quickstart/)

Two things to get right on Kafka. First, KRaft is fine and ZooKeeper is unnecessary: *"KRaft mode was
declared production ready in 3.3.1"*, enabling *"a single node Kafka installation"* without *"external
Zookeeper installation"*. Second, **`org.testcontainers.containers.KafkaContainer` is deprecated** —
pick `KafkaContainer` (Apache images) or `ConfluentKafkaContainer` (Confluent images) to match your
image, and note that KRaft support is documented on the `KafkaContainer` path.

### The cost table

**Everything in the two right-hand columns is an estimate**, not a documented figure. No primary source
states startup times or resident memory for these images; the numbers below are order-of-magnitude
figures for a 4-core runner and should be treated as such. The only cited figures here are the Apache
Kafka image size and the Testcontainers default wait timeout.

| Container | Image pull, cold (estimate) | Start to ready, warm image (estimate) | Resident RAM (estimate) |
|---|---|---|---|
| `postgres:16-alpine` | ~5–10 s (~80–100 MB) | **~1–3 s** | **~30–80 MB** |
| `redis:7-alpine` | ~2–4 s (~15–40 MB) | **< 1 s** | **~10–20 MB** |
| `apache/kafka:3.9` (JVM, KRaft) | ~15–25 s (227.8 MB, cited) | **~5–15 s** | **~350–600 MB** |
| `apache/kafka-native:3.8.0` (GraalVM native) | ~10–20 s | **~1–3 s** | **~150–250 MB** |
| Ryuk reaper | ~1–2 s | ~1 s | ~10 MB |

Testcontainers' documented default patience is the useful anchor: *"Ordinarily Testcontainers will wait
for up to 60 seconds for the container's first mapped network port to start listening."*
([Testcontainers — Waiting for containers to start](https://java.testcontainers.org/features/startup_and_waits/)).
If any of these approach that ceiling, something is wrong — it is not a normal cost.

### Can all three run concurrently? Yes, easily.

Estimated worst case: **~0.9 GB** resident for Postgres + JVM Kafka + Redis + Ryuk, against a documented
**16 GB**. Memory is nowhere near being the constraint. Neither is CPU: three mostly-idle services on 4
cores leave the cores free for the JVM running the tests.

**What actually constrains the integration job, in order:**

1. **Cold image pull — an estimated 25–40 s of every run, unavoidable.** No prepulled images on the
   runner (§2), and Docker-layer caching does not pay it back (§4).
2. **Spring context rebuilds** — see §5. This is the one that grows without bound as the suite grows.
3. **Disk, in the long run.** The documented figure is **14 GB SSD**, and Maven dependencies, build
   output, and several hundred MB of container images all land on it. Fine for three images; worth
   remembering before adding a fourth heavyweight.

**So the answer to the requirement is yes.** Postgres + Kafka + Redis on one free runner is
comfortable. Adding a second *deployable* costs another Spring context, not another set of containers —
which is why §5 matters more than §3.

---

## 4. Reuse and speed: which mechanisms help on CI

### `withReuse(true)` — confirmed, not for CI

The suspicion in the brief is correct, and the documentation says it outright:

> *"Reusable containers are not suited for CI usage"*
> — [Testcontainers — Reusable containers (experimental)](https://java.testcontainers.org/features/reuse/)

Three independent reasons, all documented on that page:

1. It is **opt-in per developer machine**, via `testcontainers.reuse.enable=true` in
   `~/.testcontainers.properties`. That file lives in the developer's home directory, not the repo — it
   cannot be committed, so it cannot be part of the project's CI configuration.
2. Reused containers deliberately outlive the suite: *"Those containers won't stop after all tests are
   finished."* On an ephemeral runner there is no next run to reuse them, so the only effect is
   containers left running until the VM is destroyed.
3. It is experimental, and *"not all Testcontainers features are fully working (e.g., resource cleanup
   or networking)"* — i.e. Ryuk cleanup is degraded, which is precisely the guarantee you want on CI.

`withReuse` is a **local developer inner-loop** feature. Keep it out of the repo, or gate it behind an
environment check so CI never takes that path.

### Singleton containers — the mechanism that *does* help on CI

The documented substitute. Start the container once, in a static initializer on a shared base class:

> *"The singleton container is started only once when the base class is loaded."* … *"At the end of the
> test suite the Ryuk container that is started by Testcontainers core will take care of stopping the
> singleton container."*
> — [Testcontainers — Manual container lifecycle control](https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/)

The scoping rule, and the antipattern to avoid:

> *"If the field is a **static field** then that container will be started once before running all tests
> of the test instance and will be stopped after executing all of them."* … *"If the field is an
> instance field then a new container is started before every test method"* … *"it is recommended to
> define container definitions as **static** fields."*
>
> And the trap: combining a singleton with the JUnit 5 extension means *"containers will be stopped at
> the end of each test class, but the subsequent tests still try to connect to those stopped
> containers…hence the tests will fail."* The fix: *"when using Singleton Containers use a class
> initializer or **@BeforeAll** lifecycle callback method to start the containers instead of using the
> **@Testcontainers** and **@Container** annotations."*
> — [Testcontainers — Container lifecycle guide](https://testcontainers.com/guides/testcontainers-container-lifecycle/)

Note that the Testcontainers quickstart's own Redis example uses a **non-static** `@Container` field —
one Redis per test method. That is a teaching example, not a CI pattern. **Do not copy it.**

### `@ServiceConnection` (Spring Boot 3.1+)

> *"When using Testcontainers, connection details can be automatically created for a service running in
> a container by annotating the container field in the test class."* … *"This is done by automatically
> defining a `Neo4jConnectionDetails` bean which is then used by the Neo4j auto-configuration,
> **overriding any connection-related configuration properties**."*
> — [Spring Boot — Testcontainers support](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)

Requires the `spring-boot-testcontainers` test dependency. Applicable `ConnectionDetails` beans exist
for `JdbcConnectionDetails`, `KafkaConnectionDetails` and `DataRedisConnectionDetails` among others —
i.e. **all three of this project's services are covered**, and none of them need a hand-written
`@DynamicPropertySource` method.

Spring Boot further recommends managing containers **as Spring beans** rather than through the JUnit
extension, for lifecycle ordering:

> *"Container beans are created and started before all other beans. Container beans are stopped after
> the destruction of all other beans."* … Whereas with the JUnit extension, *"It can happen that
> containers are shutdown before the beans relying on container functionality are cleaned up. This can
> lead to exceptions being thrown by client beans, for example, due to loss of connection."*

And crucially for suite speed:

> *"A single test container instance can, and often is, retained across execution of tests from multiple
> test classes."*

That retention is exactly the win — and it is a *consequence of the context cache* (§5). The container
is retained for as long as the context that owns it stays cached.

### `@DynamicPropertySource`

> *"A slightly more verbose but also more flexible alternative to service connections is
> `@DynamicPropertySource`."*
> — [Spring Boot — Testcontainers support](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)

It carries a cost that is easy to trip over here: `@DynamicPropertySource` methods are part of the
**context cache key** (§5). Declaring them per test class, with even trivially different content,
fragments the cache. Declare them **once**, on a shared base class or a single imported
`@TestConfiguration` — or prefer `@ServiceConnection` and avoid the question.

### Docker layer caching on Actions — skip it

There is no documented Actions feature for caching the Docker *image* store between jobs. The available
mechanism is the generic dependency cache, capped at *"10 GB per repository"* with entries *"not been
accessed in over 7 days"* evicted
([GitHub — Dependency caching](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)).

**Judgement, not a citation:** hand-rolling `docker save` → `actions/cache` → `docker load` for these
three images means writing and reading ~400 MB through the cache service to avoid a ~400 MB registry
pull. It usually loses, it always adds workflow complexity, and it competes for the same 10 GB budget
as `~/.m2`. Spend the cache on Maven; take the pull.

### Summary

| Mechanism | Helps locally | Helps on CI | Notes |
|---|---|---|---|
| **Singleton containers** (static, base class) | ✅ | ✅ **the main one** | One container start per JVM, not per class |
| **`@ServiceConnection`** | ✅ | ✅ | Removes hand-written wiring; keeps the cache key uniform |
| **Containers as Spring beans** | ✅ | ✅ | Correct shutdown ordering; retained across test classes |
| **Context cache hygiene** (§5) | ✅ | ✅ **the biggest one** | Dominates as the suite grows |
| **`~/.m2` cache via `setup-java`** | n/a | ✅ | Minutes on a cold run |
| **`@DynamicPropertySource`** | ✅ | ⚠️ | Works, but fragments the context cache if declared per class |
| **`withReuse(true)`** | ✅ | ❌ **documented as unsuitable** | Local inner loop only |
| **Docker layer caching** | n/a | ❌ | Not worth the complexity at this image size |

---

## 5. Spring context caching — where the time actually goes

### The rule

The TestContext framework caches application contexts and keys them on a specific set of inputs
([Spring Framework — Context caching](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)):

- `locations` and `classes` (from `@ContextConfiguration`)
- `contextInitializerClasses`
- **`contextCustomizers`** (from `ContextCustomizerFactory`) — *"this includes `@DynamicPropertySource`
  methods, bean overrides (such as `@TestBean`, `@MockitoBean`, `@MockitoSpyBean` etc.), as well as
  various features from Spring Boot's testing support"*
- `contextLoader`
- `parent` (from `@ContextHierarchy`)
- `activeProfiles` (from `@ActiveProfiles`)
- `propertySourceDescriptors` and `propertySourceProperties` (from `@TestPropertySource`)
- `resourceBasePath` (from `@WebAppConfiguration`)

> *"The size of the context cache is bounded with a default maximum size of 32. Whenever the maximum
> size is reached, a least recently used (LRU) eviction policy is used to evict and close stale
> contexts."*

Configurable via the `spring.test.context.cache.maxSize` JVM system property.

### What causes a rebuild

1. **A different cache key.** Any of the inputs above differing between two test classes yields two
   contexts. In practice the offenders are: a stray `@ActiveProfiles`, a one-off `@TestPropertySource`,
   a `@MockitoBean` on one class, or a `@DynamicPropertySource` method duplicated with a small
   variation.
2. **`@DirtiesContext`.** *"This instructs Spring to remove the context from the cache and rebuild the
   application context before running the next test that requires the same application context."*
3. **LRU eviction** once more than 32 distinct contexts exist.

### Why this dominates container startup

**Estimate, not a citation.** Container startup is paid *once per JVM* if you use singletons (§4).
Context construction is paid *once per distinct cache key* — and that number grows with the number of
test classes that disagree about their configuration, not with the number of services.

Order-of-magnitude arithmetic for this project:

| | Cost | Times paid | Total |
|---|---|---|---|
| Containers (singleton) | ~15–20 s including pull | 1 | ~15–20 s |
| Spring Boot context | ~2–5 s each (estimate) | 1 if disciplined; 10+ if not | ~5 s … ~50 s |

At one context, containers dominate and the suite is fast. At ten contexts, contexts dominate by ~3×
and the suite is annoying. At thirty-plus, the cache begins *evicting and rebuilding* and it is
unusable. The container bill is fixed; the context bill is the one that runs away.

**A second deployable makes this concrete.** Two deployables means at minimum two distinct
`@SpringBootTest` configurations, hence two contexts — legitimate and unavoidable. What is not
legitimate is each deployable's suite fragmenting further into five variants apiece.

### The discipline

- **One base class per deployable**, holding the containers and the
  `@ServiceConnection`/`@DynamicPropertySource` declarations. Everything extends it. Same key, one
  context.
- **Avoid `@MockitoBean` in integration tests.** It is a documented cache-key input; each distinct set
  of overrides is a separate context. Mock in unit tests, where there is no context to rebuild.
- **Avoid `@DirtiesContext`.** If a test needs a clean slate, clean the *data* (truncate, flush Redis,
  use a fresh topic per test), not the context.
- **Measure it.** Set the log level for `org.springframework.test.context.cache` to `DEBUG` — the
  framework reports cache hit/miss/size statistics. This is the single most useful diagnostic when the
  suite gets slow, and it is documented rather than folklore.

---

## 6. Kafka in tests: real broker, embedded broker, or fake

### `EmbeddedKafkaBroker`, per Spring for Apache Kafka

- Ships in `spring-kafka-test`; *"When using Spring Boot, use the `spring-boot-starter-kafka-test`
  dependency instead"*.
- KRaft only, now: *"Since Kafka 4.0 has fully transitioned to KRaft mode, only the
  `EmbeddedKafkaKraftBroker` implementation is now available"*, and *"As of version 4.0, all
  ZooKeeper-related properties have been removed from the `@EmbeddedKafka` annotation"*.
- *"We generally recommend that you use a single broker instance to avoid starting and stopping the
  broker between tests (and use a different topic for each test)."*
- **The catch, for this project:** *"When using `@EmbeddedKafka` with `@SpringJUnitConfig`, it is
  recommended to use `@DirtiesContext` on the test class."* `@DirtiesContext` is exactly what §5 says to
  avoid — it evicts the context and forces a rebuild.
- *"It is recommended to not combine a global embedded Kafka and per-class in a single test suite. Both
  of them share the same system properties, so it is very likely going to lead to unexpected
  behavior."*

— [Spring for Apache Kafka — Testing Applications](https://docs.spring.io/spring-kafka/reference/testing.html)

### The comparison

| | Real container (`KafkaContainer`, KRaft) | `EmbeddedKafkaBroker` | Fake / in-memory producer+consumer |
|---|---|---|---|
| Startup cost | **~5–15 s JVM, ~1–3 s native** (estimate), once per suite | **~2–5 s** (estimate), in-process | ~0 |
| Memory | ~350–600 MB (estimate), separate process | shares the test JVM's heap — competes with it | negligible |
| Needs Docker | yes | no | no |
| Fidelity of broker behaviour | **highest** — the shipped broker binary | high — the real broker classes, embedded | none |
| Catches wrong serializer / topic config | ✅ | ✅ | ❌ |
| Catches consumer-group / rebalance behaviour | ✅ | ✅ (mostly) | ❌ |
| Catches broker config, retention, partition count as deployed | ✅ | ⚠️ defaults differ from your deployment | ❌ |
| Catches network failure modes, TLS, auth | ✅ (with effort) | ❌ | ❌ |
| Catches version skew between client and deployed broker | ✅ **only this one does** | ❌ — broker version is pinned to the `spring-kafka` version | ❌ |
| Context-cache friendliness | ✅ good — singleton lives outside the context | ⚠️ pushed toward `@DirtiesContext` | ✅ |
| Parity with local `docker compose` | ✅ **same image** | ❌ a different broker instance entirely | ❌ |

### What each fails to catch

- **A fake** fails to catch everything about Kafka. It verifies that your code calls a method. It is the
  right tool for testing *business logic that happens to emit an event* — and the wrong tool for
  claiming "Kafka works". It cannot catch a serializer misconfiguration, a topic that does not exist, a
  partition-key bug, or an offset-commit bug.
- **`EmbeddedKafkaBroker`** catches almost all client-side wiring, and is genuinely fast. What it misses
  is *deployment* fidelity: its broker version is whatever `spring-kafka-test` pulls in, its
  configuration defaults are the embedded ones, and nothing about it resembles the container in the
  compose file. If the point of the exercise is "here it is, running the same way it runs for real", the
  embedded broker quietly does not demonstrate that. It also nudges you toward `@DirtiesContext`, whose
  cost lands in §5 rather than here.
- **A real container** catches all of the above and costs an estimated 5–15 s once per suite. Its only
  real failure mode is that it needs Docker — which this project already requires, everywhere.

### Recommendation for this project

**Use a real `KafkaContainer` in KRaft mode, as a singleton, one per suite, on the same image tag as the
compose file.** The project's stated purpose is to demonstrate a working distributed system; a test
suite that runs against a different broker than the demo does undercuts the claim it exists to support.
An estimated 5–15 s, once, is affordable inside a 6-hour job limit on an unmetered runner.

Consider `apache/kafka-native` if that startup ever becomes irritating: it trades a slightly less
representative runtime for an estimated 1–3 s start. Reach for it as an optimisation, not a default.

Keep fakes for **unit** tests of the code that produces or consumes messages. They are a different tool,
not a cheaper version of the same one.

---

## 7. Practical shape of the pipeline

### Two jobs

| Job | Runs | Contains | Wall clock (estimate) |
|---|---|---|---|
| `build` | every push / PR | compile, unit tests (no Docker), static checks | **~2–4 min** |
| `integration` | every push / PR, in parallel with `build` | full Spring Boot suite, all containers started once | **~5–8 min** |

Total wall clock with the two in parallel: **~6–9 minutes (estimate).** A cold `~/.m2` cache adds an
estimated 1–3 minutes on the first run of a branch.

**Two jobs, not one, and not five.** Two gives fast feedback on cheap failures (compile errors, unit
test failures) without waiting for containers, and the 20-job concurrency cap is nowhere in sight.
Splitting the integration suite further would mean paying the ~25–40 s image pull and the container
startup *per shard*, and each shard would rebuild its own Spring contexts from scratch — those costs are
per-job, so sharding a suite this size loses.

**Revisit sharding only when the integration job exceeds ~15 minutes**, and shard along the *deployable*
boundary (each deployable a job, carrying only the containers it needs) rather than arbitrarily, so each
shard carries fewer Spring contexts rather than the same number spread across more machines.

### Caching

Use `actions/setup-java`'s built-in Maven cache rather than hand-wiring `actions/cache`:

```yaml
- uses: actions/setup-java@v4
  with:
    distribution: temurin
    java-version: '21'
    cache: maven
```

Per [`actions/setup-java`](https://github.com/actions/setup-java): `cache: maven` caches the Maven
dependency repository at `~/.m2/repository`; the cache key is built from *"runner OS, architecture,
package manager, and a hash of the dependency files"*, hashing `**/pom.xml`,
`**/.mvn/wrapper/maven-wrapper.properties` and `**/.mvn/extensions.xml` by default, overridable with
`cache-dependency-path` (*"especially"* useful in monorepos — relevant here, since multiple deployables
means multiple `pom.xml` files).

Both jobs should configure this identically so they share one cache entry, comfortably inside the 10 GB
per-repo budget.

### Things to put in the workflow deliberately

- `timeout-minutes` on each job. The platform default is 6 hours; a runaway container wait should fail in
  20 minutes, not hold a job slot for a quarter of a day.
- `concurrency` with `cancel-in-progress: true`, keyed on the ref, so a rapid second push cancels the
  first run rather than queueing against the 20-job limit.
- **Never** a non-standard `runs-on` label (§1). That is the only line that can turn this pipeline from
  free into billed.
- Log `org.springframework.test.context.cache` at DEBUG in the CI profile (§5). When the suite gets slow,
  the answer will be in that output.

---

## Recommended pattern

1. **Public repo, `ubuntu-latest`, standard runner, always.** Free and unlimited, and worth 4 cores /
   16 GB rather than 2 / 8.
2. **All three services as real containers**, on the same image tags as `docker compose`, started as
   **singletons** in one base class per deployable, wired via **`@ServiceConnection`**.
3. **`withReuse(true)` never reaches CI.** Documented as unsuitable; local inner loop only.
4. **Guard the Spring context cache like a budget.** One context per deployable, no `@DirtiesContext`,
   no `@MockitoBean` in integration tests, DEBUG logging on the cache to keep it honest.
5. **Cache `~/.m2` through `setup-java`; do not cache Docker layers.**
6. **Two jobs, ~6–9 minutes (estimate).** Revisit only when the integration job passes ~15 minutes, and
   shard by deployable when you do.

**The requirement survives.** Postgres + Kafka + Redis plus more than one deployable is an estimated
~1 GB of a 16 GB runner and a handful of minutes of an unmetered budget. The thing that will eventually
make this pipeline slow is not the containers — it is an undisciplined set of Spring test
configurations, and that is a design problem the CI runner cannot solve for you.

---

## Sources

- [GitHub — About billing for GitHub Actions](https://docs.github.com/en/billing/concepts/product-billing/github-actions) — Actions is free for public repositories on standard GitHub-hosted runners; larger runners are always charged even on public repos; per-plan included minutes and per-minute rates.
- [GitHub — GitHub-hosted runners reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners) — `ubuntu-latest` specs: 4 CPU / 16 GB / 14 GB SSD (public) vs 2 CPU / 8 GB / 14 GB SSD (private); "free and unlimited on public repositories".
- [GitHub — Usage limits, billing, and administration](https://docs.github.com/en/actions/reference/limits) — 6-hour job limit, 35-day workflow run limit, 20 concurrent jobs on Free, 256-job matrix cap, `GITHUB_TOKEN` 1,000 requests/hour/repo.
- [GitHub — Dependency caching](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching) — 10 GB cache per repository, 7-day eviction of unused entries, branch/default-branch restore scoping.
- [GitHub — Communicating with Docker service containers](https://docs.github.com/en/actions/tutorials/communicating-with-docker-service-containers) — Docker container actions, job containers and service containers require a Linux runner; on GitHub-hosted runners that means an Ubuntu runner.
- [`actions/runner-images` — Ubuntu 24.04 image manifest](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md) — preinstalled Docker Client/Server 28.0.4, Compose 2.38.2, Buildx 0.36.1; JDK 21 at `JAVA_HOME_21_X64`; PostgreSQL 16.15 present but disabled by default; no cached Docker images section.
- [`actions/setup-java`](https://github.com/actions/setup-java) — `cache: maven` caches `~/.m2/repository`; cache key from OS/arch/package manager plus a hash of `**/pom.xml`, wrapper and extensions files; `cache-dependency-path` override.
- [Testcontainers for Java — Reusable containers (experimental)](https://java.testcontainers.org/features/reuse/) — "Reusable containers are not suited for CI usage"; opt-in via `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`; containers are not stopped after tests; resource cleanup and networking not fully working.
- [Testcontainers for Java — Manual container lifecycle control](https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/) — singleton container pattern; started once when the base class loads; Ryuk stops it at the end of the suite.
- [Testcontainers — Container lifecycle guide](https://testcontainers.com/guides/testcontainers-container-lifecycle/) — static field = once per class, instance field = once per method; define containers as static; do not combine singletons with `@Testcontainers`/`@Container`.
- [Testcontainers for Java — Kafka module](https://java.testcontainers.org/modules/kafka/) — `KafkaContainer` (apache/kafka, apache/kafka-native) vs `ConfluentKafkaContainer` (confluentinc/cp-kafka 7.4.0+); legacy `org.testcontainers.containers.KafkaContainer` deprecated; KRaft production-ready since 3.3.1, no ZooKeeper needed.
- [Testcontainers for Java — PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/) — `PostgreSQLContainer` and compatible image variants.
- [Testcontainers for Java — JUnit 5 Quickstart](https://java.testcontainers.org/quickstart/junit_5_quickstart/) — Redis via `GenericContainer(DockerImageName.parse("redis:6-alpine")).withExposedPorts(6379)`; per-method lifecycle of a non-static `@Container` field.
- [Testcontainers for Java — Waiting for containers to start](https://java.testcontainers.org/features/startup_and_waits/) — default 60-second wait for the first mapped port to start listening; wait and startup-check strategies.
- [Docker Hub — `apache/kafka`](https://hub.docker.com/r/apache/kafka) — single-node KRaft combined mode; port 9092; latest image 227.8 MB.
- [Docker Hub — `postgres`](https://hub.docker.com/_/postgres) — image variants including `16-alpine`; `shm_size` guidance for compose.
- [Spring Boot — Testcontainers support](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html) — `@ServiceConnection` auto-creates `ConnectionDetails` beans that override connection properties; requires `spring-boot-testcontainers`; containers as Spring beans start before and stop after all other beans; `@DynamicPropertySource` as the more verbose, more flexible alternative; container instances retained across test classes.
- [Spring Framework — Context caching](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html) — the full context cache key; `contextCustomizers` includes `@DynamicPropertySource` methods and bean overrides; default max size 32 with LRU eviction; `spring.test.context.cache.maxSize`; `@DirtiesContext` semantics; DEBUG logging for cache statistics.
- [Spring for Apache Kafka — Testing Applications](https://docs.spring.io/spring-kafka/reference/testing.html) — `spring-kafka-test` / `spring-boot-starter-kafka-test`; KRaft-only `EmbeddedKafkaKraftBroker` since 4.0; single broker instance recommended; `@DirtiesContext` recommended with `@SpringJUnitConfig`; do not mix global and per-class embedded brokers.
