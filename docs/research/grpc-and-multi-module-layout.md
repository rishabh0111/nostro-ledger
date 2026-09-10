# gRPC between the API service and the projection service, and the Maven layout that carries it

Research note on wiring a synchronous internal call from Nostro's **API service** to its **projection
service** over gRPC, and on the Maven reactor, build plugins and `docker compose` arrangement that
has to exist around it.

Investigated against primary sources (Spring, gRPC, Maven Central metadata and published POMs,
Micrometer, Docker). Every non-obvious claim is cited inline; see [Sources](#sources). Where the
documentation does not settle a question it is marked **needs a test** or **estimate** rather than
asserted.

Sibling notes: [`rls-pooling.md`](rls-pooling.md) (Tenant context, the two-role split, migrations on a
direct connection), [`ci-and-testcontainers-budget.md`](ci-and-testcontainers-budget.md) (the Spring
context cache, which governs §7) and [`ordering-and-watermarks.md`](ordering-and-watermarks.md) (the
relay and what a Balance reflects). This note assumes all three and does not restate them.

---

## Executive summary

- **The official library is GA on the wrong Spring Boot.** Spring gRPC reached 1.0 on 2025-12-04, but
  1.x depends on **Spring Boot 4** (`spring-grpc-spring-boot-starter:1.0.3` → `spring-boot-starter:4.0.2`).
  Boot 3.x gets **0.12.0** (→ `spring-boot-starter:3.5.6`), which has had no release since
  2025-10-24. Use it, and treat it as frozen: no security patches, so pin grpc-java and
  protobuf-java yourself. The community `net.devh` starter is quieter still (last release 2024-04-14).
- **Two Maven plugins share the artifactId `protobuf-maven-plugin`.** `org.xolstice.maven.plugins`
  (0.6.1, **2018**) is what every tutorial and grpc-java's own README shows;
  **`io.github.ascopes` (5.1.8, 2026)** is the maintained one. The Ascopes plugin resolves `protoc`
  and `protoc-gen-grpc-java` from Maven Central with an auto-detected OS/CPU classifier, so
  `os-maven-plugin` and `build-helper-maven-plugin` both become unnecessary, and a clean
  `mvn verify` on `ubuntu-latest` needs no extra setup beyond Java 21 and Maven 3.9+.
- **The `.proto` lives in one module both sides depend on** — stubs only, no domain, no Spring. Nine
  modules total; the load-bearing rules are that the projection service may not depend on the ledger's
  persistence module, and that `balance-proto` may not depend on `domain`.
- **A repackaged library jar is undependable**, by design: *"application classes are packaged in
  `BOOT-INF/classes` so that the dependent module cannot load a repackaged jar's classes."* Put
  `spring-boot-maven-plugin` in `<pluginManagement>`, execute `repackage` only in the three service
  modules, and add a one-line CI guard that greps library jars for `BOOT-INF/`.
- **`spring.threads.virtual.enabled` almost certainly does not touch the gRPC server** — it configures
  Boot's own executors, and a Spring gRPC server is Netty, not a servlet container. grpc-java documents
  no virtual-thread support either way. Both halves are **unverified** and cheap to test.
- **Trace context crosses in gRPC metadata** — Micrometer's observation contexts are literally typed on
  `io.grpc.Metadata` — but the interceptors are **manual**, not auto-configured. The **Tenant** should
  travel the same way: a metadata key plus a server interceptor, fail-closed, never a field on every
  request message and never Micrometer baggage.
- **Test mostly without Spring.** In-process transport in plain JUnit 5 costs zero application
  contexts; `@AutoConfigureInProcessTransport` is a context-cache key input, so it belongs on one base
  class per deployable, not scattered.
- **Three services under compose:** long-form `depends_on: condition: service_healthy` everywhere, a
  **gRPC** healthcheck for the projection (it serves no HTTP), multi-stage builds so a clean clone works,
  and one short-lived migration container **per database** gated by `service_completed_successfully`.

---

## 1. gRPC in Spring Boot 3.x as of 2026

### The awkward fact first

**Spring gRPC went GA — and left Spring Boot 3.x behind in the same move.** Both halves matter, and
the second is the one that decides this project.

The official project is [Spring gRPC](https://spring.io/projects/spring-grpc)
(`org.springframework.grpc`), led from Broadcom/Spring. Its release history, from the GitHub release
list and Maven Central metadata:

| Line | Latest | Released | Spring Boot it pulls |
|---|---|---|---|
| 0.x (milestone line) | **0.12.0** | 2025-10-24 | `spring-boot-starter` **3.5.6** |
| 1.0.x | 1.0.0 GA 2025-12-04, **1.0.3** | 2026-05-27 | `spring-boot-starter` **4.0.2** |
| 1.1.x | **1.1.1** | 2026-08-21 | Spring Boot **4.1** |

Verified directly from the published POMs: `spring-grpc-server-spring-boot-starter:0.12.0` declares
a compile dependency on `org.springframework.boot:spring-boot-starter:3.5.6`, while
`spring-grpc-spring-boot-starter:1.0.3` declares `spring-boot-starter:4.0.2`. The 1.0.x [system
requirements](https://docs.spring.io/spring-grpc/reference/system-requirements.html) state Java 17+
and Spring Framework 7.0.1+, i.e. the Boot 4 stack. The project's own announcement is explicit that
this was deliberate: *"The new release will be dependent on Spring Boot 4.0, instead of being a
dependency of Spring Boot as originally planned"* ([Spring gRPC Next Steps for
1.0.0](https://spring.io/blog/2025/11/05/spring-grpc-next-steps/)).

So there is no version of Spring gRPC that is both GA and Boot-3-compatible. Boot 3.x gets **0.12.0**,
which is a pre-1.0 release with no successor: the 0.x line has had no release since 2025-10-24, and
everything since has gone into the Boot 4 line. Treat 0.x as **frozen, not maintained** — the same
blog's reassurance is about migrating *off* it (*"There should be minimal disruption for projects
already using version 0.x"*), not about continuing on it.

### The community options

- **`net.devh` / [grpc-ecosystem/grpc-spring](https://github.com/grpc-ecosystem/grpc-spring)** — the
  starter everyone's older blog posts use, donated from `yidongnan/grpc-spring-boot-starter` into the
  grpc-ecosystem org. Last release **3.1.0.RELEASE, 2024-04-14**; last commit on `master`
  **2024-10-27**. Its README states it was *"compiled with spring-boot `3.2.4` and spring-cloud
  `2023.0.0`"*. There is no deprecation notice, but two years without a release against a stack that
  has since shipped Boot 3.3, 3.4, 3.5 and 4.x is a dormancy signal, not a stability signal. It is
  also Boot-3-only in practice.
- **[`io.github.danielliu1123:grpc-starter`](https://github.com/DanielLiu1123/grpc-starter)** — an
  actively maintained community fork whose version numbers track Boot's: `3.5.5` for Boot 3.5, `4.x`
  for Boot 4, latest **4.1.0 on 2026-06-20** (Maven Central metadata `lastUpdated 20260620`). Alive,
  but a single-maintainer project.

### Recommendation

**Use Spring gRPC 0.12.0 on Boot 3.5.x, and treat the Boot 4 / Spring gRPC 1.x upgrade as the exit
path.** Reasoning:

1. The alternative that is *maintained* (`grpc-starter`) is one person's project; the alternative
   that is *popular* (`net.devh`) has been quiet for two years. Neither is safer than a frozen
   Spring-team artifact.
2. 0.12.0 pins grpc-java **1.76.0** and protobuf-java **4.32.1** (from
   `spring-grpc-dependencies:0.12.0`), both recent. The real work — the generated stubs, the service
   implementations, the interceptors — is grpc-java API, not Spring gRPC API, so the amount of code
   coupled to the starter is small: a few properties, `@GrpcService`-style registration, and client
   channel configuration.
3. The migration to 1.x is the same migration as Boot 3 → Boot 4, which this project will face
   anyway.

**Honest read on the risk.** This is a young, officially-branded project whose GA release is on a
Boot version we are not on. Picking 0.12.0 means depending on an artifact that will receive **no
security patches**: any CVE in grpc-java or protobuf-java has to be fixed by overriding the version
in our own `dependencyManagement`, not by bumping the starter. Budget for that override, and keep
grpc-java usage idiomatic enough that swapping the starter out for hand-written
`ServerBuilder`/`ManagedChannelBuilder` beans is a day's work rather than a rewrite. **Needs a
test:** that Spring gRPC 0.12.0 actually runs clean on Boot 3.5.x *later* than 3.5.6 — the version it
was built against — since that is the only compatibility claim we would be leaning on.

## 2. Protobuf under Maven

### The two plugins with the same artifactId

They differ only in `groupId`, and copy-paste does not warn you:

| Coordinates | Latest | Last published |
|---|---|---|
| `org.xolstice.maven.plugins:protobuf-maven-plugin` | **0.6.1** | **2018-10-01** |
| `io.github.ascopes:protobuf-maven-plugin` | **5.1.8** | **2026-08-10** |

(Maven Central `maven-metadata.xml` for each.) The Xolstice plugin is the one every tutorial and
Stack Overflow answer uses — including **grpc-java's own README**, which still shows
`org.xolstice.maven.plugins:protobuf-maven-plugin:0.6.1` ([grpc-java
README](https://github.com/grpc/grpc-java/blob/master/README.md)). It has had no release in eight
years. The Ascopes plugin is the maintained successor and is what to use.

### The `protoc` classifier problem

`protoc` is a native binary. Google publishes it to Maven Central as an artifact of type `exe` with
an OS/architecture **classifier** — for `com.google.protobuf:protoc:4.32.1` the published classifiers
are `linux-x86_64`, `linux-aarch_64`, `linux-x86_32`, `linux-ppcle_64`, `linux-s390_64`,
`osx-aarch_64`, `osx-x86_64`, `osx-universal_binary`, `windows-x86_64`, `windows-x86_32` (directory
listing on `repo1.maven.org`). `io.grpc:protoc-gen-grpc-java` — the codegen plugin that turns
`service` blocks into stubs — is published the same way.

Maven has no built-in property for "which of those am I". That is the entire reason
`kr.motd.maven:os-maven-plugin` exists: registered as a build **extension**, it sets
`${os.detected.classifier}` so the coordinate can be written once. grpc-java's README shows exactly
this:

```xml
<extension>
  <groupId>kr.motd.maven</groupId><artifactId>os-maven-plugin</artifactId><version>1.7.1</version>
</extension>
...
<protocArtifact>com.google.protobuf:protoc:3.25.8:exe:${os.detected.classifier}</protocArtifact>
<pluginArtifact>io.grpc:protoc-gen-grpc-java:1.84.0:exe:${os.detected.classifier}</pluginArtifact>
```

`os-maven-plugin`'s own latest is **1.7.1 (2022-11-07)** — stable, but another unmaintained
dependency in the critical path of the build.

**The Ascopes plugin removes the need for it.** Its plugin `classifier` property is documented as
*"Defaults to an OS and CPU-specific string matching the conventions used by `protoc`"*
([Using protoc Plugins](https://ascopes.github.io/protobuf-maven-plugin/using-protoc-plugins.html)),
and `protoc` itself is selected by version alone. So the config is architecture-free:

```xml
<plugin>
  <groupId>io.github.ascopes</groupId>
  <artifactId>protobuf-maven-plugin</artifactId>
  <version>5.1.8</version>
  <configuration>
    <protoc kind="binary-maven"><version>${protobuf.version}</version></protoc>
    <plugins>
      <plugin kind="binary-maven">
        <groupId>io.grpc</groupId>
        <artifactId>protoc-gen-grpc-java</artifactId>
        <version>${grpc.version}</version>
      </plugin>
    </plugins>
  </configuration>
  <executions><execution><goals><goal>generate</goal></goals></execution></executions>
</plugin>
```

Set `${protobuf.version}` and `${grpc.version}` from the Spring gRPC BOM's own values (0.12.0 pins
protobuf-java **4.32.1** and grpc **1.76.0**) so the generated code and the runtime agree.

### How generated sources reach the compile path

The `generate` goal binds to **`generate-sources`**, reads `src/main/protobuf` (with `src/main/proto`
accepted as a migration fallback), and writes to `target/generated-sources/protobuf`. The parameter
`registerAsCompilationRoot` **defaults to `true`**, which *"allows `maven-compiler-plugin` to detect
and compile generated code"* ([generate
mojo](https://ascopes.github.io/protobuf-maven-plugin/generate-mojo.html)). `generate-test` is the
test-scoped twin (`src/test/protobuf` → `target/generated-test-sources/protobuf`) and does not run by
default ([Basic Usage](https://ascopes.github.io/protobuf-maven-plugin/basic-usage.html)).

So **no `build-helper-maven-plugin` is needed.** That is the other piece of folklore worth dropping:
it was required with older plugins and is not required here.

### Does a clean `mvn verify` work on `ubuntu-latest` with nothing installed?

**Yes, with two conditions, both satisfied by this project.**

- The plugin requires *"Apache Maven 3.9 or newer"* and *"Java 17 or newer"*
  ([Requirements](https://ascopes.github.io/protobuf-maven-plugin/requirements.html)). We are on
  Java 21; `setup-java` plus the Maven wrapper covers this. Pin the wrapper.
- `protoc` and `protoc-gen-grpc-java` are resolved **as ordinary Maven artifacts** — no `apt install
  protobuf-compiler`, no Docker, no `PATH` requirement. GitHub's `ubuntu-latest` is glibc x86_64, for
  which both binaries publish a classifier.

Two real caveats, cited rather than assumed:

- grpc-java's README warns that *"The prebuilt `protoc-gen-grpc-java` binary uses glibc on Linux"* and
  points Alpine users at the distro package. Irrelevant on `ubuntu-latest`; relevant if a Dockerfile
  ever tries to build (rather than merely run) on `eclipse-temurin:21-jre-alpine`. Build on a glibc
  image.
- Apple Silicon and `ubuntu-24.04-arm` need `*-aarch_64`, which both artifacts publish — the Ascopes
  plugin picks it automatically. The Xolstice/`os-maven-plugin` route historically needed a manual
  override here, which is one more reason not to take it.

**Needs a test.** The claim "clean checkout, `mvn -B verify`, green on a cold `~/.m2`" is exactly the
kind of thing that is true until someone adds a `.proto` import or a second module. Make it a CI job
that runs on a runner with no cache, not an assumption.

## 3. Module layout

### Where the `.proto` lives

Both sides need the generated stubs: the projection service extends the generated
`…ImplBase` to serve, the API service injects the generated blocking or future stub to call. So the
`.proto` and its generated code belong in **one module that both depend on**, built once, published
as a plain jar.

The alternative — each side keeping its own copy of the `.proto` and generating separately — is what
you do across organisational boundaries where the two sides ship independently. Inside one Maven
reactor it buys nothing and costs you the guarantee that the two copies are the same file. Put it in
one place.

The module contains **only** `src/main/protobuf/**.proto`, the protobuf plugin, and the runtime
dependencies the generated code needs (`protobuf-java`, `grpc-protobuf`, `grpc-stub`,
`grpc-api`, plus `javax.annotation-api`/`tomcat-annotations-api` for the `@Generated` annotation on
the generated stubs). **No Spring, no domain classes, no `Account` entity.** The moment a hand-written
class lands there, both deployables inherit it.

### The reactor

Nine modules, one aggregator. Maven's `<modules>` in a `packaging=pom` aggregator builds them in
dependency order — Maven *"sorts the projects to ensure dependencies are built before the projects
that need them"* ([Maven — Guide to Working with Multiple
Modules](https://maven.apache.org/guides/mini/guide-multiple-modules-4.html)).

```
nostro-parent                (pom)  — dependencyManagement, pluginManagement, BOM imports
├── nostro-domain            (jar)  — Entry, Posting, Account, Amount, Currency, Tenant;
│                                     the balancing rule. No Spring, no JPA, no Kafka.
├── nostro-persistence       (jar)  — Hibernate entities, repositories, RLS tenant hook,
│                                     Flyway migrations for the ledger database
├── nostro-outbox            (jar)  — the outbox row shape and the Kafka event contract
│                                     (schema of what the relay publishes)
├── nostro-balance-proto     (jar)  — THE .proto + generated stubs. Nothing else.
├── nostro-grpc-common       (jar)  — Tenant metadata key, client + server interceptors,
│                                     status/exception mapping. Depends on grpc only.
├── nostro-api-service       (jar, executable)  — HTTP; records Entries; serves reads;
│                                                 gRPC *client* of the projection
├── nostro-outbox-relay      (jar, executable)  — single instance; outbox → Kafka, in order
├── nostro-projection-service(jar, executable)  — Kafka consumer; own Postgres; gRPC *server*
└── nostro-test-support      (jar, test-scoped) — Testcontainers singletons, fixture builders
```

### Dependency graph, and what each module may depend on

```
                       nostro-domain
                      /      |       \
        nostro-persistence   |    nostro-outbox
                  \          |        /      \
                   \         |       /        \
                nostro-api-service   nostro-outbox-relay
                        |    \
                        |     \
        nostro-balance-proto   nostro-grpc-common
                        |     /
                nostro-projection-service ── nostro-domain (Amount, Currency, Tenant)
                                          ── its own persistence (see below)
```

| Module | May depend on | Must **not** depend on |
|---|---|---|
| `nostro-parent` | nothing (it is a POM) | — |
| `nostro-domain` | nothing but the JDK | Spring, Hibernate, gRPC, protobuf |
| `nostro-persistence` | `domain`, Hibernate, Flyway, Spring Data | gRPC, protobuf, Kafka |
| `nostro-outbox` | `domain` | the projection's schema |
| `nostro-balance-proto` | protobuf + grpc runtime only | `domain`, Spring, anything of ours |
| `nostro-grpc-common` | grpc-api, Micrometer Tracing API | `domain`, `balance-proto` |
| `nostro-api-service` | `domain`, `persistence`, `outbox`, `balance-proto`, `grpc-common`, Boot web | `projection-service` |
| `nostro-outbox-relay` | `domain`, `outbox`, Boot, Kafka client, JDBC | `balance-proto`, `persistence`'s JPA layer, `api-service` |
| `nostro-projection-service` | `domain`, `outbox`, `balance-proto`, `grpc-common`, Boot, Kafka, its own JPA/JDBC | `persistence` (that is the *ledger* database), `api-service` |
| `nostro-test-support` | Testcontainers, JUnit; nothing of ours | any service module (that inverts the graph) |

Three of those rows are the ones that actually earn their keep:

- **`projection-service` must not depend on `persistence`.** The projection maintains Account
  balances *in its own Postgres database*. If it can see the ledger's entities and repositories, the
  first performance problem will be "solved" by reading straight from the ledger, and the whole
  asynchronous design is gone. Enforce it as a dependency rule, not a convention. Two separate Flyway
  migration sets, one per database.
- **`balance-proto` must not depend on `domain`.** It is a wire contract. If a change to `Amount`
  can break the build of the proto module, the contract has stopped being a contract — and see
  `ordering-and-watermarks.md` on the API being *"what allows the internals … to change later without
  an API version."*
- **`grpc-common` must not depend on `balance-proto`.** It carries the Tenant metadata key and the
  interceptors, which are about *any* call, not about the balance service. Keeping it separate is
  what lets a second gRPC service appear without a circular dependency.

`nostro-grpc-common` is arguably over-engineering at three deployables and two of them talking. It is
justified only because the interceptors are the tenant-isolation boundary (§6) and want to be in one
place with their own tests rather than duplicated. If it stays at two classes, fold it into
`balance-proto` and accept the coupling.

### Parent versus BOM

Keep the aggregator (`<modules>`) and the dependency parent as the **same POM** here — a nine-module
single-repo reactor does not need them split. Import `spring-boot-dependencies` and
`spring-grpc-dependencies` under `<dependencyManagement><scope>import</scope>` rather than using
`spring-boot-starter-parent` as the actual parent, so `nostro-parent` stays free to declare its own
`pluginManagement` and its own version.

## 4. Fat jars versus plain jars

### The trap, stated by the documentation itself

`spring-boot-maven-plugin`'s `repackage` goal *"repackages existing JAR and WAR archives so that they
can be executed from the command line using `java -jar`"*, and *"the original (that is non-executable)
artifact is renamed to `.original` by default"*. The consequence for a library module is spelled out
in the plugin's own packaging guide:

> *"By default, the `repackage` goal replaces the original artifact with the executable one. That is a
> sane behavior for modules that represent an application but if your module is used as a dependency
> of another module, you need to provide a classifier for the repackaged one. The reason for that is
> that **application classes are packaged in `BOOT-INF/classes`** so that the dependent module cannot
> load a repackaged jar's classes."*
> — [Spring Boot Maven Plugin — Packaging](https://docs.spring.io/spring-boot/maven-plugin/packaging.html)

That is the failure mode in full. A repackaged `nostro-balance-proto.jar` still exists, still installs
to `~/.m2`, still resolves — and the API service compiling against it cannot find
`BalanceServiceGrpc`, because the classes moved to `BOOT-INF/classes` where javac does not look. The
error is `cannot find symbol` on a class you can see with `unzip -l`. It costs an afternoon the first
time.

### The configuration

**Do not put `spring-boot-maven-plugin` in the parent's `<build><plugins>`.** That block is inherited
by every child, which repackages `domain`, `persistence`, `balance-proto` and `grpc-common` too.
Declare it in the parent's **`<pluginManagement>`** (version and shared config only), and add the
execution in each of the three service modules:

```xml
<!-- nostro-parent: pluginManagement — configuration, no execution -->
<plugin>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-maven-plugin</artifactId>
  <configuration><layers><enabled>true</enabled></layers></configuration>
</plugin>

<!-- nostro-api-service / -outbox-relay / -projection-service: build/plugins -->
<plugin>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-maven-plugin</artifactId>
  <executions><execution><goals><goal>repackage</goal></goals></execution></executions>
</plugin>
```

If the plugin is nonetheless inherited — most commonly because the reactor uses
`spring-boot-starter-parent`, which defines the `repackage` execution for you — turn it off per
library module. Either configuration form works:

| Form | Effect |
|---|---|
| `<configuration><skip>true</skip></configuration>` on the plugin, or `-Dspring-boot.repackage.skip=true` | No repackaging at all. The module's jar **is** the plain jar. **Use this for library modules.** |
| `<configuration><classifier>exec</classifier></configuration>` | Plain jar keeps the default coordinates; the fat jar is attached as `…-exec.jar`. Use when a module must be *both* runnable and dependable. |

The `skip` parameter is `boolean`, default `false`, user property **`spring-boot.repackage.skip`**
(same reference).

**Prefer `skip` over `classifier` for our libraries**, because `classifier` still produces a fat jar
nobody wants and doubles the module's build output for nothing. Reserve `classifier` for the case it
was designed for.

### Consequences for the rest of the build

- Docker copies `nostro-api-service/target/nostro-api-service-*.jar` — the repackaged one, at the
  default coordinates. Do not point a Dockerfile at `*.jar` with a wildcard in a `target/` that also
  contains `*.jar.original`; be explicit, or the `COPY` matches two files and fails.
- `<layers><enabled>true</enabled></layers>` is worth turning on now: it makes `java
  -Djarmode=tools -jar app.jar extract --layers` possible, which is what lets a Dockerfile cache
  dependencies separately from application classes. With three images built from one reactor, that is
  the difference between a rebuild touching one layer and three images re-pushing everything.
- **A cheap guard:** a CI step that runs `unzip -l` on each library jar and fails if it contains
  `BOOT-INF/`. One line, catches the whole class of mistake, and does not rely on anyone remembering
  the rule.

## 5. Virtual threads and gRPC

### What gRPC-Java documents

Very little, and that is the finding. The one relevant, documented hook is `ServerBuilder.executor`:

> *"Set the default executor for service callbacks. It's an optional parameter. If the user has not
> provided an executor when the server is built, the builder will use a static cached thread pool."*
> — [`io.grpc.ServerBuilder`](https://grpc.github.io/grpc-java/javadoc/io/grpc/ServerBuilder.html)

So handlers already run on an application executor, not the transport thread (that is what
`directExecutor()` is for, and its javadoc warns *"The application must not block under any
circumstances"*). Passing `Executors.newVirtualThreadPerTaskExecutor()` there is mechanically
straightforward, and the default — an unbounded cached thread pool — is exactly the thing virtual
threads are meant to replace.

What does **not** exist is a statement of support. grpc-java's reference documentation and javadoc do
not mention virtual threads. The community question
([grpc-java #11726](https://github.com/grpc/grpc-java/issues/11726), *"Compatibility with virtual
threads"*) is closed without a documented compatibility guarantee, and the concern it raises — pinning inside
`synchronized` blocks in the grpc/Netty stack — is a property of a specific version of a specific
library, not something a document settles. Note also that JEP 491 (Java 24) removed the
`synchronized`-pinning problem at the JVM level; we are on **Java 21**, where it has not been.

### Does `spring.threads.virtual.enabled` affect a gRPC server?

The Boot property's own documentation is one line — *"Whether to use virtual threads"*, default
`false` ([Spring Boot — Application
Properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)) — and the
reference adds only consequences, not a scope list: *"If virtual threads are enabled, properties
which configure thread pools don't have an effect anymore"*, and *"One side effect of virtual threads
is that they are daemon threads. A JVM will exit if all of its threads are daemon threads"*, for which
it recommends `spring.main.keep-alive=true` ([Spring Boot — SpringApplication /
Virtual threads](https://docs.spring.io/spring-boot/reference/features/spring-application.html)).

What it actually switches on is Boot's own auto-configured executors — the servlet container's
request executor, `TaskExecutor`, the scheduler, and the Boot-managed listener containers. **A gRPC
server built by Spring gRPC is a Netty server, not a servlet container**, and Spring gRPC's server
reference documents no virtual-thread property at all — the only executor guidance on that page is
*"if you customize the gRPC server call executors, you will need to ensure that you wrap them in a
`DelegatingSecurityContextExecutor`"* ([Spring gRPC —
Server](https://docs.spring.io/spring-grpc/reference/server.html)).

**Read: `spring.threads.virtual.enabled` almost certainly does nothing for the projection service's
gRPC handlers.** It will still matter for the API service, whose inbound edge *is* Tomcat.

**This is unverified and should not be taken on trust.** Two things need measuring rather than
assuming:

- **Needs a test.** Whether `spring.threads.virtual.enabled=true` changes the thread that a gRPC
  handler runs on. It is a five-line test — log `Thread.currentThread()` inside a handler with the
  flag on and off. Until that runs, treat the two settings as independent.
- **Needs a test.** Whether an explicit `Executors.newVirtualThreadPerTaskExecutor()` on the gRPC
  server executor is stable under load on Java 21 with grpc-java 1.76. The projection's handlers do
  blocking JDBC, which is precisely the workload virtual threads help — and precisely the workload
  that pins if a `synchronized` block sits in the path. Measure carrier-thread pinning with
  `-Djdk.tracePinnedThreads=full` before adopting.

**Estimate:** at this system's scale the gain is not throughput but bounded memory under a burst of
concurrent balance queries. It is not urgent. Ship on the default executor with an explicit bounded
pool — the javadoc's own advice is that *"users are encouraged to specify their own executor that
limits the number of threads"* rather than accept the unbounded cached pool — and revisit virtual
threads after the pinning test exists.

## 6. Tracing and Tenant identity across the boundary

### Micrometer Tracing over gRPC: manual, and only just

Micrometer ships the instrumentation in **`micrometer-core`**, package
`io.micrometer.core.instrument.binder.grpc`: `ObservationGrpcClientInterceptor` and
`ObservationGrpcServerInterceptor`. Registration is by hand — instantiate with the
`ObservationRegistry` and add to the builder ([Micrometer — gRPC
Instrumentation](https://docs.micrometer.io/micrometer/reference/reference/grpc.html)). The
observations are named **`grpc.client`** and **`grpc.server`**.

Trace context travels in **gRPC metadata**, and this is visible in the type signature rather than
prose. `GrpcServerObservationContext extends RequestReplyReceiverContext<Metadata, Object>` and the
client side is the matching `RequestReplySenderContext<Metadata, Object>` (micrometer-core source).
`io.grpc.Metadata` *is* the propagation carrier, so Micrometer Tracing's `Propagator` injects and
extracts through it exactly as it does with HTTP headers. With Boot's defaults —
`management.tracing.propagation.produce` = `[W3C]`, `consume` = `[W3C, B3, B3_MULTI]` ([Spring Boot —
Application
Properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)) — the key
on the wire is `traceparent`, and a trace begun at the API service's HTTP edge continues into the
projection's handler with no format translation.

**Is it automatic under Spring gRPC?** No. Spring gRPC's observability section says only that
*"Spring gRPC provides some additional auto-configuration to add gRPC-specific metrics"* ([Spring
gRPC — Server](https://docs.spring.io/spring-grpc/reference/server.html)) — metrics, not tracing, and
the reference documents no Observation interceptor wiring. Assume manual. The wiring itself is small,
because Spring gRPC gives both sides a registration point: a `ServerInterceptor` bean annotated
**`@GlobalServerInterceptor`** is applied to all services, and a `ClientInterceptor` bean annotated
**`@GlobalClientInterceptor`** to all channels, both ordered by `@Order` (same reference, and
[Client](https://docs.spring.io/spring-grpc/reference/client.html)). So:

```java
@Bean @GlobalServerInterceptor @Order(0)
ServerInterceptor observationServerInterceptor(ObservationRegistry registry) {
  return new ObservationGrpcServerInterceptor(registry);
}
```

**Needs a test.** That a single trace id spans HTTP request → gRPC call → projection query. The
assertion is cheap (`Tracer.currentSpan().context().traceId()` logged on both sides, or a
`SimpleTracer`/test `ObservationRegistry`) and the failure mode — two disconnected traces — is
invisible until you are debugging a latency problem and need it.

### How the Tenant should travel

This is the more consequential question, because the projection has **its own Postgres with its own
RLS**, and something has to set `app.current_tenant` there transaction-locally (see
[`rls-pooling.md`](rls-pooling.md)). Whatever crosses this boundary *is* the input to the isolation
mechanism on the far side.

| Option | Mechanics | Verdict |
|---|---|---|
| **(a) Metadata key + server interceptor** | `Metadata.Key.of("x-nostro-tenant", ASCII_STRING_MARSHALLER)`; client interceptor writes it from the API service's request context, server interceptor reads it into a `io.grpc.Context` key that the RLS hook consumes | **Recommended.** One place to write it, one place to read it, no handler can forget |
| (b) A `tenant_id` field on every request message | Ordinary proto field | Rejected — see below |
| (c) Propagate the caller's token; projection re-validates | Bearer token in metadata; projection verifies signature and derives Tenant itself | The most defensible, and the most work. See the honest read |

**Why not (b), a field on every request.** It is the option that looks safest and is not. It puts the
Tenant in the *domain payload*, so every message type carries it, every new RPC has to remember it,
and every handler has to remember to use it rather than the one it already has in scope. That is the
same failure that `rls-pooling.md` names — *"you need to always make sure every connection (including
jobs and tasks) sets the needed context"* — and its answer there is the same: **centralise the hook so
no route can forget.** An interceptor is a centralised hook; a required field on twelve messages is
twelve chances to forget. It also conflates two things this codebase keeps apart: the Tenant is an
isolation boundary, not a query parameter. A `GetBalance(tenant, account)` message invites someone to
pass a tenant that is not the caller's, and the type system will not stop them.

**Honest read on (a) versus (c).** They differ in one thing: whether the projection *trusts the API
service* to have already authenticated.

- Under **(a)** the API service is a trusted issuer of tenant identity. A bug there — a Tenant read
  from a request body instead of the validated token, a request-scoped value leaking across threads —
  becomes a cross-tenant read in the projection, and the projection cannot detect it, because a
  well-formed tenant id is all it ever sees. The blast radius is the whole point of the design.
- Under **(c)** the projection re-derives the Tenant from a signed token and never takes an
  assertion. It is genuinely stronger. It also means the projection needs the JWKS, the clock skew
  handling, and a second copy of the validation code — real work, on a zero-budget project, for a
  call that never leaves the compose network.

**(a) is defensible here, and worth saying why rather than assuming.** The projection service is
internal: it listens on the compose network, publishes no port to the host, and has no ingress. The
trust boundary is the API service's HTTP edge, which is where the token is validated once. The cost
of (c) is duplicated auth infrastructure for a caller that is already inside the boundary.

What makes it defensible rather than merely convenient is that the constraints are *stated and
enforceable*, not assumed:

1. The projection's gRPC port is **never** published to the host or an ingress. The moment it is, (a)
   is unauthenticated tenant impersonation and the choice must be revisited.
2. The server interceptor **rejects the call** with `Status.UNAUTHENTICATED` when the metadata key is
   absent or unparseable. Missing tenant is never "no tenant" — fail closed, matching the database's
   default-deny.
3. The Tenant the client interceptor writes comes from the **validated token**, never from client
   input — same rule as the RLS hook, and worth asserting in one shared place so both obey it.
4. The projection's RLS is real: it sets `app.current_tenant` from the interceptor's value and
   connects as a non-`BYPASSRLS` role. (a) decides *which* tenant; it does not replace the barrier.
5. The upgrade to (c) is additive — the metadata key becomes a token and the interceptor gains a
   verifier — so this is a deferrable decision, not a one-way door. **Write it down as deferred**,
   because an undocumented (a) reads to a stranger as an oversight rather than a decision.

**Do not use Micrometer baggage for the Tenant.** It would work — Boot notes baggage *"is
automatically propagated over the network if you're using W3C propagation"* ([Spring Boot —
Tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)) — and that is precisely
the objection. It makes the isolation boundary a property of the observability stack, so turning
tracing off, or switching to B3 (where Boot notes baggage *"is not automatically propagated"*),
silently breaks tenant isolation. Isolation must not depend on whether metrics are enabled.

## 7. Testing

### The three transports

**In-process.** `InProcessServerBuilder`'s javadoc: *"Builder for a server that services in-process
requests. Clients identify the in-process server by its name … The server is intended to be
fully-featured, high performance, and useful in testing"*
([javadoc](https://grpc.github.io/grpc-java/javadoc/io/grpc/inprocess/InProcessServerBuilder.html)).
No socket, no port, no TCP. Note the documented caveat that metadata serialization is *skipped* unless
explicitly enabled — *"potential for performance penalty when this setting is enabled, as the
`Metadata` must actually be serialized"*. That matters here: **the Tenant metadata key and the
`traceparent` header are exactly what §6 is about**, so a test that relies on the default in-process
behaviour is not proving the marshalling works.

**`grpc-testing`.** Provides `GrpcCleanupRule` — *"A JUnit `ExternalResource` that can register gRPC
resources and manages its automatic release at the end of the test"*
([javadoc](https://grpc.github.io/grpc-java/javadoc/io/grpc/testing/GrpcCleanupRule.html)). Read the
type: `ExternalResource` is **JUnit 4**. On a JUnit 5 suite it does not apply, and the leak it exists
to prevent — undrained channels and unshut servers accumulating across hundreds of tests — is real.
Manage the lifecycle yourself in `@AfterEach`, or let Spring's context do it.

**A real server on a random port.** `spring.grpc.server.port=0`, plus the injected actual port. Full
Netty stack, real sockets, real metadata serialization.

### What Spring gRPC supplies

More than the reference page admits. Inspecting the published artifact
`org.springframework.grpc:spring-grpc-test:0.12.0` (jar contents, since the 0.x reference has no
testing chapter):

- `@AutoConfigureInProcessTransport` — switches the whole context onto the in-process transport
- `@LocalGrpcPort` — injects the port a random-port server actually bound
- `ServerPortInfoApplicationContextInitializer` — the mechanism behind it
- property `spring.grpc.test.inprocess.enabled`, *"Whether to enable the in-process server and client
  for testing"*, default `false` (the jar's `additional-spring-configuration-metadata.json`)

### The context-cache interaction, which is the actual constraint

[`ci-and-testcontainers-budget.md`](ci-and-testcontainers-budget.md) §5 is the governing document
here: the Spring context cache keys on `contextCustomizers`, `activeProfiles`,
`propertySourceProperties` and friends, is bounded at 32 with LRU eviction, and *"the context bill is
the one that runs away"* while the container bill is fixed. Its rule — **one base class per
deployable** — is what this section has to obey.

| Approach | Context cost | Proves | Verdict |
|---|---|---|---|
| **No Spring at all**: hand-built `InProcessServer` + your `ServerInterceptor` and service impl, plain JUnit 5 | **Zero contexts** | Interceptor logic, status mapping, stub behaviour | **Use for the bulk of gRPC tests.** Milliseconds each |
| `@SpringBootTest` + `@AutoConfigureInProcessTransport` on the projection service | **One extra context per distinct annotation set** — `@AutoConfigureInProcessTransport` is a context customizer, so it forks the projection's cached context | Spring wiring, interceptor registration order, real handlers | Use for **one** wiring test, on **one** base class |
| `@SpringBootTest(webEnvironment=…)` + `spring.grpc.server.port=0` on the projection, real channel from the test | One context, **plus** the Netty server per context | Real sockets, real metadata serialization, real TLS/keepalive settings | Use for **one** end-to-end test |
| Both services' contexts in one JVM, API service calling the projection for real | **Two contexts, plus containers** | The whole boundary | One test, deliberately |

The trap is the second row. `@AutoConfigureInProcessTransport` sets
`spring.grpc.test.inprocess.enabled` and participates in the cache key, so **a class that has it and a
class that does not are two contexts, even if everything else is identical.** Scattering it across
five test classes in the projection module fragments a context that `ci-and-testcontainers-budget.md`
spends its §5 arguing should be one. Put it on the projection's single integration base class or
nowhere.

### Recommended shape

1. **Most gRPC tests are not Spring tests.** Build the in-process server directly, register the real
   interceptor, assert on `Status` codes and on the Tenant `Context` key. Zero context cost, and it
   is the layer where the interesting logic lives.
2. **Explicitly enable metadata serialization** on the in-process builder for the interceptor tests
   (`InProcessServerBuilder…` / `InProcessChannelBuilder…`), so the tests that exist to prove the
   Tenant key and `traceparent` cross the wire actually exercise marshalling. Otherwise the one
   property you are testing is the one the transport optimises away.
3. **One real-port test**, in the projection's integration base class, alongside its Testcontainers
   Postgres — because the thing worth proving end to end is *tenant metadata → RLS GUC → correct
   rows*, and that needs a real database, not a real socket. This is also the natural home for the
   trace-propagation assertion from §6.
4. **No `@MockitoBean` on gRPC stubs in integration tests.** `ci-and-testcontainers-budget.md` flags
   bean overrides as a documented cache-key input. If the API service's tests need a stub that
   answers, start a small in-process projection server in the base class and point the channel at it —
   one context, no override.

**Estimate, not measured.** Two extra contexts (one per gRPC-touching deployable) at the ~2–5 s each
that note estimates, i.e. **~5–10 s added to the integration job** — negligible against its ~6–9 minute
budget. The cost only becomes real if the annotation spreads.

## 8. `docker compose` for three services

All quotations in this section are from the [Compose file reference —
Services](https://docs.docker.com/reference/compose-file/services/) unless noted.

### Startup ordering is not free

The short `depends_on` form is the trap, and the spec says so: *"Compose guarantees dependency
services have been started before starting a dependent service. With short syntax, **Compose does not
wait for dependency services to be 'healthy'** before starting a dependent service."* A Postgres
container is "started" long before it accepts connections. Use the long form:

| Condition | Meaning (quoted) |
|---|---|
| `service_started` | *"An equivalent of the short syntax described previously"* |
| `service_healthy` | *"Specifies that a dependency is expected to be 'healthy' (as indicated by healthcheck) before starting a dependent service"* |
| `service_completed_successfully` | *"Specifies that a dependency is expected to run to successful completion before starting a dependent service"* |

Two sub-options matter here. `restart: true` — *"When set to `true` Compose restarts this service
after it updates the dependency service"* (Compose 2.17.0+) — and `required` — *"When set to `false`
Compose only warns you when the dependency service isn't started or available"*, default `true`
(Compose 2.20.0+). Leave `required` at its default; a silently-absent Kafka is not a scenario we want
to degrade into.

### Healthchecks

*"The `healthcheck` attribute declares a check that's run to determine whether or not the service
containers are 'healthy'. It works in the same way, and has the same default values, as the
HEALTHCHECK Dockerfile instruction."* `test` may be a string (*"equivalent to specifying `CMD-SHELL`
followed by that string"*) or a list whose first item is `NONE`, `CMD` or `CMD-SHELL`. `interval`,
`timeout`, `start_period` and `start_interval` are durations; `start_period` *"gives the container an
initialization time to reach a running state before starting health-check probes"* — i.e. failures
during it do not count toward `retries`. `start_interval` (Compose 2.20.2+) polls faster during that
window, which is what turns a 30-second cold start into a 3-second one.

Per service:

| Service | Healthcheck | Note |
|---|---|---|
| `postgres-ledger`, `postgres-projection` | `pg_isready -U … -d …` | Two separate databases (§3). `pg_isready` ships in the image |
| `redis` | `redis-cli ping` | |
| `kafka` | a broker API call, e.g. `kafka-topics.sh --bootstrap-server localhost:9092 --list` | Slowest to become healthy; give it the largest `start_period` |
| `api-service` | `curl -f http://localhost:8080/actuator/health/readiness` | Boot's readiness probe. Needs `curl` or `wget` **in the image** |
| `outbox-relay` | actuator readiness on a management port | It serves no HTTP of its own; expose actuator only |
| `projection-service` | gRPC health, see below | The interesting one |

**The projection service has no HTTP.** Spring gRPC *"autoconfigures the standard gRPC Health service
for performing health check calls against gRPC servers"*, but *"the health service resides in the
`io.grpc:grpc-services` library which is marked as `optional` by Spring gRPC. You must add this
dependency to your application in order for it to be autoconfigured"*, and *"health is …
enabled by default when the application defines at least one `BindableService`"* ([Spring gRPC —
Server](https://docs.spring.io/spring-grpc/reference/server.html)). Two workable checks, and the
choice is a real one:

- **`grpc_health_probe` in the image** — exercises the actual gRPC port and the actual health service.
  Costs an extra binary in the Dockerfile.
- **Boot actuator on a separate management HTTP port** — no extra binary, but proves only that the JVM
  is up, not that the gRPC port is listening. It is the *wrong* check for the exact failure this
  design is exposed to.

Prefer `grpc_health_probe`. Also register the `HealthStatusManager` so the service reports `NOT_SERVING`
until its Kafka consumer has actually joined — otherwise "healthy" means "process started".

### Does Compose build the images, or expect prebuilt jars?

Either — and the default surprises people. *"If the image does not exist on the platform, Compose
attempts to pull it based on the `pull_policy`"*, and if both `build` and `image` are set, *"there are
alternative options for controlling the precedence of pull over building the image from source,
however **pulling the image is the default behavior**"* ([Compose Build
Specification](https://docs.docker.com/reference/compose-file/build/)). `pull_policy` values are
`always`, `never`, `missing` (the default without the Build Specification), `build` (*"Compose builds
the image. Compose rebuilds the image if it's already present"*), `daily`, `weekly`,
`every_<duration>`.

**Recommendation: multi-stage `build`, no prebuilt-jar assumption.** A `COPY target/*.jar` Dockerfile
means `docker compose up` on a clean clone produces `COPY failed: no source files` — the single most
common first-run failure of this arrangement, and the stranger has no idea they were supposed to run
`mvn package` first. A multi-stage build (`maven:…-eclipse-temurin-21` builder → `eclipse-temurin:21-jre`
runtime, a **glibc** image per §2 if anything protobuf-related runs at build time) makes the compose
file self-contained. Build the reactor **once** in a shared builder stage and have each of the three
runtime stages `COPY --from` its own jar, or you will compile the whole project three times.

### Migrations exactly once

`service_completed_successfully` is the documented mechanism, and it is the right one. One
short-lived migration service per database:

```yaml
services:
  ledger-migrate:
    build: { context: ., dockerfile: docker/migrate.Dockerfile }
    depends_on:
      postgres-ledger: { condition: service_healthy }
    environment:                    # owner role, direct connection — see rls-pooling.md
      FLYWAY_URL: jdbc:postgresql://postgres-ledger:5432/nostro
      FLYWAY_USER: nostro_owner
    restart: "no"                   # the default; state it, because a restart loop here is silent
  api-service:
    depends_on:
      ledger-migrate:  { condition: service_completed_successfully }
      postgres-ledger: { condition: service_healthy }
      kafka:           { condition: service_healthy }
```

Why not just let Flyway run at application startup: the API service and the outbox relay share the
ledger database, so both would attempt to migrate concurrently on a cold `up`. Flyway's own lock
makes that survivable but not deterministic, and — per
[`rls-pooling.md`](rls-pooling.md) — *"Flyway and Liquibase take session-level locks, which
transaction pooling does not support"*, and migrations want the **owner role on a direct connection**
while the services want the restricted role on a pooled one. A separate migration container is the
natural place for that split: different credentials, different connection string, exits when done.

Set `spring.flyway.enabled=false` in the services so nobody accidentally reintroduces the race.

**Two databases, two migration services**, each depending on its own Postgres. The projection's schema
is not the ledger's (§3) and must not share a migration history table.

**Needs a test.** That `service_completed_successfully` actually gates a *failed* migration — a
migration container exiting non-zero must stop the dependents, not merely log. Verify by breaking a
migration on purpose once; the whole point of the arrangement is what it does when something is
wrong.

---

## Recommendation

### The module list

```
nostro-parent (pom)
├── nostro-domain              nostro-api-service        (executable jar)
├── nostro-persistence         nostro-outbox-relay       (executable jar)
├── nostro-outbox              nostro-projection-service (executable jar)
├── nostro-balance-proto
├── nostro-grpc-common
└── nostro-test-support
```

Six libraries, three deployables, one parent. The three rules that carry the weight:
`projection-service` may not depend on `persistence`; `balance-proto` may not depend on `domain`;
`spring-boot-maven-plugin` lives in `<pluginManagement>` and is executed only by the three service
modules.

### The gRPC library

**Spring gRPC `0.12.0`** on Boot 3.5.x — the only Boot-3-compatible version of the official project,
frozen since 2025-10-24 because 1.x moved to Boot 4. Accept it with eyes open: no security patches,
so override `grpc-java` and `protobuf-java` versions in our own `dependencyManagement`, and keep the
code idiomatic grpc-java so the starter is replaceable. Compile `.proto` with
**`io.github.ascopes:protobuf-maven-plugin` 5.1.8**, not the eight-year-dead
`org.xolstice.maven.plugins` one that grpc-java's README still shows, and skip `os-maven-plugin`
entirely.

### The three things most likely to break for a stranger running `docker compose up`

1. **A Dockerfile that `COPY`s a jar nobody built.** `git clone && docker compose up` with
   `COPY target/*.jar` fails on `no source files`, and the error names a path the reader has never
   seen. Multi-stage build inside the compose file, one shared Maven builder stage, three runtime
   stages. This is the single highest-probability failure and it happens in the first ten seconds.
2. **Ordering that looks handled and is not.** Short-form `depends_on` starts the API service against
   a Postgres that has not finished initialising, and against a Kafka that is minutes from ready. The
   symptom is a crash loop with a connection-refused stack trace and a compose file that appears to
   declare the dependency. Long-form `condition: service_healthy` everywhere, a real healthcheck on
   every backing service including a **gRPC** one on the projection, and generous `start_period` on
   Kafka.
3. **Migrations racing, or not running.** Two services share the ledger database, so
   application-startup Flyway means two concurrent migrators on a cold `up` — and the projection's
   separate database is easy to forget entirely, giving a projection service that starts healthy and
   fails on its first message. One migration container per database, gated by
   `service_completed_successfully`, `spring.flyway.enabled=false` in the services.

Honourable mention, because it will not break the first run but will break the first *change*: a
repackaged `nostro-balance-proto.jar` whose classes are in `BOOT-INF/classes`, producing `cannot find
symbol` on a class that is visibly present in the jar. The `unzip -l | grep BOOT-INF` CI guard in §4
costs one line.

---

## Sources

- [Spring gRPC (project page)](https://spring.io/projects/spring-grpc) — the official project; current version line.
- [Spring gRPC — Next Steps for 1.0.0](https://spring.io/blog/2025/11/05/spring-grpc-next-steps/) — 1.0 depends on Spring Boot 4.0 rather than shipping inside Boot; minimal disruption migrating off 0.x.
- [Spring gRPC — System Requirements](https://docs.spring.io/spring-grpc/reference/system-requirements.html) — 1.0.x needs Java 17+ and Spring Framework 7.0.1+ (the Boot 4 stack).
- [Spring gRPC — Server](https://docs.spring.io/spring-grpc/reference/server.html) — `@GlobalServerInterceptor` and per-service interceptors with ordering; observability is *metrics* auto-configuration only; gRPC Health service autoconfigured, `io.grpc:grpc-services` optional, enabled when a `BindableService` exists; `DelegatingSecurityContextExecutor` note on custom call executors.
- [Spring gRPC — Client](https://docs.spring.io/spring-grpc/reference/client.html) — `@GlobalClientInterceptor`, per-channel interceptors via `ChannelBuilderOptions`, `InProcessGrpcChannelFactory`.
- Maven Central metadata and published POMs (`repo1.maven.org`) — `spring-grpc-spring-boot-starter` versions 0.1.0–1.0.3; `…server-spring-boot-starter:0.12.0` → `spring-boot-starter:3.5.6`; `…spring-boot-starter:1.0.3` → `spring-boot-starter:4.0.2`; `spring-grpc-dependencies:0.12.0` pins grpc 1.76.0 / protobuf-java 4.32.1; `protoc:4.32.1` OS/arch classifier listing; `org.xolstice.maven.plugins:protobuf-maven-plugin` last published 2018-10-01; `io.github.ascopes:protobuf-maven-plugin` 5.1.8 (2026-08-10); `kr.motd.maven:os-maven-plugin` 1.7.1 (2022-11-07); contents of `spring-grpc-test:0.12.0` (`@AutoConfigureInProcessTransport`, `@LocalGrpcPort`, `spring.grpc.test.inprocess.enabled`).
- [grpc-ecosystem/grpc-spring](https://github.com/grpc-ecosystem/grpc-spring) — the `net.devh` starter; compiled against Spring Boot 3.2.4; last release 3.1.0.RELEASE (2024-04-14), last commit 2024-10-27.
- [DanielLiu1123/grpc-starter](https://github.com/DanielLiu1123/grpc-starter) — maintained community alternative; versions track Boot (3.5.5, 4.1.0 as of 2026-06-20).
- [grpc-java README](https://github.com/grpc/grpc-java/blob/master/README.md) — the canonical Maven setup: `os-maven-plugin` extension, `${os.detected.classifier}` on `protocArtifact`/`pluginArtifact`; prebuilt `protoc-gen-grpc-java` uses glibc on Linux.
- [protobuf-maven-plugin (Ascopes) — Requirements](https://ascopes.github.io/protobuf-maven-plugin/requirements.html) — Maven 3.9+, Java 17+; the OS/architecture matrix Google publishes `protoc` for.
- [protobuf-maven-plugin — Basic Usage](https://ascopes.github.io/protobuf-maven-plugin/basic-usage.html) — `src/main/protobuf` (with `src/main/proto` fallback), `target/generated-sources/protobuf`, `generate` vs `generate-test`.
- [protobuf-maven-plugin — Using protoc Plugins](https://ascopes.github.io/protobuf-maven-plugin/using-protoc-plugins.html) — `kind="binary-maven"` for `io.grpc:protoc-gen-grpc-java`; `classifier` *"defaults to an OS and CPU-specific string matching the conventions used by protoc"*.
- [protobuf-maven-plugin — generate mojo](https://ascopes.github.io/protobuf-maven-plugin/generate-mojo.html) — binds to `generate-sources`; `registerAsCompilationRoot` defaults to `true`.
- [Maven — Guide to Working with Multiple Modules](https://maven.apache.org/guides/mini/guide-multiple-modules-4.html) — the reactor sorts projects so dependencies build first.
- [Spring Boot Maven Plugin — Packaging](https://docs.spring.io/spring-boot/maven-plugin/packaging.html) — `repackage` makes `java -jar`-executable archives; original renamed `.original`; **application classes move to `BOOT-INF/classes` so a dependent module cannot load them**; `skip` (user property `spring-boot.repackage.skip`, default `false`) and `classifier`.
- [`io.grpc.ServerBuilder` javadoc](https://grpc.github.io/grpc-java/javadoc/io/grpc/ServerBuilder.html) — `executor` defaults to a static cached thread pool, *"users are encouraged to specify their own executor that limits the number of threads"*; `directExecutor()` forbids blocking.
- [grpc-java issue #11726 — Compatibility with virtual threads](https://github.com/grpc/grpc-java/issues/11726) — the open question about `newVirtualThreadPerTaskExecutor` and `synchronized` pinning; closed without a documented guarantee.
- [Spring Boot — SpringApplication (Virtual threads)](https://docs.spring.io/spring-boot/reference/features/spring-application.html) — thread-pool properties stop having effect; virtual threads are daemon threads, hence `spring.main.keep-alive=true`.
- [Spring Boot — Application Properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) — `spring.threads.virtual.enabled` (*"Whether to use virtual threads"*, default `false`); `management.tracing.propagation.produce` default `[W3C]`, `consume` default `[W3C, B3, B3_MULTI]`.
- [Spring Boot — Tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html) — baggage is propagated automatically with W3C, not with B3.
- [Micrometer — gRPC Instrumentation](https://docs.micrometer.io/micrometer/reference/reference/grpc.html) — `ObservationGrpcServerInterceptor` / `ObservationGrpcClientInterceptor` are registered manually with an `ObservationRegistry`; observations named `grpc.server` and `grpc.client`.
- [micrometer `GrpcServerObservationContext`](https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-core/src/main/java/io/micrometer/core/instrument/binder/grpc/GrpcServerObservationContext.java) — `extends RequestReplyReceiverContext<Metadata, Object>`: `io.grpc.Metadata` is the trace-context carrier.
- [`io.grpc.inprocess.InProcessServerBuilder` javadoc](https://grpc.github.io/grpc-java/javadoc/io/grpc/inprocess/InProcessServerBuilder.html) — in-process transport *"intended to be fully-featured, high performance, and useful in testing"*; metadata serialization is skipped unless explicitly enabled.
- [`io.grpc.testing.GrpcCleanupRule` javadoc](https://grpc.github.io/grpc-java/javadoc/io/grpc/testing/GrpcCleanupRule.html) — a JUnit **4** `ExternalResource` that releases registered gRPC resources at test end.
- [Compose file reference — Services](https://docs.docker.com/reference/compose-file/services/) — short-form `depends_on` does not wait for health; `service_started` / `service_healthy` / `service_completed_successfully`; `restart` and `required` sub-options; `healthcheck` attributes and `start_period` / `start_interval`; `pull_policy` values.
- [Compose Build Specification](https://docs.docker.com/reference/compose-file/build/) — with both `build` and `image`, *"pulling the image is the default behavior"*; `pull_policy` controls precedence.
