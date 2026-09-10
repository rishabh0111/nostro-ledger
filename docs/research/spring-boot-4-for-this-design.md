# Spring Boot 3.x or Spring Boot 4.x for this ledger

Research note on the single stack-wide decision every other choice in this repository now hangs off:
**does Nostro build on Spring Boot 3.5.x or Spring Boot 4.x?**

The system is fixed: Java 21, Maven, Postgres 16, Kafka, Redis, a multitenant double-entry ledger with
transaction-local Tenant context under RLS, three deployables under `docker compose`, Testcontainers on
GitHub Actions. It is also meant to be read by other Java engineers, so "what will a reader
recognise" is a real criterion, weighed here alongside the technical ones rather than
apologised for.

Investigated against primary sources (Spring project pages and the Spring release-metadata API, Spring
Boot and Spring Framework release notes and migration guides, Hibernate ORM 7 documentation and
javadoc, published POMs and Maven Central metadata). Every non-obvious claim is cited inline. Claims
the documentation does not settle are marked **needs a test** or **estimate** and are not asserted. See
[Sources](#sources).

Sibling notes this one depends on and does not restate: [`rls-pooling.md`](rls-pooling.md) (Tenant
context, the two-role split, the pooler), [`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md)
(composite keys, insert-only tables, batching, `StatelessSession` — **researched against Hibernate ORM
6.x, and §2 below is largely about which of it survives ORM 7**),
[`grpc-and-multi-module-layout.md`](grpc-and-multi-module-layout.md) (Spring gRPC 1.0 GA requires Boot
4; Boot 3.x is stuck on the frozen pre-GA 0.12.0),
[`hot-account-contention.md`](hot-account-contention.md),
[`ordering-and-watermarks.md`](ordering-and-watermarks.md) and
[`ci-and-testcontainers-budget.md`](ci-and-testcontainers-budget.md).

---

## Executive summary

- **Recommendation: Spring Boot 4.1.x** (4.1.1, Spring Framework 7.0.9, Hibernate ORM 7.4.5) on Java 21.
  The full argument is at the bottom; the three findings that decide it are the next three bullets.
- **Spring Boot 3.5's OSS support ended on 2026-06-30, and 3.5 is the last 3.x line.** Spring's own release
  metadata gives 3.5.x an OSS end of 2026-06-30 with commercial support to 2032 — the extended window
  Spring gives the final minor of a major — and lists no 3.6. The last free patch, 3.5.16, was published
  2026-06-25. There is no supported free 3.x branch to start a 2026 project on (§1).
- **gRPC is a hard requirement and it only has a GA implementation on Boot 4.**
  [`grpc-and-multi-module-layout.md`](grpc-and-multi-module-layout.md) already established that Spring gRPC
  1.0 requires Boot 4 and that Boot 3.x is stuck on 0.12.0, frozen since 2025-10-24 with no security
  patches. Boot 3.5 means shipping a required capability on an unmaintained pre-GA dependency, and doing
  the Boot 4 migration later anyway (§5, §7).
- **The ecosystem risk everyone expects here — springdoc — does not exist.** springdoc-openapi **3.x is the
  Boot 4 line**; 3.0.0 shipped one day after Boot 4.0 GA, and 3.1.1 (2026-09-06) declares
  `spring-boot-starter-parent:4.1.0` as its parent. Boot 4 ships **no** first-party OpenAPI generation —
  that rumour is the new API-versioning auto-configuration, which is routing, not schema (§6).
- **[`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md) survives Hibernate ORM 7 nearly intact.**
  Records as `@Embeddable`/`@EmbeddedId`, the `@EmbeddedId`-over-`@IdClass` recommendation, the
  `@JoinColumns(insertable=false, updatable=false)` composite-FK mechanism (same source, same error text),
  `@Immutable` still ignoring updates silently, and the whole batching configuration all hold. Three things
  change (§2).
- **Change 1 — `StatelessSession` can batch now.** *"`hibernate.jdbc.batch_size` … has no effect on a
  `StatelessSession`"*; use `insertMultiple()`. The sibling note's "persistence context gone **or** the
  batch, not both" is false on ORM 7 — you can have both.
- **Change 2 — `StatelessSession` now uses the second-level cache by default.** Harmless here only because
  the L2 cache is already off globally for RLS reasons. Keep that setting explicit rather than implied.
- **Change 3 — bulk HQL `update`/`delete` against an `@Immutable` entity now throws by default**, where 6.6
  warned and proceeded. One of the four bypass paths closes for free. The `REVOKE UPDATE, DELETE` barrier
  is still the barrier.
- **Framework 7's transactional `StatelessSession` provably uses the transaction's own connection**, which
  is the load-bearing question for tenant isolation. Spring calls Hibernate's
  `statelessWithOptions().connection().open()`, and Hibernate documents `connection()` as meaning *"the
  connection, and therefore also the JDBC transaction, should be shared from parent to child"* (§3).
- **But Boot does not auto-configure it, and under Boot's default `JpaTransactionManager` the guarantee
  rests on three Spring classes agreeing, which no document states.** Declare one `@Bean` via
  `SharedSessionCreator`, and **write the test** — compare `pg_backend_pid()` through the `EntityManager`
  and the `StatelessSession` — because under fail-closed RLS the failure mode is an empty result set, not
  an exception. This is the single biggest risk in the recommendation (§3, Recommendation).
- **Java 21 is fine on both branches.** Framework 7 *"retains a JDK 17 baseline while … recommending JDK
  25"*; Boot 4 gives *"first class support for Java 25 (whilst retaining Java 17 compatibility)"*. Nothing
  forces a JDK move (§1).
- **The honest cost of Boot 4 is that a lot of internet advice is now wrong** — `@MockBean`,
  `spring-boot-starter-web`, `com.fasterxml.jackson`, `authorizeRequests`, Testcontainers 1.x imports.
  That is a real readability risk, argued at full strength in §7, and it does not outweigh starting on an
  end-of-free-life branch.
- **One genuine ecosystem gap:** the Bucket4j Spring Boot starter is six months stale and unverified on
  Boot 4.1. Use Bucket4j's core library directly — which is better material to be able to explain anyway.

---

## 1. Release state and support

### The versions, as of 2026-09-10

| | Spring Boot 3.5.x | Spring Boot 4.0.x | Spring Boot 4.1.x |
|---|---|---|---|
| Initial release | 2025-05-31 | **2025-11-30** (4.0.0 announced 2025-11-20) | 2026-06-30 |
| Latest patch on Maven Central | **3.5.16** (2026-06-25) | 4.0.8 | **4.1.1** (2026-08-20) |
| Spring Framework | 6.2.19 | 7.0.9 | 7.0.9 |
| **OSS support ends** | **2026-06-30 — already past** | 2026-12-31 | 2027-07-31 |
| Commercial support ends | 2032-06-30 | 2027-12-31 | 2028-07-31 |

Dates from Spring's own release metadata
([`api.spring.io/projects/spring-boot/generations`](https://api.spring.io/projects/spring-boot/generations));
latest patch versions and their publication dates from Maven Central metadata for
`org.springframework.boot:spring-boot`. Spring Boot 4.0.0 was released *"November 20, 2025"*
([Spring Boot 4.0.0 available now](https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/)).

### The finding that should be read first

**Spring Boot 3.5.x stopped receiving free patches on 2026-06-30 — ten weeks ago.** Its last Central
publication is 3.5.16 on 2026-06-25, five days before that date. And 3.5 is the **final 3.x line**:
the generations list runs 3.5.x → 4.0.x with no 3.6, and 3.5.x carries the extended commercial window
(to 2032-06-30) that Spring gives the last minor of a major — the same shape 2.7.x has (to 2029-06-30).

Spring's policy: *"Minor versions will be supported for at least 12 months"*, majors *"for at least 3
years from the release date (but you must run a supported minor version)"*
([Spring Boot wiki — Supported Versions](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions)),
with the OSS window stated elsewhere as *"a minimum of 13 months"*
([Spring — Support Policy](https://spring.io/support-policy)). The parenthesis is the whole point:
Spring Boot 3 as a *major* is nominally supported into 2028, but only via a supported minor, and there
is no longer a 3.x minor under free support. Continuing on 3.x means **no free security patches, from
today, forever** — not "in a year".

That is not fatal for a project with no production data. It is, however, exactly the kind of
thing a reviewer can check in thirty seconds, and "I chose the branch that went end-of-free-life
before I started" is a worse conversation than any Boot 4 rough edge below.

### Java baseline

Neither branch forces a JDK change, and **Java 21 is fine on both**.

- Spring Framework 7.0 *"retains a JDK 17 baseline while at the same time recommending JDK 25 as the
  latest LTS release"*
  ([Spring Framework 7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes)).
- Spring Boot 4.0 gives *"first class support for Java 25 (whilst retaining Java 17 compatibility)"*
  ([Spring Boot 4.0.0 available now](https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/)).

So Java 21 sits inside the supported range on Boot 4 but is no longer the *recommended* LTS — which is
worth knowing for one reason specific to this project. `grpc-and-multi-module-layout.md` records that
JEP 491 (Java 24) removed the `synchronized`-pinning problem for virtual threads at the JVM level, and
that this project is on Java 21 where it has not been. Boot 4 does not require moving off 21, but it
makes JDK 25 the frictionless upgrade if that pinning caveat ever needs to be retired. Boot 3.5 runs on Java 21 equally
well; the JDK is not a differentiator between the two branches, only a note about where each stack's
recommended path points.

### Jakarta EE baseline

Spring Framework 7.0 *"introduces a Jakarta EE 11 baseline"* with minimum Servlet 6.1 (Tomcat 11.0,
Jetty 12.1), **JPA 3.2**, and Bean Validation 3.1 (Hibernate Validator 9.0/9.1)
([Framework 7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes)).
The managed versions in the two BOMs confirm it:

| Managed artifact | Boot 3.5.16 | Boot 4.0.8 | Boot 4.1.1 |
|---|---|---|---|
| `jakarta.persistence-api` | 3.1.0 | **3.2.0** | 3.2.0 |
| `jakarta.servlet-api` | 6.0.0 | **6.1.0** | 6.1.0 |
| `jakarta.validation-api` | 3.0.2 | **3.1.1** | 3.1.1 |
| Tomcat | 10.1.55 | **11.0.24** | 11.0.24 |

(from the published `spring-boot-dependencies` POMs for each version — this table and the ones in §2
and §6 are all read out of those POMs rather than from prose.)

Jakarta Persistence **3.2** is the one that matters here, because it is what carries the
`EntityManager`-level API changes Hibernate 7 implements — see §2 and §3.

---


## 2. Hibernate ORM version, and what it invalidates

### Which version each branch manages

| | Hibernate ORM | Jakarta Persistence | Hibernate Validator |
|---|---|---|---|
| Boot 3.5.16 (last free 3.x) | **6.6.53.Final** | 3.1.0 | 8.0.3.Final |
| Boot 4.0.8 | **7.2.24.Final** | 3.2.0 | 9.0.1.Final |
| Boot 4.1.1 | **7.4.5.Final** | 3.2.0 | 9.1.3.Final |

(read out of the published `spring-boot-dependencies` POMs). Two things follow immediately. Boot 3.5 is
still on the ORM 6.6 line that [`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md) was researched
against — **that note is exactly right for Boot 3.5 and needs no revision there**. And Boot 4 lands on
ORM 7.2+, which is the version Spring Framework 7 wants for `StatelessSession` (§3).

Hibernate 7 baselines on Java 17 (*"Hibernate now baselines on Java 17. Newer Java versions may also be
used"*) and migrates to Jakarta Persistence 3.2, which the migration guide itself calls *"fairly
disruptive"*
([Hibernate ORM 7.0 migration guide](https://github.com/hibernate/hibernate-orm/blob/7.0/migration-guide.adoc)).

### Claim by claim

| Claim in [`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md) | On Hibernate ORM 7 |
|---|---|
| Records as `@Embeddable` / `@EmbeddedId`, supported and recommended | **Holds, verbatim** |
| `@EmbeddedId` over `@IdClass` | **Holds — and the case gets stronger** |
| `@Immutable` ignores updates silently | **Holds for dirty checking; one bypass path closes** |
| Composite FK via `@JoinColumns(insertable=false, updatable=false)` | **Holds — same code, same error text** |
| JDBC insert batching configuration | **Holds for stateful sessions; redefined for `StatelessSession`** |
| Pin `>= 6.6.1.Final` | **Superseded** — the floor moves, the reasoning does not |
| `StatelessSession` "does not interact with any second-level cache" | **False on ORM 7** — see the end of this section |

#### Records as `@Embeddable` and `@EmbeddedId` — unchanged

The Hibernate 7.2 *Short Guide* carries the same text the 6.6 Introduction did: *"Alternatively, an
embeddable type may be defined as a Java record type"* … *"In this case, the requirement for a
constructor with no parameters is relaxed"*
([A Short Guide to Hibernate 7](https://docs.hibernate.org/orm/7.2/introduction/html_single/Hibernate_Introduction.html)).
Nothing about the feature was withdrawn or narrowed.

**HHH-18445 (*"Embeddable as Java Record has wrong order of columns"*) is behind us.** It was fixed in
6.6.1.Final, well before the 7.0 branch point, and does not reappear in the 7.x changelog. But the
*class* of bug is alive in the 7.x line, and it has moved to the `@IdClass` side of the fence:

- **`HHH-19214`** — *"Record as `@IdClass` — some tests are failing when record componets of ID class
  are not alphabetically sorted, but passing when sorted"* — fixed in **7.2.16.Final (2026-05-24)**.
- **`HHH-20273`** — *"Failed to set List type field in Embeddable record"* — fixed in **7.2.8.Final
  (2026-03-26)**.

([hibernate-orm 7.2 `changelog.txt`](https://github.com/hibernate/hibernate-orm/blob/7.2/changelog.txt))

`HHH-19214` is the same silently-transposed-columns failure mode as `HHH-18445`, on the mapping style
the sibling note already rejected. `HHH-20273` is a record embeddable containing a `List` — which the
sibling note already says not to write ("keep record embeddables to plain values"). Both fixes are
*inside* what Boot 4.0.8 and 4.1.1 manage, so the practical instruction changes shape: **on Boot 4, take
the Boot-managed Hibernate and do not downgrade it.** The explicit `hibernate.version` override in the
`pom.xml` can be dropped — it exists only to defeat a BOM that resolves too low, and neither Boot 4 BOM
does. Keep it on Boot 3.5, where 6.6.53 is already well past 6.6.1 but the override still documents why.

#### `@EmbeddedId` versus `@IdClass` — recommendation unchanged, support better

The 7.2 guide still says of `@IdClass`: *"This is not our preferred approach. Instead, we recommend that
the `BookId` class be declared as an `@Embeddable` type"*, and still says *"Every such id class must
override `equals()` and `hashCode()`. Of course, the easiest way to satisfy these requirements is to
declare the id class as a `record`."*
([Short Guide to Hibernate 7](https://docs.hibernate.org/orm/7.2/introduction/html_single/Hibernate_Introduction.html)).
The 7.2 *User Guide* still restates the four spec rules — including *"The primary key class must be
serializable"* — and still calls bare multiple `@Id` attributes without an id class *"generally
considered poor design"*
([Hibernate 7.2 User Guide](https://docs.hibernate.org/orm/7.2/userguide/html_single/Hibernate_User_Guide.html)).

So `@EmbeddedId` with `@Embeddable record PostingId(UUID tenantId, UUID id) implements Serializable`
carries over intact, `Serializable` is still required, and `HHH-19214` is one more reason not to take the
other road.

What is new is that ORM 7 refuses more mapping mistakes at bootstrap rather than ignoring them: *"7.0
does much more in-depth checking that annotations appear in the proper place. While previous versions did
not necessarily throw errors, in most cases these annotations were simply ignored"*, plus stricter
`PersistentAttributeType` validation aligned to Jakarta Persistence 3.2, stricter identifier-generator
validation, and errors for `AttributeConverter`s on incompatible attributes where *"previously, any
converter applied to an attribute with an incompatible annotation was simply ignored"*
([7.0 migration guide](https://github.com/hibernate/hibernate-orm/blob/7.0/migration-guide.adoc)).

For a project whose thesis is that wrong states should be impossible rather than reviewed, this is a gain
in exactly the register the project argues in. The cost is that a mapping which boots on 6.6 may not boot
on 7 — loudly, at startup, which is the acceptable direction of failure.

#### `@Immutable` — silent updates unchanged, the bulk-DML hole closes

The 7.2 javadoc is **word for word** what the sibling note quotes: *"Changes made in memory to the state
of an immutable entity are never synchronized to the database. The changes are ignored, with no exception
thrown."* Immutable collections still throw a `HibernateException`; delete still appears nowhere
([hibernate-orm 7.2, `annotations/Immutable.java`](https://github.com/hibernate/hibernate-orm/blob/7.2/hibernate-core/src/main/java/org/hibernate/annotations/Immutable.java)).
The asymmetry stands, and so does the conclusion: **the insert-only barrier stays in the database as
`REVOKE UPDATE, DELETE` on `app_user`.**

One row of that section's table does change, in this project's favour:

> Previously, `hibernate.query.immutable_entity_update_query_handling_mode` defaulted to `warning`, and
> update and delete queries affecting immutable entities were allowed. **Now, by default, such update and
> delete queries result in an exception.**

([7.0 migration guide](https://github.com/hibernate/hibernate-orm/blob/7.0/migration-guide.adoc))

So on ORM 7, HQL/JPQL bulk `update`/`delete` against an `@Immutable` entity **throws by default**, where
6.6 logged a warning and proceeded. That is a free tightening of one of the four bypass paths, and it
costs nothing because nothing here wants the escape hatch. The other bypasses — native SQL,
`StatelessSession`, anything outside this JVM — are unchanged, and the `remove()`-on-`@Immutable`
question is **still unsettled by the documentation and still needs a test in this repo**.

#### The composite foreign key — bit-for-bit unchanged

`org.hibernate.mapping.Value#checkColumnDuplication` on the 7.2 branch is the same code, same gate, same
message:

```java
if ( isColumnInsertable( i ) || isColumnUpdateable( i ) ) {
    final Column col = (Column) selectable;
    if ( !distinctColumns.add( col.getName() ) ) {
        throw new MappingException(
                "Column '" + col.getName()
                        + "' is duplicated in mapping for " + owner
                        + " (use '@Column(insertable=false, updatable=false)' when mapping multiple properties to the same column)"
        );
    }
}
```

([hibernate-orm 7.2, `mapping/Value.java`](https://github.com/hibernate/hibernate-orm/blob/7.2/hibernate-core/src/main/java/org/hibernate/mapping/Value.java))

The `@JoinColumns(… insertable=false, updatable=false)` mechanism for the shared `tenant_id`, the
bootstrap-time failure, the two-writers-one-muted hazard and its factory mitigation all carry over
without amendment. `insertable`/`updatable` on `@JoinColumn` are Jakarta Persistence semantics and did
not change in a way that touches this between 3.1 and 3.2.

**Measured, 2026-09-18: the paragraph above is wrong on ORM 7.4.** `checkColumnDuplication` is
unchanged, but a *second* check now runs first. Muting only the shared column of a two-column
`@JoinColumns` — `tenant_id` read-only, `entry_id` writable — fails at bootstrap with
`AnnotationException: Column mappings for property 'entry' mix insertable with 'insertable=false'`
(`AnnotatedColumns.checkPropertyConsistency`, reached from `ToOneBinder.processManyToOneProperty`).
ORM 7 requires every column of one association to agree on insertability. This is the "7.0 does
much more in-depth checking" tightening from the migration guide, landing on exactly the mapping the
sibling note recommends. What the project does instead (`PostingEntity`): the association is
read-only in *both* columns and exists for navigation; the writable copies are the `@EmbeddedId` for
`tenant_id` and a plain `@Column entryId` for `entry_id`, both derived from the parent in the one
constructor. The database-side guarantee — composite FK plus RLS `WITH CHECK` — is untouched, and
that is still where the invariant lives.

#### Batching — unchanged for `persist()`, redefined for `StatelessSession`

For the stateful path the sibling note actually recommends, nothing moved. The 7.2 User Guide still says
*"JDBC batching is not enabled by default … To enable JDBC batching, set the `hibernate.jdbc.batch_size`
property to an integer between 10 and 50"*, and still carries the sentence that matters most here:

> Hibernate disables insert batching at the JDBC level transparently if you use an identity identifier
> generator.

([Hibernate 7.2 User Guide, Batching](https://docs.hibernate.org/orm/7.2/userguide/html_single/Hibernate_User_Guide.html))

`hibernate.jdbc.batch_size`, `hibernate.order_inserts` and `hibernate.order_updates` all survive with the
same descriptions, still passed through `spring.jpa.properties.*`. The assigned-UUID immunity to the
IDENTITY trap still applies. **The properties block in the sibling note's recommendation needs no edit.**

**For `StatelessSession`, the sibling note's headline trade-off is now false.** From the 7.0 migration
guide:

> The configuration property `hibernate.jdbc.batch_size` now has no effect on a `StatelessSession`. JDBC
> batching may be enabled by explicitly calling `setJdbcBatchSize()`. However, the preferred approach is
> to use the new explicit batch operations via `insertMultiple()`, `updateMultiple()`, or
> `deleteMultiple()`.

and the 7.2 javadoc gives the reasoning:

> Since version 7, the configuration property `hibernate.jdbc.batch_size` has no effect on a stateless
> session. Automatic batching may be enabled by explicitly setting the batch size. However, automatic
> batching has the side effect of delaying execution of the batched operation, thus undermining the
> synchronous nature of operations performed through a stateless session. A preferred approach is to
> explicitly batch operations via `insertMultiple`, `updateMultiple`, or `deleteMultiple`.

([hibernate-orm 7.2, `StatelessSession.java`](https://github.com/hibernate/hibernate-orm/blob/7.2/hibernate-core/src/main/java/org/hibernate/StatelessSession.java))

The sibling note states the trade-off as absolute — *"you get the persistence context gone, or the batch,
not both"*. **On ORM 7 you can have both**, via `statelessSession.insertMultiple(postings)`. For a
one-Entry, N-Posting write that is precisely the shape this ledger wants: no persistence context, no
dirty checking, no first-level cache, and one batched INSERT per table, in one round trip, with the
statement issued synchronously rather than deferred to a flush. That sentence in §7 of the sibling note
should be read as **struck on Boot 4 and standing on Boot 3.5**.

#### One new hazard the RLS design has to notice

> **Since version 7, a stateless session makes use of the second-level cache by default.** To bypass the
> second-level cache, call `setCacheMode(CacheMode.IGNORE)` … or set the configuration properties
> `jakarta.persistence.cache.retrieveMode` and `jakarta.persistence.cache.storeMode` to `BYPASS`.

([`StatelessSession.java`](https://github.com/hibernate/hibernate-orm/blob/7.2/hibernate-core/src/main/java/org/hibernate/StatelessSession.java); also the
[7.0 migration guide](https://github.com/hibernate/hibernate-orm/blob/7.0/migration-guide.adoc), which
flags it as a behaviour change)

The sibling note lists *"nor interact with any second-level cache"* among `StatelessSession`'s
designed-in limitations and treats it as an RLS **feature** — nothing outlives the transaction that
authorised reading it. **That limitation is gone on ORM 7.**

It is harmless here only because [`rls-pooling.md` §2](rls-pooling.md) already requires the second-level
cache to be off globally, which makes the session's cache mode moot. But the *reason* it is safe has
changed: the guarantee now comes from a global setting rather than from the shape of the session. If
anyone ever enables the L2 cache for a "harmless read-only reference table", a stateless session will
start reading it by default — outside any tenant context. **Keep
`spring.jpa.properties.hibernate.cache.use_second_level_cache=false` explicit in the properties block
next to `spring.jpa.open-in-view=false`, rather than relying on the default**, and say in a comment why.

---

## 3. `StatelessSession` and Spring Framework 7

[`hibernate-vs-the-schema.md` §6](hibernate-vs-the-schema.md) says: Boot 3.x has no `StatelessSession`
transaction integration; a no-arg `openStatelessSession()` inside `@Transactional` takes a *different*
pooled connection with no `app.current_tenant`, which under fail-closed RLS is silently wrong data; and
Spring Framework 7 fixes it. All of that is correct. This section establishes exactly what the fix is,
and answers the one question that decides whether it is usable here.

### What Framework 7 provides

> `LocalSessionFactoryBean` exposes transactional `Session` and `StatelessSession` proxy references for
> dependency injection, along the lines of the JPA 3.2 arrangement for `EntityManager` injection.
> … We recommend using Hibernate ORM 7.2 (rather than 7.1) for `StatelessSession` support since we can
> derive a transactional `StatelessSession` from the current transactional `Session` there.

([Spring Framework 7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes))

Concretely, `spring-orm` gained `org.springframework.orm.jpa.hibernate.SharedSessionCreator`, whose
`createSharedStatelessSession(SessionFactory)` returns a JDK proxy that

> automatically delegates every `StatelessSession` method invocation to the current thread-bound
> transactional session instance. On the first invocation within a new transaction, a `StatelessSession`
> will be opened **for the current transactional JDBC Connection**.

([`SharedSessionCreator.java`, v7.0.9](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-orm/src/main/java/org/springframework/orm/jpa/hibernate/SharedSessionCreator.java))

The same javadoc names the proxy-free alternative: call
`SpringSessionContext.currentStatelessSession(sessionFactory)` per operation.

**Which Hibernate it wants.** ORM **7.2+** for the good path; 7.0/7.1 fall back. Both paths ship in
Framework 7.0.9 and are selected by a capability check, not by configuration:

```java
private static final boolean DERIVE_STATELESS_FROM_SESSION =
        ClassUtils.hasMethod(Session.class, "statelessWithOptions");  // Hibernate 7.2+
```

Boot 4.0.8 manages ORM 7.2.24 and Boot 4.1.1 manages ORM 7.4.5, so **on either Boot 4 line the 7.2+ path
is the one that runs**, with no pinning required. On Boot 3.5 none of this exists: Framework 6.2 has no
`SharedSessionCreator`, and ORM 6.6 has no `statelessWithOptions()`.

### The load-bearing question: same transaction, therefore same connection?

**Yes — and it is settled in the source rather than inferred.** From
`SpringSessionContext.currentStatelessSession`:

```java
if (holder != null && DERIVE_STATELESS_FROM_SESSION) {
    // from HibernateTransactionManager on Hibernate 7.2+
    session = holder.getSession().statelessWithOptions().connection().open();
}
else {
    // from JdbcTransactionManager (or HibernateTransactionManager against Hibernate 7.0/7.1)
    session = sessionFactory.openStatelessSession(determineConnection(sessionFactory, holder));
}
```

([`SpringSessionContext.java`, v7.0.9](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-orm/src/main/java/org/springframework/orm/jpa/hibernate/SpringSessionContext.java))

The `.connection()` call is the whole answer, and Hibernate documents precisely what it means:

> When resource-local transaction management is used: by default, each session executes with its own
> dedicated JDBC connection and therefore has its own isolated transaction, but **calling the
> `connection()` method specifies that the connection, and therefore also the JDBC transaction, should be
> shared from parent to child.**

([hibernate-orm 7.2, `SharedStatelessSessionBuilder.java`](https://github.com/hibernate/hibernate-orm/blob/7.2/hibernate-core/src/main/java/org/hibernate/SharedStatelessSessionBuilder.java), `@since 7.2`)

Read that inverted, because the inverse is the trap: **`statelessWithOptions()` *without* `.connection()`
is the same bug as the no-arg `openStatelessSession()`** — its own connection, its own transaction, no
tenant GUC, and under fail-closed RLS an empty result rather than an error. Spring calls `.connection()`.
Hand-rolled code must too.

The fallback branch is equally explicit: `determineConnection(sessionFactory, holder)` returns
`session.getJdbcCoordinator().getLogicalConnection().getPhysicalConnection()` when a Hibernate
`SessionHolder` is bound, and otherwise `DataSourceUtils.getConnection(dataSource)` — the thread-bound
connection. Both are the transaction's own connection.

**For this project that means the tenant GUC established by `set_config('app.current_tenant', ?, true)`
is visible to the stateless session, because it is literally the same server session inside the same
transaction.** The manual `session.doWork(conn -> openStatelessSession(conn))` dance the sibling note
prescribes for Boot 3.x becomes unnecessary on Boot 4 — the framework does the identical thing, in one
place, where no route can forget it. That is the same argument [`rls-pooling.md` §5](rls-pooling.md)
makes for centralising the tenant hook.

### How it is obtained and injected on Spring Boot 4 — the part that is not automatic

Two caveats, and the second is the one to plan around.

**1. Boot does not auto-configure a `StatelessSession` bean.** A code search of
`spring-projects/spring-boot` at `v4.1.1` returns **zero** occurrences of `StatelessSession`,
`SharedSessionCreator` or `LocalSessionFactoryBean`. The Framework release note's phrasing —
*"`LocalSessionFactoryBean` exposes …"* — describes the **native Hibernate** arrangement; Boot's JPA
auto-configuration uses `LocalContainerEntityManagerFactoryBean` instead. So nothing is injectable until
you declare it:

```java
@Bean
StatelessSession statelessSession(EntityManagerFactory emf) {
    return SharedSessionCreator.createSharedStatelessSession(emf.unwrap(SessionFactory.class));
}
```

One bean, in application code, over a public Framework API. Swapping Boot's JPA auto-configuration for a
hand-declared `LocalSessionFactoryBean` just to get the exposure "for free" would cost far more than it
saves; don't.

**2. Under Boot's default `JpaTransactionManager` the chain is one link longer, and no single document
closes it.** `currentStatelessSession` looks up `TransactionSynchronizationManager.getResource(sessionFactory)`
and expects a Hibernate `SessionHolder` — which is what `HibernateTransactionManager` binds.
`JpaTransactionManager` binds an `EntityManagerHolder` keyed on the `EntityManagerFactory`, so that
lookup misses, `holder` is `null`, and the fallback runs `DataSourceUtils.getConnection(dataSource)`.
That still yields the transaction's own connection *provided* `JpaTransactionManager` has exposed it —
and it does: `afterPropertiesSet()` adopts the `DataSource` from `EntityManagerFactoryInfo`, and `doBegin`
binds a `ConnectionHolder` obtained from `getJpaDialect().getJdbcConnection(em, …)`
([`JpaTransactionManager.java`, v7.0.9](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-orm/src/main/java/org/springframework/orm/jpa/JpaTransactionManager.java)).
Framework 7.0.4 also added a JPA-side "entity agent" path in which
`HibernateJpaDialect.deriveEntityAgent(...)` does
`entityManager.unwrap(Session.class).statelessWithOptions().connection().open()`
([`HibernateJpaDialect.java`, v7.0.9](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-orm/src/main/java/org/springframework/orm/jpa/vendor/HibernateJpaDialect.java))
— the same `.connection()` guarantee reached through `EntityManagerFactoryUtils`.

**Three classes have to agree for that to be the same connection, and no document states the conclusion.
This needs a test, and it is a small one.** Inside one `@Transactional` method, after the tenant hook has
run:

- read `nullif(current_setting('app.current_tenant', true), '')` through the injected `StatelessSession`
  and assert it equals the request's Tenant; **and**
- read `pg_backend_pid()` through the `EntityManager` and through the `StatelessSession` and assert the
  two are equal — blunter, and impossible to satisfy by accident.

Under fail-closed RLS the failure mode of getting this wrong is an empty result set rather than an
exception, so it has to be asserted, not observed. Write this test **before** any write path depends on
the stateless session; it is the single cheapest de-risking action in this whole note (§Recommendation).

### Measured, 2026-09-18: the `SharedSessionCreator` bean does not work here

The test the paragraph above asks for was written (`StatelessSessionConnectionIT`), and the
three-class chain does **not** close on Boot 4.1.1 / Framework 7.0.9 / ORM 7.4.5. Every call on the
`SharedSessionCreator.createSharedStatelessSession(sessionFactory)` proxy inside a `@Transactional`
method fails with `IllegalStateException: Already value [EntityManagerHolder] ... bound to thread`.

The cause is one line of the source quoted above. In ORM 7 the `SessionFactoryImpl` *is* the
`EntityManagerFactory`, so `TransactionSynchronizationManager.getResource(sessionFactory)` finds the
`EntityManagerHolder` that `JpaTransactionManager` bound — and `currentStatelessSession` has no
branch for an `EntityManagerHolder` (`currentSession` does). `holder` stays `null`, the fallback
opens a stateless session on `DataSourceUtils.getConnection(dataSource)` — correctly the
transaction's own connection — and then `bindSessionHolder` tries to bind a second holder under a
key that is already taken. The JPA-side entity-agent path (`EntityManagerHolder.setEntityAgent`)
would handle it, but its accessors are package-private and it is wired only for JPA 4.0's
`jakarta.persistence.EntityAgent`, which this stack does not have.

**What the project does instead:** `io.nostro.persistence.TransactionalStatelessSession` derives
the session from the transactional `Session` with `statelessWithOptions().connection().open()` —
the same call as `HibernateJpaDialect.deriveEntityAgent` — binds it to the transaction under its
own key, closes it at completion, and throws outside a transaction. The same test then asserts the
three `pg_backend_pid()` values agree, the tenant GUC is visible through it, nothing leaks across
pooled transactions, and closing the child leaves the parent's transaction committable.

### What does not change

Everything else the sibling note records about `StatelessSession` holds on ORM 7: no cascades,
collections ignored, event listeners and interceptors bypassed (a fourth reason the insert-only guard
belongs in the database), and — the sharp one — *"when an exception is thrown by a stateless session, the
current transaction is not automatically marked for rollback"*
([`StatelessSession.java`](https://github.com/hibernate/hibernate-orm/blob/7.2/hibernate-core/src/main/java/org/hibernate/StatelessSession.java)).
A half-written, unbalanced Entry still reaches COMMIT if someone catches the exception and continues.
That is a discipline, not a version.

---

## 4. What Boot 4 / Framework 7 add that this design would actually use

Selective on purpose. A feature earns a row here only if this system would touch it, and each row says
what it replaces.

| Feature | Verdict for Nostro | Replaces |
|---|---|---|
| Core retry: `@EnableResilientMethods` + `@Retryable` | **Use it** — it is already in the design, under another name | the Spring Retry dependency |
| `@ConcurrencyLimit` | **Worth evaluating** for the hot Account path | nothing; there is no equivalent today |
| Transactional `StatelessSession` (§3) | **Use it** | a hand-rolled `doWork(…)` + `openStatelessSession(conn)` |
| JSpecify null-safety | **Use it**, at least in `nostro-domain` | Spring's deprecated JSR-305-style `@Nullable` |
| `@Autowired EntityManager` (JPA 3.2) | Minor, but tidy | `@PersistenceContext` |
| Observability / Micrometer 1.17 + OTel 1.62 | Use it — but it is *newer*, not *new* | the same story on Boot 3.5, older versions |
| Spring Kafka 4 / Kafka 4.x clients | Comes along; note what it removes | Spring Kafka 3.3 |
| API versioning | **Optional. Decoration unless a v2 exists** | a hand-written `/v1` path prefix |
| HTTP service clients (`@ImportHttpServices`) | **Not used** — the internal call is gRPC | nothing here |

### Core retry — the one that changes an existing decision

Framework 7 absorbed Spring Retry:

> The Spring team has been working on the Spring Retry project for a very long time, and we decided that
> it was time to trim unnecessary features, revisit some of its APIs, and merge the resulting work into
> the `spring-core` module of Spring Framework.

with `RetryTemplate`/`RetryPolicy` in `org.springframework.core.retry`, and

> Aligned with `core.retry`, there is also `@Retryable` annotation support in the `spring-context` module,
> accompanied by a `@ConcurrencyLimit` annotation based on Spring's concurrency throttling support. Both
> of those can be conveniently enabled through `@EnableResilientMethods`.

([Framework 7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes))

[`hot-account-contention.md` §3](hot-account-contention.md) builds its serialization-failure retry on
Spring Retry — `@EnableRetry`, `@Retryable(retryFor = …, maxAttempts = 4, backoff = @Backoff(delay = 25,
multiplier = 2.0, maxDelay = 400))`, on a bean *outside* the transaction proxy. On Boot 4 that snippet
does not compile as written, and **Boot 4 removed dependency management for Spring Retry** ([Spring Boot
4.0 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes)),
so the dependency would have to be version-pinned by hand to keep it.

The replacement is `org.springframework.resilience.annotation.Retryable`, whose attributes are
`includes`/`excludes` (or `predicate`), `maxRetries` (default 3), `delay` (default 1000ms), `multiplier`,
`maxDelay`, `jitter` and `timeout`
([`Retryable.java`, v7.0.9](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/resilience/annotation/Retryable.java)).
Two things are worth noting rather than glossing:

- **`jitter` is a first-class attribute.** The sibling note wants jittered exponential backoff so that N
  writers contending on one Account do not re-collide in lockstep; with Spring Retry that means reaching
  for `ExponentialRandomBackOffPolicy`. Here it is one attribute.
- **`maxRetries` counts retries, not attempts.** `maxAttempts = 4` becomes `maxRetries = 3`. Exactly the
  kind of off-by-one that silently changes a contention benchmark.

Everything §3 of that note says about *where* the retry boundary goes — outside the transaction proxy, on
a different bean, because Postgres has already aborted the transaction and because a `SERIALIZABLE`
conflict can surface at COMMIT — is a property of transactions, not of the retry library. It survives the
swap unchanged.

**`@ConcurrencyLimit`** has no counterpart in the current design and is worth a look on the constrained-
Account path: an in-JVM cap on concurrent writers to the hot row, ahead of the database lock queue that
[`hot-account-contention.md` §5](hot-account-contention.md) worries about starving the connection pool.
**Estimate, not a finding:** it throttles per method within one JVM, so with three deployables and any
horizontal scaling it is a mitigation, not a bound. Measure before claiming anything.

### JSpecify null-safety

> The Spring Framework codebase is annotated with JSpecify annotations to declare the nullness of APIs,
> fields, and related type usage … significant enhancements compared to the previous JSR 305 based
> arrangement, such as properly defined specifications, a canonical dependency with no split-package
> issue, better tooling, better Kotlin integration, and the capability to specify nullness for generic
> types, arrays, and vararg elements.

([Framework 7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes))

This is the one item on the list that speaks directly to gap #1 in [ADR-0016](../adr/0016-the-constraints-this-project-was-given.md) — *"almost
nothing so far exercises Java itself"*. `nostro-domain` depends on nothing but the JDK
([`grpc-and-multi-module-layout.md`](grpc-and-multi-module-layout.md)), which makes it the natural place
to put `@NullMarked` at the package level and run a checker in CI: a compile-time guarantee about the
domain model, alongside the database-level guarantees the project already argues for. Spring's own
`org.springframework.lang.Nullable`/`NonNull` are deprecated in favour of it, and on the actuator side
`org.springframework.lang.Nullable` **no longer marks an endpoint parameter optional** — a silent
behaviour change if it is used.

Boot's migration guide warns in the other direction too: *"If you are using a null checker in your build
or using Kotlin, this could lead to compilation failures because of now nullable or non-nullable types"*
([Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)).
That is a cost paid once, in the direction of finding real bugs.

### `@Autowired EntityManager`

> An `EntityManagerFactory` as well as its shared `EntityManager` reference can be generally injected via
> `@Inject`/`@Autowired` now, including qualifier support for selecting a specific persistence unit …
> there is no need for a separate `SharedEntityManagerBean` definition anymore.

([Framework 7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes), on the JPA 3.2 baseline)

Small, but it lands exactly on [`hibernate-vs-the-schema.md` §5](hibernate-vs-the-schema.md), whose
recommendation is *"don't call `save()`"* — inject the `EntityManager` and call `persist()`. On Boot 4
that injection is ordinary constructor injection rather than `@PersistenceContext` field injection, which
makes the recommended path the *shorter* path for once.

### Observability

Boot 4.1.1 manages Micrometer **1.17.1**, Micrometer Tracing **1.7.1** and the OpenTelemetry BOM
**1.62.0** (Boot 4.0.8: 1.16.7 / 1.6.7 / 1.55.0), against 1.15.12 / 1.5.12 / 1.49.0 on Boot 3.5.16. This
addresses gap #4 in [ADR-0016](../adr/0016-the-constraints-this-project-was-given.md), but **honestly: it is the same story on both branches** —
Boot 3.5 has Micrometer, tracing, and Actuator too. What Boot 4 changes is mostly packaging and naming,
which is migration cost rather than capability:

- new starters `spring-boot-starter-micrometer-metrics`, `spring-boot-starter-opentelemetry`,
  `spring-boot-starter-micrometer-tracing-*`;
- `org.springframework.boot.observation` → `org.springframework.boot.micrometer.observation`,
  `org.springframework.boot.tracing` → `org.springframework.boot.micrometer.tracing`;
- `management.tracing.enabled` → `management.tracing.export.enabled`, and
  `@ConditionalOnEnabledTracing` → `@ConditionalOnEnabledTracingExport`;
- **liveness/readiness probes are enabled by default** — which is a small genuine win for the
  `docker compose` health checks this project needs anyway.

One trap worth carrying forward: Micrometer 1.16 moved to a Prometheus client with Unicode support, so
`:` in a meter or tag name is **no longer sanitised to `_`**. Nothing in this project has such names yet;
don't introduce them. (There is no Micrometer 2 — the line is still 1.x.)

### Spring Kafka 4

Boot 4.1.1 manages Spring Kafka 4.1.1 and kafka-clients 4.2.1; Boot 3.5.16 manages 3.3.16 and 3.9.2.
Relevant to [`ordering-and-watermarks.md`](ordering-and-watermarks.md) and
[`ci-and-testcontainers-budget.md`](ci-and-testcontainers-budget.md):

- **ZooKeeper support is gone and `EmbeddedKafkaBroker` is KRaft-only.** The CI note already chooses a
  real broker in a container over the embedded one, so this mostly removes a footgun.
- **Spring Kafka dropped its Spring Retry dependency** in favour of Framework 7's core retry — the same
  swap as above, so the two land together rather than leaving one library behind for one use.
- Jackson 3 preferred, Jackson 2 deprecated (see §5).

The producer-ordering guarantees the sibling note relies on (`max.in.flight.requests.per.connection`,
idempotence, per-partition ordering keyed by `tenant_id`) are Kafka protocol properties and are unaffected.

### API versioning — optional, and say so

> Spring MVC and WebFlux now provide first class support for API versioning. On the server side, you can
> map requests to controller methods and route requests to functional endpoints by taking into account
> the API version of the request.

([Framework 7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes);
Boot 4 adds `spring.mvc.apiversion.*` auto-configuration.)

With one version of one API, this replaces a `/v1` string in a `@RequestMapping`. It becomes interesting
only where a real second version exists — and this design does have one plausible candidate, the shape of
the Position token in Balance and Entry responses ([ADR
0007](../adr/0007-balances-are-projected-and-disclose-their-position.md)), which is exactly the sort of
field whose representation a caller might have to be migrated off. **Do not build it speculatively.** If
a second version never appears, configuring version resolution is decoration, and a reviewer who reads
carefully will see that.

### HTTP service clients — not used, and the reason is a decision already made

`@ImportHttpServices` and the declarative `RestClient` proxies are the headline REST feature of Framework
7. This system has no HTTP client: the one internal synchronous call is API service → projection service
over **gRPC** ([ADR 0008](../adr/0008-what-kafka-redis-and-grpc-are-for.md),
[`grpc-and-multi-module-layout.md`](grpc-and-multi-module-layout.md)), and there are no external services
at all because the project is zero-budget and must run entirely under `docker compose`. Listing it as a
benefit would be padding. It is not one here.

### Removed or deprecated in Boot 4 that a Boot 3 tutorial still teaches

Every item below is something a 2024–2025 tutorial would tell you to write, and which will not work:

| A Boot 3 tutorial says | On Boot 4 |
|---|---|
| `spring-boot-starter-web` | Deprecated in favour of `spring-boot-starter-webmvc` (the old one still resolves, for now) |
| Add `org.flywaydb:flyway-core` and you're done | Needs `spring-boot-starter-flyway` **plus** `org.flywaydb:flyway-database-postgresql` |
| `@MockBean` / `@SpyBean` | **Removed.** Use `@MockitoBean` / `@MockitoSpyBean` — and they are not allowed on `@Configuration` class fields |
| `@SpringBootTest` gives you `MockMvc` / `TestRestTemplate` | It does not. Add `@AutoConfigureMockMvc`, or `@AutoConfigureRestTestClient` and the `spring-boot-resttestclient` dependency |
| `new RestTemplate()` | Deprecated in the Framework 7 docs, formally `@Deprecated` in 7.1, removal targeted at Framework 8 |
| `authorizeRequests`, `and()`, `AntPathRequestMatcher`, `MvcRequestMatcher` | **All removed in Spring Security 7.** `authorizeHttpRequests`, lambda DSL only, `PathPatternRequestMatcher` |
| Define an `ObjectMapper` bean to customise JSON | No longer overrides the auto-configured mapper — define a `JsonMapper` bean; and `com.fasterxml.jackson.databind` is now `tools.jackson.databind` |
| `@JsonComponent`, `@JsonMixin` | `@JacksonComponent`, `@JacksonMixin` |
| Undertow as the embedded server | **Removed** — it is not Servlet 6.1 compatible |
| `new PostgreSQLContainer<>(…)` from `org.testcontainers.containers` | Testcontainers 2.x: artifact `org.testcontainers:testcontainers-postgresql`, class `org.testcontainers.postgresql.PostgreSQLContainer` |
| `@EnableRetry` / `@Retryable` from `org.springframework.retry` | Spring Retry is no longer dependency-managed; use `@EnableResilientMethods` and `org.springframework.resilience.annotation.Retryable` |
| `management.tracing.enabled` | `management.tracing.export.enabled` |

(Sources: [Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide),
[Boot 4.0 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes),
[Framework 7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes),
[What's New in Spring Security 7.0](https://docs.spring.io/spring-security/reference/7.0/whats-new.html),
[Testcontainers 2.0.0 release notes](https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0).)

That table is the honest cost of Boot 4 for someone learning from the internet — and, read the other way,
it is also a list of things that are simply *true of Java in 2026* and that a candidate will have to know
either way.

---

## 5. Migration cost and breaking changes

A caveat that shapes this whole section: **this project has no code yet.** "Migration cost" here means
two different things, and they should not be conflated:

- **The greenfield cost** — writing against Boot 4 from an empty repo. Most of what follows is then not a
  migration at all; it is just "the API is different from the tutorial you are reading".
- **The later-upgrade cost** — starting on Boot 3.5 and moving to Boot 4 afterwards, which is the path
  [`grpc-and-multi-module-layout.md`](grpc-and-multi-module-layout.md) contemplates when it calls Spring
  gRPC 1.x *"the exit"*. That is a real migration, and it is the one costed below.

### The modularisation, which is the headline break

> Spring Boot 4.0 has a new modular design and now ships smaller focused modules rather than several
> large jars.

with the convention:

> All Spring Boot modules are named `spring-boot-<technology>`. The root package of each module is
> `org.springframework.boot.<technology>`. All "starter" POMs are named `spring-boot-starter-<technology>`.

([Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide))

For a starter-based Maven build — which this is — the consequences that actually bite:

1. **Technologies that had no starter now need one.** *"For instance, if you are using Flyway or
   Liquibase you used to only have the relevant third-party dependency. You now need to replace that with
   `spring-boot-starter-flyway` or `spring-boot-starter-liquibase`."* This project uses Flyway
   ([`rls-pooling.md` §5](rls-pooling.md)), so it is directly affected.
2. **Renamed starters.** `spring-boot-starter-web` → `spring-boot-starter-webmvc`;
   `spring-boot-starter-aop` → `spring-boot-starter-aspectj`; the OAuth2 starters gain a `security-`
   prefix. The old names remain but are deprecated.
3. **Test starters are per technology now.** `spring-boot-starter-test` alone no longer brings what it
   used to; you list the starter of each technology under test, and each brings `spring-boot-starter-test`
   transitively. Concretely: `@WithMockUser` needs `spring-boot-starter-security-test`.
4. **Package relocations follow one systematic rule:**
   `org.springframework.boot.autoconfigure.<tech>.*` → `org.springframework.boot.<tech>.autoconfigure.*`
   (e.g. `org.springframework.boot.jpa.autoconfigure.JpaProperties`). `spring-boot-autoconfigure` still
   exists but holds only the machinery (`@SpringBootApplication`, `@EnableAutoConfiguration`,
   `@AutoConfiguration`, conditions). Individually called out: `@EntityScan` →
   `org.springframework.boot.persistence.autoconfigure.EntityScan`; `TestRestTemplate` →
   `org.springframework.boot.resttestclient.TestRestTemplate`; `EnvironmentPostProcessor` →
   `org.springframework.boot`; `BootstrapRegistry` → `org.springframework.boot.bootstrap`.
5. **Auto-configuration classes are no longer extensible** — *"Public members (aside from constants) have
   been removed from auto-configuration classes"*. Anything that subclassed one has to be rewritten.
   Nothing here does.

**`spring-boot-testcontainers` is unchanged** — same module, same
`org.springframework.boot.testcontainers.*` package, `@ServiceConnection` neither moved nor deprecated.
The churn on that front is entirely Testcontainers' own (§6).

**The documented escape hatch.** *"`spring-boot-starter` becomes `spring-boot-starter-classic`;
`spring-boot-starter-test` becomes `spring-boot-starter-test-classic`"*, which pull in all modules and let
the application compile; *"We recommend that you eventually migrate your application away from using the
classic starters."* The guide's strategy is: classic starters → fix imports → drop the classic starters →
let the compile errors name the real starters you need. That is a genuinely good migration ladder, and it
is worth knowing that it exists.

### The other breaking changes that touch this stack

- **Jakarta EE 11 / Servlet 6.1.** *"Spring Boot 4 is based on Jakarta EE 11 and requires a Servlet 6.1
  baseline."* Tomcat 11, Jakarta Persistence 3.2, Bean Validation 3.1. **Undertow support is removed
  outright** because it is not Servlet 6.1 compatible. `javax.annotation` and `javax.inject` are no longer
  honoured at all. `MutablePersistenceUnitInfo` no longer implements
  `jakarta.persistence.spi.PersistenceUnitInfo`.
- **Jackson 3 is the default.** Boot 4 manages Jackson **3.1.5** (Boot 3.5.16: 2.21.4).
  `com.fasterxml.jackson` becomes `tools.jackson` — *except* `jackson-annotations`, which keeps its old
  group and package, so `@JsonProperty` and friends are safe while `ObjectMapper`, `JsonNode` and
  `SimpleModule` all move. Defining an `ObjectMapper` bean **no longer overrides** the auto-configured
  mapper (define a `JsonMapper`); `@JsonComponent`/`@JsonMixin` become `@JacksonComponent`/`@JacksonMixin`;
  and all classpath modules are now auto-registered where Boot 3 registered only "well-known" ones. There
  is a `spring.jackson.use-jackson2-defaults=true` escape and a deprecated `spring-boot-jackson2` module.
  For this project the exposure is small — one error shape and a handful of DTOs — but the error-shape
  contract is exactly the thing you do not want silently re-serialised, so it deserves a serialisation
  test either way.
- **Test-support removals.** `@MockBean`/`@SpyBean` are **removed** in favour of
  `@MockitoBean`/`@MockitoSpyBean`, which *"are allowed to be used as fields in test classes, but not in
  `@Configuration` classes"*. `@SpringBootTest` no longer configures MockMvc, WebClient or
  `TestRestTemplate` by itself. JUnit 6 is the baseline (Boot 4 manages JUnit Jupiter 6.0.3; Boot 3.5.16
  manages 5.12.2) and JUnit 4 support in the TestContext framework is deprecated. Given that
  [`ci-and-testcontainers-budget.md`](ci-and-testcontainers-budget.md) is entirely about the shape of the
  test suite, **this is where a Boot 3 → Boot 4 move would actually be felt in this repo.**
- **Spring Security 7** removes `and()` from the `HttpSecurity` DSL, removes `authorizeRequests` in
  favour of `authorizeHttpRequests`, drops `AntPathRequestMatcher` and `MvcRequestMatcher` for
  `PathPatternRequestMatcher`, and makes login redirects relative. [ADR
  0006](../adr/0006-the-tenant-comes-from-the-credential.md) puts a credential at the centre of the
  design, so the security configuration is not incidental here — but it is also new code either way.
- **Configuration property renames.** There is an official list — the [Spring Boot 4.0 Configuration
  Changelog](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Configuration-Changelog)
  — and **`spring-boot-properties-migrator` still exists for 4.0**: *"Once added as a dependency to your
  project, this will not only analyze your application's environment and print diagnostics at startup, but
  also temporarily migrate properties at runtime for you."* Of the renames, the ones this project would
  hit are `management.tracing.enabled` → `management.tracing.export.enabled`, and
  `spring.dao.exceptiontranslation.enabled` → `spring.persistence.exceptiontranslation.enabled`. The
  properties this design leans on hardest — `spring.jpa.open-in-view`, `spring.jpa.properties.hibernate.*`
  — are unchanged.
- **Batch, if it ever appears:** Spring Batch now runs in-memory by default and no longer writes metadata
  to your database unless you use `spring-boot-starter-batch-jdbc`. Nothing here uses Batch; noted because
  it is the kind of silent behaviour change that would be very bad in a ledger.

### Tooling

**OpenRewrite has a recipe: `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0`** (in
`rewrite-spring`), composed of ~20 sub-recipes that cover the path 3.5 → Framework 7 → Security 7 →
properties → `@MockBean` replacement → web-server class relocations → Hibernate 7 → Testcontainers 2 →
springdoc 3 → **modular starters** (which drives `AddDependency` off `onlyIfUsing:` import patterns and
then rewrites the `org.springframework.boot.autoconfigure.<tech>` imports)
([OpenRewrite recipe](https://docs.openrewrite.org/recipes/java/spring/boot4/upgradespringboot_4_0-community-edition),
[`spring-boot-40-modular-starters.yml`](https://github.com/openrewrite/rewrite-spring/blob/main/src/main/resources/META-INF/rewrite/spring-boot-40-modular-starters.yml)).

Three honest caveats:

1. **Spring does not mention it.** OpenRewrite appears nowhere in the Boot 4.0 Migration Guide or the
   Framework 7.0 Release Notes (verified). Spring's own tooling story is `spring-boot-properties-migrator`
   plus the classic-starter ladder.
2. **Licensing.** The recipe YAML is under the Moderne Source Available License rather than Apache-2.0,
   with precompiled artifacts for Moderne customers and a broader "Moderne Edition" of the same recipe.
   For a zero-budget public repo, plan on running the community edition from source.
3. **Coverage is undocumented.** Neither OpenRewrite nor Spring states what the recipe misses.
   **Unconfirmed — treat it as a first pass that still needs a green build behind it**, not as a migration.

`spring-projects-experimental/spring-boot-migrator` is effectively dead (last push 2024-08-06, before Boot
4 existed). Do not plan around it.

### The cost, summarised

For a **greenfield** build the migration cost is close to zero and is paid as "read the current docs, not
a 2024 blog post". For a **3.5-first-then-upgrade** plan the cost is real but bounded: dependency
restructuring in three `pom.xml` files, a test-annotation sweep (`@MockBean` → `@MockitoBean`,
`@SpringBootTest` no longer implying MockMvc), a Testcontainers 1.x → 2.x rename sweep, a Jackson
2 → 3 import sweep, a Security DSL rewrite, and the Hibernate 6 → 7 behaviour changes in §2. Days, not
weeks — but paid **twice**, because everything written against Boot 3.5 gets written again.

---

## 6. Ecosystem readiness for this exact stack

Every library this project has committed to, checked against Boot 4. Versions read from Maven Central
metadata and published POMs; "in the BOM" means `spring-boot-dependencies` manages the version for you.

| Library | Boot 4 today? | Version | In Boot 4's BOM? |
|---|---|---|---|
| **springdoc-openapi** | **Yes — the 3.x line is the Boot 4 line** | 3.1.1 (2026-09-06) | No (never was — pin it) |
| Testcontainers Java | Yes, via **2.x** | 2.0.5 in Boot 4.0.8 and 4.1.1 | Yes |
| Boot `@ServiceConnection` | Yes — **same package, no deprecation** | `spring-boot-testcontainers` 4.x | Yes |
| Flyway | Yes | 11.14.1 (Boot 4.0.x) / 12.4.0 (Boot 4.1.x) | Yes, incl. `flyway-database-postgresql` |
| Liquibase | Yes | 5.0.3 | Yes |
| Spring for Apache Kafka | Yes | 4.0.7 (Boot 4.0.8) / 4.1.1 (Boot 4.1.1) | Yes |
| kafka-clients | Yes | 4.1.2 / 4.2.1 | Yes |
| Spring Data Redis | Yes | via `spring-data-bom` 2025.1.7 / 2026.0.1 | Yes |
| Lettuce | Yes | 6.8.2 (Boot 4.0.x) → **7.5.2** (Boot 4.1.x) | Yes |
| Redisson Spring Boot starter | Yes, from **4.0.0** (2025-12-16) | 4.7.0 (2026-08-04) | No |
| Bucket4j Spring Boot starter | Yes, from **0.14.0** (2026-02-28) | 0.14.0 — **~6 months stale** | No |
| `gatling-maven-plugin` | N/A — no Boot coupling | 4.21.12 (2026-09-07) | No |
| Micrometer / Micrometer Tracing | Yes | 1.17.1 / 1.7.1 (Boot 4.1.1) | Yes |
| OpenTelemetry BOM | Yes | 1.55.0 / 1.62.0 | Yes |

**Nothing this project needs was dropped from Boot 4's managed dependencies.** The four that are not
managed — springdoc, Redisson, Bucket4j, Gatling — were not managed on Boot 3.5 either.

### springdoc — the feared gap does not exist

This was the one that could have decided the whole question, since [ADR-0016](../adr/0016-the-constraints-this-project-was-given.md) requires
*"OpenAPI generated from the code, never hand-maintained"*, and springdoc has historically lagged Boot
releases by months.

**It did not lag this time.** `springdoc-openapi` **3.0.0 shipped 2025-11-21 — one day after Boot 4.0.0
GA** — and the line has been maintained since: 3.1.1 on 2026-09-06, four days ago. Verified directly:
`springdoc-openapi:3.1.1`'s POM declares `<parent>` `spring-boot-starter-parent:4.1.0`, so it is built
against Boot 4.1, not merely tolerant of it. The split is clean: **springdoc 3.x → Boot 4, springdoc 2.x
→ Boot 3** (2.9.1 is the last 2.x).

Use `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1`, **with an explicit `<version>`** — it is
not in the BOM, on either branch.

Two things to carry into the build:

- **Take 3.1.1 or later, not 3.0.x.** 3.1.1 is a security patch release (bundled swagger-ui / DOMPurify
  XSS, plus fixes in springdoc's new MCP feature, which 3.1.1 also makes opt-in behind
  `springdoc.ai.mcp.enabled=true`).
- **One open Boot-4-specific bug** ([springdoc#3333](https://github.com/springdoc/springdoc-openapi/issues/3333)):
  model `properties`/`required` go missing when `@JsonSerialize`/`@JsonDeserialize` use **Jackson 3**
  (`tools.jackson`) annotations. Boot 4 defaults to Jackson 3, so this is live. This project's DTOs are
  simple and are unlikely to need custom serializers — but the money type is the obvious exception. **If a
  custom `Money` serializer is written, check the generated schema rather than assuming it.**

And to refute the rumour explicitly: **Spring Boot 4 ships no first-party OpenAPI generation.** Neither
the 4.0 nor the 4.1 release notes mention OpenAPI. What Boot 4 added is auto-configuration for **API
versioning** (`spring.mvc.apiversion.*`) — request routing, not schema generation (§4). There is no
`spring-boot-starter-openapi`, and no reason to look at springwolf or raw swagger-core here.

### Testcontainers — the real churn, and it is not Spring's

`@ServiceConnection` is fine: still `org.springframework.boot.testcontainers.service.connection.ServiceConnection`
in `spring-boot-testcontainers`, unmoved and undeprecated. **Testcontainers itself went 1.x → 2.x**, and
Boot 4 manages 2.0.5:

- Module coordinates gained a prefix: `org.testcontainers:postgresql` → **`org.testcontainers:testcontainers-postgresql`**
  (and `-kafka`, `-junit-jupiter`).
- Classes moved out of `org.testcontainers.containers` into per-technology packages:
  `org.testcontainers.postgresql.PostgreSQLContainer`, `org.testcontainers.kafka.KafkaContainer`.
- **JUnit 4 support is gone**, and generics were removed from many container classes.

([Testcontainers 2.0.0 release notes](https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0))

Spring's migration guide does not mention any of this — it arrives through dependency management. Every
Testcontainers snippet written before late 2025 will fail to compile, and a deprecated container class
that survives compilation can then fail at runtime with *no `ConnectionDetails` found for source*.
[`ci-and-testcontainers-budget.md`](ci-and-testcontainers-budget.md)'s conclusions are unaffected in
substance — singleton containers over `withReuse(true)`, one broker for the suite, the Spring context
cache dominating the bill — but **its code shapes are 1.x and would need the rename sweep.**

### Flyway

Two things, one of which the sibling notes should absorb:

- **Boot 4 requires `spring-boot-starter-flyway`**, where Boot 3 needed only `flyway-core` (§5).
- **Postgres support is a separate artifact**: `spring-boot-starter-flyway` brings only
  `org.flywaydb:flyway-core`, so add **`org.flywaydb:flyway-database-postgresql`** with no `<version>` —
  the BOM manages it. (This split is a Flyway 10 change, not a Boot 4 one; it applies on Boot 3.5 too, and
  is the single most common "Flyway can't find a driver for jdbc:postgresql" failure.)

Nothing about [`rls-pooling.md` §5](rls-pooling.md) changes: migrations still run as the owner role over
the **direct, unpooled** endpoint, because they take session-level locks.

### Kafka, Redis, rate limiting

- **Spring Kafka 4** is covered in §4. The ordering guarantees in
  [`ordering-and-watermarks.md`](ordering-and-watermarks.md) are protocol-level and unaffected.
- **Spring Data Redis / Lettuce** are managed and current. Note that Lettuce jumps **6.8 → 7.5 between
  Boot 4.0 and Boot 4.1** — if 4.1 is chosen, that is a major-version bump in the Redis driver on top of
  everything else. **On balance this is an argument for starting on Boot 4.0.x rather than 4.1.x**, though
  4.0.x's OSS support ends 2026-12-31 (§1), so it is a short-lived stay of execution.
- **Redisson** has a Boot 4 line (4.x since 2025-12-16, 4.7.0 current). Only relevant if a distributed
  lock is wanted, which this design deliberately avoids in favour of database-level mechanisms
  ([`hot-account-contention.md`](hot-account-contention.md)).
- **Bucket4j's Spring Boot starter is the weakest link in the list.** 0.14.0 (2026-02-28) targets Boot
  **4.0.3** and has not been released since; running it on Boot 4.1.x is **unconfirmed by the maintainer**.
  Per-tenant rate limiting on Redis is the plausible Redis justification in [ADR
  0008](../adr/0008-what-kafka-redis-and-grpc-are-for.md), so this matters. Two mitigations, both cheap:
  pin Boot 4.0.x while this is the binding constraint, or **use Bucket4j's core library directly with its
  Redis/Lettuce integration and write the filter by hand** — perhaps twenty lines, no starter, no version
  risk, and a more interesting thing to be able to explain than a starter's YAML.
- **Gatling** has no coupling to Spring Boot at all; `gatling-maven-plugin` 4.21.12 runs a separate JVM
  against HTTP endpoints, and Gatling supports OpenJDK LTS 11–25. It is orthogonal to this decision, and
  it is the answer to gap #3 in [ADR-0016](../adr/0016-the-constraints-this-project-was-given.md) on either branch.

### Gaps, restated as a list

1. **Bucket4j starter**: Boot 4.1 compatibility unconfirmed; pin 4.0.x or drop the starter. *(only real gap)*
2. **springdoc + Jackson 3 custom serializers**: known open bug; verify the generated schema.
3. **Testcontainers 2.x rename sweep**: mechanical, but it invalidates every pre-2026 snippet.
4. **Spring gRPC**: covered in [`grpc-and-multi-module-layout.md`](grpc-and-multi-module-layout.md) and it
   points the other way — **1.0 GA requires Boot 4**, and the only Boot-3-compatible version (0.12.0,
   frozen since 2025-10-24) is a pre-GA line receiving no security patches. This is the ecosystem gap on
   the **Boot 3.5** side, and it is larger than anything on the Boot 4 side.

---

## 7. The familiarity criterion, argued both ways

The project's own framing ([ADR-0016](../adr/0016-the-constraints-this-project-was-given.md)) is blunt about this: *"the stack is the point, not
an implementation detail"*, and JPA was chosen over jOOQ because it is *"what most Spring codebases run on"*, which
outranks technical fit here. The same reasoning must
now be applied to the Boot version, and applied honestly rather than used to rubber-stamp the newer thing.

There is one citable fact, and it is the strongest card in the Boot 3.5 hand. From the **2026 State of
Open Source Report**: *"52.05% of Spring Boot users operate on version 3.5, which exits community support
on June 30, 2026"*, and *"27.40% still use Spring Boot 2.7, which reached end of life (EOL) in 2023"*;
on the Framework side, 46.58% on 6.2 and 30.14% *"remain on Spring Framework 5.3, which became EOL in
2024"* (as reported by [OpenLogic — Java ecosystem
trends](https://www.openlogic.com/blog/java-ecosystem-trends)). Both sides of this argument have to
account for that number, and each reads it differently.

### The strongest case for Spring Boot 3.5

**1. It is what the reader uses.** Half of Spring Boot deployments are 3.5 and a quarter
are on a version that has been EOL for three years. A near-zero share is on 4.x. The realistic
probability that a reader's own production system is Boot 3.x is very high, and everything they
recognise instantly — `spring-boot-starter-web`, `@MockBean`, `com.fasterxml.jackson`, `authorizeRequests`
— is exactly what §4 says Boot 4 has renamed or removed. Recognition is not a vanity metric here; it is
the difference between the reviewer reading the design and the reviewer reading the imports.

**2. Every piece of secondary material matches.** Blog posts, Stack Overflow answers, and a reader's
muscle memory. On Boot 3.5 the project sits in the middle of a very large, very
well-documented corpus. On Boot 4 a meaningful fraction of the internet's advice is silently wrong.

**3. The risk that the stack becomes the topic.** This is the real danger and it deserves to be
stated at full strength. The point of the project is a schema that makes bad states impossible, RLS under a
transaction-mode pooler, a comparable Position, contention on a constrained Account. Every minute spent
explaining why `spring-boot-starter-webmvc` exists, or why the Testcontainers imports look unfamiliar, or
what `tools.jackson` is, is a minute not spent on any of that. An unfamiliar stack can convert a design
conversation into a trivia conversation, and the author does not control which one happens.

**4. There is a live, non-hypothetical ecosystem wart** — the Bucket4j starter is six months stale and
untested on Boot 4.1 (§6). Small, but it is not nothing, and on Boot 3.5 it does not exist.

### The strongest case for Spring Boot 4

**1. Boot 3.5 is already out of free support — and the same survey is the evidence that this matters.**
The OpenLogic figure cuts both ways: a quarter of the industry is running an EOL Spring Boot, which is a
description of technical debt, not a standard to aim at. A project **created in 2026** that starts on a
branch whose OSS support ended on 2026-06-30 (§1) has made a dated choice on day one, and it is checkable
in thirty seconds by anyone who opens `spring.io/projects/spring-boot`. "Why is this on a version that
stopped getting security patches before you wrote it?" is a worse question to face than any of the
questions in the Boot 3.5 column above, because there is no good answer to it.

**2. Every dependency is GA instead of one frozen pre-1.0 pin.**
[`grpc-and-multi-module-layout.md`](grpc-and-multi-module-layout.md) already established the shape of
this: Spring gRPC went 1.0 GA on 2025-12-04 **and requires Boot 4**; Boot 3.x gets 0.12.0, last released
2025-10-24, *"frozen, not maintained — no security patches, so pin grpc-java and protobuf-java
yourself."* gRPC is a **hard requirement** of this project (ADR-0016). So
the Boot 3.5 build ships a pre-GA, unmaintained dependency on a required feature, and the note that
established that already calls the Boot 4 upgrade *"the exit"*. Choosing Boot 3.5 means choosing to do
that migration later, having written everything twice (§5).

**3. Boot 4 makes the riskiest mechanism in the design a framework feature instead of hand-rolled
plumbing.** Tenant isolation under fail-closed RLS is the one place where a mistake produces *silently
wrong data* rather than an error. On Boot 3.5 the stateless write path is a hand-written
`doWork(conn -> openStatelessSession(conn))`; on Boot 4 the framework does exactly that, in one place,
verified in its own source (§3). And ORM 7 throws on bulk DML against `@Immutable` entities and lets a
stateless session batch (§2). Those are correctness and performance arguments, not novelty arguments.

**4. The migration is itself the talking point.** "I upgraded a real multi-module system to Boot 4 and
here is the list of what broke" is a more interesting five minutes than anything in the Boot 3.5 column,
and it is a thing most Spring teams will have to do within the year — Boot 3.5's commercial window
runs to 2032 precisely because so many organisations will be late. Having already read the
migration guide, run the OpenRewrite recipe, and seen what it *missed* answers a question those teams
actually have.

**5. The recognition argument is weaker than it looks, because the interesting code is unchanged.** What a
reviewer reads in this repository is SQL migrations, RLS policies, a double-entry schema, `@EmbeddedId`
records, a Kafka relay, a gRPC service and a set of tests. None of that differs between the branches.
The Boot version changes some starter artifactIds in three `pom.xml` files, a handful of test annotations,
and one Jackson package prefix — and every one of those is a thing a Boot 3 team will itself be
renaming next year.

### Where that leaves the criterion

The familiarity argument does not point cleanly at Boot 3.5. It points at **familiar where it costs nothing,
current where it buys something** — and the two branches are not symmetric on that test. Boot 3.5 buys
familiarity at the price of an EOL runtime and a frozen pre-GA gRPC library on a required feature. Boot 4
buys supported, GA everything at the price of unfamiliar starter names and a documentation corpus that
lags. One of those prices is paid by the reviewer's eyes for thirty seconds; the other is paid by the
project's credibility for its whole life.

---

## Recommendation

**Build on Spring Boot 4.1.x** — currently 4.1.1, Spring Framework 7.0.9, Hibernate ORM 7.4.5, on Java 21.

Not 4.0.x: its OSS support ends **2026-12-31**, three months from now, and the whole first finding below
is about not starting on a line that is about to stop receiving free patches. The two things that argue
for 4.0.x — the Bucket4j starter targeting 4.0.3, and Lettuce staying on 6.x — are both cheaper to solve
directly than to buy with an expiring support window.

### The three findings that decide it

1. **Spring Boot 3.5's OSS support ended on 2026-06-30, and 3.5 is the last 3.x line.** There is no
   supported free 3.x to start on. A project dated 2026 beginning on an end-of-free-life branch is a
   30-second finding for any reviewer, and there is no good answer to it (§1).
2. **gRPC is a hard requirement, and Spring gRPC 1.0 GA requires Boot 4.** Boot 3.x gets 0.12.0 —
   pre-GA, frozen since 2025-10-24, no security patches — on a feature this project requires
   ([`grpc-and-multi-module-layout.md`](grpc-and-multi-module-layout.md)). Boot 3.5 means
   shipping a required capability on an unmaintained dependency and doing the Boot 4 migration later
   anyway, having written everything twice (§5).
3. **The ecosystem is ready, including the one that historically was not.** springdoc-openapi 3.x is the
   Boot 4 line, shipped one day after Boot 4.0 GA, and 3.1.1 is four days old and built against Boot 4.1
   (§6). The single real gap in the whole stack is a stale Bucket4j **starter** — solvable by using
   Bucket4j's core library directly, which is better material to be able to explain than a starter's YAML anyway.

Supporting, and not small: on Boot 4 the tenant-isolation mechanism that most easily fails *silently* —
a stateless write path taking its own connection with no `app.current_tenant` — becomes a framework
feature that provably shares the transaction's connection, instead of hand-rolled `doWork(…)` plumbing
(§3). And on Hibernate 7, bulk HQL against an `@Immutable` entity throws by default, and a stateless
session can batch (§2).

### The single biggest risk, and how to de-risk it in week one

**The risk:** this design's correctness rests on every request-path statement running inside the one
transaction that set `app.current_tenant`. On Boot 4 the recommended stateless write path reaches that
guarantee through **three Spring classes agreeing** — `SpringSessionContext` →
`JpaTransactionManager`/`HibernateJpaDialect` → `DataSourceUtils` — and no single document states the
conclusion (§3). Under fail-closed RLS, getting it wrong yields **an empty result set, not an exception**.
That is the worst failure mode in the system, on the newest code path in the stack.

**De-risk it before writing any domain code**, with a walking skeleton — one day's work — that proves the
whole chain end to end:

1. Boot 4.1.x + Postgres 16 Testcontainer (2.x coordinates) + Flyway with
   `flyway-database-postgresql`, migrations as the owner role on the direct endpoint.
2. One tenant-scoped table with `ENABLE`/`FORCE ROW LEVEL SECURITY` and the
   `nullif(current_setting('app.current_tenant', true), '')` policies from
   [`rls-pooling.md`](rls-pooling.md); runtime as a non-owner, non-`BYPASSRLS` role.
3. The transaction-boundary hook issuing `set_config('app.current_tenant', ?, true)`.
4. **The assertion that decides it:** inside one `@Transactional` method, read `pg_backend_pid()` and
   `current_setting('app.current_tenant', true)` through both the `EntityManager` and an injected
   `StatelessSession` (the `SharedSessionCreator` bean from §3) and require both to match. Plus a
   negative test: a second Tenant's row must be invisible to the stateless read.
5. `springdoc-openapi-starter-webmvc-ui:3.1.1` on the same context, and one Spring gRPC 1.x call, so the
   two dependencies that are least covered by existing tutorials are proven on day one rather than in
   month two.

**The fallback if step 4 fails:** the Boot 3.x technique still works on ORM 7 — obtain the transaction's
connection via `session.doWork(…)` and call `openStatelessSession(connection)`, or `statelessWithOptions()
.connection().open()` explicitly. Nothing else in the recommendation changes. That is the point of running
the test first: the answer either removes a piece of plumbing or tells you to keep it, and either way it
is known before anything depends on it.

### Consequences for the sibling notes

- [`hibernate-vs-the-schema.md`](hibernate-vs-the-schema.md) — three edits, all in §2 of this note:
  `StatelessSession` **can** batch (`insertMultiple`) and **does** use the second-level cache by default;
  bulk HQL against `@Immutable` now throws; and the `hibernate.version` pin can be dropped in favour of
  the Boot-managed version. Everything else in it survives ORM 7 unchanged.
- [`hot-account-contention.md`](hot-account-contention.md) — the retry snippet moves from Spring Retry to
  `@EnableResilientMethods` + `org.springframework.resilience.annotation.Retryable` (`maxRetries`, not
  `maxAttempts`; `jitter` built in). The reasoning about *where* the retry boundary sits is unaffected.
- [`ci-and-testcontainers-budget.md`](ci-and-testcontainers-budget.md) — conclusions stand; container
  coordinates and class packages need the Testcontainers 2.x rename.
- [`rls-pooling.md`](rls-pooling.md) and [`ordering-and-watermarks.md`](ordering-and-watermarks.md) — no
  changes. They are about Postgres and Kafka, which do not care.

---

## Sources

**Spring release state and support**

- [Spring Boot release metadata — `api.spring.io/projects/spring-boot/generations`](https://api.spring.io/projects/spring-boot/generations) — initial release, OSS support end and commercial support end per generation: 3.5.x OSS ends **2026-06-30** (commercial 2032-06-30), 4.0.x OSS 2026-12-31, 4.1.x OSS 2027-07-31; no 3.6 line.
- [Spring Boot wiki — Supported Versions](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions) — majors *"supported for at least 3 years from the release date (but you must run a supported minor version)"*; minors *"at least 12 months"*; May/November cadence.
- [Spring — Support Policy](https://spring.io/support-policy) — OSS support *"a minimum of 13 months"* per minor; commercial extension.
- [Spring Boot 4.0.0 available now](https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/) — released *"November 20, 2025"*; *"first class support for Java 25 (whilst retaining Java 17 compatibility)"*; modularization, JSpecify, API versioning, HTTP service clients.
- [Spring Framework 7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes) — *"retains a JDK 17 baseline while at the same time recommending JDK 25"*; Jakarta EE 11 baseline (Servlet 6.1, JPA 3.2, Bean Validation 3.1); *"embraces Hibernate ORM 7.1/7.2 as a JPA provider"*; `LocalSessionFactoryBean` exposing transactional `Session`/`StatelessSession` proxies and the ORM 7.2 recommendation; core retry merged into `spring-core` with `@Retryable`/`@ConcurrencyLimit` via `@EnableResilientMethods`; JPA 3.2 `@Autowired EntityManager`; API versioning; `@ImportHttpServices`; JSpecify; removals (spring-jcl, `javax.annotation`/`javax.inject`, Undertow, `ListenableFuture`, path-mapping options) and deprecations (`RestTemplate`, JUnit 4, Jackson 2.x).
- Maven Central metadata and published POMs (`repo1.maven.org`) — `org.springframework.boot:spring-boot` versions and publication dates (3.5.16 on 2026-06-25, 4.1.1 on 2026-08-20); `spring-boot-dependencies` managed versions for 3.5.16 / 4.0.8 / 4.1.1 (Hibernate, Jakarta APIs, Jackson, JUnit, Kafka, Testcontainers, Flyway, Liquibase, Micrometer, Lettuce, Tomcat, Spring Security, Spring Data BOM, Spring Framework).

**Spring Boot 4 migration**

- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) — *"Spring Boot 4.0 has a new modular design…"*; module/package/starter naming conventions; the Flyway/Liquibase starter requirement; `spring-boot-starter-classic` / `spring-boot-starter-test-classic` and the recommended ladder; `spring-boot-properties-migrator`; *"Spring Boot 4 is based on Jakarta EE 11 and requires a Servlet 6.1 baseline"*; *"Spring Boot 4.0 requires Java 17 or later"*; JSpecify compilation-failure warning. **Contains no mention of OpenRewrite.**
- [Spring Boot 4.0 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes) — `@MockBean`/`@SpyBean` removed; `@SpringBootTest` no longer configuring MockMvc/TestRestTemplate; Jackson 3 as preferred JSON library and the `tools.jackson` move with `jackson-annotations` excepted; auto-configuration classes no longer extensible; Undertow, Spring Session Hazelcast/MongoDB and Pulsar Reactive removals; Spring Retry and Spring Authorization Server dropped from dependency management; observability package moves; liveness/readiness probes on by default; Spring Batch in-memory by default.
- [Spring Boot 4.0 Configuration Changelog](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Configuration-Changelog) — the authoritative added/removed/deprecated property list.
- [What's New in Spring Security 7.0](https://docs.spring.io/spring-security/reference/7.0/whats-new.html) — `and()` removed from the `HttpSecurity` DSL; `authorizeRequests` removed in favour of `authorizeHttpRequests`; `AntPathRequestMatcher`/`MvcRequestMatcher` unsupported, `PathPatternRequestMatcher` default; relative login redirects; OAuth2 password grant removed.
- [OpenRewrite — Migrate to Spring Boot 4.0 (Community Edition)](https://docs.openrewrite.org/recipes/java/spring/boot4/upgradespringboot_4_0-community-edition) and [`spring-boot-40-modular-starters.yml`](https://github.com/openrewrite/rewrite-spring/blob/main/src/main/resources/META-INF/rewrite/spring-boot-40-modular-starters.yml) — the recipe and its sub-recipes; Moderne Source Available licensing; **no documented coverage guarantees**.

**Hibernate ORM 7**

- [Hibernate ORM 7.0 migration guide](https://github.com/hibernate/hibernate-orm/blob/7.0/migration-guide.adoc) — Java 17 baseline; Jakarta Persistence 3.2 *"fairly disruptive"*; stricter domain-model validation (misplaced annotations, `PersistentAttributeType`, identifier generators, JavaBean conventions, disallowed converters); **`StatelessSession` now uses the second-level cache by default**; **`hibernate.jdbc.batch_size` has no effect on a `StatelessSession`**, with `setJdbcBatchSize()` and `insertMultiple()`/`updateMultiple()`/`deleteMultiple()` instead; **update/delete queries against immutable entities now throw by default**; connection-pool support dropped for Vibur/Proxool/UCP.
- [Hibernate ORM 7.2 migration guide](https://github.com/hibernate/hibernate-orm/blob/7.2/migration-guide.adoc) — child `Session`/`StatelessSession` sharing transactional context: flush and close follow the parent; `SharedStatelessSessionBuilder` introduced.
- [A Short Guide to Hibernate 7 (7.2)](https://docs.hibernate.org/orm/7.2/introduction/html_single/Hibernate_Introduction.html) — *"This is not our preferred approach"* / *"we recommend that the `BookId` class be declared as an `@Embeddable` type"*; *"the easiest way to satisfy these requirements is to declare the id class as a `record`"*; records as embeddables with the no-arg-constructor requirement relaxed.
- [Hibernate 7.2 User Guide](https://docs.hibernate.org/orm/7.2/userguide/html_single/Hibernate_User_Guide.html) — composite-identifier rules including *"The primary key class must be serializable"*; multiple bare `@Id` attributes *"generally considered poor design"*; `hibernate.jdbc.batch_size` / `order_inserts` / `order_updates`; *"Hibernate disables insert batching at the JDBC level transparently if you use an identity identifier generator"*.
- [hibernate-orm 7.2 — `annotations/Immutable.java`](https://github.com/hibernate/hibernate-orm/blob/7.2/hibernate-core/src/main/java/org/hibernate/annotations/Immutable.java) — *"The changes are ignored, with no exception thrown"* (unchanged from 6.6); collections throw; delete not addressed.
- [hibernate-orm 7.2 — `mapping/Value.java`](https://github.com/hibernate/hibernate-orm/blob/7.2/hibernate-core/src/main/java/org/hibernate/mapping/Value.java) — `checkColumnDuplication` unchanged, still gated on `isColumnInsertable() || isColumnUpdateable()`, same `MappingException` text.
- [hibernate-orm 7.2 — `StatelessSession.java`](https://github.com/hibernate/hibernate-orm/blob/7.2/hibernate-core/src/main/java/org/hibernate/StatelessSession.java) — no persistence context, no first-level cache, synchronous operations, no cascades, exceptions do not mark the transaction rollback-only; *"Since version 7"* notes on batching and the second-level cache; `insertMultiple`/`updateMultiple`/`deleteMultiple`.
- [hibernate-orm 7.2 — `SharedStatelessSessionBuilder.java`](https://github.com/hibernate/hibernate-orm/blob/7.2/hibernate-core/src/main/java/org/hibernate/SharedStatelessSessionBuilder.java) — *"calling the `connection()` method specifies that the connection, and therefore also the JDBC transaction, should be shared from parent to child"*; `@since 7.2`.
- [hibernate-orm 7.2 — `changelog.txt`](https://github.com/hibernate/hibernate-orm/blob/7.2/changelog.txt) — `HHH-19214` (record `@IdClass` column ordering) fixed 7.2.16.Final, 2026-05-24; `HHH-20273` (List field in a record embeddable) fixed 7.2.8.Final, 2026-03-26.

**Spring Framework 7 source (v7.0.9), for claims the documentation does not state**

- [`orm/jpa/hibernate/SharedSessionCreator.java`](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-orm/src/main/java/org/springframework/orm/jpa/hibernate/SharedSessionCreator.java) — `createSharedStatelessSession(SessionFactory)`; *"a `StatelessSession` will be opened for the current transactional JDBC Connection"*.
- [`orm/jpa/hibernate/SpringSessionContext.java`](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-orm/src/main/java/org/springframework/orm/jpa/hibernate/SpringSessionContext.java) — `DERIVE_STATELESS_FROM_SESSION` capability check for Hibernate 7.2+; `statelessWithOptions().connection().open()`; `determineConnection(…)` returning the session's physical connection or `DataSourceUtils.getConnection(dataSource)`.
- [`orm/jpa/JpaTransactionManager.java`](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-orm/src/main/java/org/springframework/orm/jpa/JpaTransactionManager.java) — `afterPropertiesSet()` adopting the `DataSource` from `EntityManagerFactoryInfo`; `ConnectionHolder` bound from `JpaDialect#getJdbcConnection`.
- [`orm/jpa/vendor/HibernateJpaDialect.java`](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-orm/src/main/java/org/springframework/orm/jpa/vendor/HibernateJpaDialect.java) — `deriveEntityAgent(...)` = `entityManager.unwrap(Session.class).statelessWithOptions().connection().open()`.
- [`resilience/annotation/Retryable.java`](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/resilience/annotation/Retryable.java) — `includes`/`excludes`/`predicate`, `maxRetries` (default 3), `delay` (default 1000), `jitter`, `multiplier`, `maxDelay`, `timeout`.
- Code search of `spring-projects/spring-boot` at `v4.1.1` — **zero** occurrences of `StatelessSession`, `SharedSessionCreator` or `LocalSessionFactoryBean`: Boot does not auto-configure a transactional `StatelessSession`.

**Ecosystem**

- Maven Central metadata and POMs — `org.springdoc:springdoc-openapi-starter-webmvc-ui` versions (2.9.1 last of 2.x; 3.0.0 → 3.1.1); `springdoc-openapi:3.1.1` POM declaring `<parent>` `spring-boot-starter-parent:4.1.0`, published 2026-09-06.
- [springdoc-openapi releases](https://github.com/springdoc/springdoc-openapi/releases) — 3.0.0 (2025-11-21) *"Upgrade to Spring Boot 4.0.0"*; [issue #3333](https://github.com/springdoc/springdoc-openapi/issues/3333) — missing schema `properties`/`required` with Jackson 3 `@JsonSerialize`/`@JsonDeserialize`.
- [Testcontainers Java 2.0.0 release notes](https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0) — `testcontainers-` artifact prefix, per-technology packages, JUnit 4 support removed.
- [Spring Boot `@ServiceConnection` javadoc](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/testcontainers/service/connection/ServiceConnection.html) — unchanged package in Boot 4.
- [Spring for Apache Kafka 4.0 GA](https://spring.io/blog/2025/11/18/spring-kafka-4/) and [what's new](https://docs.spring.io/spring-kafka/reference/4.0/whats-new.html) — ZooKeeper removed, KRaft-only `EmbeddedKafkaBroker`, Spring Retry dependency dropped, Jackson 3 preferred.
- [Micrometer 1.16 migration guide](https://github.com/micrometer-metrics/micrometer/wiki/1.16-Migration-Guide) — Prometheus client Unicode support; `:` no longer sanitised to `_`.
- [Redisson 4.0.0 release](https://github.com/redisson/redisson/releases/tag/redisson-4.0.0) and [Bucket4j Spring Boot starter releases](https://github.com/MarcGiffing/bucket4j-spring-boot-starter/releases) — Boot 4 lines; Bucket4j 0.14.0 (2026-02-28) targets Boot 4.0.3.
- [Gatling — install / supported JDKs](https://docs.gatling.io/reference/deploy/install-local/) and `io.gatling:gatling-maven-plugin` Maven Central metadata — 4.21.12 (2026-09-07); no Spring Boot coupling.

**Adoption data (§7)**

- [OpenLogic — State of Open Source: Java ecosystem trends](https://www.openlogic.com/blog/java-ecosystem-trends), reporting the 2026 State of Open Source Report — *"52.05% of Spring Boot users operate on version 3.5"*; *"27.40% still use Spring Boot 2.7, which reached end of life (EOL) in 2023"*; Spring Framework 6.2 at 46.58%, 5.3 at 30.14%. Survey data, not a primary vendor document — used only for the argument in §7 and marked as such.
